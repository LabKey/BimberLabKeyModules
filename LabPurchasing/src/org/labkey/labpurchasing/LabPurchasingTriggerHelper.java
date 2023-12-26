package org.labkey.labpurchasing;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.security.User;
import org.labkey.api.security.UserManager;
import org.labkey.api.util.MailHelper;
import org.labkey.api.util.PageFlowUtil;

import jakarta.mail.Message;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LabPurchasingTriggerHelper
{
    private Container _container = null;
    private User _user = null;
    private static final Logger _log = LogManager.getLogger(LabPurchasingTriggerHelper.class);

    public LabPurchasingTriggerHelper(int userId, String containerId)
    {
        _user = UserManager.getUser(userId);
        if (_user == null)
        {
            throw new RuntimeException("User does not exist: " + userId);
        }

        _container = ContainerManager.getForId(containerId);
        if (_container == null)
        {
            throw new RuntimeException("Container does not exist: " + containerId);
        }
    }

    public void sendNotifications(List<Integer> rowIds) {
        Map<User, StringBuilder> requestMap = new HashMap<>();
        TableInfo ti = QueryService.get().getUserSchema(_user, _container, LabPurchasingSchema.NAME).getTable(LabPurchasingSchema.TABLE_PURCHASES);
        Set<FieldKey> fieldKeys = PageFlowUtil.set(
                FieldKey.fromString("rowId"),
                FieldKey.fromString("requestor"),
                FieldKey.fromString("vendorId/vendorName"),
                FieldKey.fromString("itemName"),
                FieldKey.fromString("itemNumber"),
                FieldKey.fromString("itemLocation"),
                FieldKey.fromString("receivedBy")
        );

        Map<FieldKey, ColumnInfo> colMap = QueryService.get().getColumns(ti, fieldKeys);
        new TableSelector(ti, colMap.values(), new SimpleFilter(FieldKey.fromString("rowId"), rowIds, CompareType.IN), null).forEachResults(rs -> {
            if (rs.getObject(FieldKey.fromString("requestor")) != null)
            {
                int requestor = rs.getInt(FieldKey.fromString("requestor"));
                User u = UserManager.getUser(requestor);
                if (u == null)
                {
                    _log.error("Unknown user: " + requestor + ", row purchasing row: " + rs.getObject(FieldKey.fromString("rowId")));
                    return;
                }

                if (!requestMap.containsKey(u))
                {
                    requestMap.put(u, new StringBuilder());
                }

                StringBuilder sb = requestMap.get(u);
                if (!sb.isEmpty())
                {
                    sb.append("<br>");
                }

                sb.append(rs.getString(FieldKey.fromString("itemName")));
                if (rs.getObject(FieldKey.fromString("itemNumber")) != null)
                {
                    sb.append(" (").append(rs.getString(FieldKey.fromString("itemNumber"))).append(")");
                }

                if (rs.getObject(FieldKey.fromString("itemLocation")) != null)
                {
                    sb.append(". Location: ").append(rs.getString(FieldKey.fromString("itemLocation")));
                }
            }
        });

        try
        {
            for (User u : requestMap.keySet())
            {
                MailHelper.MultipartMessage mail = MailHelper.createMultipartMessage();
                mail.setFrom("purchasing-do-not-reply@ohsu.edu");
                mail.setSubject("Purchases Received");

                mail.addRecipients(Message.RecipientType.TO, u.getEmail());
                mail.setEncodedHtmlContent("The following purchases were received:<br><br>" + requestMap.get(u).toString());

                MailHelper.send(mail, _user, _container);

            }
        }
        catch (Exception e)
        {
            _log.error("Unable to send purchasing email", e);
        }
    }
}
