/*
 * Copyright (c) 2020 LabKey Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.labkey.mcc;

import jakarta.mail.Address;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.ldk.notification.NotificationService;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.module.ModuleProperty;
import org.labkey.api.security.SecurityManager;
import org.labkey.api.security.User;
import org.labkey.api.security.UserManager;
import org.labkey.api.security.UserPrincipal;
import org.labkey.api.security.ValidEmail;
import org.labkey.api.security.permissions.Permission;
import org.labkey.mcc.security.MccFinalReviewPermission;
import org.labkey.mcc.security.MccRequestAdminPermission;
import org.labkey.mcc.security.MccRequestorPermission;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class MccManager
{
    private static final Logger _log = LogManager.getLogger(MccManager.class);

    public enum RequestStatus
    {
        Draft(1, "Draft", MccRequestorPermission.class),
        Submitted(2, "Submitted", MccRequestorPermission.class),
        RabReview(3, "RAB Review", MccRequestAdminPermission.class),
        PendingDecision(4, "Decision Pending", MccFinalReviewPermission.class),
        Approved(5, "Approved", MccRequestAdminPermission.class),
        Rejected(6, "Rejected", MccRequestAdminPermission.class),
        Processing(7, "Processing", MccRequestAdminPermission.class),
        Fulfilled(8, "Fulfilled", MccRequestAdminPermission.class),
        Withdrawn(9, "Withdrawn", MccRequestorPermission.class);

        int sortOrder;
        String label;
        Class<? extends Permission> editPermission;

        RequestStatus(int sortOrder, String label, Class<? extends Permission> editPermission)
        {
            this.sortOrder = sortOrder;
            this.label = label;
            this.editPermission = editPermission;
        }

        public boolean canEdit(User u, Container c)
        {
            return c.hasPermission(u, this.editPermission);
        }

        public String getLabel()
        {
            return label;
        }

        public static RequestStatus resolveStatus(String val)
        {
            if (val == null)
            {
                return null;
            }

            try
            {
                return valueOf(val);
            }
            catch (IllegalArgumentException e)
            {
                for (RequestStatus s : RequestStatus.values())
                {
                    if (s.label.equals(val))
                    {
                        return s;
                    }
                }

                return valueOf(val.substring(0, 1).toUpperCase() + val.substring(1).toLowerCase());
            }
        }
    }

    public static final String ContainerPropName = "MCCContainer";
    public static final String MCCInternalDataContainerPropName = "MCCInternalDataContainer";
    public static final String NotifyPropName = "MCCContactUsers";
    public static final String MCCRequestNotificationUsers = "MCCRequestNotificationUsers";
    public static final String MCCRequestContainer = "MCCRequestContainer";
    public static final String ZIMSImportPath = "ZIMSImportPath";

    public static final String MCC_GROUP_NAME = "MCC Users";
    public static final String ANIMAL_GROUP_NAME = "MCC Animal Data Access";
    public static final String REQUEST_GROUP_NAME = "MCC Animal Requestors";
    public static final String REQUEST_REVIEW_GROUP_NAME = "MCC RAB Members";
    public static final String FINAL_REVIEW_GROUP_NAME = "MCC Final Reviewers";
    public static final String ADMIN_GROUP_NAME = "MCC Admins";

    private static final MccManager _instance = new MccManager();

    private MccManager()
    {
        // prevent external construction with a private default constructor
    }

    public static MccManager get()
    {
        return _instance;
    }

    public Container getMCCRequestContainer(Container c)
    {
        Module m = ModuleLoader.getInstance().getModule(MccModule.NAME);
        ModuleProperty mp = m.getModuleProperties().get(MccManager.MCCRequestContainer);
        String path = mp.getEffectiveValue(c);
        if (path == null)
            return null;

        return ContainerManager.getForPath(path);
    }

    public String getMccAdminEmail()
    {
        return "mcc@ohsu.edu";
    }

    public Container getMCCInternalDataContainer(Container c)
    {
        Module m = ModuleLoader.getInstance().getModule(MccModule.NAME);
        ModuleProperty mp = m.getModuleProperties().get(MccManager.MCCInternalDataContainerPropName);
        String path = mp.getEffectiveValue(c);
        if (path == null)
            return null;

        return ContainerManager.getForPath(path);
    }

    public Container getMCCContainer(Container c)
    {
        Module m = ModuleLoader.getInstance().getModule(MccModule.NAME);
        ModuleProperty mp = m.getModuleProperties().get(MccManager.ContainerPropName);
        String path = mp.getEffectiveValue(c);
        if (path == null)
            return null;

        return ContainerManager.getForPath(path);
    }

    public boolean isRequestAdmin(User u, Container container)
    {
        Container c = getMCCRequestContainer(container);
        if (c == null)
        {
            _log.error("MccManager.isRequestAdmin called, but MCCRequestContainer has not been set");
            return false;
        }

        return c.hasPermission(u, MccRequestAdminPermission.class);
    }

    public File getZimsImportFolder(Container c)
    {
        Module m = ModuleLoader.getInstance().getModule(MccModule.NAME);
        ModuleProperty mp = m.getModuleProperties().get(MccManager.ZIMSImportPath);
        String val = StringUtils.trimToNull(mp.getValueContainerSpecific(c));
        if (val == null)
        {
            return null;
        }

        return new File(val);
    }

    public Set<Address> getNotificationUserEmails(Container c)
    {
        return getUserEmailsForProp(MccManager.NotifyPropName, c);
    }

    public Set<Address> getRequestNotificationUserEmails(Container c)
    {
        return getUserEmailsForProp(MccManager.MCCRequestNotificationUsers, c);
    }

    private Set<Address> getUserEmailsForProp(String propName, Container c)
    {
        Module m = ModuleLoader.getInstance().getModule(MccModule.NAME);
        ModuleProperty mp = m.getModuleProperties().get(propName);
        String userNames = mp.getEffectiveValue(c);
        userNames = StringUtils.trimToNull(userNames);
        if (userNames == null)
            return null;

        Set<Address> ret = new HashSet<>();
        for (String principalName : userNames.split(","))
        {
            User u = UserManager.getUserByDisplayName(principalName);
            if (u != null)
            {
                try
                {
                    ret.add(new ValidEmail(u.getEmail()).getAddress());
                }
                catch (ValidEmail.InvalidEmailException e)
                {
                    _log.error("Invalid MCC email: " + principalName, e);
                }
            }
            else
            {
                UserPrincipal up = SecurityManager.getPrincipal(principalName, getMCCContainer(c), true);
                if (up == null)
                {
                    _log.error("Unknown user/group registered for MCC notifications: [" + principalName + "]", new Exception());
                    continue;
                }

                try
                {
                    ret.addAll(NotificationService.get().getEmailsForPrincipal(up));
                }
                catch (ValidEmail.InvalidEmailException e)
                {
                    _log.error("Invalid MCC email: " + principalName, e);
                }
            }
        }

        return ret;
    }
}