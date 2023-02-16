package org.labkey.mcc.query;

import org.labkey.api.ldk.table.SimpleButtonConfigFactory;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.view.template.ClientDependency;
import org.labkey.mcc.MccModule;
import org.labkey.mcc.security.MccRequestAdminPermission;

import java.util.Arrays;
import java.util.List;

public class ReviewerNotifyButton extends SimpleButtonConfigFactory
{
    public ReviewerNotifyButton()
    {
        super(ModuleLoader.getInstance().getModule(MccModule.class), "Notify Reviewers", "MCC.window.ReviewerNotifyWindow.buttonHandler(dataRegionName);", List.of(ClientDependency.supplierFromPath("mcc/window/ReviewerNotifyWindow.js")));
        setPermission(MccRequestAdminPermission.class);
    }
}
