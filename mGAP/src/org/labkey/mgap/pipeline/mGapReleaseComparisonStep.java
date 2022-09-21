package org.labkey.mgap.pipeline;

import htsjdk.samtools.util.Interval;
import org.json.old.JSONObject;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.pipeline.AbstractVariantProcessingStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStep;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStepOutputImpl;
import org.labkey.api.sequenceanalysis.run.AbstractCommandPipelineStep;
import org.labkey.api.util.PageFlowUtil;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * User: bimber
 * Date: 6/15/2014
 * Time: 12:39 PM
 */
public class mGapReleaseComparisonStep extends AbstractCommandPipelineStep<VcfComparisonStep.VcfComparison> implements VariantProcessingStep
{
    public static final String REF_VCF = "refVcf";

    public mGapReleaseComparisonStep(PipelineStepProvider<?> provider, PipelineContext ctx)
    {
        super(provider, ctx, new VcfComparisonStep.VcfComparison(ctx.getLogger()));
    }

    public static class Provider extends AbstractVariantProcessingStepProvider<mGapReleaseComparisonStep> implements SupportsScatterGather
    {
        public Provider()
        {
            super("mGapReleaseComparison", "mGapReleaseComparison", "VcfComparison", "Compare the VCF to the specified mGAP release VCF, producing TSV/VCF reports with site- and genotype-level concordance.", Arrays.asList(
                    ToolParameterDescriptor.createExpDataParam(REF_VCF, "mGAP Release", "This is the pre-computed celltypist model to use for classification", "sequenceanalysis-sequenceoutputfileselectorfield", new JSONObject(){{
                        put("allowBlank", false);
                        put("category", "mGAP Release");
                        put("performGenomeFilter", false);
                    }}, null)
            ), PageFlowUtil.set("sequenceanalysis/field/SequenceOutputFileSelectorField.js"), null);
        }

        public mGapReleaseComparisonStep create(PipelineContext ctx)
        {
            return new mGapReleaseComparisonStep(this, ctx);
        }
    }

    @Override
    public Output processVariants(File inputVCF, File outputDirectory, ReferenceGenome genome, @Nullable List<Interval> intervals) throws PipelineJobException
    {
        VariantProcessingStepOutputImpl output = new VariantProcessingStepOutputImpl();
        getPipelineCtx().getLogger().info("Running mGAP Release Comparison");

        Integer refFileId = getProvider().getParameterByName(REF_VCF).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        File refVcf = getPipelineCtx().getSequenceSupport().getCachedData(refFileId);
        if (refVcf == null || !refVcf.exists())
        {
            throw new PipelineJobException("Unable to find file: " + refFileId + "/" + refVcf);
        }

        List<String> extraArgs = new ArrayList<>();
        if (intervals != null)
        {
            intervals.forEach(interval -> {
                extraArgs.add("-L");
                extraArgs.add(interval.getContig() + ":" + interval.getStart() + "-" + interval.getEnd());
            });
        }

        File outputTable = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".comparison.txt");
        File missingSitesVcf = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".missingSites.vcf.gz");
        File novelSitesVcf = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".novelSites.vcf.gz");

        extraArgs.add("--novel-or-altered-sites-vcf");
        extraArgs.add(novelSitesVcf.getPath());

        extraArgs.add("--missing-sites-vcf");
        extraArgs.add(missingSitesVcf.getPath());

        getWrapper().runTool(inputVCF, refVcf, outputTable, genome.getWorkingFastaFile(), extraArgs);
        if (!outputTable.exists())
        {
            throw new PipelineJobException("Unable to find output: " + outputTable.getPath());
        }

        output.addInput(inputVCF, "Input VCF");
        output.addInput(genome.getWorkingFastaFile(), "Reference Genome");
        output.addOutput(outputTable, "VcfComparison Table");
        output.addOutput(outputTable, "VcfComparison Site Table");

        return output;
    }
}
