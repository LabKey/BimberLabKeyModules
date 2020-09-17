package org.labkey.tcrdb;

import org.json.JSONObject;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.laboratory.DetailsUrlWithoutLabelNavItem;
import org.labkey.api.laboratory.LaboratoryService;
import org.labkey.api.laboratory.NavItem;
import org.labkey.api.laboratory.QueryCountNavItem;
import org.labkey.api.laboratory.QueryImportNavItem;
import org.labkey.api.laboratory.QueryTabbedReportItem;
import org.labkey.api.laboratory.SimpleSettingsItem;
import org.labkey.api.laboratory.SummaryNavItem;
import org.labkey.api.laboratory.TabbedReportItem;
import org.labkey.api.ldk.table.QueryCache;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.query.FieldKey;
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
    private Module _module;

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
    public List<NavItem> getMiscItems(Container c, User u)
    {
        List<NavItem> items = new ArrayList<>();
        if (c.getActiveModules().contains(ModuleLoader.getInstance().getModule(TCRdbModule.class)))
        {
            items.add(new DetailsUrlWithoutLabelNavItem(this, "Export 10x Library Information", DetailsURL.fromString("tcrdb/libraryExport.view"), LaboratoryService.NavItemCategory.misc, NAME));
        }

        return items;
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

        TCRdbImportNavItem item = new TCRdbImportNavItem(this, "TCR Stims/Sorts (SMART-seq)", LaboratoryService.NavItemCategory.data, NAME);
        item.setQueryCache(cache);
        items.add(item);

        TCRdbBulkImportNavItem item2 = new TCRdbBulkImportNavItem(this, "TCR/10x Import 1: Stims/cDNA", LaboratoryService.NavItemCategory.data, NAME, "tcrdb/poolImport.view");
        item2.setQueryCache(cache);
        items.add(item2);

        TCRdbBulkImportNavItem item3 = new TCRdbBulkImportNavItem(this, "TCR/10x Import 2: Libraries/Readsets", LaboratoryService.NavItemCategory.data, NAME, "tcrdb/cDNAImport.view");
        item3.setQueryCache(cache);
        items.add(item3);

        items.add(new QueryImportNavItem(this, TCRdbSchema.NAME, TCRdbSchema.TABLE_CLONES, "TCR Clones", LaboratoryService.NavItemCategory.data, NAME, cache));
        items.add(new QueryImportNavItem(this, TCRdbSchema.NAME, TCRdbSchema.TABLE_CDNAS, "TCR cDNA Libraries", LaboratoryService.NavItemCategory.data, NAME, cache){
            @Override
            public ActionURL getImportUrl(Container c, User u)
            {
                return null;
            }
        });

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
            items.add(new QueryImportNavItem(this, ContainerManager.getSharedContainer(), TCRdbSchema.NAME, TCRdbSchema.TABLE_LIBRARIES, LaboratoryService.NavItemCategory.settings, "MiXCR Libraries", NAME));
        }
        else
        {
            items.add(new SimpleSettingsItem(this, TCRdbSchema.NAME, "peptides", NAME, "Peptides/Stims"));
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
        List<SummaryNavItem> items = new ArrayList<>();

        items.add(new QueryCountNavItem(this, TCRdbSchema.NAME, "stims", LaboratoryService.NavItemCategory.data, LaboratoryService.NavItemCategory.data.name(),  "TCR Stims"));
        items.add(new QueryCountNavItem(this, TCRdbSchema.NAME, "sorts", LaboratoryService.NavItemCategory.data, LaboratoryService.NavItemCategory.data.name(), "TCR Sorts"));
        items.add(new QueryCountNavItem(this, TCRdbSchema.NAME, "cdnas", LaboratoryService.NavItemCategory.data, LaboratoryService.NavItemCategory.data.name(), "TCR cDNA Libraries"));
        items.add(new QueryCountNavItem(this, TCRdbSchema.NAME, "clones", LaboratoryService.NavItemCategory.data, LaboratoryService.NavItemCategory.data.name(),  "TCR Clones"));

        return Collections.unmodifiableList(items);
    }

    @Override
    public List<NavItem> getSubjectIdSummary(Container c, User u, String subjectId)
    {
        return Collections.emptyList();
    }

    @Override
    public List<TabbedReportItem> getTabbedReportItems(Container c, User u)
    {
        if (!c.getActiveModules().contains(getOwningModule()))
        {
            return Collections.emptyList();
        }

        List<TabbedReportItem> items = new ArrayList<>();

        NavItem owner = getDataNavItems(c, u).get(0);
        String category = NAME;
        QueryCache cache = new QueryCache();

        TabbedReportItem stims = new QueryTabbedReportItem(cache, this, TCRdbSchema.NAME, TCRdbSchema.TABLE_STIMS, "TCR Stims/Blood Draws", category);
        stims.setOwnerKey(owner.getPropertyManagerKey());
        items.add(stims);

        TabbedReportItem sorts = new QueryTabbedReportItem(cache, this, TCRdbSchema.NAME, TCRdbSchema.TABLE_SORTS, "TCR Sorts", category);
        sorts.setSubjectIdFieldKey(FieldKey.fromString("stimId/animalId"));
        sorts.setSampleDateFieldKey(FieldKey.fromString("stimId/date"));
        sorts.setAllProjectsFieldKey(FieldKey.fromString("stimId/allProjectsPivot"));
        sorts.setOverlappingProjectsFieldKey(FieldKey.fromString("stimId/overlappingProjectsPivot"));
        sorts.setOwnerKey(owner.getPropertyManagerKey());
        items.add(sorts);

        TabbedReportItem cdnas = new QueryTabbedReportItem(cache, this, TCRdbSchema.NAME, TCRdbSchema.TABLE_CDNAS, "TCR cDNA Libraries", category);
        cdnas.setSubjectIdFieldKey(FieldKey.fromString("sortId/stimId/animalId"));
        cdnas.setSampleDateFieldKey(FieldKey.fromString("sortId/stimId/date"));
        cdnas.setAllProjectsFieldKey(FieldKey.fromString("sortId/stimId/allProjectsPivot"));
        cdnas.setOverlappingProjectsFieldKey(FieldKey.fromString("sortId/stimId/overlappingProjectsPivot"));
        cdnas.setOwnerKey(owner.getPropertyManagerKey());
        items.add(cdnas);

        return items;
    }
}
