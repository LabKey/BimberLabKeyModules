package org.labkey.mgap;

import org.jetbrains.annotations.Nullable;
import org.labkey.api.data.Container;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableSelector;
import org.labkey.api.ldk.notification.AbstractNotification;
import org.labkey.api.module.Module;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.UserSchema;
import org.labkey.api.security.User;

public class mGAPUserNotification extends AbstractNotification
{
    public mGAPUserNotification(Module owner)
{
    super(owner);
}

    @Override
    public String getName()
    {
        return "mGAP User Request Notification";
    }

    @Override
    public String getCategory()
    {
        return "mGAP";
    }

    @Override
    public String getCronString()
    {
        return "0 0 22 * * ?";
    }

    @Override
    public String getScheduleDescription()
    {
        return "Daily at 10PM";
    }

    @Override
    public @Nullable String getMessageBodyHTML(Container c, User u)
    {
        //query pending user requests
        Container target = mGAPManager.get().getMGapContainer();
        if (target == null)
        {
            return null;
        }

        UserSchema us = QueryService.get().getUserSchema(u, target, mGAPSchema.NAME);
        long count = new TableSelector(us.getTable(mGAPSchema.TABLE_USER_REQUESTS), new SimpleFilter(FieldKey.fromString("hasAccess"), false), null).getRowCount();
        if (count == 0)
        {
            return null;
        }

        String url = getExecuteQueryUrl(target, mGAPSchema.NAME, mGAPSchema.TABLE_USER_REQUESTS, "Pending Requests");

        return "There are " + count + " pending mGAP user requests.  <a href='" + url + "'>Click here to view them.</a>";
    }

    @Override
    public String getDescription()
    {
        return "If there are pending mGAP user requests, a reminder email will be sent";
    }

    @Override
    public String getEmailSubject(Container c)
    {
        return "Pending mGAP User Requests";
    }
}
