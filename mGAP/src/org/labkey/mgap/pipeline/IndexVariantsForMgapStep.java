package org.labkey.mgap.pipeline;

import htsjdk.samtools.util.Interval;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.labkey.api.data.Container;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableSelector;
import org.labkey.api.jbrowse.JBrowseService;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.pipeline.AbstractVariantProcessingStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStep;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStepOutputImpl;
import org.labkey.api.sequenceanalysis.run.AbstractCommandPipelineStep;
import org.labkey.api.sequenceanalysis.run.SelectVariantsWrapper;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.mgap.mGAPSchema;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class IndexVariantsForMgapStep extends AbstractCommandPipelineStep<SelectVariantsWrapper> implements VariantProcessingStep
{
    public static final String CATEGORY = "Lucene Index";

    public IndexVariantsForMgapStep(PipelineStepProvider<?> provider, PipelineContext ctx)
    {
        super(provider, ctx, new SelectVariantsWrapper(ctx.getLogger()));
    }

    public static class Provider extends AbstractVariantProcessingStepProvider<IndexVariantsForMgapStep> implements SupportsScatterGather
    {
        public Provider()
        {
            super("IndexVariantsForMgapStep", "Index VCF for mGAP", "DISCVR-seq", "Create a lucene index for the selected fields, using the fields for mGAP", Arrays.asList(
                    ToolParameterDescriptor.create("releaseVersion", "mGAP Version", "This is a string that will be used in the file name.", "textfield", new JSONObject(){{
                        put("allowBlank", false);
                        put("doNotIncludeInTemplates", true);
                    }}, null),
                    ToolParameterDescriptor.create("allowLenientProcessing", "Allow Lenient Processing", "If selected, many error types will be logged but ignored.", "checkbox", null, false)
            ), null, "https://github.com/BimberLab/DISCVRSeq");
        }

        @Override
        public IndexVariantsForMgapStep create(PipelineContext ctx)
        {
            return new IndexVariantsForMgapStep(this, ctx);
        }
    }

    @Override
    public void init(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles) throws PipelineJobException
    {
        List<String> infoFields = getInfoFieldsToIndex(job.getContainer(), job.getUser());
        support.cacheObject("INFO_FIELDS", StringUtils.join(infoFields, ";"));
    }

    public static List<String> getInfoFieldsToIndex(Container c, User u)
    {
        Container target = c.isWorkbook() ? c.getParent() : c;
        return new TableSelector(QueryService.get().getUserSchema(u, target, mGAPSchema.NAME).getTable(mGAPSchema.TABLE_VARIANT_ANNOTATIONS), PageFlowUtil.set("infoKey"), new SimpleFilter(FieldKey.fromString("isIndexed"), true), null).getArrayList(String.class).stream().sorted().toList();
    }

    @Override
    public Output processVariants(File inputVCF, File outputDirectory, ReferenceGenome genome, @Nullable List<Interval> intervals) throws PipelineJobException
    {
        VariantProcessingStepOutputImpl output = new VariantProcessingStepOutputImpl();

        String infoFieldsRaw = getPipelineCtx().getSequenceSupport().getCachedObject("INFO_FIELDS", String.class);
        List<String> infoFields = Arrays.stream(infoFieldsRaw.split(";")).sorted().toList();
        boolean allowLenientProcessing = getProvider().getParameterByName("allowLenientProcessing").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Boolean.class, false);

        String releaseVersion = getProvider().getParameterByName("releaseVersion").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), String.class);

        File indexDir = new File(outputDirectory, "lucene");
        JBrowseService.get().prepareLuceneIndex(inputVCF, indexDir, getPipelineCtx().getLogger(), infoFields, allowLenientProcessing);

        File idx = new File(indexDir, "write.lock");
        if (!idx.exists())
        {
            throw new PipelineJobException("Unable to find file: " + idx.getPath());
        }

        output.addSequenceOutput(idx, "mGAP Lucene Index: " + releaseVersion, CATEGORY, null, null, genome.getGenomeId(), "Fields indexed: " + infoFieldsRaw);

        return output;
    }
}
