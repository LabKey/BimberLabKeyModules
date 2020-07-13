package org.labkey.mgap.query;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.DataColumn;
import org.labkey.api.data.DisplayColumn;
import org.labkey.api.data.DisplayColumnFactory;
import org.labkey.api.data.RenderContext;

public class BreakTextDisplayColumnFactory implements DisplayColumnFactory
{
    @Override
    public DisplayColumn createRenderer(ColumnInfo colInfo)
    {
        return new DataColumn(colInfo)
        {
            @Override
            public @NotNull String getCssStyle(RenderContext ctx)
            {
                String ret = super.getCssStyle(ctx);
                ret += (StringUtils.isEmpty(ret) ? "" : ";") + "word-break: break-all;max-width:100px;";

                return ret;
            }
        };
    }
}
