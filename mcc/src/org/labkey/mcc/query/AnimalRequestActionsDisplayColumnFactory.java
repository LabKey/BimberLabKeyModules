package org.labkey.mcc.query;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.DisplayColumn;
import org.labkey.api.data.DisplayColumnFactory;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.writer.HtmlWriter;
import org.labkey.api.data.RenderContext;
import org.labkey.api.query.FieldKey;
import org.labkey.mcc.MccManager;

import java.util.Set;

public class AnimalRequestActionsDisplayColumnFactory implements DisplayColumnFactory
{
    private static final Logger _log = LogManager.getLogger(AnimalRequestActionsDisplayColumnFactory.class);

    @Override
    public DisplayColumn createRenderer(ColumnInfo colInfo)
    {
        return new AbstractMccDisplayColumn(colInfo)
        {
            @Override
            public void renderGridCellContents(RenderContext ctx, HtmlWriter out)
            {
                int rowId = ctx.get(getBoundKey("rowid"), Integer.class);
                out.write(PageFlowUtil.link("Contact MCC").href("mailto:" + MccManager.get().getMccAdminEmail() + "?subject=MCC Request #" + rowId));
            }

            @Override
            public void addQueryFieldKeys(Set<FieldKey> keys)
            {
                super.addQueryFieldKeys(keys);

                keys.add(getBoundKey("rowid"));
                keys.add(getBoundKey("createdby"));
                keys.add(getBoundKey("status"));
            }
        };
    }
}
