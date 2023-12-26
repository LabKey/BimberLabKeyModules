package org.labkey.mgap.pipeline;

import htsjdk.samtools.util.Interval;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
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
import org.labkey.api.util.PageFlowUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * User: bimber
 * Date: 6/15/2014
 * Time: 12:39 PM
 */
public class mGapReleaseAlleleFreqStep extends AbstractCommandPipelineStep<mGapReleaseAlleleFreqStep.VariantAnnotatorWrapper> implements VariantProcessingStep, VariantProcessingStep.SupportsScatterGather
{
    public static final String REF_VCF = "refVcf";

    public mGapReleaseAlleleFreqStep(PipelineStepProvider<?> provider, PipelineContext ctx)
    {
        super(provider, ctx, new VariantAnnotatorWrapper(ctx.getLogger()));
    }

    public static class Provider extends AbstractVariantProcessingStepProvider<mGapReleaseAlleleFreqStep> implements SupportsScatterGather
    {
        public Provider()
        {
            super("mGapReleaseAlleleFreq", "Annotate using mGap Release AFs", "DiscvrVariantAnnotator", "Annotate a VCF using the AF field from an mGAP release.", List.of(
                    ToolParameterDescriptor.createExpDataParam(REF_VCF, "mGAP Release", "The mGAP release VCF to use for annotation", "sequenceanalysis-sequenceoutputfileselectorfield", new JSONObject()
                    {{
                        put("allowBlank", false);
                        put("category", "mGAP Release: Sites Only");
                        put("performGenomeFilter", false);
                        put("doNotIncludeInTemplates", true);
                    }}, null)
            ), PageFlowUtil.set("sequenceanalysis/field/SequenceOutputFileSelectorField.js"), null);
        }

        @Override
        public mGapReleaseAlleleFreqStep create(PipelineContext ctx)
        {
            return new mGapReleaseAlleleFreqStep(this, ctx);
        }
    }

    @Override
    public Output processVariants(File inputVCF, File outputDirectory, ReferenceGenome genome, @Nullable List<Interval> intervals) throws PipelineJobException
    {
        VariantProcessingStepOutputImpl output = new VariantProcessingStepOutputImpl();
        getPipelineCtx().getLogger().info("Annotating VCF using mGAP Release");

        Integer refFileId = getProvider().getParameterByName(REF_VCF).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        File refVcf = getPipelineCtx().getSequenceSupport().getCachedData(refFileId);
        if (refVcf == null || !refVcf.exists())
        {
            throw new PipelineJobException("Unable to find file: " + refFileId + ", path: " + refVcf);
        }

        List<String> extraArgs = new ArrayList<>();
        if (intervals != null)
        {
            intervals.forEach(interval -> {
                extraArgs.add("-L");
                extraArgs.add(interval.getContig() + ":" + interval.getStart() + "-" + interval.getEnd());
            });
        }

        extraArgs.add("-A");
        extraArgs.add("RefAlleleFrequency");

        extraArgs.add("--target-info-field-key");
        extraArgs.add("mGAP.AF");

        extraArgs.add("--af-source-vcf");
        extraArgs.add(refVcf.getPath());

        File outputVcf = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".af.vcf.gz");

        getWrapper().execute(genome.getWorkingFastaFile(), inputVCF, outputVcf, extraArgs);
        if (!outputVcf.exists())
        {
            throw new PipelineJobException("Unable to find output: " + outputVcf.getPath());
        }

        output.addInput(inputVCF, "Input VCF");
        output.addInput(genome.getWorkingFastaFile(), "Reference Genome");

        output.addOutput(outputVcf, "Annotated VCF");
        output.setVcf(outputVcf);

        return output;
    }

    public static class VariantAnnotatorWrapper extends AbstractDiscvrSeqWrapper
    {
        public VariantAnnotatorWrapper(Logger log)
        {
            super(log);
        }

        public void execute(File referenceFasta, File inputVcf, File outputVcf, List<String> options) throws PipelineJobException
        {
            getLogger().info("Running DiscvrVariantAnnotator");

            ensureDictionary(referenceFasta);

            List<String> args = new ArrayList<>(getBaseArgs());
            args.add("DiscvrVariantAnnotator");
            args.add("-R");
            args.add(referenceFasta.getPath());

            args.add("-V");
            args.add(inputVcf.getPath());

            args.add("-O");
            args.add(outputVcf.getPath());

            if (options != null)
            {
                args.addAll(options);
            }

            execute(args);
            if (!outputVcf.exists())
            {
                throw new PipelineJobException("Expected output not found: " + outputVcf.getPath());
            }
        }
    }
}
