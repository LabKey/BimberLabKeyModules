package org.labkey.tcrdb;

import org.json.JSONObject;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.laboratory.LaboratoryService;
import org.labkey.api.laboratory.NavItem;
import org.labkey.api.laboratory.QueryCountNavItem;
import org.labkey.api.laboratory.QueryImportNavItem;
import org.labkey.api.laboratory.SummaryNavItem;
import org.labkey.api.ldk.table.QueryCache;
import org.labkey.api.module.Module;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.AbstractSequenceDataProvider;
import org.labkey.api.view.ActionURL;
import org.labkey.api.view.ViewContext;
import org.labkey.api.view.template.ClientDependency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Created by bimber on 6/14/2016.
 */
public class TCRdbProvider extends AbstractSequenceDataProvider
{
    public static final String NAME = "TCRdb";
    private final Module _module;

    public TCRdbProvider(Module module)
    {
        _module = module;
    }

    @Override
    public List<NavItem> getSequenceNavItems(Container c, User u, SequenceNavItemCategory category)
    {
        return Collections.emptyList();
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public ActionURL getInstructionsUrl(Container c, User u)
    {
        return null;
    }

    @Override
    public List<NavItem> getSubjectIdSummary(Container c, User u, String subjectId)
    {
        return Collections.emptyList();
    }

    @Override
    public List<NavItem> getDataNavItems(Container c, User u)
    {
        List<NavItem> items = new ArrayList<>();
        QueryCache cache = new QueryCache();
        if (!c.getActiveModules().contains(getOwningModule()))
        {
            return Collections.emptyList();
        }

        items.add(new QueryImportNavItem(this, TCRdbSchema.NAME, TCRdbSchema.TABLE_CLONES, "TCR Clones", LaboratoryService.NavItemCategory.data, NAME, cache));
        return Collections.unmodifiableList(items);
    }

    @Override
    public List<NavItem> getSampleNavItems(Container c, User u)
    {
        return Collections.emptyList();
    }

    @Override
    public List<NavItem> getSettingsItems(Container c, User u)
    {
        List<NavItem> items = new ArrayList<>();
        if (ContainerManager.getSharedContainer().equals(c))
        {
            items.add(new QueryImportNavItem(this, ContainerManager.getSharedContainer(), TCRdbSchema.NAME, TCRdbSchema.TABLE_MIXCR_LIBRARIES, LaboratoryService.NavItemCategory.settings, "MiXCR Libraries", NAME));
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
        return null;
    }

    @Override
    public Module getOwningModule()
    {
        return _module;
    }

    @Override
    public List<SummaryNavItem> getSummary(Container c, User u)
    {
        return Collections.singletonList(new QueryCountNavItem(this, TCRdbSchema.NAME, TCRdbSchema.TABLE_CLONES, LaboratoryService.NavItemCategory.data, LaboratoryService.NavItemCategory.data.name(),  "TCR Clones"));
    }
}
