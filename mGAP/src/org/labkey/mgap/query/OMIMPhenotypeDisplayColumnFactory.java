package org.labkey.mgap.query;

import org.apache.commons.lang3.StringUtils;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.DataColumn;
import org.labkey.api.data.DisplayColumn;
import org.labkey.api.data.DisplayColumnFactory;
import org.labkey.api.data.RenderContext;
import org.labkey.api.query.FieldKey;
import org.labkey.api.util.HtmlString;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.writer.HtmlWriter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class OMIMPhenotypeDisplayColumnFactory implements DisplayColumnFactory
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
            }

            private FieldKey getBoundKey(String colName)
            {
                return new FieldKey(getBoundColumn().getFieldKey().getParent(), colName);
            }

            @Override
            public Object getExcelCompatibleValue(RenderContext ctx)
            {
                Object val = super.getExcelCompatibleValue(ctx);
                if (val != null)
                {
                    String ret = String.valueOf(val);
                    ret.replaceAll("<>", ", ");

                    return ret;
                }

                return val;
            }

            @Override
            public void renderGridCellContents(RenderContext ctx, HtmlWriter out)
            {
                String rawValue = StringUtils.trimToNull(ctx.get(getBoundKey("omim_phenotype"), String.class));
                if (rawValue == null)
                {
                    return;
                }

                List<String> tokens = Arrays.asList(rawValue.split(";"));
                Collections.sort(tokens);

                HtmlString delim = HtmlString.EMPTY_STRING;
                for (String entry : tokens)
                {
                    String[] elements = entry.split("<>");
                    out.write(delim);
                    if (elements.length > 1)
                    {
                        out.write(PageFlowUtil.link(elements[0]).target("_blank").href("https://www.omim.org/entry/" + elements[1]).clearClasses());
                    }
                    else
                    {
                        out.write(PageFlowUtil.link(elements[0]).clearClasses());
                    }

                    delim = HtmlString.BR;
                }
            }
        };
    }
}
