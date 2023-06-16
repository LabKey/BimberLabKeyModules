package org.labkey.mgap.query;

import org.apache.commons.lang3.StringUtils;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.DataColumn;
import org.labkey.api.data.DisplayColumn;
import org.labkey.api.data.DisplayColumnFactory;
import org.labkey.api.data.RenderContext;
import org.labkey.api.query.FieldKey;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class OverlappingGenesDisplayColumnFactory implements DisplayColumnFactory
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
            public void renderGridCellContents(RenderContext ctx, Writer out) throws IOException
            {
                String rawValue = StringUtils.trimToNull(ctx.get(getBoundKey("overlappingGenes"), String.class));
                if (rawValue == null)
                {
                    return;
                }

                List<String> tokens = Arrays.asList(rawValue.split(";"));
                Collections.sort(tokens);

                String delim = "";
                for (String geneName : tokens)
                {
                    String url = null;
                    if (geneName.startsWith("ENSMMUT"))
                    {
                        url = "http://ensembl.org/Macaca_mulatta/Transcript/Summary?db=core;t=" + geneName;
                    }
                    else if (geneName.startsWith("ENSMMUE"))
                    {
                        //exons.  these should be getting filtered out upstream
                        continue;
                    }
                    else //if (geneName.startsWith("ENSMMUG"))
                    {
                        //this appears to also work based on gene name
                        url = "http://ensembl.org/Macaca_mulatta/Gene/Summary?db=core;g=" + geneName;
                    }

                    out.write(delim + "<a target=\"_blank\" href=\"" + url + "\">" + geneName + "</a>");
                    delim = "<br>";
                }
            }
        };
    }
}
