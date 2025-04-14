package org.labkey.mgap.query;

import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.DisplayColumn;
import org.labkey.api.data.RenderContext;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.util.LinkBuilder;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.writer.HtmlWriter;

/**
 * Created by bimber on 5/17/2017.
 */
public class TracksPerReleaseGenomeBrowserDisplayColumnFactory extends VariantReleaseGenomeBrowserDisplayColumnFactory
{
    @Override
    public DisplayColumn createRenderer(ColumnInfo colInfo)
    {
        return new VariantReleaseGenomeBrowserDisplayColumnFactory.BrowserDataColumn(colInfo, PageFlowUtil.set("releaseId/jbrowseId", "releaseId/container", "trackName"))
        {
            @Override
            public void renderGridCellContents(RenderContext ctx, HtmlWriter out)
            {
                String jbrowseId = ctx.get(getBoundKey("releaseId", "jbrowseId"), String.class);
                String containerId = ctx.get(getBoundKey("releaseId", "container"), String.class);
                String trackName = ctx.get(getBoundKey("trackName"), String.class);

                if (jbrowseId != null && trackName != null)
                {
                    DetailsURL url = DetailsURL.fromString("/mgap/genomeBrowser.view?database=" + jbrowseId + "&activeTracks=" + trackName, ContainerManager.getForId(containerId));
                    out.write(LinkBuilder.labkeyLink("View In Genome Browser", url.getActionURL()));
                }
            }
        };
    }
}
