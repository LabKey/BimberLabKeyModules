package org.labkey.mgap.pipeline;

import htsjdk.samtools.util.Interval;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.pipeline.AbstractVariantProcessingStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.TaskFileManager;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStep;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStepOutputImpl;
import org.labkey.api.sequenceanalysis.run.AbstractCommandPipelineStep;
import org.labkey.api.util.PageFlowUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * User: bimber
 * Date: 6/15/2014
 * Time: 12:39 PM
 */
public class mGapReleaseComparisonStep extends AbstractCommandPipelineStep<VcfComparisonStep.VcfComparison> implements VariantProcessingStep, VariantProcessingStep.SupportsScatterGather
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
            super("mGapReleaseComparison", "Compare VCF to mGap Release", "VcfComparison", "Compare the VCF to the specified mGAP release VCF, producing TSV/VCF reports with site- and genotype-level concordance.", List.of(
                    ToolParameterDescriptor.createExpDataParam(REF_VCF, "mGAP Release", "The mGAP release VCF to use for comparison", "sequenceanalysis-sequenceoutputfileselectorfield", new JSONObject()
                    {{
                        put("allowBlank", false);
                        put("category", "mGAP Release: Sites Only");
                        put("performGenomeFilter", false);
                        put("doNotIncludeInTemplates", true);
                    }}, null)
            ), PageFlowUtil.set("sequenceanalysis/field/SequenceOutputFileSelectorField.js"), null);
        }

        @Override
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

        File outputSummaryTable = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".comparison.txt");
        File missingSitesVcf = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".missingSites.vcf.gz");
        File novelSitesVcf = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".novelSites.vcf.gz");

        extraArgs.add("--novel-or-altered-sites-vcf");
        extraArgs.add(novelSitesVcf.getPath());

        extraArgs.add("--missing-sites-vcf");
        extraArgs.add(missingSitesVcf.getPath());

        getWrapper().runTool(inputVCF, refVcf, outputSummaryTable, genome.getWorkingFastaFile(), extraArgs);
        if (!novelSitesVcf.exists())
        {
            throw new PipelineJobException("Unable to find output: " + novelSitesVcf.getPath());
        }

        output.addInput(inputVCF, "Input VCF");
        output.addInput(genome.getWorkingFastaFile(), "Reference Genome");
        output.addOutput(novelSitesVcf, "VcfComparison Novel Sites VCF");
        output.addOutput(missingSitesVcf, "VcfComparison Missing Sites VCF");
        output.addOutput(outputSummaryTable, "VcfComparison Summary Table");

        output.setVcf(novelSitesVcf);

        return output;
    }

    @Override
    public void performAdditionalMergeTasks(SequenceOutputHandler.JobContext ctx, PipelineJob job, TaskFileManager manager, ReferenceGenome genome, List<File> orderedScatterOutputs, List<String> orderedJobDirs) throws PipelineJobException
    {
        job.getLogger().info("Merging missing sites VCFs");
        List<File> toConcat = orderedScatterOutputs.stream().map(f -> {
            f = new File(f.getParentFile(), f.getName().replaceAll("novelSites", "missingSites"));
            if (!f.exists())
            {
                throw new IllegalStateException("Missing file: " + f.getPath());
            }

            ctx.getFileManager().addIntermediateFile(f);
            ctx.getFileManager().addIntermediateFile(new File(f.getPath() + ".tbi"));

            return f;
        }).toList();

        String basename = SequenceAnalysisService.get().getUnzippedBaseName(toConcat.get(0).getName());
        File combined = new File(ctx.getSourceDirectory(), basename + ".vcf.gz");
        File combinedIdx = new File(combined.getPath() + ".tbi");
        if (combinedIdx.exists())
        {
            job.getLogger().info("VCF exists, will not recreate: " + combined.getPath());
        }
        else
        {
            combined = SequenceAnalysisService.get().combineVcfs(toConcat, combined, genome, job.getLogger(), true, null);
        }

        SequenceOutputFile so = new SequenceOutputFile();
        so.setName(basename + ": Missing Sites");
        so.setFile(combined);
        so.setCategory("Missing Sites VCF");
        so.setLibrary_id(genome.getGenomeId());
        manager.addSequenceOutput(so);
    }
}
