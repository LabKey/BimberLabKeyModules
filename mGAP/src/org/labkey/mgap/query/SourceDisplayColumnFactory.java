package org.labkey.mgap.query;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.DataColumn;
import org.labkey.api.data.DisplayColumn;
import org.labkey.api.data.DisplayColumnFactory;
import org.labkey.api.data.RenderContext;
import org.labkey.api.query.FieldKey;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;

public class SourceDisplayColumnFactory implements DisplayColumnFactory
{
    private static final Logger _log = Logger.getLogger(SourceDisplayColumnFactory.class);

    @Override
    public DisplayColumn createRenderer(ColumnInfo colInfo)
    {
        return new DataColumn(colInfo)
        {
            @Override
            public void addQueryFieldKeys(Set<FieldKey> keys)
            {
                super.addQueryFieldKeys(keys);
                keys.add(getBoundKey("identifier"));
            }

            @Override
            public void renderGridCellContents(RenderContext ctx, Writer out) throws IOException
            {
                String val = ctx.get(getBoundKey("source"), String.class);
                if (val == null)
                {
                    return;
                }

                String identifier = StringUtils.trimToNull(ctx.get(getBoundKey("identifier"), String.class));
                String url = null;
                if (identifier != null && identifier.contains(":"))
                {
                    String[] parts = identifier.split(":");
                    if (parts.length != 2)
                    {
                        _log.error("Invalid variant identifier: " + val, new Exception());
                    }
                    else
                    {
                        switch (parts[0])
                        {
                            case "ClinVar":
                                if (!StringUtils.isEmpty(parts[1]))
                                {
                                    url = "https://www.ncbi.nlm.nih.gov/clinvar/variation/" + parts[1] + "/";
                                }
                                break;
                        }
                    }
                }

                out.write(url == null ? val : "<a href=\"" + url + "\">" + val + "</a>");
            }

            private FieldKey getBoundKey(String colName)
            {
                return new FieldKey(getBoundColumn().getFieldKey().getParent(), colName);
            }
        };
    }
}
