package org.labkey.mgap.pipeline;

import htsjdk.samtools.util.Interval;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
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
import org.labkey.api.sequenceanalysis.run.AbstractDiscvrSeqWrapper;

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
public class VcfComparisonStep extends AbstractCommandPipelineStep<VcfComparisonStep.VcfComparison> implements VariantProcessingStep
{
    public static final String REF_VCF = "refVcf";

    public VcfComparisonStep(PipelineStepProvider<?> provider, PipelineContext ctx)
    {
        super(provider, ctx, new VcfComparison(ctx.getLogger()));
    }

    public static class Provider extends AbstractVariantProcessingStepProvider<VcfComparisonStep> implements SupportsScatterGather
    {
        public Provider()
        {
            super("VcfComparison", "VcfComparison", "VcfComparison", "Compare the VCF to a reference VCF, producing TSV reports with site- and genotype-level concordance.", List.of(
                    ToolParameterDescriptor.createExpDataParam(REF_VCF, "Reference VCF", "This is the file ID of the VCF to use as the reference.", "ldk-expdatafield", new JSONObject()
                    {{
                        put("allowBlank", false);
                    }}, null)
            ), null, null);
        }

        @Override
        public VcfComparisonStep create(PipelineContext ctx)
        {
            return new VcfComparisonStep(this, ctx);
        }
    }

    @Override
    public Output processVariants(File inputVCF, File outputDirectory, ReferenceGenome genome, @Nullable List<Interval> intervals) throws PipelineJobException
    {
        VariantProcessingStepOutputImpl output = new VariantProcessingStepOutputImpl();
        getPipelineCtx().getLogger().info("Running VcfComparison");

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

    public static class VcfComparison extends AbstractDiscvrSeqWrapper
    {
        public VcfComparison(Logger log)
        {
            super(log);
        }

        public void runTool(File inputVCF, File refVcf, File outputTable, File genomeFasta, List<String> extraArgs) throws PipelineJobException
        {
            List<String> args = new ArrayList<>(getBaseArgs());
            args.add("VcfComparison");
            args.add("-R");
            args.add(genomeFasta.getPath());

            args.add("-V");
            args.add(inputVCF.getPath());

            args.add("-rv");
            args.add(refVcf.getPath());

            args.add("-O");
            args.add(outputTable.getPath());

            args.add("--ignore-variants-starting-outside-interval");

            if (extraArgs != null)
            {
                args.addAll(extraArgs);
            }

            execute(args);
        }
    }
}
