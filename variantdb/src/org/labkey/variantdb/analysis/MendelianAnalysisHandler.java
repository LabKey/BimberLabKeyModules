package org.labkey.variantdb.analysis;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.exp.api.ExperimentService;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.PipelineJobService;
import org.labkey.api.pipeline.RecordedAction;
import org.labkey.api.resource.FileResource;
import org.labkey.api.resource.Resource;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.pipeline.AbstractParameterizedOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.sequenceanalysis.run.AbstractCommandWrapper;
import org.labkey.api.util.FileType;
import org.labkey.api.util.FileUtil;
import org.labkey.api.writer.PrintWriters;
import org.labkey.variantdb.VariantDBModule;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Created by bimber on 8/26/2014.
 */
public class MendelianAnalysisHandler extends AbstractParameterizedOutputHandler<SequenceOutputHandler.SequenceOutputProcessor>
{
    private final FileType _vcfType = new FileType("vcf", FileType.gzSupportLevel.SUPPORT_GZ);

    public MendelianAnalysisHandler()
    {
        super(ModuleLoader.getInstance().getModule(VariantDBModule.class), "Mendelian Check", "This will check the input VCF against the pedigree data and create two new VCFs, with passing/failing positions", new LinkedHashSet<>(Arrays.asList("/LDK/field/ExpDataField.js")), null);

    }

    @Override
    public boolean canProcess(SequenceOutputFile f)
    {
        return f.getFile() != null && (_vcfType.isType(f.getFile()));
    }

    @Override
    public boolean doRunRemote()
    {
        return true;
    }

    @Override
    public boolean doRunLocal()
    {
        return false;
    }

    @Override
    public SequenceOutputProcessor getProcessor()
    {
        return new Processor();
    }

    public class Processor implements SequenceOutputProcessor
    {
        @Override
        public void init(JobContext ctx, List<SequenceOutputFile> inputFiles, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {
            for (ToolParameterDescriptor pd : getParameters())
            {
                if (ctx.getParams().has(pd.getName()) && !StringUtils.isEmpty(ctx.getParams().getString(pd.getName())))
                {
                    ExpData d = ExperimentService.get().getExpData(ctx.getParams().getInt(pd.getName()));
                    if (d != null)
                    {
                        ctx.getSequenceSupport().cacheExpData(d);
                    }
                }
            }

            for (SequenceOutputFile f : inputFiles)
            {
                if (f.getLibrary_id() != null)
                {
                    ctx.getSequenceSupport().cacheGenome(SequenceAnalysisService.get().getReferenceGenome(f.getLibrary_id(), ctx.getJob().getUser()));
                }
            }
        }

        @Override
        public void processFilesOnWebserver(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles, JSONObject params, File outputDir, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {


        }

        @Override
        public void processFilesRemote(List<SequenceOutputFile> inputFiles, JobContext ctx) throws UnsupportedOperationException, PipelineJobException
        {
            String scriptFile = getScriptPath(VariantDBModule.NAME, "/external/gbsAnalysis.sh");
            String histogramScript = getScriptPath("sequenceanalysis", "/external/basicHistogram.r");

            int i = 0;
            for (SequenceOutputFile o : inputFiles)
            {
                i++;
                ctx.getJob().getLogger().info("processing: " + o.getName() + ", " + i + " of " + inputFiles.size());
                RecordedAction action = new RecordedAction("GBS Analysis");
                //action.addParameter(new RecordedAction.ParameterType("", PropertyType.STRING), "f");
                action.setStartTime(new Date());
                action.addInputIfNotPresent(o.getFile(), "Input BAM");

                List<String> arguments = new ArrayList<>();
                arguments.add("bash");
                arguments.add(scriptFile);

                arguments.add("-h");
                arguments.add(histogramScript);

                arguments.add("-i");
                arguments.add(o.getFile().getPath());

                ReferenceGenome g = ctx.getSequenceSupport().getCachedGenome(o.getLibrary_id());
                if (g != null)
                {
                    arguments.add("-r");
                    arguments.add(g.getWorkingFastaFile().getPath());
                }

                if (ctx.getParams().has("vcfFile") && !StringUtils.isEmpty(ctx.getParams().getString("vcfFile")))
                {
                    arguments.add("-v");
                    File f = ctx.getSequenceSupport().getCachedData(ctx.getParams().getInt("vcfFile"));
                    if (f == null)
                    {
                        throw new PipelineJobException("Unable to find cached file for exp data: " + ctx.getParams().getInt("vcfFile"));
                    }
                    arguments.add(f.getPath());
                }

                if (ctx.getParams().has("maskFile") && !StringUtils.isEmpty(ctx.getParams().getString("maskFile")))
                {
                    arguments.add("-m");
                    File f = ctx.getSequenceSupport().getCachedData(ctx.getParams().getInt("maskFile"));
                    if (f == null)
                    {
                        throw new PipelineJobException("Unable to find cached file for exp data: " + ctx.getParams().getInt("vcfFile"));
                    }
                    arguments.add(f.getPath());
                }

                if (ctx.getParams().has("cutSitesFile") && !StringUtils.isEmpty(ctx.getParams().getString("cutSitesFile")))
                {
                    arguments.add("-c");
                    File f = ctx.getSequenceSupport().getCachedData(ctx.getParams().getInt("cutSitesFile"));
                    if (f == null)
                    {
                        throw new PipelineJobException("Unable to find cached file for exp data: " + ctx.getParams().getInt("vcfFile"));
                    }
                    arguments.add(f.getPath());
                }

                String toolDir = PipelineJobService.get().getAppProperties().getToolsDirectory();
                if (!StringUtils.isEmpty(toolDir))
                {
                    arguments.add("-l");
                    arguments.add(toolDir);
                }

                AbstractCommandWrapper wrapper = new AbstractCommandWrapper(ctx.getJob().getLogger()){};
                wrapper.setOutputDir(ctx.getOutputDir());
                wrapper.setWorkingDir(ctx.getOutputDir());
                wrapper.execute(arguments);

                String basename = FileUtil.getBaseName(o.getFile());
                File html = new File(ctx.getOutputDir(), basename + ".summary.html");
                try (PrintWriter writer = PrintWriters.getPrintWriter(html))
                {
                    //find outputs
                    File insertSize = new File(ctx.getOutputDir(), basename + "_insertSize.pdf");
                    if (insertSize.exists())
                    {
                        action.addOutputIfNotPresent(insertSize, "Insert Size Histogram", false);
                    }

                    writer.write("<html><body><h2>" + o.getName() + ":</h2>");
                    writer.write("This report contains multiple graphs showing summaries of GBS coverage.<p>");

                    for (String depth : Arrays.asList("3", "20", "30"))
                    {
                        writer.write("<h3>GBS Fragments With >" + depth + "X Coverage:</h3><p>");

                        File cutSites = new File(ctx.getOutputDir(), basename + "_cutSites_" + depth + ".png");
                        if (cutSites.exists())
                        {
                            appendImage(cutSites, writer);
                        }

                        File merged = new File(ctx.getOutputDir(), basename + "_coverage_merged_distance_" + depth + ".png");
                        if (merged.exists())
                        {
                            appendImage(merged, writer);
                        }

                        File merged2 = new File(ctx.getOutputDir(), basename + "_coverage_merged_per_chromosome_" + depth + ".png");
                        if (merged2.exists())
                        {
                            appendImage(merged2, writer);
                        }

                        File merged3 = new File(ctx.getOutputDir(), basename + "_fragment_length_" + depth + ".png");
                        if (merged3.exists())
                        {
                            appendImage(merged3, writer);
                        }
                    }

                    File coverageSummary = new File(ctx.getOutputDir(), "coverage_summary.txt");
                    if (coverageSummary.exists())
                    {
                        action.addOutputIfNotPresent(coverageSummary, "GBS Summary", false);
                    }

                    action.addOutputIfNotPresent(html, "GBS Summary Report", false);

                    action.setEndTime(new Date());
                    ctx.addActions(action);
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }
            }
        }

        private void appendImage(File image, Writer writer) throws IOException
        {
            String encoded = Base64.encodeBase64String(FileUtils.readFileToByteArray(image));
            writer.write("<img src=\"data:image/png;base64," + encoded + "\"/>");
            writer.write("<br>");
            image.delete();
        }

        private String getScriptPath(String moduleName, String path) throws PipelineJobException
        {
            Module module = ModuleLoader.getInstance().getModule(moduleName);
            Resource script = module.getModuleResource(path);
            if (script == null || !script.exists())
                throw new PipelineJobException("Unable to find file: " + script.getPath() + " in module: " + moduleName);

            File f = ((FileResource) script).getFile();
            if (!f.exists())
                throw new PipelineJobException("Unable to find file: " + f.getPath());

            return f.getPath();
        }
    }
}
