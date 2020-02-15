package org.labkey.mgap.pipeline;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.Interval;
import htsjdk.variant.utils.SAMSequenceDictionaryExtractor;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.writer.Options;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Results;
import org.labkey.api.data.Selector;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.reader.Readers;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.pipeline.AbstractPipelineStep;
import org.labkey.api.sequenceanalysis.pipeline.AbstractVariantProcessingStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStep;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStep;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStepOutputImpl;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.writer.PrintWriters;
import org.labkey.mgap.mGAPSchema;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RenameSamplesForMgapStep extends AbstractPipelineStep implements VariantProcessingStep
{
    public RenameSamplesForMgapStep(PipelineStepProvider provider, PipelineContext ctx)
    {
        super(provider, ctx);
    }

    public static class Provider extends AbstractVariantProcessingStepProvider<RenameSamplesForMgapStep> implements VariantProcessingStep.SupportsScatterGather
    {
        public Provider()
        {
            super("RenameSamplesForMgap", "Rename Sample For mGAP", "RenameSamplesForMgapStep", "This will rename the samples in the VCF based on the mGAP animal mapping table.  If the VCF contains samples not found in this table it will throw an error.", Arrays.asList(

            ), null, null);
        }

        @Override
        public PipelineStep create(PipelineContext context)
        {
            return new RenameSamplesForMgapStep(this, context);
        }
    }

    @Override
    public void init(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles) throws PipelineJobException
    {
        for (SequenceOutputFile so : inputFiles)
        {
            writeSampleInfoToFile(so.getFile());
        }
    }

    @Override
    public Output processVariants(File inputVCF, File outputDirectory, ReferenceGenome genome, @Nullable List<Interval> intervals) throws PipelineJobException
    {
        VariantProcessingStepOutputImpl output = new VariantProcessingStepOutputImpl();

        File outputFile = renameSamples(inputVCF, genome, intervals);

        output.setVcf(outputFile);
        output.addIntermediateFile(outputFile);
        output.addIntermediateFile(new File(outputFile.getPath() + ".tbi"));

        return output;
    }

    private boolean indexExists(File vcf)
    {
        return new File(vcf.getPath() + ".tbi").exists();
    }

    private File getSampleNameFile(File outputDir)
    {
        return new File(outputDir, "sampleMapping.txt");
    }

    private File renameSamples(File currentVCF, ReferenceGenome genome, @Nullable List<Interval> intervals) throws PipelineJobException
    {
        getPipelineCtx().getLogger().info("renaming samples in VCF");

        Set<String> allSamples = new HashSet<>();
        File outputFile = new File(currentVCF.getParentFile(), SequenceAnalysisService.get().getUnzippedBaseName(currentVCF.getName()) + ".renamed.vcf.gz");
        if (indexExists(outputFile))
        {
            getPipelineCtx().getLogger().info("re-using existing output: " + outputFile.getPath());
        }
        else
        {
            Map<String, String> sampleMap = parseSampleMap(getSampleNameFile(getPipelineCtx().getSourceDirectory(true)));

            VariantContextWriterBuilder builder = new VariantContextWriterBuilder();
            builder.setReferenceDictionary(SAMSequenceDictionaryExtractor.extractDictionary(genome.getSequenceDictionary()));
            builder.setOutputFile(outputFile);
            builder.setOption(Options.USE_ASYNC_IO);

            try (VCFFileReader reader = new VCFFileReader(currentVCF); VariantContextWriter writer = builder.build())
            {
                VCFHeader header = reader.getFileHeader();
                List<String> samples = header.getSampleNamesInOrder();
                List<String> remappedSamples = new ArrayList<>();

                for (String sample : samples)
                {
                    if (sampleMap.containsKey(sample))
                    {
                        remappedSamples.add(sampleMap.get(sample));
                    }
                    else if (!allSamples.contains(sample))
                    {
                        getPipelineCtx().getLogger().info("sample lacks an alias, but will not be included in output: " + sample);
                        remappedSamples.add(sample);
                    }
                    else
                    {
                        throw new PipelineJobException("No alternate name provided for sample: " + sample);
                    }
                }

                if (remappedSamples.size() != samples.size())
                {
                    throw new PipelineJobException("The number of renamed samples does not equal starting samples: " + samples.size() + " / " + remappedSamples.size());
                }

                writer.writeHeader(new VCFHeader(header.getMetaDataInInputOrder(), remappedSamples));
                if (intervals == null)
                {
                    try (CloseableIterator<VariantContext> it = reader.iterator())
                    {
                        while (it.hasNext())
                        {
                            writer.add(it.next());
                        }
                    }

                }
                else
                {
                    for (Interval interval : intervals)
                    {
                        try (CloseableIterator<VariantContext> it = reader.query(interval.getContig(), interval.getStart(), interval.getEnd()))
                        {
                            while (it.hasNext())
                            {
                                writer.add(it.next());
                            }
                        }
                    }
                }
            }
        }

        return outputFile;
    }

    private Map<String, String> parseSampleMap(File sampleMapFile) throws PipelineJobException
    {
        Map<String, String> ret = new HashMap<>();
        try (CSVReader reader = new CSVReader(Readers.getReader(sampleMapFile), '\t'))
        {
            String[] line;
            while ((line = reader.readNext()) != null)
            {
                ret.put(line[0], line[1]);
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        return ret;
    }

    private void writeSampleInfoToFile(File input) throws PipelineJobException
    {

        File outputFile = getSampleNameFile(getPipelineCtx().getSourceDirectory(true));
        getPipelineCtx().getLogger().debug("caching mGAP aliases to file: " + outputFile.getPath());

        Map<String, String> sampleNameMap = new HashMap<>();
        try
        {
            SequenceAnalysisService.get().ensureVcfIndex(input, getPipelineCtx().getLogger());
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        try (VCFFileReader reader = new VCFFileReader(input))
        {
            VCFHeader header = reader.getFileHeader();
            List<String> subjects = header.getSampleNamesInOrder();
            if (subjects.isEmpty())
            {
                return;
            }

            TableInfo ti = QueryService.get().getUserSchema(getPipelineCtx().getJob().getUser(), (getPipelineCtx().getJob().getContainer().isWorkbook() ? getPipelineCtx().getJob().getContainer().getParent() : getPipelineCtx().getJob().getContainer()), mGAPSchema.NAME).getTable(mGAPSchema.TABLE_ANIMAL_MAPPING);
            TableSelector ts = new TableSelector(ti, PageFlowUtil.set("subjectname", "externalAlias"), new SimpleFilter(FieldKey.fromString("subjectname"), subjects, CompareType.IN), null);
            ts.forEachResults(new Selector.ForEachBlock<Results>()
            {
                @Override
                public void exec(Results rs) throws SQLException
                {
                    sampleNameMap.put(rs.getString(FieldKey.fromString("subjectname")), rs.getString(FieldKey.fromString("externalAlias")));
                }
            });

            Set<String> sampleNames = new HashSet<>(header.getSampleNamesInOrder());
            getPipelineCtx().getLogger().info("total samples in input VCF: " + sampleNames.size());

            sampleNames.retainAll(subjects);
            getPipelineCtx().getLogger().info("total samples to be written to any track: " + sampleNames.size());

            sampleNames.removeAll(sampleNameMap.keySet());
            if (!sampleNames.isEmpty())
            {
                throw new PipelineJobException("mGAP Aliases were not found for all IDs.  Missing: " + StringUtils.join(sampleNames, ", "));
            }
        }

        getPipelineCtx().getLogger().info("total sample names to alias: " + sampleNameMap.size());
        try (CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(outputFile), '\t', CSVWriter.NO_QUOTE_CHARACTER))
        {
            for (String name : sampleNameMap.keySet())
            {
                writer.writeNext(new String[]{name, sampleNameMap.get(name)});
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }
}
