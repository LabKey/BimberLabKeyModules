/*
 * Copyright (c) 2019 LabKey Corporation
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

package org.labkey.primeseq;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.labkey.api.cluster.ClusterService;
import org.labkey.api.data.Container;
import org.labkey.api.ldk.ExtendedSimpleModule;
import org.labkey.api.module.ModuleContext;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.SystemMaintenance;
import org.labkey.api.view.WebPartFactory;
import org.labkey.primeseq.analysis.CombineMethylationRatesHandler;
import org.labkey.primeseq.analysis.MethylationRateComparisonHandler;
import org.labkey.primeseq.pipeline.BisSnpGenotyperAnalysis;
import org.labkey.primeseq.pipeline.BisSnpIndelRealignerStep;
import org.labkey.primeseq.pipeline.BismarkWrapper;
import org.labkey.primeseq.pipeline.BlastPipelineJobResourceAllocator;
import org.labkey.primeseq.pipeline.ClusterMaintenanceTask;
import org.labkey.primeseq.pipeline.ExacloudResourceSettings;
import org.labkey.primeseq.pipeline.SequenceJobResourceAllocator;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

public class PrimeseqModule extends ExtendedSimpleModule
{
    public static final String NAME = "Primeseq";

    @Override
    public String getName()
    {
        return NAME;
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
        addController(PrimeseqController.NAME, PrimeseqController.class);
    }

    @Override
    protected void doStartupAfterSpringConfig(ModuleContext moduleContext)
    {
        SequencePipelineService.get().registerResourceSettings(new ExacloudResourceSettings());

        SystemMaintenance.addTask(new ClusterMaintenanceTask());

        ClusterService.get().registerResourceAllocator(new BlastPipelineJobResourceAllocator.Factory());
        ClusterService.get().registerResourceAllocator(new SequenceJobResourceAllocator.Factory());

        //register resources
        new PipelineStartup();

    }

    @Override
    @NotNull
    public Collection<String> getSummary(Container c)
    {
        return Collections.emptyList();
    }

    public static class PipelineStartup
    {
        private static final Logger _log = LogManager.getLogger(PipelineStartup.class);
        private static boolean _hasRegistered = false;

        public PipelineStartup()
        {
            if (_hasRegistered)
            {
                _log.warn("Primeseq resources have already been registered, skipping");
            }
            else
            {
                SequencePipelineService.get().registerPipelineStep(new BismarkWrapper.Provider());
                SequencePipelineService.get().registerPipelineStep(new BismarkWrapper.MethylationExtractorProvider());

                SequencePipelineService.get().registerPipelineStep(new BisSnpIndelRealignerStep.Provider());
                SequencePipelineService.get().registerPipelineStep(new BisSnpGenotyperAnalysis.Provider());

                SequenceAnalysisService.get().registerFileHandler(new MethylationRateComparisonHandler());
                SequenceAnalysisService.get().registerFileHandler(new CombineMethylationRatesHandler());

                _hasRegistered = true;
            }
        }
    }

    @Override
    public @NotNull Set<Class> getUnitTests()
    {
        return PageFlowUtil.set(ClusterMaintenanceTask.TestCase.class);
    }

    @Override
    public boolean hasScripts()
    {
        return false;
    }
}