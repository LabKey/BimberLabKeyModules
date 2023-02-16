/*
 * Copyright (c) 2020 LabKey Corporation
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

package org.labkey.test.tests.mcc;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.labkey.remoteapi.query.Filter;
import org.labkey.remoteapi.query.SelectRowsCommand;
import org.labkey.remoteapi.query.SelectRowsResponse;
import org.labkey.remoteapi.query.Sort;
import org.labkey.test.BaseWebDriverTest;
import org.labkey.test.Locator;
import org.labkey.test.ModulePropertyValue;
import org.labkey.test.TestTimeoutException;
import org.labkey.test.WebTestHelper;
import org.labkey.test.categories.External;
import org.labkey.test.categories.LabModule;
import org.labkey.test.components.ext4.Window;
import org.labkey.test.util.ApiPermissionsHelper;
import org.labkey.test.util.DataRegionTable;
import org.labkey.test.util.Ext4Helper;
import org.labkey.test.util.PermissionsHelper;
import org.labkey.test.util.ext4cmp.Ext4ComboRef;
import org.labkey.test.util.ext4cmp.Ext4FieldRef;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Category({External.class, LabModule.class})
public class MccTest extends BaseWebDriverTest
{
    @Test
    public void testMccModule() throws Exception
    {
        doRequestFormTest();
        doRequestFormTestWithFailure();

        testInvalidId();

        testAnimalImportAndTransfer();
    }

    private static final String ANIMAL_DATA_HEADER = "animal ID\tprevious IDs\tsource\t\"DOB\n(MM/DD/YYYY)\"\tsex\tmaternal ID\tpaternal ID\t\"weight(grams)\"\t\"date of weight\n(MM/DD/YY)\"\tU24 status\tavailalble to transfer\tcurrent housing status\tinfant history\tfertility status\tmedical history\n";

    private static final String ANIMAL_DATA1 = "Animal1\t\t\t7/10/2011\t0 - male\tDam1\tSire1\t382.8\t5/19/2021\t0 - not assigned to U24 breeding colony\t0 - not available for transfer\t1 - natal family group\t3 - successful rearing of offspring\t2 - successful offspring produced\t0 - naive animal\n";


    private static final String ANIMAL_DATA2 = "Animal2\t\t\t6/3/2015\t1 - female\tDam2\tSire2\t361.2\t1/28/2021\t0 - not assigned to U24 breeding colony\t0 - not available for transfer\t2 - active breeding\t3 - successful rearing of offspring\t2 - successful offspring produced\t0 - naive animal\n";

    private static final String ANIMAL_DATA3 = "Animal3\t\t\t6/4/2015\t1 - female\tDam2\tSire2\t361.2\t1/28/2021\t0 - not assigned to U24 breeding colony\t0 - not available for transfer\t2 - active breeding\t3 - successful rearing of offspring\t2 - successful offspring produced\t0 - naive animal";

    private void testAnimalImportAndTransfer() throws Exception
    {
        beginAt(getProjectName() + "/Colonies/SNPRC/project-begin.view");
        waitAndClickAndWait(Locator.tagWithText("a", "Import Excel-Based Data"));
        waitForElement(Locator.tagWithText("label", "Paste Data Below:"));
        Ext4FieldRef.getForLabel(this, "Center/Colony Name").setValue("SNPRC");

        Ext4FieldRef.getForLabel(this, "Paste Data Below").setValue(ANIMAL_DATA_HEADER + ANIMAL_DATA1 + ANIMAL_DATA2 + ANIMAL_DATA3);

        waitAndClick(Ext4Helper.Locators.ext4Button("Preview"));
        waitForElement(Locator.tagWithText("td", "Animal2").withClass("dt-center"));

        waitAndClick(getButton("Submit"));
        new Window.WindowFinder(getDriver()).withTitle("Success").waitFor();
        waitAndClick(Ext4Helper.Locators.ext4Button("OK"));

        waitAndClickAndWait(Locator.tagWithText("a", "View Study Datasets"));
        waitAndClickAndWait(Locator.tagWithText("a", "Demographics"));

        DataRegionTable dr = DataRegionTable.DataRegion(getDriver()).withName("Dataset").waitFor();
        dr.checkCheckbox(1); //Animal2
        Assert.assertEquals("Incorrect ID", "Animal2", dr.getDataAsText(1, "Id"));
        Assert.assertEquals("Incorrect Status", "<Alive>", dr.getDataAsText(1, "Status"));
        String mccId = dr.getDataAsText(1, "MCC Alias");

        dr.clickHeaderMenu("More Actions", false, "Mark Animal Shipped");

        new Window.WindowFinder(getDriver()).withTitle("Mark ID Shipped").waitFor();
        Ext4FieldRef.getForLabel(this, "Effective Date").setValue(new SimpleDateFormat("MM/dd/yyyy").format(new Date()));
        Ext4ComboRef combo = Ext4ComboRef.getForLabel(this, "Destination Center Name");
        combo.clickTrigger();
        waitAndClick(Locator.tagContainingText("li", "Other"));

        Window<?> dialog = new Window.WindowFinder(getDriver()).withTitle("Enter Value").waitFor();
        dialog.findElement(Locator.tag("input")).sendKeys("TargetColony");
        waitAndClick(Ext4Helper.Locators.ext4Button("OK"));
        sleep(100);

        Ext4ComboRef.getForLabel(this, "Target Folder").setComboByDisplayValue("Other");
        waitAndClick(Ext4Helper.Locators.ext4Button("Submit"));

        new Window.WindowFinder(getDriver()).withTitle("Success").waitFor();
        waitAndClickAndWait(Ext4Helper.Locators.ext4Button("OK"));

        dr = DataRegionTable.DataRegion(getDriver()).withName("Dataset").waitFor();
        Assert.assertEquals("Incorrect ID", "Animal2", dr.getDataAsText(1, "Id"));
        Assert.assertEquals("Incorrect Status", "<Shipped>", dr.getDataAsText(1, "Status"));
        Assert.assertEquals("Incorrect Value", "true", dr.getDataAsText(1, "Exclude From Census?"));
        Assert.assertEquals("Incorrect Colony", "TargetColony", dr.getDataAsText(1, "Current Colony"));

        // Verify result:
        beginAt(getProjectName() + "/Colonies/Other/project-begin.view");
        waitAndClickAndWait(Locator.tagWithText("a", "View Study Datasets"));
        waitAndClickAndWait(Locator.tagWithText("a", "Demographics"));

        dr = DataRegionTable.DataRegion(getDriver()).withName("Dataset").waitFor();
        Assert.assertEquals("Incorrect ID", "Animal2", dr.getDataAsText(0, "Id"));
        Assert.assertEquals("Incorrect Alias", mccId, dr.getDataAsText(0, "MCC Alias"));
        Assert.assertEquals("Incorrect Status", "<Alive>", dr.getDataAsText(0, "Status"));
        Assert.assertEquals("Incorrect Colony", "TargetColony", dr.getDataAsText(0, "colony"));
        Assert.assertEquals("Incorrect Source", "SNPRC", dr.getDataAsText(0, "source"));

        // These were inserted using a cross-folder SaveRows, and this check ensures the trigger script containerPath and serverContex works as expected:
        SelectRowsCommand sr = new SelectRowsCommand("study", "demographics");
        sr.setColumns(Arrays.asList("Id", "QCState/Label"));
        SelectRowsResponse srr = sr.execute(createDefaultConnection(), getProjectName() + "/Colonies/Other");
        srr.getRows().forEach(row -> {
            Assert.assertEquals("Incorrect QCState", "Completed", row.get("QCState/Label"));
        });

        // Now try a within-folder transfer:
        dr = DataRegionTable.DataRegion(getDriver()).withName("Dataset").waitFor();
        dr.checkCheckbox(0); //Animal2
        dr.clickHeaderMenu("More Actions", false, "Mark Animal Shipped");

        new Window.WindowFinder(getDriver()).withTitle("Mark ID Shipped").waitFor();
        Ext4FieldRef.getForLabel(this, "Effective Date").setValue(new SimpleDateFormat("MM/dd/yyyy").format(new Date()));
        combo = Ext4ComboRef.getForLabel(this, "Destination Center Name");
        combo.clickTrigger();
        waitAndClick(Locator.tagContainingText("li", "Other"));

        dialog = new Window.WindowFinder(getDriver()).withTitle("Enter Value").waitFor();
        dialog.findElement(Locator.tag("input")).sendKeys("TargetColony2");
        waitAndClick(Ext4Helper.Locators.ext4Button("OK"));
        sleep(100);

        Ext4ComboRef.getForLabel(this, "Target Folder").setComboByDisplayValue("Other");
        waitAndClick(Ext4Helper.Locators.ext4Button("Submit"));

        new Window.WindowFinder(getDriver()).withTitle("Success").waitFor();
        waitAndClickAndWait(Ext4Helper.Locators.ext4Button("OK"));

        dr = DataRegionTable.DataRegion(getDriver()).withName("Dataset").waitFor();
        Assert.assertEquals("Incorrect ID", "Animal2", dr.getDataAsText(0, "Id"));
        Assert.assertEquals("Incorrect Alias", mccId, dr.getDataAsText(0, "MCC Alias"));
        Assert.assertEquals("Incorrect Status", "<Alive>", dr.getDataAsText(0, "Status"));
        Assert.assertEquals("Incorrect Status", "Dam2", dr.getDataAsText(0, "Dam"));
        Assert.assertEquals("Incorrect Status", "Sire2", dr.getDataAsText(0, "Sire"));
        Assert.assertNull("Incorrect Value", StringUtils.trimToNull(dr.getDataAsText(0, "Exclude From Census?")));
        Assert.assertEquals("Incorrect Colony", "TargetColony2", dr.getDataAsText(0, "colony"));
        Assert.assertEquals("Incorrect Colony", "TargetColony", dr.getDataAsText(0, "source"));

        sr = new SelectRowsCommand("study", "departure");
        sr.setColumns(Arrays.asList("Id", "QCState/Label"));
        sr.setFilters(List.of(new Filter("Id", "Animal2")));
        srr = sr.execute(createDefaultConnection(), getProjectName() + "/Colonies/Other");
        Assert.assertEquals("Incorrect number of departures", 1, srr.getRowCount().intValue());
        srr.getRows().forEach(row -> {
            Assert.assertEquals("Incorrect QCState", "Completed", row.get("QCState/Label"));
        });

        // Now check status update:
        populateLookups("SNPRC"); //status is needed for this to work
        beginAt(getProjectName() + "/Colonies/SNPRC/project-begin.view");
        waitAndClickAndWait(Locator.tagWithText("a", "Import Excel-Based Data"));
        waitForElement(Locator.tagWithText("label", "Paste Data Below:"));
        Ext4FieldRef.getForLabel(this, "Center/Colony Name").setValue("SNPRC");

        Ext4FieldRef.getForLabel(this, "Paste Data Below").setValue(ANIMAL_DATA_HEADER + ANIMAL_DATA1);

        waitAndClick(Ext4Helper.Locators.ext4Button("Preview"));
        waitForElement(Locator.tagWithText("td", "Animal1").withClass("dt-center"));

        waitAndClick(getButton("Process Missing IDs"));
        new Window.WindowFinder(getDriver()).withTitle("Reconcile Census with Existing IDs").waitFor();

        String comboQuery = "combo[dataIndex='status_code']";
        Ext4ComboRef.waitForComponent(this, comboQuery);
        Ext4ComboRef statusCombo = _ext4Helper.queryOne(comboQuery, Ext4ComboRef.class);
        statusCombo.setComboByDisplayValue("Dead");

        String comboQuery2 = "combo[dataIndex='cause']";
        Ext4ComboRef.waitForComponent(this, comboQuery2);
        Ext4ComboRef causeCombo = _ext4Helper.queryOne(comboQuery2, Ext4ComboRef.class);
        causeCombo.setComboByDisplayValue("Cause of Death Unknown");

        waitAndClick(Ext4Helper.Locators.ext4Button("Update IDs"));
        sleep(100);
        new Window.WindowFinder(getDriver()).withTitle("Success").waitFor();
        waitAndClick(Ext4Helper.Locators.ext4Button("OK"));

        sr = new SelectRowsCommand("study", "demographics");
        sr.setColumns(Arrays.asList("Id", "calculated_status"));
        sr.setFilters(List.of(new Filter("Id", "Animal3")));
        srr = sr.execute(createDefaultConnection(), getProjectName() + "/Colonies/SNPRC");
        Assert.assertEquals("Incorrect status", "Dead", srr.getRows().get(0).get("calculated_status"));

        sr = new SelectRowsCommand("study", "deaths");
        sr.setColumns(Arrays.asList("Id", "cause"));
        sr.setFilters(List.of(new Filter("Id", "Animal3")));
        srr = sr.execute(createDefaultConnection(), getProjectName() + "/Colonies/SNPRC");
        Assert.assertEquals("No death record", 1, srr.getRowCount().intValue());
        Assert.assertEquals("Incorrect cause", "Cause of Death Unknown", srr.getRows().get(0).get("cause"));
    }

    private static class FormElement
    {
        String inputType = "input";
        String databaseValue = null;

        final String inputName;
        final Object fieldValue;
        final String databaseFieldName;

        public FormElement(String inputName, String databaseFieldName, Object fieldValue)
        {
            this.inputName = inputName;
            this.fieldValue = fieldValue;
            this.databaseFieldName = databaseFieldName;
        }

        public String getDatabaseFieldName()
        {
            return databaseFieldName;
        }

        public FormElement checkBox()
        {
            if (!(fieldValue instanceof Boolean))
            {
                throw new IllegalStateException("Checkbox must have a boolean input fieldValue");
            }

            this.inputType = "checkbox";
            return this;
        }

        public FormElement select(String databaseValue)
        {
            this.databaseValue = databaseValue;
            this.inputType = "select";

            return this;
        }

        public FormElement radio()
        {
            if (!(fieldValue instanceof Boolean))
            {
                throw new IllegalStateException("Radio must have a boolean input fieldValue");
            }

            this.inputType = "radio";
            return this;
        }

        public Object getDatabaseValue()
        {
            return databaseValue == null ? fieldValue : databaseValue;
        }

        public FormElement inputType(String inputType)
        {
            this.inputType = inputType;
            return this;
        }

        public String getInputName()
        {
            if ("radio".equals(inputType))
            {
                return inputName + ((boolean)fieldValue ? "-yes" : "-no");
            }

            return inputName;
        }

        public Locator getLocator()
        {
            return Locator.tagWithId("radio".equals(inputType) || "checkbox".equals(inputType) ? "input" : inputType, getInputName());
        }

        public void waitFor(BaseWebDriverTest test)
        {
            test.waitForElement(getLocator());
        }

        public void setFieldValue(BaseWebDriverTest test)
        {
            waitFor(test);
            if ("checkbox".equals(inputType) || "radio".equals(inputType))
            {
                test.waitAndClick(getLocator());
            }
            else if ("select".equals(inputType))
            {
                test.selectOptionByText(getLocator(), String.valueOf(fieldValue));
            }
            else
            {
                test.setFormElement(getLocator(), String.valueOf(fieldValue));
            }
        }

        public String getExpectedInputFieldValue()
        {
            if (fieldValue instanceof Boolean)
            {
                boolean val = (boolean)fieldValue;
                if ("checkbox".equals(inputType))
                {
                    return val ? "on" : null;
                }
                else if ("radio".equals(inputType))
                {
                    return val ? "yes" : "no";
                }
            }

            return String.valueOf(databaseValue == null ? fieldValue : databaseValue);
        }
    }

    // Field name / field value:
    final FormElement[] FORM_DATA = new FormElement[]{
            new FormElement("project-title", "title", "the project title"),
            new FormElement("project-narrative", "narrative", "the narrative").inputType("textarea"),
            new FormElement("investigator-last-name", "lastName", "last name"),
            new FormElement("neuroscience", "neuroscience", "neuroscience connection").inputType("textarea"),
            new FormElement("investigator-first-name", "firstName", "first name"),
            new FormElement("investigator-middle-initial", "middleinitial", "m"),
            new FormElement("is-early-stage-investigator", "earlystageinvestigator", true).radio(),
            new FormElement("institution-name", "institutionname", "institution name"),
            new FormElement("institution-city", "institutioncity", "institution city"),
            new FormElement("institution-state", "institutionstate", "institution state"),
            new FormElement("institution-type", "institutiontype", "Minority serving").select("minorityServing"),
            new FormElement("institution-country", "institutioncountry", "institution country"),
            new FormElement("official-last-name", "officiallastname", "official last name"),
            new FormElement("official-first-name", "officialfirstname", "official first name"),
            new FormElement("official-email", "officialemail", "official@email.com"),
            //TODO: multi-select
            new FormElement("funding-source", "fundingsource", "NIH-supported research").select("nih"),
            new FormElement("funding-grant-number", "grantnumber", "grant number"),
            new FormElement("experiment-rationale", "experimentalrationale", "rationale").inputType("textarea"),
            new FormElement("methods-proposed", "methodsproposed", "methods").inputType("textarea"),
            new FormElement("collaborations", "collaborations", "collaborations").inputType("textarea"),

            new FormElement("animal-breeding-is-planning-to-breed-animals", "breedinganimals", "Will not breed").select("Will not breed"),
            new FormElement("existing-marmoset-colony", "existingmarmosetcolony", "Existing marmoset colony").select("existing"),
            new FormElement("existing-nhp-facilities", "existingnhpfacilities", "Existing NHP facilities").select("existing"),
            new FormElement("animal-welfare", "animalwelfare", "welfare").inputType("textarea"),
            new FormElement("certify", "certify", true).checkBox(),
            new FormElement("vet-last-name", "vetlastname", "vet last name"),
            new FormElement("vet-first-name", "vetfirstname", "vet first name"),
            new FormElement("vet-email", "vetemail", "vet@email.com"),
            new FormElement("iacuc-approval", "iacucapproval", "Provisional").select("provisional"),
            new FormElement("is-terminalprocedures", "terminalprocedures", true).radio(),
            new FormElement("census-participate-in-census", "census", true).radio()
    };

    private Locator getButton(String text)
    {
        return Locator.tagWithText("button", text);
    }

    private void waitForCensusToLoad()
    {
        waitForElement(Locator.tagWithText("div", "Age (Living Animals)")); //proxy for data loading
    }

    private void goToAnimalRequests()
    {
        goToProjectHome();
        waitForCensusToLoad();
        waitAndClickAndWait(Locator.tagContainingText("div", "Animal Requests"));
        waitForElement(Locator.tagWithText("a", "Submit New Animal Request"));
    }

    private void waitForSaveToComplete()
    {
        waitForElementToDisappear(Locator.tagWithText("h2", "Saving").withClass("text-xl"));
    }

    private void doRequestFormTest() throws Exception
    {
        goToAnimalRequests();
        waitAndClickAndWait(Locator.tagWithText("a", "Submit New Animal Request"));
        waitForElement(getButton("Save"));

        setAllFormValues();

        addCohort(0);

        addCoinvestigator(0, true);
        addCoinvestigator(1, true);

        int expectedRequests = getRequestRows().size();

        waitAndClick(getButton("Save"));
        waitForSaveToComplete();

        List<Map<String, Object>> requestRows = getRequestRows();
        String requestId = (String)requestRows.get(0).get("objectid");
        Assert.assertEquals(expectedRequests +  1, requestRows.size());

        Assert.assertEquals(2, getCoinvestigatorRecords(requestId).size());
        Assert.assertEquals(1, getCohortRecords(requestId).size());

        // This is to ensure we update the record we have, rather than create a new one
        waitAndClick(getButton("Save"));
        waitForSaveToComplete();
        Assert.assertEquals(expectedRequests +  1, getRequestRows().size());
        Assert.assertEquals(2, getCoinvestigatorRecords(requestId).size());
        Assert.assertEquals(1, getCohortRecords(requestId).size());

        // Verify record created:
        Map<String, Object> request = getLastModifiedRequestRow();
        for (FormElement f : FORM_DATA)
        {
            Assert.assertEquals(request.get(f.databaseFieldName), f.getDatabaseValue());
        }

        assertCohortValues((String)request.get("objectid"), 1);
        assertCoinvestigatorValues((String)request.get("objectid"), 2);

        // Remove Co-I, save:
        Locator removeBtn = Locator.tagWithText("p", "Co-Investigator 2").parent("div").followingSibling("div").index(0).child("input");
        waitForElement(removeBtn);
        click(removeBtn);
        waitForElementToDisappear(removeBtn);

        waitAndClick(getButton("Save"));
        waitForSaveToComplete();
        Assert.assertEquals(1, getCoinvestigatorRecords(requestId).size());
        Assert.assertEquals(1, getCohortRecords(requestId).size());

        // Test IACUC toggle
        selectOptionByText(getFormElementByName("iacucapproval").getLocator(), "Approved");
        waitForElement(Locator.tagWithId("input", "iacuc-protocol"));
        setFormElement(Locator.tagWithId("input", "iacuc-protocol"), "IACUC 123456");
        waitAndClick(getButton("Save"));
        waitForSaveToComplete();

        Assert.assertEquals(getLastModifiedRequestRow().get("iacucprotocol"), "IACUC 123456");
        waitAndClickAndWait(getButton("Submit"));

        DataRegionTable dr = new DataRegionTable.DataRegionFinder(getDriver()).waitFor();
        Assert.assertEquals("Submitted", dr.getDataAsText(0, "status"));

        beginAt("/mcc/" + getProjectName() + "/mccRequestAdmin.view");

        // Ensure groups set up correctly:
        String dataRegionName = getDataRegionName("webpartRAB");
        dr = new DataRegionTable.DataRegionFinder(getDriver()).withName(dataRegionName).waitFor();
        Assert.assertEquals(dr.getDataRowCount(), 1);

        waitAndClickAndWait(Locator.tagWithText("a", "Enter MCC Internal Review"));

        waitAndClick(getButton("Return to Investigator"));
        waitForElement(Locator.tagWithText("h2", "Return To Investigator").notHidden());
        waitAndClickAndWait(getButton("Submit"));

        dr = new DataRegionTable.DataRegionFinder(getDriver()).withName(getDataRegionName("webpartPending")).waitFor();
        dr.clickRowDetails(0);

        // Reset status to Submitted:
        waitAndClickAndWait(Locator.tagWithText("span", "Edit Request"));
        waitAndClickAndWait(getButton("Submit"));

        beginAt("/mcc/" + getProjectName() + "/mccRequestAdmin.view");
        dataRegionName = getDataRegionName("webpartPending");
        new DataRegionTable.DataRegionFinder(getDriver()).withName(dataRegionName).waitFor();
        waitAndClickAndWait(Locator.tagWithText("a", "Enter MCC Internal Review"));

        waitAndClick(Locator.tagWithText("span", "Assign to RAB Reviewers"));
        waitForElement(Locator.tagWithText("h2", "Assign for RAB Review").notHidden());
        waitForElement(Locator.tagWithText("p", getCurrentUserName()));
        waitAndClickAndWait(Locator.tagWithText("span", "Submit"));

        goBack();

        // Repeat to ensure the assignment is picked up
        waitAndClick(Locator.tagWithText("span", "Assign to RAB Reviewers"));
        waitForElement(Locator.tagWithText("h2", "Assign for RAB Review").notHidden());
        waitForElement(Locator.tagWithText("p", getCurrentUserName() + " (already assigned)"));
        waitAndClickAndWait(Locator.tagWithText("span", "Submit"));

        beginAt("/mcc/" + getProjectName() + "/rabRequestReview.view");
        waitAndClickAndWait(Locator.tagWithText("a", "Enter Review"));
        waitForElement(Locator.tagWithText("td", "cohort-othercharacteristics"));

        waitAndClick(Locator.tagWithText("span", "Submit Review"));
        waitForElement(Locator.tagWithClass("div", "Mui-error"));

        waitAndClick(Locator.tagWithText("div", "Not Decided"));
        waitAndClick(Locator.tagWithText("li", "I recommend this proposal"));
        waitAndClickAndWait(Locator.tagWithText("span", "Submit Review"));
        dr = new DataRegionTable.DataRegionFinder(getDriver()).waitFor();
        Assert.assertEquals(dr.getDataRowCount(), 0);


        beginAt("/mcc/" + getProjectName() + "/mccRequestAdmin.view");
        dataRegionName = getDataRegionName("webpartPending");
        new DataRegionTable.DataRegionFinder(getDriver()).withName(dataRegionName).waitFor();
        waitAndClickAndWait(Locator.tagWithText("a", "Enter Resource Availability Assessment"));

        // Assessment missing so form should not be valid
        waitAndClick(Locator.tagWithText("span", "Submit"));
        waitForElement(Locator.tagWithClass("label", "Mui-focused"));

        setFormElement(Locator.tagWithAttribute("textarea", "name",  "resourceAvailabilityAssessment"), "This is the assessment");
        waitAndClickAndWait(Locator.tagWithText("span", "Submit"));

        waitAndClickAndWait(Locator.tagWithText("a", "Enter Final Review"));
        waitAndClick(Locator.tagWithText("span", "Approve Request"));

        dataRegionName = getDataRegionName("webpartPending");
        dr = new DataRegionTable.DataRegionFinder(getDriver()).withName(dataRegionName).waitFor();
        Assert.assertEquals(dr.getDataRowCount(), 1);
    }

    private String getDataRegionName(String divName)
    {
        Locator.XPathLocator l = Locator.tagWithAttributeContaining("div", "id", divName);
        waitForElement(l);
        l = l.append(Locator.tag("form"));
        waitForElement(l);

        return getAttribute(l, "lk-region-form");
    }

    private FormElement[] getCoinvestigatorFields(int idx)
    {
        return new FormElement[]{
                new FormElement("coinvestigators-" + idx + "-lastName", "lastname", "coinvestigators-lastName"),
                new FormElement("coinvestigators-" + idx + "-firstName", "firstname", "coinvestigators-firstName"),
                new FormElement("coinvestigators-" + idx + "-middleInitial", "middleInitial", "mi"),
                new FormElement("coinvestigators-" + idx + "-institution", "institutionname", "coinvestigators-institution")
        };
    }

    private FormElement[] getCohortFields(int idx)
    {
        return new FormElement[]{
                new FormElement("animal-cohorts-" + idx + "-numberofanimals", "numberofanimals", 12),
                new FormElement("animal-cohorts-" + idx + "-sex", "sex", "Male").select("male"),
                new FormElement("animal-cohorts-" + idx + "-othercharacteristics", "othercharacteristics", "cohort-othercharacteristics").inputType("textarea")
        };
    }

    private void addCoinvestigator(int idx, boolean clickBtn)
    {
        if (clickBtn)
        {
            waitAndClick(Locator.tagWithAttribute("input", "value", "Add Co-investigator"));
        }

        FormElement[] fields = getCoinvestigatorFields(idx);
        for (FormElement f : fields)
        {
            f.setFieldValue(this);
        }
    }

    private void addCohort(int idx)
    {
        if (idx > 0)
        {
            waitAndClick(Locator.tagWithText("button", "Add Cohort"));
        }

        FormElement[] cohort = getCohortFields(idx);
        for (FormElement f : cohort)
        {
            f.setFieldValue(this);
        }
    }

    private void assertCoinvestigatorValues(String requestId, int expectedRecords) throws Exception
    {
        List<Map<String, Object>> rows = getCoinvestigatorRecords(requestId);
        Assert.assertEquals(rows.size(), expectedRecords);

        for (int i=0;i<rows.size();i++)
        {
            Map<String, Object> row = rows.get(i);
            FormElement[] cohort = getCoinvestigatorFields(i);
            for (FormElement f : cohort)
            {
                Assert.assertEquals(f.getDatabaseValue(), row.get(f.getDatabaseFieldName()));
            }
        }
    }

    private void assertCohortValues(String requestId, int expectedRecords) throws Exception
    {
        List<Map<String, Object>> cohortRows = getCohortRecords(requestId);
        Assert.assertEquals(cohortRows.size(), expectedRecords);

        for (int i=0;i<cohortRows.size();i++)
        {
            Map<String, Object> row = cohortRows.get(i);
            FormElement[] cohort = getCohortFields(i);
            for (FormElement f : cohort)
            {
                Assert.assertEquals(f.getDatabaseValue(), row.get(f.getDatabaseFieldName()));
            }
        }
    }

    private List<Map<String, Object>> getRequestRows() throws Exception
    {
        SelectRowsCommand sr = new SelectRowsCommand("mcc", "animalrequests");
        sr.addSort(new Sort("modified", Sort.Direction.DESCENDING));
        List<String> cols = new ArrayList<>(Arrays.stream(FORM_DATA).map(FormElement::getDatabaseFieldName).collect(Collectors.toList()));
        cols.add("objectid");
        cols.add("status");
        cols.add("iacucprotocol");
        sr.setColumns(cols);

        SelectRowsResponse srr = sr.execute(createDefaultConnection(), getProjectName());
        return srr.getRows();
    }

    private Map<String, Object> getLastModifiedRequestRow() throws Exception
    {
        List<Map<String, Object>> rr = getRequestRows();

        return rr.isEmpty() ? null : rr.get(0);
    }

    private List<Map<String, Object>> getCohortRecords(String requestId) throws Exception
    {
        SelectRowsCommand sr = new SelectRowsCommand("mcc", "requestcohorts");
        sr.addSort(new Sort("rowid", Sort.Direction.ASCENDING));
        sr.setColumns(Arrays.stream(getCohortFields(0)).map(FormElement::getDatabaseFieldName).collect(Collectors.toList()));
        sr.addFilter(new Filter("requestid", requestId));

        SelectRowsResponse srr = sr.execute(createDefaultConnection(), getProjectName());

        return srr.getRows();
    }

    private List<Map<String, Object>> getCoinvestigatorRecords(String requestId) throws Exception
    {
        SelectRowsCommand sr = new SelectRowsCommand("mcc", "coinvestigators");
        sr.addSort(new Sort("rowid", Sort.Direction.ASCENDING));
        sr.setColumns(Arrays.stream(getCoinvestigatorFields(0)).map(FormElement::getDatabaseFieldName).collect(Collectors.toList()));
        sr.addFilter(new Filter("requestid", requestId));

        SelectRowsResponse srr = sr.execute(createDefaultConnection(), getProjectName());

        return srr.getRows();
    }

    private void doRequestFormTestWithFailure() throws Exception
    {
        goToAnimalRequests();

        waitAndClickAndWait(Locator.tagWithText("a", "Submit New Animal Request"));
        waitForElement(getButton("Submit"));

        // This omits a required field
        setFormValues(List.of("lastName"));
        addCohort(0);
        waitAndClick(getButton("Submit"));

        waitForElement(Locator.tagWithText("li", "Last Name: Please fill out this field."));

        // Add Co-I
        waitAndClick(Locator.tagWithAttribute("input", "value", "Add Co-investigator"));
        waitForElement(Locator.tagWithText("li", "Institution: Please fill out this field."));

        addCoinvestigator(0, false);  //the button was clicked above
        waitAndClick(getButton("Save"));
        waitForElementToDisappear(Locator.tagWithText("li", "Institution: Please fill out this field."));

        // Add another, which makes it invalid again
        waitAndClick(Locator.tagWithAttribute("input", "value", "Add Co-investigator"));
        waitAndClick(getButton("Submit"));
        waitForElement(Locator.tagWithText("li", "Institution: Please fill out this field."));

        // Check for error messages. Now remove that Co-I block
        waitAndClick(Locator.tagWithText("p", "Co-Investigator 2").parent("div").followingSibling("div").index(0).child("input"));

        // Ensure form still in error-reporting state
        waitForElement(Locator.tagWithText("li", "Last Name: Please fill out this field."));

        // Repeat for Cohorts
        waitAndClick(Locator.tagWithAttribute("input", "value", "Add Cohort"));
        Locator removeBtn = Locator.tagWithText("p", "Cohort 2").parent("div").followingSibling("div").index(0).child("input");
        waitForElement(removeBtn);
        scrollIntoView(Locator.tagWithAttribute("input", "value", "Add Cohort"));

        // NOTE: this message seems to be browser-dependent. On Chrome is says 'Please fill out this field', but FF says 'Please enter a number'
        waitForElement(Locator.tagContainingText("li", "Number of Animals: Please"));
        waitAndClick(removeBtn);

        // Even though last name is missing, it should still be savable:
        waitAndClick(getButton("Save"));
        waitForSaveToComplete();

        doAndWaitForPageToLoad(() -> {
            waitAndClick(getButton("Cancel"));
            assertAlert("You are about to leave this page.");
        });

        // Now reopen this form:
        DataRegionTable dr = new DataRegionTable.DataRegionFinder(getDriver()).waitFor();
        dr.clickEditRow(0);

        getFormElementByName("lastName").waitFor(this);

        // TODO: make sure the field values are remembered
        // including cohort and co-investigator
        for (FormElement f : FORM_DATA)
        {
            if ("lastName".equals(f.getDatabaseFieldName()))
            {
                continue;
            }

            Assert.assertEquals(getFormElement(f.getLocator()), f.getExpectedInputFieldValue());
        }

        // Now fill in the missing field:
        getFormElementByName("lastName").setFieldValue(this);

        waitAndClickAndWait(getButton("Submit"));

        dr = new DataRegionTable.DataRegionFinder(getDriver()).waitFor();
        Assert.assertEquals("Submitted", dr.getDataAsText(0, "status"));

        dr.clickEditRow(0);
        getFormElementByName("lastName").waitFor(this);

        waitForElement(getButton("Update Request"));
        assertElementNotPresent(getButton("Submit"));
        impersonateRoles("Editor", "MCC Requestor");

        assertElementNotPresent(getButton("Save"));
        assertElementNotPresent(getButton("Submit"));
        assertElementNotPresent(getButton("Approve Request"));

        stopImpersonating();
    }

    private FormElement getFormElementByName(String name)
    {
        for (FormElement e : FORM_DATA)
        {
            if (name.equals(e.databaseFieldName))
            {
                return e;
            }
        }

        throw new IllegalArgumentException("Unknown field: " + name);
    }

    private void setAllFormValues()
    {
        setFormValues(null);
    }

    private void setFormValues(@Nullable Collection<String> fieldsToSkip)
    {
        for (FormElement el : FORM_DATA)
        {
            if (fieldsToSkip != null && fieldsToSkip.contains(el.databaseFieldName))
            {
                continue;
            }

            el.setFieldValue(this);
        }
    }

    @Override
    protected void doCleanup(boolean afterTest) throws TestTimeoutException
    {
        super.doCleanup(afterTest);
    }

    @BeforeClass
    public static void setupProject() throws Exception
    {
        MccTest init = (MccTest)getCurrentTest();
        init.doSetup();
    }

    private void doSetup() throws Exception
    {
        // NOTE: delay setting module properties until the study exists, since dashboard depends on it
        _containerHelper.createProject(getProjectName());
        _containerHelper.enableModules(Arrays.asList("MCC", "Study"));

        importStudy(getProjectName());

        _containerHelper.setFolderType("MCC");
        setModuleProperties(Arrays.asList(
                new ModulePropertyValue("MCC", "/" + getProjectName(), "MCCContainer", "/" + getProjectName()),
                new ModulePropertyValue("MCC", "/" + getProjectName(), "MCCRequestContainer", "/" + getProjectName()),
                new ModulePropertyValue("MCC", "/" + getProjectName(), "MCCContactUsers", getCurrentUserName()),
                new ModulePropertyValue("MCC", "/" + getProjectName(), "MCCRequestNotificationUsers", getCurrentUserName()),
                new ModulePropertyValue("MCC", "/" + getProjectName(), "MCCInternalDataContainer", "/" + getProjectName() + "/Colonies"),
                new ModulePropertyValue("EHR", "/" + getProjectName(), "EHRStudyContainer", "/" + getProjectName()),
                new ModulePropertyValue("EHR", "/" + getProjectName(), "EHRAdminUser", getCurrentUser())
        ));

        beginAt("/mcc/" + getProjectName() + "/configureMcc.view");
        clickButton("OK");
        waitForCensusToLoad();

        ApiPermissionsHelper helper = new ApiPermissionsHelper(this);
        if (!helper.isUserInGroup(getCurrentUser(), "MCC RAB Members", "/", PermissionsHelper.PrincipalType.USER))
        {
            helper.addUserToSiteGroup(getCurrentUser(), "MCC RAB Members");
        }
        else
        {
            log("Member already in group. This is not expected for fresh installations or TeamCity");
        }

        // Raw data folders:
        _containerHelper.createSubfolder(getProjectName(), "Colonies", "MCC Colony");
        for (String name : Arrays.asList("SNPRC", "WNPRC", "UCSD", "Other"))
        {
            _containerHelper.createSubfolder(getProjectName() + "/Colonies", name, "MCC Colony");
            importStudy(getProjectName() + "/Colonies/" + name);
            waitForElement(Locator.tagWithText("a", "Populate Lookups"));
        }
    }

    private void populateLookups(String name)
    {
        beginAt(getProjectName() + "/Colonies/" + name + "/project-begin.view");
        waitAndClickAndWait(Locator.tagWithText("a", "Populate Lookups"));
        waitAndClick(Ext4Helper.Locators.ext4Button("Populate Lookup Sets"));
        waitForElement(Locator.tagWithText("div", "Populating lookup_sets..."));
        waitForElement(Locator.tagWithText("div", "Populate Complete"));

        waitAndClick(Ext4Helper.Locators.ext4Button("Populate All"));
        waitForElement(Locator.tagWithText("div", "Populate Complete"));
    }

    private void testInvalidId()
    {
        beginAt("/mcc/" + getProjectName() + "/animalRequest.view?requestId=foo");

        waitForElement(Locator.tagWithText("div", "There is no request with id: foo"));
    }


    private void importStudy(String containerPath)
    {
        beginAt(WebTestHelper.getBaseURL() + "/mcc/" + containerPath + "/importStudy.view");
        clickButton("OK");
        waitForPipelineJobsToComplete(1, "Study import", false, MAX_WAIT_SECONDS * 2500);

        beginAt(WebTestHelper.getBaseURL() + "/ehr/" + containerPath + "/ensureQcStates.view");
        clickButton("OK");
    }

    @Before
    public void preTest()
    {
        goToProjectHome();
    }

    @Override
    protected String getProjectName()
    {
        return "MccTestProject";
    }

    @Override
    public List<String> getAssociatedModules()
    {
        return Collections.singletonList("Mcc");
    }
}