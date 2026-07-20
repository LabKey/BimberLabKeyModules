/*
 * Copyright (c) 2023 LabKey Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.labkey.test.tests.pmr;

import au.com.bytecode.opencsv.CSVReader;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.labkey.remoteapi.query.SelectRowsCommand;
import org.labkey.remoteapi.query.TruncateTableCommand;
import org.labkey.serverapi.reader.Readers;
import org.labkey.test.BaseWebDriverTest;
import org.labkey.test.Locator;
import org.labkey.test.ModulePropertyValue;
import org.labkey.test.TestFileUtils;
import org.labkey.test.TestTimeoutException;
import org.labkey.test.WebTestHelper;
import org.labkey.test.categories.External;
import org.labkey.test.categories.LabModule;
import org.labkey.test.util.Ext4Helper;
import org.labkey.test.util.RReportHelper;
import org.labkey.test.util.RemoteConnectionHelper;
import org.labkey.test.util.di.DataIntegrationHelper;
import org.labkey.test.util.ehr.EHRClientAPIHelper;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Category({External.class, LabModule.class})
public class PMRTest extends BaseWebDriverTest
{
    private final DataIntegrationHelper _etlHelper = new DataIntegrationHelper(getProjectName());

    @Override
    protected void doCleanup(boolean afterTest) throws TestTimeoutException
    {
        super.doCleanup(afterTest);
    }

    @BeforeClass
    public static void setupProject() throws Exception
    {
        PMRTest init = (PMRTest)getCurrentTest();
        init.doSetup();
    }

    private File getKinshipPath()
    {
        return new File(TestFileUtils.getDefaultFileRoot(getProjectName()), "kinshipEtlDir");
    }

    private void doSetup()
    {
        _containerHelper.createProject(getProjectName(), "PMR");

        setModuleProperties(Arrays.asList(
                new ModulePropertyValue("EHR", "/" + getProjectName(), "EHRStudyContainer", "/" + getProjectName()),
                new ModulePropertyValue("EHR", "/" + getProjectName(), "EHRAdminUser", getCurrentUser()),
                new ModulePropertyValue("GeneticsCore", "/" + getProjectName(), "KinshipDataPath", getKinshipPath().getPath())
        ));

        importStudy(getProjectName());
        populateLookups();

        RemoteConnectionHelper rconnHelper = new RemoteConnectionHelper(this);
        rconnHelper.goToManageRemoteConnections();
        rconnHelper.createConnection("EHR_ClinicalSource", WebTestHelper.getBaseURL(), getProjectName());

        new RReportHelper(this).ensureRConfig();

        goToHome();
    }

    private void importStudy(String containerPath)
    {
        beginAt(WebTestHelper.getBaseURL() + "/pmr/" + containerPath + "/importStudy.view");
        clickButton("OK");
        waitForPipelineJobsToComplete(1, "Study import", false, MAX_WAIT_SECONDS * 2500);
    }

    private void populateLookups()
    {
        beginAt(getProjectName() + "/pmr-populateData.view");
        waitAndClick(Ext4Helper.Locators.ext4Button("Populate Lookup Sets"));
        waitForElement(Locator.tagWithText("div", "Populating lookup_sets..."));
        waitForElement(Locator.tagWithText("div", "Populate Complete"));

        waitAndClick(Ext4Helper.Locators.ext4Button("Populate All"));
        waitForElement(Locator.tagWithText("div", "Populate Complete"));
    }

    @Before
    public void preTest()
    {
        goToProjectHome();
    }

    @Test
    public void testPMRModule() throws Exception
    {
        testKinshipEtl();
    }

    private void testKinshipEtl() throws Exception
    {
        createTestPedigreeData();

        // Calculate genetics, which will output results in the projects file root:
        beginAt(getProjectName() + "/ehr-ehrAdmin.view");
        waitAndClickAndWait(Locator.tagContainingText("a", "Genetics Calculations"));
        _ext4Helper.checkCheckbox(Ext4Helper.Locators.checkbox(this, "Kinship validation?:"));
        _ext4Helper.checkCheckbox(Ext4Helper.Locators.checkbox(this, "Allow Import During Business Hours?:"));
        Locator loc = Locator.inputByIdContaining("numberfield");
        waitForElement(loc);
        setFormElement(loc, "23");
        click(Ext4Helper.Locators.ext4Button("Save Settings"));
        waitAndClick(Ext4Helper.Locators.ext4Button("OK"));
        waitAndClickAndWait(Ext4Helper.Locators.ext4Button("Run Now"));
        waitAndClickAndWait(Locator.lkButton("OK"));
        waitForPipelineJobsToComplete(2, "EHR Kinship Calculation", false);

        // Verify data imported, and then delete from the DB
        SelectRowsCommand select1 = new SelectRowsCommand("ehr", "kinship");
        Assert.assertEquals("Incorrect number of kinship rows", 136, select1.execute(getApiHelper().getConnection(), getProjectName()).getRowCount().intValue());

        new TruncateTableCommand("ehr", "kinship").execute(getApiHelper().getConnection(), getProjectName());
        Assert.assertEquals("Incorrect number of kinship rows", 0, select1.execute(getApiHelper().getConnection(), getProjectName()).getRowCount().intValue());

        // Kick off ETL to stage data. This should also kick off a separate pipeline job to import, using geneticscore-importGeneticsData.view
        _etlHelper.runTransform("{PMR}/KinshipDataStaging");
        goToDataPipeline();
        waitForPipelineJobsToComplete(4, "ETL Job: Import PRIMe-seq Kinship Data", false);

        Assert.assertEquals("Incorrect number of kinship rows after ETL", 136, select1.execute(getApiHelper().getConnection(), getProjectName()).getRowCount().intValue());
    }

    private void createTestPedigreeData() throws Exception
    {
        // Create dummy data:
        Set<String> demographicsAdded = new HashSet<>();
        final Date d = new Date();

        File testPedigree = TestFileUtils.getSampleData("PMR/testPedigree.txt");
        try (CSVReader reader = new CSVReader(Readers.getReader(testPedigree), '\t'))
        {
            String[] line;
            while ((line = reader.readNext()) != null)
            {
                if (!demographicsAdded.contains(line[0]))
                {
                    getApiHelper().insertRow("study", "demographics", Map.of("Id", line[0], "species", line[4], "gender", ("1".equals(line[3]) ? "m" : "f"), "date", d, "QCStateLabel", "Completed"), false);
                    demographicsAdded.add(line[0]);
                }

                // dam
                if (!line[1].isEmpty())
                {
                    if (!demographicsAdded.contains(line[1]))
                    {
                        getApiHelper().insertRow("study", "demographics", Map.of("Id", line[1], "species", line[4], "gender", "f", "date", d, "QCStateLabel", "Completed"), false);
                        demographicsAdded.add(line[1]);
                    }

                    getApiHelper().insertRow("study", "parentage", Map.of("Id", line[0], "parent", line[1], "relationship", "Dam", "method", "Genetic", "date", d, "QCStateLabel", "Completed"), false);
                }

                // sire
                if (!line[2].isEmpty())
                {
                    if (!demographicsAdded.contains(line[2]))
                    {
                        getApiHelper().insertRow("study", "demographics", Map.of("Id", line[2], "species", line[4], "gender", "m", "date", d, "QCStateLabel", "Completed"), false);
                        demographicsAdded.add(line[2]);
                    }

                    getApiHelper().insertRow("study", "parentage", Map.of("Id", line[0], "parent", line[2], "relationship", "Sire", "method", "Genetic", "date", d, "QCStateLabel", "Completed"), false);
                }
            }
        }
    }

    private EHRClientAPIHelper getApiHelper()
    {
        return new EHRClientAPIHelper(this, getProjectName());
    }

    @Override
    protected BrowserType bestBrowser()
    {
        return BrowserType.CHROME;
    }

    @Override
    protected String getProjectName()
    {
        return "PMRTest Project";
    }

    @Override
    public List<String> getAssociatedModules()
    {
        return Collections.singletonList("PMR");
    }
}