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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.labkey.api.data.Container;
import org.labkey.api.ehr.EHRService;
import org.labkey.api.ldk.ExtendedSimpleModule;
import org.labkey.api.ldk.LDKService;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleContext;
import org.labkey.api.query.DefaultSchema;
import org.labkey.api.query.QuerySchema;
import org.labkey.api.security.roles.RoleManager;
import org.labkey.api.util.SystemMaintenance;
import org.labkey.api.view.WebPartFactory;
import org.labkey.api.writer.ContainerUser;
import org.labkey.mcc.query.MccEhrCustomizer;
import org.labkey.mcc.query.ReviewerNotifyButton;
import org.labkey.mcc.security.MccDataAdminRole;
import org.labkey.mcc.security.MccFinalReviewPermission;
import org.labkey.mcc.security.MccFinalReviewerRole;
import org.labkey.mcc.security.MccRabReviewPermission;
import org.labkey.mcc.security.MccRabReviewerRole;
import org.labkey.mcc.security.MccRequestAdminPermission;
import org.labkey.mcc.security.MccRequesterRole;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

public class MccModule extends ExtendedSimpleModule
{
    public static final String NAME = "MCC";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public @Nullable Double getSchemaVersion()
    {
        return 20.013;
    }

    @Override
    public boolean hasScripts()
    {
        return true;
    }

    @Override
    @NotNull
    protected Collection<WebPartFactory> createWebPartFactories()
    {
        return Collections.emptyList();
    }

    @Override
    protected void init()
    {
        addController(MccController.NAME, MccController.class);

        RoleManager.registerRole(new MccRequesterRole());
        RoleManager.registerRole(new MccDataAdminRole());
        RoleManager.registerRole(new MccRabReviewerRole());
        RoleManager.registerRole(new MccFinalReviewerRole());
    }

    @NotNull
    @Override
    public JSONObject getPageContextJson(ContainerUser context)
    {
        JSONObject ret = super.getPageContextJson(context);

        Container requestContainer = MccManager.get().getMCCRequestContainer();
        ret.put("hasRequestAdminPermission", requestContainer != null && requestContainer.hasPermission(context.getUser(), MccRequestAdminPermission.class));
        ret.put("hasRabPermission", requestContainer != null && requestContainer.hasPermission(context.getUser(), MccRabReviewPermission.class));
        ret.put("hasFinalDecisionPermission", requestContainer != null && requestContainer.hasPermission(context.getUser(), MccFinalReviewPermission.class));

        return ret;
    }

    @Override
    protected void doStartupAfterSpringConfig(ModuleContext moduleContext)
    {
        registerEHRResources();
        LDKService.get().registerQueryButton(new ReviewerNotifyButton(), MccSchema.NAME, MccSchema.TABLE_REQUEST_REVIEWS);

        SystemMaintenance.addTask(new MccMaintenanceTask());
    }

    @Override
    @NotNull
    public Set<String> getSchemaNames()
    {
        return Collections.singleton(MccSchema.NAME);
    }

    private void registerEHRResources()
    {
        EHRService.get().registerModule(this);
        EHRService.get().registerTableCustomizer(this, MccEhrCustomizer.class);
    }

    @Override
    public void registerSchemas()
    {
        DefaultSchema.registerProvider(MccSchema.NAME, new DefaultSchema.SchemaProvider(this)
        {
            @Override
            public QuerySchema createSchema(final DefaultSchema schema, Module module)
            {
                return new MccUserSchema(schema.getUser(), schema.getContainer(), MccSchema.getInstance().getSchema());
            }
        });
    }
}