package org.labkey.mcc.query;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.Container;
import org.labkey.api.data.DisplayColumn;
import org.labkey.api.data.DisplayColumnFactory;
import org.labkey.api.data.RenderContext;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.query.FieldKey;
import org.labkey.api.security.User;
import org.labkey.api.security.UserManager;
import org.labkey.api.util.HtmlString;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.view.HttpView;
import org.labkey.api.view.template.ClientDependency;
import org.labkey.api.writer.HtmlWriter;
import org.labkey.mcc.MccManager;
import org.labkey.mcc.security.MccFinalReviewPermission;
import org.labkey.mcc.security.MccRequestAdminPermission;

import java.util.LinkedHashSet;
import java.util.Set;

public class RequestScoreActionsDisplayColumnFactory implements DisplayColumnFactory
{
    private static final Logger _log = LogManager.getLogger(RequestScoreActionsDisplayColumnFactory.class);

    @Override
    public DisplayColumn createRenderer(ColumnInfo colInfo)
    {
        return new AbstractMccDisplayColumn(colInfo)
        {
            private boolean _hasRegisteredApprovedHandler = false;

            @Override
            public void renderGridCellContents(RenderContext ctx, HtmlWriter out)
            {
                int requestRowId = ctx.get(getBoundKey("requestId", "rowid"), Integer.class);
                if (MccManager.get().isRequestAdmin(ctx.getViewContext().getUser(), ctx.getContainer()))
                {
                    int userId = ctx.get(getBoundKey("requestId", "createdby"), Integer.class);
                    User u = UserManager.getUser(userId);
                    if (u == null)
                    {
                        _log.error("Unknown user: " + userId + " for MCC request " + requestRowId, new Exception());
                        return;
                    }

                    out.write(PageFlowUtil.link("Contact Investigator").addClass("labkey-text-link").href("mailto:" + u.getEmail() + "?subject=MCC Request #" + requestRowId));
                }

                String status = ctx.get(getBoundKey("requestId", "status"), String.class);
                if (status == null)
                {
                    _log.error("MCC Request lacks status: " + requestRowId, new Exception());
                }
                else
                {
                    try
                    {
                        String requestId = ctx.get(getBoundKey("requestId"), String.class);

                        MccManager.RequestStatus st = MccManager.RequestStatus.resolveStatus(status);
                        Container requestContainer = MccManager.get().getMCCRequestContainer(ctx.getContainer());
                        if (requestContainer == null)
                        {
                            _log.error("RequestScoreActionsDisplayColumnFactory was called, but MCCRequestContainer is not set", new Exception());
                            return;
                        }

                        if (st == MccManager.RequestStatus.Submitted)
                        {
                            if (requestContainer.hasPermission(ctx.getViewContext().getUser(), MccRequestAdminPermission.class))
                            {
                                DetailsURL url = DetailsURL.fromString("/mcc/requestReview.view?requestId=" + requestId + "&mode=primaryReview", requestContainer);
                                out.write(HtmlString.BR);
                                out.write(PageFlowUtil.link("Enter MCC Internal Review").addClass("labkey-text-link").href(url.getActionURL().addReturnUrl(ctx.getViewContext().getActionURL())));
                            }
                        }
                        else if (st == MccManager.RequestStatus.RabReview && ctx.get(FieldKey.fromString("pendingRabReviews"), Integer.class) == 0)
                        {
                            if (requestContainer.hasPermission(ctx.getViewContext().getUser(), MccFinalReviewPermission.class))
                            {
                                DetailsURL url = DetailsURL.fromString("/mcc/requestReview.view?requestId=" + requestId + "&mode=resourceAvailability", requestContainer);
                                out.write(HtmlString.BR);
                                out.write(PageFlowUtil.link("Enter Resource Availability Assessment").addClass("labkey-text-link").href(url.getActionURL().addReturnUrl(ctx.getViewContext().getActionURL())));
                            }
                        }
                        else if (st == MccManager.RequestStatus.PendingDecision)
                        {
                            if (requestContainer.hasPermission(ctx.getViewContext().getUser(), MccFinalReviewPermission.class))
                            {
                                DetailsURL url = DetailsURL.fromString("/mcc/requestReview.view?requestId=" + requestId + "&mode=finalReview", requestContainer);
                                out.write(HtmlString.BR);
                                out.write(PageFlowUtil.link("Enter Final Review").addClass("labkey-text-link").href(url.getActionURL().addReturnUrl(ctx.getViewContext().getActionURL())));
                            }
                        }
                        else if (st == MccManager.RequestStatus.Approved)
                        {
                            out.write(HtmlString.BR);
                            out.write(PageFlowUtil.link("Mark Fulfilled").
                                    addClass("labkey-text-link").
                                    addClass("rsadc-approved").
                                    attributes(PageFlowUtil.map("data-requestrowid", String.valueOf(requestRowId))));

                            if (!_hasRegisteredApprovedHandler)
                            {
                                HttpView.currentPageConfig().addHandlerForQuerySelector("a.rsadc-approved", "click", "MCC.window.ChangeStatusWindow.buttonHandler(" + PageFlowUtil.jsString(ctx.getCurrentRegion().getName()) + ", this.attributes.getNamedItem('data-requestrowid').value, 'Fulfilled'); return false;");

                                _hasRegisteredApprovedHandler = true;
                            }
                        }
                    }
                    catch (IllegalArgumentException e)
                    {
                        _log.error("Unknown MCC Request status: [" + status + "] for row: " + requestRowId, new Exception());
                    }
                }
            }

            @Override
            public void addQueryFieldKeys(Set<FieldKey> keys)
            {
                super.addQueryFieldKeys(keys);

                keys.add(getBoundKey("rowid"));
                keys.add(getBoundKey("requestId", "createdby"));
                keys.add(getBoundKey("requestId"));
                keys.add(getBoundKey("pendingRabReviews"));
                keys.add(getBoundKey("requestId", "status"));
                keys.add(getBoundKey("requestId" , "rowid"));
            }

            @Override
            public @NotNull Set<ClientDependency> getClientDependencies()
            {
                Set<ClientDependency> ret = new LinkedHashSet<>(super.getClientDependencies());
                ret.add(ClientDependency.fromPath("LDK/LDKApi.lib.xml"));
                ret.add(ClientDependency.fromPath("mcc/window/ChangeStatusWindow.js"));

                return ret;
            }
        };
    }
}
