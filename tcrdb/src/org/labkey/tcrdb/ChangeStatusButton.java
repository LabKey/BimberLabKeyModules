package org.labkey.tcrdb;

import org.labkey.api.ldk.table.SimpleButtonConfigFactory;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.view.template.ClientDependency;

import java.util.Arrays;

/**
 * User: bimber
 * Date: 7/16/2014
 * Time: 5:37 PM
 */
public class ChangeStatusButton extends SimpleButtonConfigFactory
{
    public ChangeStatusButton()
    {
        super(ModuleLoader.getInstance().getModule(TCRdbModule.class), "Change Status", "TCRdb.window.ChangeStatusWindow.buttonHandlerForStims(dataRegionName);", Arrays.asList(
                ClientDependency.supplierFromPath("tcrdb/window/ChangeStatusWindow.js"),
                ClientDependency.supplierFromPath("ldk/field/SimpleCombo.js")));
    }
}
