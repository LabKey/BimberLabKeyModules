/*
 * Copyright (c) 2014 LabKey Corporation
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

package org.labkey.variantdb;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.labkey.api.data.Container;
import org.labkey.api.laboratory.LaboratoryService;
import org.labkey.api.ldk.ExtendedSimpleModule;
import org.labkey.api.module.ModuleContext;
import org.labkey.api.security.roles.RoleManager;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.view.WebPartFactory;
import org.labkey.variantdb.analysis.GBSAnalysisHandler;
import org.labkey.variantdb.analysis.ImputationAnalysis;
import org.labkey.variantdb.query.VariantDBUserSchema;
import org.labkey.variantdb.security.VariantManagerRole;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

public class VariantDBModule extends ExtendedSimpleModule
{
    public static final String NAME = "VariantDB";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public Double getSchemaVersion()
    {
        return 13.40;
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
        addController(VariantDBController.NAME, VariantDBController.class);
    }

    @Override
    public void doStartupAfterSpringConfig(ModuleContext moduleContext)
    {
        RoleManager.registerRole(new VariantManagerRole());
        LaboratoryService.get().registerDataProvider(new VariantDBDataProvider(this));
        SequenceAnalysisService.get().registerDataProvider(new VariantDBDataProvider(this));

        //register resources
        new PipelineStartup();
    }

    @Override
    protected void registerSchemas()
    {
        VariantDBUserSchema.register(this);
    }

    @Override
    @NotNull
    public Collection<String> getSummary(Container c)
    {
        return Collections.emptyList();
    }

    @Override
    @NotNull
    public Set<String> getSchemaNames()
    {
        return Collections.singleton(VariantDBSchema.NAME);
    }

    public static class PipelineStartup
    {
        private static final Logger _log = Logger.getLogger(PipelineStartup.class);
        private static boolean _hasRegistered = false;

        public PipelineStartup()
        {
            if (_hasRegistered)
            {
                _log.warn("VariantDB resources have already been registered, skipping");
            }
            else
            {
                SequenceAnalysisService.get().registerFileHandler(new ImputationAnalysis());
                SequenceAnalysisService.get().registerFileHandler(new GBSAnalysisHandler());

                _hasRegistered = true;
            }
        }
    }
}