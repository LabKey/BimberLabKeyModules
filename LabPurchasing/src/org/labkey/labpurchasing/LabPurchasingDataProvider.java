package org.labkey.labpurchasing;

import org.json.JSONObject;
import org.labkey.api.data.Container;
import org.labkey.api.laboratory.AbstractDataProvider;
import org.labkey.api.laboratory.DetailsUrlWithoutLabelNavItem;
import org.labkey.api.laboratory.LaboratoryService;
import org.labkey.api.laboratory.NavItem;
import org.labkey.api.laboratory.SummaryNavItem;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.security.User;
import org.labkey.api.view.ActionURL;
import org.labkey.api.view.ViewContext;
import org.labkey.api.view.template.ClientDependency;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class LabPurchasingDataProvider extends AbstractDataProvider
{
    @Override
    public String getName()
    {
        return "Lab Purchasing";
    }

    @Override
    public ActionURL getInstructionsUrl(Container c, User u)
    {
        return null;
    }

    @Override
    public List<NavItem> getDataNavItems(Container c, User u)
    {
        return Collections.emptyList();
    }

    @Override
    public List<NavItem> getSampleNavItems(Container c, User u)
    {
        return Collections.emptyList();
    }

    @Override
    public List<NavItem> getSettingsItems(Container c, User u)
    {
        return List.of(
                new DetailsUrlWithoutLabelNavItem(this, "Purchasing", DetailsURL.fromString("/labpurchasing/begin.view"), LaboratoryService.NavItemCategory.misc, "Purchasing")
        );
    }

    @Override
    public List<NavItem> getMiscItems(Container c, User u)
    {
        List<NavItem> items = new ArrayList<>();
        if (c.getActiveModules().contains(ModuleLoader.getInstance().getModule(LabPurchasingModule.class)))
        {
            items.add(new DetailsUrlWithoutLabelNavItem(this, "Purchasing", DetailsURL.fromString("/labpurchasing/begin.view"), LaboratoryService.NavItemCategory.misc, "Purchasing"));
        }

        return items;
    }

    @Override
    public JSONObject getTemplateMetadata(ViewContext ctx)
    {
        return null;
    }

    @Override
    public Set<ClientDependency> getClientDependencies()
    {
        return Collections.emptySet();
    }

    @Override
    public Module getOwningModule()
    {
        return ModuleLoader.getInstance().getModule(LabPurchasingModule.class);
    }

    @Override
    public List<SummaryNavItem> getSummary(Container c, User u)
    {
        return Collections.emptyList();
    }

    @Override
    public List<NavItem> getSubjectIdSummary(Container c, User u, String subjectId)
    {
        return Collections.emptyList();
    }
}
