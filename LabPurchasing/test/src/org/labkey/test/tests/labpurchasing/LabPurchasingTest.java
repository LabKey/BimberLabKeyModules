package org.labkey.test.tests.labpurchasing;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.labkey.test.BaseWebDriverTest;
import org.labkey.test.Locator;
import org.labkey.test.TestTimeoutException;
import org.labkey.test.categories.External;
import org.labkey.test.categories.LabModule;
import org.labkey.test.components.ext4.Window;
import org.labkey.test.util.DataRegionTable;
import org.labkey.test.util.Ext4Helper;
import org.labkey.test.util.ext4cmp.Ext4ComboRef;
import org.labkey.test.util.ext4cmp.Ext4FieldRef;
import org.labkey.test.util.ext4cmp.Ext4GridRef;
import org.openqa.selenium.WebElement;

import java.util.Collections;
import java.util.List;

@Category({External.class, LabModule.class})
public class LabPurchasingTest extends BaseWebDriverTest
{
    @Override
    protected void doCleanup(boolean afterTest) throws TestTimeoutException
    {
        _containerHelper.deleteProject(getProjectName(), afterTest);
    }

    @BeforeClass
    public static void setupProject()
    {
        LabPurchasingTest init = (LabPurchasingTest)getCurrentTest();

        init.doSetup();
    }

    private void doSetup()
    {
        _containerHelper.createProject(getProjectName(), "Laboratory Folder");
        _containerHelper.enableModule("LabPurchasing");
        populateData();
    }

    @Before
    public void preTest()
    {
        goToProjectHome();
    }

    private void populateData()
    {
        beginAt("/" + getProjectName() + "/labpurchasing-populateData.view");

        waitAndClick(Ext4Helper.Locators.ext4Button("Delete All"));
        waitForElement(Locator.tagWithText("div", "Delete Complete"));

        waitAndClick(Ext4Helper.Locators.ext4Button("Populate Reference Vendors"));
        waitForElement(Locator.tagWithText("div", "Populating vendors..."));
        waitForElement(Locator.tagWithText("div", "Populate Complete"));

        waitAndClick(Ext4Helper.Locators.ext4Button("Populate Units"));
        waitForElement(Locator.tagWithText("div", "Populating purchasingUnits..."));
        waitForElement(Locator.tagWithText("div", "Populate Complete"));

        waitAndClick(Ext4Helper.Locators.ext4Button("Populate Locations"));
        waitForElement(Locator.tagWithText("div", "Populating purchasingLocations..."));
        waitForElement(Locator.tagWithText("div", "Populate Complete"));

        waitAndClick(Ext4Helper.Locators.ext4Button("Populate Reference Items"));
        waitForElement(Locator.tagWithText("div", "Populating referenceItems..."));
        waitForElement(Locator.tagWithText("div", "Populate Complete"), WAIT_FOR_PAGE);
    }

    @Test
    public void testLabPurchasingModule()
    {
        beginAt("/" + getProjectName() + "/labpurchasing-begin.view");
        waitAndClickAndWait(Locator.linkWithText("Enter New Order"));

        // Add a vendor:
        Ext4GridRef grid = _ext4Helper.queryOne("grid", Ext4GridRef.class);
        waitAndClick(grid.getTbarButton("Add New Vendor"));
        Ext4FieldRef.waitForField(this, "Vendor Name");
        Ext4FieldRef.getForLabel(this, "Vendor Name").setValue("New Vendor 1");
        Ext4FieldRef.getForLabel(this, "Phone").setValue("555-555-5555");
        sleep(200); //let formbind work
        waitAndClick(Ext4Helper.Locators.ext4Button("Add Vendor"));
        new Window.WindowFinder(getDriver()).withTitle("Success").waitFor();
        click(Ext4Helper.Locators.ext4Button("OK"));
        sleep(500); // allow the client store to reset

        // Adding the new vendor should have updated the combo:
        grid.clickTbarButton("Add New");
        getArtifactCollector().dumpPageSnapshot("LabPurchasingBeforeVendor");
        grid.setGridCell(1, "vendorId", "New Vendor 1");
        try
        {
            Assert.assertTrue("Missing vendor cell", isElementPresent(Ext4GridRef.locateExt4GridCell("New Vendor 1")));
        }
        catch (AssertionError e)
        {
            getArtifactCollector().dumpPageSnapshot("LabPurchasingVendor0");
            WebElement el = grid.startEditing(1, "vendorId");
            getArtifactCollector().dumpPageSnapshot("LabPurchasingVendor1");

            boolean storeExists = executeScript("return Ext4.StoreManager.get('labpurchasing||vendors||rowId||vendorName') != null", Boolean.class);
            log("Store exists: " + storeExists);
            if (storeExists)
            {
                long recordCount = executeScript("return Ext4.StoreManager.get('labpurchasing||vendors||rowId||vendorName').getCount()", Long.class);
                log("Record count: " + recordCount);
            }

            setFormElementJS(el, "");
            el.sendKeys("New Vendor 1");
            sleep(1000);

            getArtifactCollector().dumpPageSnapshot("LabPurchasingVendor2");

            throw e;
        }

        // Try to save, expect error:
        click(Ext4Helper.Locators.ext4Button("Order Items"));
        new Window.WindowFinder(getDriver()).withTitle("Error").waitFor();
        click(Ext4Helper.Locators.ext4Button("OK"));

        grid.setGridCell(1, "itemName", "Item1");
        grid.setGridCell(1, "quantity", "2");
        grid.setGridCell(1, "unitCost", "10");
        Assert.assertEquals(20L, grid.getFieldValue(1, "totalCost"));
        waitAndClick(Ext4Helper.Locators.ext4Button("Order Items"));
        new Window.WindowFinder(getDriver()).withTitle("Success").waitFor();
        clickAndWait(Ext4Helper.Locators.ext4Button("OK"));

        // Create new item, re-using previous:
        DataRegionTable dr = DataRegionTable.DataRegion(getDriver()).withName("query").waitFor();
        dr.clickHeaderButtonAndWait(DataRegionTable.getInsertNewButtonText());

        grid = _ext4Helper.queryOne("grid", Ext4GridRef.class);
        grid.clickTbarButton("Re-order Previous Item");
        new Window.WindowFinder(getDriver()).withTitle("Re-order Previous Item").waitFor();

        // NOTE: this store initially loads with the full list. If we set the vendor combo too quickly that filter event will happen before the original store load
        Ext4ComboRef.waitForField(this, "Item");
        Ext4ComboRef itemField = Ext4ComboRef.getForLabel(this, "Item");
        itemField.waitForStoreLoad();

        Ext4ComboRef vendorField = Ext4ComboRef.getForLabel(this, "Vendor (optional)");
        vendorField.setComboByDisplayValue("AbCam");
        sleep(200);
        itemField.waitForStoreLoad();
        Assert.assertEquals(25L, itemField.getFnEval("return this.store.getCount()"));

        itemField.setComboByDisplayValue("Streptavidin Conjugation Kit (300ug)");
        waitAndClick(Ext4Helper.Locators.ext4Button("Re-order Item"));

        // NOTE: need to resolve displayValue
        //Assert.assertEquals("AbCam", grid.getFieldValue(1, "vendorId"));
        Assert.assertEquals("Streptavidin Conjugation Kit (300ug)", grid.getFieldValue(1, "itemName"));
        Assert.assertEquals("ab102921", grid.getFieldValue(1, "itemNumber"));
        Assert.assertEquals("Kit", grid.getFieldValue(1, "units"));
        Assert.assertEquals(419L, grid.getFieldValue(1, "unitCost"));

        grid.setGridCell(1, "quantity", "2");
        Assert.assertEquals(838L, grid.getFieldValue(1, "totalCost"));

        waitAndClick(Ext4Helper.Locators.ext4Button("Order Items"));
        new Window.WindowFinder(getDriver()).withTitle("Success").waitFor();
        clickAndWait(Ext4Helper.Locators.ext4Button("OK"));

        dr = DataRegionTable.DataRegion(getDriver()).withName("query").waitFor();
        dr.checkCheckbox(1);
        dr.clickHeaderMenu("More Actions", true, "Enter Order Info");
        grid = _ext4Helper.queryOne("grid", Ext4GridRef.class);
        waitForElement(Locator.tagWithText("div", getCurrentUserName()).withClass("x4-grid-cell-inner "));
        Assert.assertEquals(1, grid.getRowCount());
        grid.setGridCell(1, "vendorId", "AddGene");
        grid.setGridCell(1, "orderNumber", "OrderXXXX");
        Assert.assertEquals(getCurrentUserName(), grid.getFieldValue(1, "orderedBy"));
        Assert.assertNotNull(grid.getFieldValue(1, "orderDate"));
        waitAndClick(grid.getTbarButton("Save Changes"));
        new Window.WindowFinder(getDriver()).withTitle("Success").waitFor();
        clickAndWait(Ext4Helper.Locators.ext4Button("OK"));

        dr = DataRegionTable.DataRegion(getDriver()).withName("query").waitFor();
        dr.goToView("Waiting for Item");
        dr.checkCheckbox(0);
        Assert.assertEquals("OrderXXXX", dr.getDataAsText(0, "orderNumber"));

        dr.clickHeaderMenu("More Actions", false, "Mark Received");
        new Window.WindowFinder(getDriver()).withTitle("Mark Received").waitFor();
        Ext4FieldRef.waitForField(this, "Item Location");
        Ext4FieldRef.getForLabel(this, "Item Location").setValue("-80 Freezer");
        clickAndWait(Ext4Helper.Locators.ext4Button("Submit"));

        dr = DataRegionTable.DataRegion(getDriver()).withName("query").waitFor();
        Assert.assertEquals(0, dr.getDataRowCount());

        dr.goToView("All Items");
        Assert.assertEquals("-80 Freezer", dr.getDataAsText(1, "itemLocation"));
    }

    @Override
    protected BrowserType bestBrowser()
    {
        return BrowserType.CHROME;
    }

    @Override
    protected String getProjectName()
    {
        return "LabPurchasingTest_Project";
    }

    @Override
    public List<String> getAssociatedModules()
    {
        return Collections.singletonList("LabPurchasing");
    }
}
