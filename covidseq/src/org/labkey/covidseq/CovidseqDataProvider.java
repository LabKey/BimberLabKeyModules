package org.labkey.covidseq;

import org.json.JSONObject;
import org.labkey.api.data.Container;
import org.labkey.api.data.TableInfo;
import org.labkey.api.laboratory.AbstractDataProvider;
import org.labkey.api.laboratory.LaboratoryService;
import org.labkey.api.laboratory.NavItem;
import org.labkey.api.laboratory.QueryCountNavItem;
import org.labkey.api.laboratory.QueryImportNavItem;
import org.labkey.api.laboratory.SummaryNavItem;
import org.labkey.api.ldk.table.QueryCache;
import org.labkey.api.module.Module;
import org.labkey.api.security.User;
import org.labkey.api.view.ActionURL;
import org.labkey.api.view.ViewContext;
import org.labkey.api.view.template.ClientDependency;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class CovidseqDataProvider extends AbstractDataProvider
{
    private Module _module;

    public CovidseqDataProvider(Module m)
    {
        _module = m;
    }

    @Override
    public String getName()
    {
        return "CovidSeq";
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
        QueryCache cache = new QueryCache();

        return Arrays.asList(
            new QueryImportNavItem(this, CovidseqSchema.NAME, CovidseqSchema.TABLE_SAMPLES, "COVID Samples", LaboratoryService.NavItemCategory.samples, "Samples", cache){
                @Override
                public ActionURL getImportUrl(Container c, User u)
                {
                    TableInfo ti = getTableInfo(c, u);
                    return ti == null ? null : ti.getImportDataURL(c);
                }
            }
        );
    }

    @Override
    public List<NavItem> getSettingsItems(Container c, User u)
    {
        return Collections.emptyList();
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

        for (NavItem nav : getSampleNavItems(c, u))
        {
            if (nav.isVisible(c, u))
            {
                QueryImportNavItem item = ((QueryImportNavItem)nav);
                items.add(new QueryCountNavItem(this, item.getSchema(), item.getQuery(), item.getItemType(), item.getReportCategory(), item.getLabel()));
            }
        }

        return Collections.unmodifiableList(items);
    }

    @Override
    public List<NavItem> getSubjectIdSummary(Container c, User u, String subjectId)
    {
        return Collections.emptyList();
    }
}
