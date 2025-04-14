package org.labkey.mgap.query;

import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.DataColumn;
import org.labkey.api.data.DisplayColumn;
import org.labkey.api.data.DisplayColumnFactory;
import org.labkey.api.data.RenderContext;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.query.FieldKey;
import org.labkey.api.util.LinkBuilder;
import org.labkey.api.writer.HtmlWriter;

import java.util.List;
import java.util.Set;

public class PhenotypeVariantLinkDisplayColumnFactory implements DisplayColumnFactory
{
    @Override
    public DisplayColumn createRenderer(ColumnInfo colInfo)
    {
        return new DataColumn(colInfo)
        {
            @Override
            public void addQueryFieldKeys(Set<FieldKey> keys)
            {
                super.addQueryFieldKeys(keys);

                keys.add(getBoundKey("releaseId/rowId"));
                keys.add(getBoundKey("omim_entry"));
                keys.add(getBoundKey("container"));
            }

            private FieldKey getBoundKey(String colName)
            {
                FieldKey ret = getBoundColumn().getFieldKey().getParent();
                List<String> parts = FieldKey.fromString(colName).getParts();
                for (String part : parts)
                {
                    ret = new FieldKey(ret, part);
                }

                return ret;
            }

            @Override
            public void renderGridCellContents(RenderContext ctx, HtmlWriter out)
            {
                String containerId = ctx.get(getBoundKey("container"), String.class);
                String omim = ctx.get(getBoundKey("omim_entry"), String.class);
                Integer releaseId = ctx.get(getBoundKey("releaseId/rowId"), Integer.class);
                if (releaseId != null && omim != null)
                {
                    DetailsURL url = DetailsURL.fromString("/mgap/variantList.view?release=" + releaseId + "&query.omim_phenotype~contains=" + omim, ContainerManager.getForId(containerId));
                    out.write(LinkBuilder.labkeyLink("View Variants", url.getActionURL()));
                }
            }
        };
    }
}
