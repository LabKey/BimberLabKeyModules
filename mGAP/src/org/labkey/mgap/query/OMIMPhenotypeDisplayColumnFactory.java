package org.labkey.mgap.query;

import org.apache.commons.lang3.StringUtils;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.DataColumn;
import org.labkey.api.data.DisplayColumn;
import org.labkey.api.data.DisplayColumnFactory;
import org.labkey.api.data.RenderContext;
import org.labkey.api.query.FieldKey;
import org.labkey.api.writer.HtmlWriter;

import java.io.IOException;
import java.io.Writer;
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
            public void renderGridCellContents(RenderContext ctx, Writer oldWriter, HtmlWriter out) throws IOException
            {
                String rawValue = StringUtils.trimToNull(ctx.get(getBoundKey("omim_phenotype"), String.class));
                if (rawValue == null)
                {
                    return;
                }

                List<String> tokens = Arrays.asList(rawValue.split(";"));
                Collections.sort(tokens);

                String delim = "";
                for (String entry : tokens)
                {
                    String[] elements = entry.split("<>");
                    if (elements.length > 1)
                    {
                        oldWriter.write(delim + "<a target=\"_blank\" href=\"https://www.omim.org/entry/" + elements[1] + "\">" + elements[0] + "</a>");
                    }
                    else
                    {
                        oldWriter.write(delim + "<a>" + elements[0] + "</a>");
                    }

                    delim = "<br>";
                }
            }
        };
    }
}
