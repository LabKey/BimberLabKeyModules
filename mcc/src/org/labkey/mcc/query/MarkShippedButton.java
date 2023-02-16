package org.labkey.mcc.query;

import org.labkey.api.ldk.table.SimpleButtonConfigFactory;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.view.template.ClientDependency;
import org.labkey.mcc.MccModule;
import org.labkey.mcc.security.MccDataAdminPermission;

import java.util.Arrays;
import java.util.List;

public class MarkShippedButton extends SimpleButtonConfigFactory
{
    public MarkShippedButton()
    {
        super(ModuleLoader.getInstance().getModule(MccModule.class), "Mark Animal Shipped", "MCC.window.MarkShippedWindow.buttonHandler(dataRegionName);", List.of(ClientDependency.supplierFromPath("mcc/window/MarkShippedWindow.js")));
        setPermission(MccDataAdminPermission.class);
    }
}
