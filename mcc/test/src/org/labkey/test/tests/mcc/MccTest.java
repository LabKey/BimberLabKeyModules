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

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.labkey.test.BaseWebDriverTest;
import org.labkey.test.TestTimeoutException;
import org.labkey.test.categories.External;
import org.labkey.test.categories.LabModule;

import java.util.Collections;
import java.util.List;

@Category({External.class, LabModule.class})
public class MccTest extends BaseWebDriverTest
{
    @Test
    public void testMccModule() throws Exception
    {
        _containerHelper.enableModule("Mcc");

        doRequestFormTest();
    }

    private void doRequestFormTest() throws Exception
    {
        beginAt("/home/mcc-requestForm.view");

        //click through form, etc.
    }

    @Override
    protected void doCleanup(boolean afterTest) throws TestTimeoutException
    {
        super.doCleanup(afterTest);
    }

    @BeforeClass
    public static void setupProject()
    {
        MccTest init = (MccTest)getCurrentTest();
        init.doSetup();
    }

    private void doSetup()
    {
        _containerHelper.createProject(getProjectName());
        _containerHelper.enableModule("MCC");
    }

    @Before
    public void preTest()
    {
        goToProjectHome();
    }

    @Override
    protected String getProjectName()
    {
        return "MccTest Project";
    }

    @Override
    public List<String> getAssociatedModules()
    {
        return Collections.singletonList("Mcc");
    }
}