package org.labkey.mgap.pipeline;

import htsjdk.samtools.util.Interval;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.reader.Readers;
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
import org.labkey.api.sequenceanalysis.run.AbstractDiscvrSeqWrapper;
import org.labkey.api.writer.PrintWriters;

import java.io.BufferedReader;
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
public class GroupCompareStep extends AbstractCommandPipelineStep<GroupCompareStep.GroupComparison> implements VariantProcessingStep, VariantProcessingStep.SupportsScatterGather
{
    public static final String REF_VCF = "refVcf";
    public static String GROUP1 = "group1";
    public static String GROUP2 = "group2";


    public GroupCompareStep(PipelineStepProvider<?> provider, PipelineContext ctx)
    {
        super(provider, ctx, new GroupComparison(ctx.getLogger()));
    }

    public static class Provider extends AbstractVariantProcessingStepProvider<GroupCompareStep> implements SupportsScatterGather
    {
        public Provider()
        {
            super("GroupCompare", "Group Comparison", "DISCVRseq/GroupCompare", "This is designed to help with sifting and prioritizing variants. It will generate a VCF limited to just the samples in group 1 (and group 2 if provided). It will compare the AF, N_HOMVAR, N_HOMREF, and N_HET within each group. If a reference VCF is provided (e.g., population-level data), these values will also be computed on that dataset. The resulting VCF is designed to be the starting point for secondary filtering.", List.of(
                    ToolParameterDescriptor.create(GROUP1, "Group 1 Sample(s)", "Only variants of the selected type(s) will be included", "sequenceanalysis-trimmingtextarea", new JSONObject(){{
                        put("allowBlank", false);
                    }}, null),
                    ToolParameterDescriptor.create(GROUP2, "Group 2 Sample(s)", "Optional. Only variants of the selected type(s) will be included", "sequenceanalysis-trimmingtextarea", null, null),
                    ToolParameterDescriptor.createExpDataParam(REF_VCF, "Reference VCF", "This is the file ID of the VCF to use as the reference.", "ldk-expdatafield", new JSONObject()
                    {{

                    }}, null),
                    ToolParameterDescriptor.create("vcfSelects", "VCF Select Expressions", "Filter expressions that can be used to subset variants output in the VCF.", "sequenceanalysis-variantfilterpanel", new JSONObject(){{
                        put("mode", "SELECT");
                        put("showFilterName", false);
                        put("title", "Select Expressions");
                    }}, null),
                    ToolParameterDescriptor.create("tableSelects", "Table Select Expressions", "Filter expressions that can be used to subset variants. Passing variants will be written to a separate TSV file. This does not impact the VCF", "sequenceanalysis-variantfilterpanel", new JSONObject(){{
                        put("mode", "SELECT");
                        put("showFilterName", false);
                        put("title", "Select Expressions");
                    }}, null),
                    ToolParameterDescriptor.create("extraFields", "Additional Fields", "A list of additional fields to include in the table output", "sequenceanalysis-trimmingtextarea", null, null)
                ), Arrays.asList("/sequenceanalysis/field/TrimmingTextArea.js", "sequenceanalysis/panel/VariantFilterPanel.js"), null);
        }

        @Override
        public GroupCompareStep create(PipelineContext ctx)
        {
            return new GroupCompareStep(this, ctx);
        }
    }

    @Override
    public void performAdditionalMergeTasks(SequenceOutputHandler.JobContext ctx, PipelineJob job, TaskFileManager manager, ReferenceGenome genome, List<File> orderedScatterOutputs, List<String> orderedJobDirs) throws PipelineJobException
    {
        job.getLogger().info("Merging variant tables");
        List<File> toConcat = orderedScatterOutputs.stream().map(f -> {
            f = new File(f.getParentFile(), f.getName().replaceAll("vcf.gz", "txt"));
            if (!f.exists())
            {
                throw new IllegalStateException("Missing file: " + f.getPath());
            }

            ctx.getFileManager().addIntermediateFile(f);

            return f;
        }).toList();

        String basename = SequenceAnalysisService.get().getUnzippedBaseName(toConcat.get(0).getName());
        File combined = new File(ctx.getSourceDirectory(), basename + ".txt");
        try (PrintWriter writer = PrintWriters.getPrintWriter(combined))
        {
            boolean hasWrittenHeader = false;
            for (File f : toConcat)
            {
                try (BufferedReader reader = Readers.getReader(f))
                {
                    String line;
                    int idx = 0;
                    while ((line = reader.readLine()) != null)
                    {
                        idx++;
                        if (idx == 1)
                        {
                            if (!hasWrittenHeader) {
                                writer.println(line);
                                hasWrittenHeader = true;
                            }
                        }
                        else
                        {
                            writer.println(line);
                        }
                    }
                }
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        SequenceOutputFile so = new SequenceOutputFile();
        so.setName(basename + ": Selected Variants");
        so.setFile(combined);
        so.setCategory("Variant List");
        so.setLibrary_id(genome.getGenomeId());
        manager.addSequenceOutput(so);
    }

    @Override
    public Output processVariants(File inputVCF, File outputDirectory, ReferenceGenome genome, @Nullable List<Interval> intervals) throws PipelineJobException
    {
        VariantProcessingStepOutputImpl output = new VariantProcessingStepOutputImpl();
        getPipelineCtx().getLogger().info("Running GroupCompare");

        List<String> extraArgs = new ArrayList<>();
        if (intervals != null)
        {
            intervals.forEach(interval -> {
                extraArgs.add("-L");
                extraArgs.add(interval.getContig() + ":" + interval.getStart() + "-" + interval.getEnd());
            });
        }

        Integer refFileId = getProvider().getParameterByName(REF_VCF).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        if (refFileId != null)
        {
            File refVcf = getPipelineCtx().getSequenceSupport().getCachedData(refFileId);
            if (!refVcf.exists())
            {
                throw new PipelineJobException("Unable to find file: " + refVcf.getPath());
            }

            extraArgs.add("-RV");
            extraArgs.add(refVcf.getPath());
        }

        //JEXL
        addSelects("vcfSelects", "-select", extraArgs);
        addSelects("tableSelects", "-table-select", extraArgs);

        String fieldsText = StringUtils.trimToNull(getProvider().getParameterByName("extraFields").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), String.class, null));
        if (fieldsText != null)
        {
            String[] names = fieldsText.split(";");
            for (String name : names)
            {
                extraArgs.add("-F");
                extraArgs.add(name);
            }
        }

        String group1Raw = StringUtils.trimToNull(getProvider().getParameterByName(GROUP1).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), String.class, null));
        List<String> group1 = Arrays.asList(group1Raw.split(";"));
        getPipelineCtx().getLogger().info("Total group 1 samples: " + group1.size());

        String group2Raw = StringUtils.trimToNull(getProvider().getParameterByName(GROUP2).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), String.class, null));
        List<String> group2 = null;
        if (group2Raw != null)
        {
            group2 = Arrays.asList(group2Raw.split(";"));
            getPipelineCtx().getLogger().info("Total group 2 samples: " + group2.size());
        }

        File outputVcf = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".gc.vcf.gz");
        File outputTable = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".gc.txt");

        getWrapper().runTool(inputVCF, outputVcf, outputTable, genome.getWorkingFastaFile(), group1, group2, extraArgs);
        if (!outputTable.exists())
        {
            throw new PipelineJobException("Unable to find output: " + outputTable.getPath());
        }

        output.addInput(inputVCF, "Input VCF");
        output.addInput(genome.getWorkingFastaFile(), "Reference Genome");
        output.addOutput(outputTable, "GroupCompare Table");
        output.setVcf(outputVcf);

        return output;
    }

    private void addSelects(String param, String arg, List<String> extraArgs) throws PipelineJobException
    {
        String selectText = getProvider().getParameterByName(param).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), String.class, null);
        if (selectText != null)
        {
            JSONArray filterArr = new JSONArray(selectText);
            for (int i = 0; i < filterArr.length(); i++)
            {
                JSONArray arr = filterArr.getJSONArray(i);
                if (arr.length() < 2)
                {
                    throw new PipelineJobException("Improper select expression: " + filterArr.getString(i));
                }

                extraArgs.add(arg);
                extraArgs.add(arr.getString(1));
            }
        }
    }

    public static class GroupComparison extends AbstractDiscvrSeqWrapper
    {
        public GroupComparison(Logger log)
        {
            super(log);
        }

        public void runTool(File inputVCF, File outputVcf, File outputTable, File genomeFasta, List<String> group1, @Nullable List<String> group2, List<String> extraArgs) throws PipelineJobException
        {
            List<String> args = new ArrayList<>(getBaseArgs());
            args.add("GroupCompare");
            args.add("-R");
            args.add(genomeFasta.getPath());

            args.add("-V");
            args.add(inputVCF.getPath());

            args.add("-O");
            args.add(outputVcf.getPath());

            args.add("-OT");
            args.add(outputTable.getPath());

            args.add("--ignore-variants-starting-outside-interval");

            File group1File = new File(outputVcf.getParentFile(), "group1.args");
            File group2File = new File(outputVcf.getParentFile(), "group2.args");
            try (PrintWriter writer = PrintWriters.getPrintWriter(group1File))
            {
                group1.forEach(writer::println);
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
            args.add("-G1");
            args.add(group1File.getPath());

            if (group2 != null)
            {
                try (PrintWriter writer = PrintWriters.getPrintWriter(group2File))
                {
                    group2.forEach(writer::println);
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }

                args.add("-G2");
                args.add(group2File.getPath());
            }

            if (extraArgs != null)
            {
                args.addAll(extraArgs);
            }

            execute(args);

            group1File.delete();
            if (group2File != null)
            {
                group2File.delete();
            }
        }
    }
}
