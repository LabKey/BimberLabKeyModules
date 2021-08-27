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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.module.ModuleProperty;
import org.labkey.api.security.User;
import org.labkey.api.security.UserManager;
import org.labkey.api.security.ValidEmail;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public class MccManager
{
    private static final Logger _log = LogManager.getLogger(MccManager.class);

    public static final String ContainerPropName = "MCCContainer";
    public static final String NotifyPropName = "MCCContactUsers";
    public static final String ZIMSImportPath = "ZIMSImportPath";

    public static final String GROUP_NAME = "MCC Users";

    private static final MccManager _instance = new MccManager();

    private MccManager()
    {
        // prevent external construction with a private default constructor
    }

    public static MccManager get()
    {
        return _instance;
    }

    public Container getMCCContainer()
    {
        Module m = ModuleLoader.getInstance().getModule(MccModule.NAME);
        ModuleProperty mp = m.getModuleProperties().get(MccManager.ContainerPropName);
        String path = mp.getEffectiveValue(ContainerManager.getRoot());
        if (path == null)
            return null;

        return ContainerManager.getForPath(path);
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

    public Set<User> getNotificationUsers()
    {
        Module m = ModuleLoader.getInstance().getModule(MccModule.NAME);
        ModuleProperty mp = m.getModuleProperties().get(MccManager.NotifyPropName);
        String userNames = mp.getEffectiveValue(ContainerManager.getRoot());
        userNames = StringUtils.trimToNull(userNames);
        if (userNames == null)
            return null;

        Set<User> ret = new HashSet<>();
        for (String username : userNames.split(","))
        {
            User u = UserManager.getUserByDisplayName(username);
            if (u == null)
            {
                try
                {
                    u = UserManager.getUser(new ValidEmail(username));
                }
                catch (ValidEmail.InvalidEmailException e)
                {
                    //ignore
                }
            }

            if (u == null)
            {
                _log.error("Unknown user registered for MCC notifcations: " + username);
            }

            if (u != null)
            {
                ret.add(u);
            }
        }

        return ret;
    }
}