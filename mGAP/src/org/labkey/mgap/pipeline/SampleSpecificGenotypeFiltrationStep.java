package org.labkey.mgap.pipeline;

import htsjdk.samtools.util.Interval;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import org.apache.logging.log4j.Logger;
import org.json.old.JSONObject;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.model.Readset;
import org.labkey.api.sequenceanalysis.pipeline.AbstractVariantProcessingStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStep;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStepOutputImpl;
import org.labkey.api.sequenceanalysis.run.AbstractCommandPipelineStep;
import org.labkey.api.sequenceanalysis.run.AbstractDiscvrSeqWrapper;
import org.labkey.api.writer.PrintWriters;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * User: bimber
 * Date: 6/15/2014
 * Time: 12:39 PM
 */
public class SampleSpecificGenotypeFiltrationStep extends AbstractCommandPipelineStep<SampleSpecificGenotypeFiltrationStep.Wrapper> implements VariantProcessingStep
{
    public SampleSpecificGenotypeFiltrationStep(PipelineStepProvider<?> provider, PipelineContext ctx)
    {
        super(provider, ctx, new Wrapper(ctx.getLogger()));
    }

    public static class Provider extends AbstractVariantProcessingStepProvider<SampleSpecificGenotypeFiltrationStep> implements VariantProcessingStep.SupportsScatterGather
    {
        public Provider()
        {
            super("SampleSpecificGenotypeFiltrationStep", "WGS/WXS Genotype Depth Filter", "DISCVRseq", "This is a specialized step that allows genotypes to be filtered in an application-aware manner with different thresholds for WGS/WXS. This requires gVCF inputs, and all sample readsets must either have the application 'Whole Exome' or 'Whole Genome: Deep Coverage'", Arrays.asList(
                    ToolParameterDescriptor.create("wgsMinDepth", "WGS Min Depth", "The min depth for WGS samples.", "ldk-integerfield", new JSONObject(){{
                        put("minValue", 0);
                    }}, 10),
                    ToolParameterDescriptor.create("wgsMaxDepth", "WGS Max Depth", "The max depth for WGS samples.", "ldk-integerfield", new JSONObject(){{
                        put("minValue", 0);
                    }}, 50),
                    ToolParameterDescriptor.create("wgsMinQual", "WGS Min Qual", "The min genotype qual for WGS samples.", "ldk-integerfield", new JSONObject(){{
                        put("minValue", 0);
                    }}, 30),
                    ToolParameterDescriptor.create("wxsMinDepth", "WXS Min Depth", "The min depth for WXS samples.", "ldk-integerfield", new JSONObject(){{
                        put("minValue", 0);
                    }}, 10),
                    ToolParameterDescriptor.create("wxsMaxDepth", "WXS Max Depth", "The max depth for WXS samples.", "ldk-integerfield", new JSONObject(){{
                        put("minValue", 0);
                    }}, null),
                    ToolParameterDescriptor.create("wxsMinQual", "WXS Min Qual", "The min genotype qual for WXS samples.", "ldk-integerfield", new JSONObject(){{
                        put("minValue", 0);
                    }}, 30)
                    ), null, "");
        }

        @Override
        public SampleSpecificGenotypeFiltrationStep create(PipelineContext ctx)
        {
            return new SampleSpecificGenotypeFiltrationStep(this, ctx);
        }
    }

    @Override
    public void init(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles) throws PipelineJobException
    {
        try (PrintWriter writer = PrintWriters.getPrintWriter(getSampleMapFile()))
        {
            getPipelineCtx().getLogger().info("Writing Sample Map for WGS/WXS filtering");
            for (SequenceOutputFile so : inputFiles)
            {
                if (so.getReadset() == null)
                {
                    throw new PipelineJobException("This step requires all inputs to have a readset");
                }

                Readset rs = support.getCachedReadset(so.getReadset());

                try (VCFFileReader reader = new VCFFileReader(so.getFile()))
                {
                    VCFHeader header = reader.getFileHeader();
                    if (header.getSampleNamesInOrder().isEmpty())
                    {
                        throw new PipelineJobException("Expected VCF to have samples: " + so.getFile().getPath());
                    }
                    else if (header.getSampleNamesInOrder().size() != 1)
                    {
                        throw new PipelineJobException("Expected VCF to a single sample: " + so.getFile().getPath());
                    }

                    String setName;
                    switch (rs.getApplication())
                    {
                        case "Whole Genome: Deep Coverage":
                            setName = "WGS";
                            break;
                        case "Whole Exome":
                            setName = "WXS";
                            break;
                        default:
                            throw new IllegalArgumentException("Unknown application: " + rs.getApplication());
                    }

                    writer.println(header.getSampleNamesInOrder().get(0) + "\t" + setName);
                }
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }

    private File getSampleMapFile()
    {
        return new File(getPipelineCtx().getJob().isSplitJob() ? getPipelineCtx().getSourceDirectory().getParentFile() : getPipelineCtx().getSourceDirectory(), "sampleMap.txt");
    }

    @Override
    public Output processVariants(File inputVCF, File outputDirectory, ReferenceGenome genome, @Nullable List<Interval> intervals) throws PipelineJobException
    {
        VariantProcessingStepOutputImpl output = new VariantProcessingStepOutputImpl();
        File outputVcf = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".genotypeFiltered.vcf.gz");

        List<String> params = new ArrayList<>();
        params.addAll(getClientCommandArgs());

        Integer wgsMinDepth = getProvider().getParameterByName("wgsMinDepth").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        if (wgsMinDepth != null)
        {
            params.add("--genotype-filter-name");
            params.add("DP-LT" + wgsMinDepth);
            params.add("--genotype-filter-expression");
            params.add("WGS:DP<" + wgsMinDepth);
        }

        Integer wgsMaxDepth = getProvider().getParameterByName("wgsMaxDepth").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        if (wgsMaxDepth != null)
        {
            params.add("--genotype-filter-name");
            params.add("DP-GT" + wgsMaxDepth);
            params.add("--genotype-filter-expression");
            params.add("WGS:DP>" + wgsMaxDepth);
        }

        Integer wgsMinQual = getProvider().getParameterByName("wgsMinQual").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        if (wgsMinQual != null)
        {
            params.add("--genotype-filter-name");
            params.add("GQ-LT" + wgsMinQual);
            params.add("--genotype-filter-expression");
            params.add("WGS:GQ<" + wgsMinQual);
        }

        Integer wxsMinDepth = getProvider().getParameterByName("wxsMinDepth").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        if (wxsMinDepth != null)
        {
            params.add("--genotype-filter-name");
            params.add("DP-LT" + wxsMinDepth);
            params.add("--genotype-filter-expression");
            params.add("WXS:DP<" + wxsMinDepth);
        }

        Integer wxsMaxDepth = getProvider().getParameterByName("wxsMaxDepth").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        if (wxsMaxDepth != null)
        {
            params.add("--genotype-filter-name");
            params.add("DP-GT" + wxsMaxDepth);
            params.add("--genotype-filter-expression");
            params.add("WXS:DP>" + wxsMaxDepth);
        }

        Integer wxsMinQual = getProvider().getParameterByName("wxsMinQual").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        if (wxsMinQual != null)
        {
            params.add("--genotype-filter-name");
            params.add("GQ-LT" + wxsMinQual);
            params.add("--genotype-filter-expression");
            params.add("WXS:GQ<" + wxsMinQual);
        }

        params.add("--sample-map");
        params.add(getSampleMapFile().getPath());

        if (intervals != null)
        {
            intervals.forEach(interval -> {
                params.add("-L");
                params.add(interval.getContig() + ":" + interval.getStart() + "-" + interval.getEnd());
            });
        }

        getWrapper().execute(genome.getWorkingFastaFile(), inputVCF, outputVcf, params);
        if (!outputVcf.exists())
        {
            throw new PipelineJobException("unable to find output: " + outputVcf.getPath());
        }

        output.setVcf(outputVcf);

        return output;
    }

    public static class Wrapper extends AbstractDiscvrSeqWrapper
    {
        public Wrapper(Logger log)
        {
            super(log);
        }

        public void execute(File fasta, File inputVcf, File outputVcf, List<String> extraParams) throws PipelineJobException
        {
            List<String> args = new ArrayList<>(getBaseArgs());
            args.add("SampleSpecificGenotypeFiltration");

            args.add("-R");
            args.add(fasta.getPath());

            args.add("-V");
            args.add(inputVcf.getPath());

            args.add("-O");
            args.add(outputVcf.getPath());

            args.addAll(extraParams);

            execute(args);
            if (!outputVcf.exists())
            {
                throw new PipelineJobException("Missing file: " + outputVcf.getPath());
            }
        }
    }
}
