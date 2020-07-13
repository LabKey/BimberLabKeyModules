package org.labkey.mgap.query;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.DbSequence;
import org.labkey.api.data.DbSequenceManager;
import org.labkey.api.security.User;
import org.labkey.api.security.UserManager;

/**
 * Created by bimber on 3/23/2017.
 */
public class TriggerHelper
{
    private Container _container = null;
    private User _user = null;
    private static final Logger _log = LogManager.getLogger(TriggerHelper.class);
    private static final String SEQUENCE_NAME = "org.labkey.mgap.MGAP_ALIAS";

    public TriggerHelper(int userId, String containerId)
    {
        _user = UserManager.getUser(userId);
        if (_user == null)
            throw new RuntimeException("User does not exist: " + userId);

        _container = ContainerManager.getForId(containerId);
        if (_container == null)
            throw new RuntimeException("Container does not exist: " + containerId);

    }

    public String getNextAlias()
    {
        DbSequence sequence = DbSequenceManager.get((_container.isWorkbookOrTab() ? _container.getParent() : _container), SEQUENCE_NAME);

        return "m" + StringUtils.leftPad(String.valueOf(sequence.next()), 5, "0");
    }
}
