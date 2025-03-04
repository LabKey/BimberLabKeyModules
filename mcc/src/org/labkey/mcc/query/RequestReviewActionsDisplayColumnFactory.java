package org.labkey.mcc.query;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.Container;
import org.labkey.api.data.DisplayColumn;
import org.labkey.api.data.DisplayColumnFactory;
import org.labkey.api.data.RenderContext;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.query.FieldKey;
import org.labkey.api.security.User;
import org.labkey.api.security.UserManager;
import org.labkey.mcc.MccManager;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;

public class RequestReviewActionsDisplayColumnFactory implements DisplayColumnFactory
{
    private static final Logger _log = LogManager.getLogger(RequestReviewActionsDisplayColumnFactory.class);

    @Override
    public DisplayColumn createRenderer(ColumnInfo colInfo)
    {
        return new AbstractMccDisplayColumn(colInfo)
        {
            @Override
            public void renderGridCellContents(RenderContext ctx, Writer out) throws IOException
            {
                int userId = ctx.get(getBoundKey("reviewerId"), Integer.class);
                User u = UserManager.getUser(userId);
                if (u == null)
                {
                    _log.error("Unknown user: " + userId + " for MCC review record " + userId, new Exception());
                    return;
                }

                if (!ctx.getViewContext().getUser().equals(u)) {
                    return;
                }

                String requestId = ctx.get(getBoundKey("requestId"), String.class);
                Container requestContainer = MccManager.get().getMCCRequestContainer(ctx.getContainer());
                DetailsURL url = DetailsURL.fromString("/mcc/requestReview.view?requestId=" + requestId + "&mode=rabReview", requestContainer);
                out.write("<a class=\"labkey-text-link\" href=\"" + url.getActionURL().addReturnUrl(ctx.getViewContext().getActionURL()) + "\">Enter Review</a>");
            }

            @Override
            public void addQueryFieldKeys(Set<FieldKey> keys)
            {
                super.addQueryFieldKeys(keys);

                keys.add(getBoundKey("container"));
                keys.add(getBoundKey("requestid"));
                keys.add(getBoundKey("rowid"));
                keys.add(getBoundKey("reviewerId"));
            }
        };
    }
}
