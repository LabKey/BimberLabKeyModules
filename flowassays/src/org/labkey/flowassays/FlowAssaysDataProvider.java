/*
 * Copyright (c) 2012 LabKey Corporation
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
package org.labkey.flowassays;

import org.json.old.JSONObject;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.laboratory.AbstractDataProvider;
import org.labkey.api.laboratory.NavItem;
import org.labkey.api.laboratory.SimpleSettingsItem;
import org.labkey.api.laboratory.SummaryNavItem;
import org.labkey.api.module.Module;
import org.labkey.api.security.User;
import org.labkey.api.view.ActionURL;
import org.labkey.api.view.ViewContext;
import org.labkey.api.view.template.ClientDependency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Created with IntelliJ IDEA.
 * User: bimber
 * Date: 10/7/12
 * Time: 3:29 PM
 */
public class FlowAssaysDataProvider extends AbstractDataProvider
{
    Module _module;
    public FlowAssaysDataProvider(Module m){
        _module = m;
    }

    public String getName()
    {
        return FlowAssaysModule.NAME;
    }

    @Override
    public ActionURL getInstructionsUrl(Container c, User u)
    {
        return null;
    }

    public boolean supportsTemplates()
    {
        return false;
    }

    @Override
    public List<NavItem> getDataNavItems(Container c, User u)
    {
        return Collections.emptyList();
    }

    @Override
    public List<NavItem> getSampleNavItems(Container c, User u)
    {
        return Collections.emptyList();
    }

    @Override
    public List<NavItem> getSettingsItems(Container c, User u)
    {
        List<NavItem> items = new ArrayList<NavItem>();
        String categoryName = "Flow Assays";
        if (ContainerManager.getSharedContainer().equals(c))
        {
            items.add(new SimpleSettingsItem(this, FlowAssaysSchema.NAME, "Assay_Types", categoryName, "Assay Types"));
            items.add(new SimpleSettingsItem(this, FlowAssaysSchema.NAME, "Instruments", categoryName, "Instruments"));
            items.add(new SimpleSettingsItem(this, FlowAssaysSchema.NAME, "Populations", categoryName, "Cell Populations"));
            items.add(new SimpleSettingsItem(this, FlowAssaysSchema.NAME, "Units", categoryName, "Units"));
        }

        return items;
    }

    @Override
    public JSONObject getTemplateMetadata(ViewContext ctx)
    {
        return null;
    }

    @Override
    public Set<ClientDependency> getClientDependencies()
    {
        return Collections.emptySet();
    }

    @Override
    public Module getOwningModule()
    {
        return _module;
    }

    @Override
    public List<SummaryNavItem> getSummary(Container c, User u)
    {
        return Collections.emptyList();
    }

    @Override
    public List<NavItem> getSubjectIdSummary(Container c, User u, String subjectId)
    {
        return Collections.emptyList();
    }
}