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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.labkey.api.audit.AuditLogService;
import org.labkey.api.data.ContainerFilter;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.Sort;
import org.labkey.api.data.TableSelector;
import org.labkey.api.data.UpgradeCode;
import org.labkey.api.jbrowse.JBrowseService;
import org.labkey.api.ldk.ExtendedSimpleModule;
import org.labkey.api.ldk.LDKService;
import org.labkey.api.ldk.buttons.ShowBulkEditButton;
import org.labkey.api.ldk.buttons.ShowEditUIButton;
import org.labkey.api.ldk.notification.NotificationService;
import org.labkey.api.module.ModuleContext;
import org.labkey.api.query.FieldKey;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.SystemMaintenance;
import org.labkey.api.writer.ContainerUser;
import org.labkey.mgap.buttons.PopulateAnnotationsButton;
import org.labkey.mgap.buttons.ReleaseButton;
import org.labkey.mgap.jbrowse.mGAPFieldCustomizer;
import org.labkey.mgap.jbrowse.mGAPGroupsProvider;
import org.labkey.mgap.jbrowse.mGAPLuceneDetector;
import org.labkey.mgap.pipeline.AnnotationStep;
import org.labkey.mgap.pipeline.GenerateMgapTracksStep;
import org.labkey.mgap.pipeline.GroupCompareStep;
import org.labkey.mgap.pipeline.IndexVariantsForMgapStep;
import org.labkey.mgap.pipeline.RemoveAnnotationsForMgapStep;
import org.labkey.mgap.pipeline.RemoveAnnotationsStep;
import org.labkey.mgap.pipeline.RenameSamplesForMgapStep;
import org.labkey.mgap.pipeline.SampleSpecificGenotypeFiltrationStep;
import org.labkey.mgap.pipeline.VcfComparisonStep;
import org.labkey.mgap.pipeline.mGapReleaseAlleleFreqStep;
import org.labkey.mgap.pipeline.mGapReleaseAnnotateNovelSitesStep;
import org.labkey.mgap.pipeline.mGapReleaseComparisonStep;
import org.labkey.mgap.pipeline.mGapReleaseGenerator;
import org.labkey.mgap.query.mGAPUserSchema;

import java.util.Set;

public class mGAPModule extends ExtendedSimpleModule
{
    public static final String NAME = "mGAP";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public Double getSchemaVersion()
    {
        return 16.71;
    }

    @Override
    protected void init()
    {
        addController(mGAPController.NAME, mGAPController.class);
    }

    @Override
    public void doStartupAfterSpringConfig(ModuleContext moduleContext)
    {
        AuditLogService.get().registerAuditType(new mGapAuditTypeProvider());

        LDKService.get().registerQueryButton(new ShowEditUIButton(this, mGAPSchema.NAME, mGAPSchema.TABLE_USER_REQUESTS), mGAPSchema.NAME, mGAPSchema.TABLE_USER_REQUESTS);
        LDKService.get().registerQueryButton(new ShowBulkEditButton(this, mGAPSchema.NAME, mGAPSchema.TABLE_ANIMAL_MAPPING), mGAPSchema.NAME, mGAPSchema.TABLE_ANIMAL_MAPPING);
        LDKService.get().registerQueryButton(new ShowBulkEditButton(this, mGAPSchema.NAME, mGAPSchema.TABLE_TRACKS_PER_RELEASE), mGAPSchema.NAME, mGAPSchema.TABLE_TRACKS_PER_RELEASE);
        LDKService.get().registerQueryButton(new ReleaseButton(this), mGAPSchema.NAME, mGAPSchema.TABLE_RELEASE_TRACKS);
        LDKService.get().registerQueryButton(new PopulateAnnotationsButton(this), mGAPSchema.NAME, mGAPSchema.TABLE_VARIANT_ANNOTATIONS);

        NotificationService.get().registerNotification(new mGAPUserNotification(this));

        JBrowseService.get().registerDemographicsSource(new mGAPDemographicsSource());
        JBrowseService.get().registerFieldCustomizer(new mGAPFieldCustomizer());
        JBrowseService.get().registerGroupsProvider(new mGAPGroupsProvider());
        JBrowseService.get().registerLuceneIndexDetector(new mGAPLuceneDetector());

        SystemMaintenance.addTask(new mGapMaintenanceTask());

        new PipelineStartup();
    }

    @Override
    protected void registerSchemas()
    {
        mGAPUserSchema.register(this);
    }

    public static class PipelineStartup
    {
        private static final Logger _log = LogManager.getLogger(mGAPModule.PipelineStartup.class);
        private static boolean _hasRegistered = false;

        public PipelineStartup()
        {
            if (_hasRegistered)
            {
                _log.warn("mGAP resources have already been registered, skipping");
            }
            else
            {
                SequenceAnalysisService.get().registerFileHandler(new mGapReleaseGenerator());
                SequencePipelineService.get().registerPipelineStep(new AnnotationStep.Provider());
                SequencePipelineService.get().registerPipelineStep(new GroupCompareStep.Provider());
                SequencePipelineService.get().registerPipelineStep(new RemoveAnnotationsForMgapStep.Provider());
                SequencePipelineService.get().registerPipelineStep(new RemoveAnnotationsStep.Provider());
                SequencePipelineService.get().registerPipelineStep(new RenameSamplesForMgapStep.Provider());
                SequencePipelineService.get().registerPipelineStep(new VcfComparisonStep.Provider());
                SequencePipelineService.get().registerPipelineStep(new mGapReleaseComparisonStep.Provider());
                SequencePipelineService.get().registerPipelineStep(new SampleSpecificGenotypeFiltrationStep.Provider());
                SequencePipelineService.get().registerPipelineStep(new mGapReleaseAnnotateNovelSitesStep.Provider());
                SequencePipelineService.get().registerPipelineStep(new GenerateMgapTracksStep.Provider());
                SequencePipelineService.get().registerPipelineStep(new IndexVariantsForMgapStep.Provider());
                SequencePipelineService.get().registerPipelineStep(new mGapReleaseAlleleFreqStep.Provider());

                _hasRegistered = true;
            }
        }
    }

    @NotNull
    @Override
    public JSONObject getPageContextJson(ContainerUser context)
    {
        JSONObject ret = super.getPageContextJson(context);

        SimpleFilter filter = new SimpleFilter();
        filter.addClause(ContainerFilter.current(context.getContainer()).createFilterClause(mGAPSchema.getInstance().getSchema(), FieldKey.fromString("container")));
        TableSelector ts = new TableSelector(mGAPSchema.getInstance().getSchema().getTable(mGAPSchema.TABLE_VARIANT_CATALOG_RELEASES), PageFlowUtil.set("rowid", "objectid", "version", "jbrowseId", "humanJbrowseId", "luceneIndex"), filter, new Sort("-releaseDate"));
        ts.setMaxRows(1);
        ts.forEachResults(rs -> {
            String jbrowseId = rs.getString(FieldKey.fromString("jbrowseId"));
            if (jbrowseId != null)
            {
                ret.put("mgapJBrowse", jbrowseId);
            }

            String humanJbrowseId = rs.getString(FieldKey.fromString("humanJbrowseId"));
            if (humanJbrowseId != null)
            {
                ret.put("mgapJBrowseHuman", humanJbrowseId);
            }

            Integer rowId = rs.getInt(FieldKey.fromString("rowid"));
            if (rowId != null && rowId > 0)
            {
                ret.put("mgapReleaseId", rowId);
            }

            Integer luceneIndexId = rs.getInt(FieldKey.fromString("luceneIndex"));
            if (luceneIndexId != null && luceneIndexId > 0)
            {
                ret.put("luceneIndexId", luceneIndexId);
            }

            String releaseVersion = rs.getString(FieldKey.fromString("version"));
            if (releaseVersion != null)
            {
                ret.put("mgapReleaseVersion", releaseVersion);
            }

            String mgapReleaseGUID = rs.getString(FieldKey.fromString("objectid"));
            if (mgapReleaseGUID != null)
            {
                ret.put("mgapReleaseGUID", mgapReleaseGUID);
            }
        });

        return ret;
    }

    @Override
    public @NotNull Set<Class> getUnitTests()
    {
        return PageFlowUtil.set(mGapReleaseGenerator.TestCase.class);
    }

    @Override
    public UpgradeCode getUpgradeCode()
    {
        return new mGapUpgradeCode();
    }
}