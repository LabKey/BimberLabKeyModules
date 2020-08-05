package org.labkey.flowassays;

import org.labkey.api.data.Container;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.exp.api.ExpProtocol;
import org.labkey.api.laboratory.LaboratoryService;
import org.labkey.api.laboratory.NavItem;
import org.labkey.api.laboratory.QueryCountNavItem;
import org.labkey.api.laboratory.SummaryNavItem;
import org.labkey.api.laboratory.TabbedReportItem;
import org.labkey.api.laboratory.assay.AbstractAssayDataProvider;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.query.FieldKey;
import org.labkey.api.security.User;
import org.labkey.flowassays.assay.FlowNavItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: bimber
 * Date: 4/14/13
 * Time: 2:09 PM
 */
public class FlowDataProvider extends AbstractAssayDataProvider
{
    public static final String ASSAY_PROVIDER_NAME = "Flow";

    public FlowDataProvider(Module m)
    {
        _providerName = ASSAY_PROVIDER_NAME;
        _module = m;
    }

    private boolean areModulesActive(Container c)
    {
        return c.getActiveModules().contains(ModuleLoader.getInstance().getModule(FlowAssaysManager.FLOW_NAME)) && c.getActiveModules().contains(_module);
    }

    @Override
    public boolean isModuleEnabled(Container c)
    {
        return super.isModuleEnabled(c) && areModulesActive(c);
    }

    @Override
    public List<NavItem> getDataNavItems(Container c, User u)
    {
        List<NavItem> items = new ArrayList<NavItem>();

        if (areModulesActive(c))
        {
            List<ExpProtocol> protocols = getProtocols(c);
            for (ExpProtocol p : protocols)
            {
                items.add(new FlowNavItem(this, LaboratoryService.NavItemCategory.data));
            }
        }

        return items;
    }

    @Override
    public List<NavItem> getReportItems(Container c, User u)
    {
        List<NavItem> items = new ArrayList<NavItem>();

        return Collections.unmodifiableList(items);
    }

    @Override
    public List<TabbedReportItem> getTabbedReportItems(Container c, User u)
    {
        List<TabbedReportItem> items = new ArrayList<TabbedReportItem>();

        return Collections.unmodifiableList(items);
    }

    @Override
    public List<SummaryNavItem> getSummary(Container c, User u)
    {
        List<SummaryNavItem> items = new ArrayList<>();

        if (areModulesActive(c))
        {
            //items.add(new QueryCountNavItem(this, FlowAssaysSchema.NAME, "FCSFileMetadata", LaboratoryService.NavItemCategory.data.name(), "FCS Files"));
            items.add(new QueryCountNavItem(this, FlowAssaysManager.FLOW_SCHEMA_NAME, "FCSFiles", LaboratoryService.NavItemCategory.data, LaboratoryService.NavItemCategory.data.name(), "FCS Files"));
        }

        return Collections.unmodifiableList(items);
    }

    @Override
    public List<NavItem> getSubjectIdSummary(Container c, User u, String subjectId)
    {
        List<NavItem> items = new ArrayList<NavItem>();

        if (areModulesActive(c))
        {
            QueryCountNavItem item = new QueryCountNavItem(this, FlowAssaysSchema.NAME, "FCSFileMetadata", LaboratoryService.NavItemCategory.data, LaboratoryService.NavItemCategory.data.name(), "FCS Files");
            item.setFilter(new SimpleFilter(FieldKey.fromString("subjectId"), subjectId));
            items.add(item);
        }

        return Collections.unmodifiableList(items);
    }
}