/*
 * Copyright (c) 2015 LabKey Corporation
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

package org.labkey.mgap;

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

import java.util.HashSet;
import java.util.Set;

public class mGAPManager
{
    private static final Logger _log = LogManager.getLogger(mGAPManager.class);

    private static final mGAPManager _instance = new mGAPManager();
    public static final String ContainerPropName = "MGAPContainer";
    public static final String NotifyPropName = "MGAPContactUsers";
    public static final String OmimApiKeyPropName = "OmimApiKey";
    public static final String GROUP_NAME = "mGAP Users";

    private mGAPManager()
    {
        // prevent external construction with a private default constructor
    }

    public static mGAPManager get()
    {
        return _instance;
    }

    public Container getMGapContainer()
    {
        Module m = ModuleLoader.getInstance().getModule(mGAPModule.NAME);
        ModuleProperty mp = m.getModuleProperties().get(mGAPManager.ContainerPropName);
        String path = mp.getEffectiveValue(ContainerManager.getRoot());
        if (path == null)
            return null;

        return ContainerManager.getForPath(path);
    }

    public String getOmimApiKey(Container c)
    {
        Module m = ModuleLoader.getInstance().getModule(mGAPModule.NAME);
        ModuleProperty mp = m.getModuleProperties().get(mGAPManager.OmimApiKeyPropName);

        return mp.getEffectiveValue(c);
    }

    public Set<User> getNotificationUsers()
    {
        Module m = ModuleLoader.getInstance().getModule(mGAPModule.NAME);
        ModuleProperty mp = m.getModuleProperties().get(mGAPManager.NotifyPropName);
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
                _log.error("Unknown user registered for mGAP notifcations: " + username);
            }

            if (u != null)
            {
                ret.add(u);
            }
        }

        return ret;
    }
}