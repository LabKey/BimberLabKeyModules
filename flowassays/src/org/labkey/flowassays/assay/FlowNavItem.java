package org.labkey.flowassays.assay;

import org.labkey.api.data.Container;
import org.labkey.api.laboratory.AbstractImportingNavItem;
import org.labkey.api.laboratory.LaboratoryService;
import org.labkey.api.laboratory.LaboratoryUrls;
import org.labkey.api.laboratory.assay.AssayDataProvider;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineUrls;
import org.labkey.api.query.QueryAction;
import org.labkey.api.query.QueryService;
import org.labkey.api.security.User;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.view.ActionURL;
import org.labkey.flowassays.FlowAssaysManager;

/**
 * Created with IntelliJ IDEA.
 * User: bimber
 * Date: 4/14/13
 * Time: 2:16 PM
 */
public class FlowNavItem extends AbstractImportingNavItem
{
    public static final String NAME = "Flow";

    public FlowNavItem(AssayDataProvider provider, LaboratoryService.NavItemCategory itemType)
    {
        super(provider, NAME, NAME, itemType, NAME);
    }

    @Override
    public boolean isImportIntoWorkbooks(Container c, User u)
    {
        return true;
    }

    @Override
    public boolean getDefaultVisibility(Container c, User u)
    {
        return getTargetContainer(c).getActiveModules().contains(ModuleLoader.getInstance().getModule(NAME));
    }

    @Override
    public ActionURL getImportUrl(Container c, User u)
    {
        return PageFlowUtil.urlProvider(PipelineUrls.class).urlBrowse(getTargetContainer(c));
    }

    @Override
    public ActionURL getSearchUrl(Container c, User u)
    {
        return PageFlowUtil.urlProvider(LaboratoryUrls.class).getSearchUrl(getTargetContainer(c), FlowAssaysManager.FLOW_SCHEMA_NAME, "FCSAnalyses");
    }

    @Override
    public ActionURL getBrowseUrl(Container c, User u)
    {
        ActionURL url = QueryService.get().urlFor(u, getTargetContainer(c), QueryAction.executeQuery, FlowAssaysManager.FLOW_SCHEMA_NAME, "FCSAnalyses");
        return appendDefaultView(c, url, "query");
    }
}
