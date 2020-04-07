package org.labkey.mgap.buttons;

import org.labkey.api.ldk.table.SimpleButtonConfigFactory;
import org.labkey.api.module.Module;
import org.labkey.api.view.template.ClientDependency;

import java.util.Arrays;

public class ReleaseButton extends SimpleButtonConfigFactory
{
    public ReleaseButton(Module owner)
    {
        super(owner, "Create mGAP Release", "mGAP.window.ReleaseWindow.buttonHandler();", Arrays.asList(
                ClientDependency.supplierFromPath("laboratory.context"),
                ClientDependency.supplierFromPath("/SequenceAnalysis/window/OutputHandlerWindow.js"),
                ClientDependency.supplierFromPath("/mGAP/window/ReleaseWindow.js"),
                ClientDependency.supplierFromPath("sequenceanalysis/field/GenomeFileSelectorField.js")));
    }
}
