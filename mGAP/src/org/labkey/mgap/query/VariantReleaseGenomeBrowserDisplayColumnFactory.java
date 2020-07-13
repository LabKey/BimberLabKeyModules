package org.labkey.mgap.query;

import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.DataColumn;
import org.labkey.api.data.DisplayColumn;
import org.labkey.api.data.DisplayColumnFactory;
import org.labkey.api.data.RenderContext;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.query.FieldKey;
import org.labkey.api.util.PageFlowUtil;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;

/**
 * Created by bimber on 5/17/2017.
 */
public class VariantReleaseGenomeBrowserDisplayColumnFactory implements DisplayColumnFactory
{
    @Override
    public DisplayColumn createRenderer(ColumnInfo colInfo)
    {
        return new BrowserDataColumn(colInfo);
    }

    public static class BrowserDataColumn extends DataColumn
    {
        protected Set<String> _queryKeys;

        public BrowserDataColumn(ColumnInfo colInfo)
        {
            this(colInfo, PageFlowUtil.set("rowid", "jbrowseId", "container"));
        }

        public BrowserDataColumn(ColumnInfo colInfo, Set<String> queryKeys)
        {
            super(colInfo);
            _queryKeys = queryKeys;
        }

        @Override
        public void addQueryFieldKeys(Set<FieldKey> keys)
        {
            super.addQueryFieldKeys(keys);

            _queryKeys.forEach(x -> keys.add(getBoundKey(x.split("/"))));
        }

        protected FieldKey getBoundKey(String... colNames)
        {
            FieldKey ret = null;
            for (String colName : colNames)
            {
                if (ret == null)
                {
                    ret = new FieldKey(getBoundColumn().getFieldKey().getParent(), colName);
                }
                else
                {
                    ret = ret.append(colName);
                }
            }

            return ret;
        }

        @Override
        public void renderGridCellContents(RenderContext ctx, Writer out) throws IOException
        {
            String jbrowseId = ctx.get(getBoundKey("jbrowseId"), String.class);
            String containerId = ctx.get(getBoundKey("container"), String.class);
            if (jbrowseId != null)
            {
                DetailsURL url = DetailsURL.fromString("/jbrowse/browser.view?database=" + jbrowseId, ContainerManager.getForId(containerId));
                out.write("<a class=\"labkey-text-link\" href=\"" + url.getActionURL().getURIString() + "\");\">View In Genome Browser</a>");
            }
        }

        @Override
        public boolean isFilterable()
        {
            return false;
        }

        @Override
        public boolean isSortable()
        {
            return false;
        }
    }
}
