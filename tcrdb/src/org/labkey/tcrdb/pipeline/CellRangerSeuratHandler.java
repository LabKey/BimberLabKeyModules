package org.labkey.tcrdb.pipeline;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;
import htsjdk.samtools.util.IOUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.DbSchemaType;
import org.labkey.api.data.Table;
import org.labkey.api.data.TableInfo;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedAction;
import org.labkey.api.reader.Readers;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.model.Readset;
import org.labkey.api.sequenceanalysis.pipeline.AbstractParameterizedOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.sequenceanalysis.run.SimpleScriptWrapper;
import org.labkey.api.util.FileType;
import org.labkey.api.util.FileUtil;
import org.labkey.api.writer.PrintWriters;
import org.labkey.tcrdb.TCRdbModule;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CellRangerSeuratHandler extends AbstractParameterizedOutputHandler<SequenceOutputHandler.SequenceOutputProcessor>
{
    private FileType _fileType = new FileType("cloupe", false);

    public CellRangerSeuratHandler()
    {
        super(ModuleLoader.getInstance().getModule(TCRdbModule.class), "Run Seurat", "This will run a standard seurat-based pipeline on the selected 10x/cellranger data and save the resulting Seurat object as an rds file for external use.", new LinkedHashSet<>(), Arrays.asList(
                ToolParameterDescriptor.create("projectName", "Output Name", "This will be used as the final sample/file name.  If blank, the readset name will be used.  The latter cannot be used when merging multiple inputs.", "textfield", new JSONObject(){{

                }}, null),
                ToolParameterDescriptor.create("doSplitJobs", "Run Separately", "If checked, each input dataset will be run separately.  Otherwise they will be merged", "checkbox", new JSONObject(){{
                    put("checked", true);
                }}, false),
                ToolParameterDescriptor.create("useOutputFileContainer", "Submit to Source File Workbook", "If checked, each job will be submitted to the same workbook as the input file, as opposed to submitting all jobs to the same workbook.  This is primarily useful if submitting a large batch of files to process separately. This only applies if 'Run Separately' is selected.", "checkbox", new JSONObject(){{
                    put("checked", false);
                }}, false),
                ToolParameterDescriptor.create("dimsToUse", "PCs To Use", "If non-blank, this is the number of PCs that seurat will use for dim reduction steps.", "ldk-integerfield", new JSONObject(){{

                }}, null),
                ToolParameterDescriptor.create("minDimsToUse", "Minimum PCs To Use", "If non-blank, the pipeline will attempt to infer the number of PCs to use for dim reduction, but will not use fewer than this value.", "ldk-integerfield", new JSONObject(){{

                }}, 10),
                ToolParameterDescriptor.create("doCellFilter", "Perform Cell Filtering", "If selected, cells will be filtered on pct.mito and number of unique genes.", "checkbox", new JSONObject(){{
                    put("checked", true);
                }}, true),
                ToolParameterDescriptor.create("doCellCycle", "Perform Cell Cycle Correction", "If selected, the pipeline will attempt to correct for cell cycle.", "checkbox", new JSONObject(){{
                    put("checked", true);
                }}, true),
                ToolParameterDescriptor.create("useSCTransform", "Use SCTransform", "If selected, the pipeline will use the newer SCtransform method instead of the standard Seurat pipeline.", "checkbox", new JSONObject(){{
                    put("checked", false);
                }}, false),
                ToolParameterDescriptor.create("runSingleR", "Run SingleR", "If selected, SingleR will be run after Seurat processing.", "checkbox", new JSONObject(){{
                    put("checked", true);
                }}, true),
                ToolParameterDescriptor.create("mergeMethod", "Merge Method", "This determines whether any batch correction will be applied when merging datasets.", "ldk-simplecombo", new JSONObject(){{
                    put("storeValues", "simple;cca");
                }}, "simple"),
                ToolParameterDescriptor.create("scanEditDistances", "Scan Edit Distances (Hashing)", "If checked, CITE-seq-count will be run using edit distances from 0-3 and the iteration with the highest singlets will be used.", "checkbox", new JSONObject(){{
                    put("checked", true);
                }}, true),
                ToolParameterDescriptor.create("editDistance", "Edit Distance (Hashing)", null, "ldk-integerfield", null, 1),
                ToolParameterDescriptor.create("minCountPerCell", "Min Reads/Cell", null, "ldk-integerfield", null, 3)
            ));
    }

    @Override
    public boolean canProcess(SequenceOutputFile o)
    {
        return o.getFile() != null && _fileType.isType(o.getFile());
    }

    @Override
    public List<String> validateParameters(List<SequenceOutputFile> outputFiles, JSONObject params)
    {
        if (!params.optBoolean("doSplitJobs", false) && StringUtils.trimToNull(params.optString("projectName")) == null && outputFiles.size() > 1)
        {
            return Collections.singletonList("Must provide the output name when merging multiple inputs");
        }

        return null;
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
    public boolean requiresSingleGenome()
    {
        return false;
    }

    @Override
    public SequenceOutputProcessor getProcessor()
    {
        return new CellRangerSeuratHandler.Processor();
    }

    @Override
    public boolean doSplitJobs()
    {
        return false;
    }

    public class Processor implements SequenceOutputProcessor
    {
        @Override
        public void init(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles, JSONObject params, File outputDir, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {
            for (SequenceOutputFile so : inputFiles)
            {
                if (so.getReadset() != null)
                {
                    support.cacheReadset(so.getReadset(), job.getUser());
                }
                else
                {
                    job.getLogger().error("Output file lacks a readset and will be skipped: " + so.getRowid());
                }
            }

            new CellRangerVDJUtils(job.getLogger(), outputDir).prepareHashingFilesIfNeeded(job, support,"readsetId");
        }

        @Override
        public void processFilesOnWebserver(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles, JSONObject params, File outputDir, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {

        }

        @Override
        public void processFilesRemote(List<SequenceOutputFile> inputFiles, JobContext ctx) throws UnsupportedOperationException, PipelineJobException
        {
            RecordedAction action = new RecordedAction(getName());
            ctx.addActions(action);

            Set<String> rsNames = new HashSet<>();
            for (SequenceOutputFile so : inputFiles)
            {
                ctx.getFileManager().addInput(action, "CellRanger Loupe", so.getFile());
                if (so.getReadset() != null)
                {
                    rsNames.add(ctx.getSequenceSupport().getCachedReadset(so.getReadset()).getName());
                }
            }

            String outPrefix = StringUtils.trimToNull(ctx.getParams().getString("projectName"));
            if (outPrefix == null)
            {
                if (rsNames.size() == 1)
                {
                    outPrefix = rsNames.iterator().next();
                }
                else
                {
                    throw new PipelineJobException("Must provide the output prefix when merging more than one output file");
                }
            }
            outPrefix = FileUtil.makeLegalName(outPrefix);

            File seuratObj = new File(ctx.getWorkingDirectory(), outPrefix + ".seurat.rds");
            File doneFile = new File(seuratObj.getPath() + ".done");
            boolean seuratHasRun = doneFile.exists();
            if (seuratHasRun)
            {
                ctx.getLogger().info("Seurat has already run, will not repeat");
            }

            Map<SequenceOutputFile, String> dataMap = new HashMap<>();

            File pr = ctx.getFolderPipeRoot().getRootPath().getParentFile();  //drop the @files or @pipeline
            for (SequenceOutputFile so : inputFiles)
            {
                //start with seurat 3
                File subDir = new File(so.getFile().getParentFile(), "raw_feature_bc_matrix");
                if (!subDir.exists())
                {
                    //try 2
                    subDir = new File(so.getFile().getParentFile(), "raw_gene_bc_matrices");
                    if (subDir.exists())
                    {
                        //now infer subdir:
                        for (File f : subDir.listFiles())
                        {
                            if (f.isDirectory())
                            {
                                subDir = f;
                                break;
                            }
                        }
                    }
                }

                if (!subDir.exists())
                {
                    throw new PipelineJobException("Unable to find raw data for input: " + so.getFile().getPath());
                }

                try
                {
                    String subDirRel = FileUtil.relativize(pr, subDir, true);
                    ctx.getLogger().debug("pipe root: " + pr.getPath());
                    ctx.getLogger().debug("file path: " + subDir.getPath());
                    ctx.getLogger().debug("relative path: " + subDirRel);

                    //Copy raw data directory locally to avoid docker permission issues
                    String dirName = so.getRowid() + "_RawData";
                    File copyDir = new File(ctx.getWorkingDirectory(), dirName);
                    if (!seuratHasRun)
                    {
                        if (copyDir.exists())
                        {
                            ctx.getLogger().debug("Deleting directory: " + copyDir.getPath());
                            FileUtils.deleteDirectory(copyDir);
                        }

                        ctx.getLogger().debug("Copying raw data directory: " + subDir.getPath());
                        ctx.getLogger().debug("To: " + copyDir.getPath());
                        FileUtils.copyDirectory(subDir, copyDir);
                    }
                    ctx.getFileManager().addIntermediateFile(copyDir);

                    dataMap.put(so, dirName);
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }
            }

            File rmdScript = new File(SequenceAnalysisService.get().getScriptPath(TCRdbModule.NAME, "external/scRNAseq/Seurat3.rmd"));
            if (!rmdScript.exists())
            {
                throw new PipelineJobException("Unable to find script: " + rmdScript.getPath());
            }

            File wrapperScript = new File(SequenceAnalysisService.get().getScriptPath(TCRdbModule.NAME, "external/scRNAseq/seuratWrapper.sh"));
            if (!wrapperScript.exists())
            {
                throw new PipelineJobException("Unable to find script: " + wrapperScript.getPath());
            }

            File tmpScript = new File(ctx.getWorkingDirectory(), "script.R");
            File outHtml = new File(ctx.getWorkingDirectory(), outPrefix + ".html");

            try (PrintWriter writer = PrintWriters.getPrintWriter(tmpScript))
            {
                File scriptCopy = new File(ctx.getWorkingDirectory(), rmdScript.getName());
                if (scriptCopy.exists())
                {
                    scriptCopy.delete();
                }

                IOUtil.copyFile(rmdScript, scriptCopy);
                rmdScript = scriptCopy;
                ctx.getFileManager().addIntermediateFile(rmdScript);

                scriptCopy = new File(ctx.getWorkingDirectory(), wrapperScript.getName());
                if (scriptCopy.exists())
                {
                    scriptCopy.delete();
                }

                IOUtil.copyFile(wrapperScript, scriptCopy);
                ctx.getFileManager().addIntermediateFile(scriptCopy);

                writer.println("outPrefix <- '" + outPrefix + "'");
                writer.println("resolutionToUse <- 0.6");
                for (String v : new String[]{"dimsToUse", "minDimsToUse"})
                {
                    String val = StringUtils.trimToNull(ctx.getParams().optString(v));
                    val = val == null ? "NULL" : val;

                    writer.println(v + " <- " + val);
                }

                String mergeMethod = StringUtils.trimToNull(ctx.getParams().optString("mergeMethod"));
                mergeMethod = mergeMethod == null ? "NULL" : "'" + mergeMethod + "'";
                writer.println("mergeMethod <- " + mergeMethod);

                boolean doCellFilter = ctx.getParams().optBoolean("doCellFilter", true);
                writer.println("doCellFilter <- " + String.valueOf(doCellFilter).toUpperCase());

                boolean runSingleR = ctx.getParams().optBoolean("runSingleR", true);
                writer.println("runSingleR <- " + String.valueOf(runSingleR).toUpperCase());

                boolean doCellCycle = ctx.getParams().optBoolean("doCellCycle", true);
                writer.println("doCellCycle <- " + String.valueOf(doCellCycle).toUpperCase());

                boolean useSCTransform = ctx.getParams().optBoolean("useSCTransform", false);
                writer.println("useSCTransform <- " + String.valueOf(useSCTransform).toUpperCase());

                writer.println("data <- list(");
                String delim = "";
                for (SequenceOutputFile so : dataMap.keySet())
                {
                    writer.println("\t" + delim + "'" + so.getRowid() + "'='" + dataMap.get(so) + "'");
                    delim = ",";
                }
                writer.println(")");
                writer.println();
                writer.println();
                writer.println("setwd('/work')");

                writer.println("rmarkdown::render('" + rmdScript.getName() + "', clean=TRUE, output_file='" + outHtml.getName() + "')");
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            if (!seuratHasRun)
            {
                SimpleScriptWrapper wrapper = new SimpleScriptWrapper(ctx.getLogger());
                wrapper.setWorkingDir(ctx.getWorkingDirectory());
                wrapper.execute(Arrays.asList("/bin/bash", wrapperScript.getName(), pr.getPath()));

                try
                {
                    FileUtils.touch(doneFile);
                    ctx.getFileManager().addIntermediateFile(doneFile);
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }
            }

            if (!seuratObj.exists())
            {
                throw new PipelineJobException("Unable to find expected file: " + seuratObj.getPath());
            }

            String dimsToUse = StringUtils.trimToNull(ctx.getParams().optString("dimsToUse"));
            String minDimsToUse = StringUtils.trimToNull(ctx.getParams().optString("minDimsToUse"));
            String mergeMethod = StringUtils.trimToNull(ctx.getParams().optString("mergeMethod"));

            String description = StringUtils.join(new String[]{
                    "Correct Cell Cycle: " + ctx.getParams().optBoolean("doCellCycle", true),
                    "Perform Cell Filtering: " + ctx.getParams().optBoolean("doCellFilter", true),
                    "Min. Dims To Use: " + (minDimsToUse == null ? "NA" : minDimsToUse),
                    "Dims To Use: " + (dimsToUse == null ? "automatic" : dimsToUse),
                    "Use SCTransform: " + ctx.getParams().optBoolean("useSCTransform", false),
                    "Merge method: " + mergeMethod
            }, "\n");

            ctx.getFileManager().addSequenceOutput(seuratObj, "Seurat Object: " + outPrefix, "Seurat Data", (inputFiles.size() == 1 ? inputFiles.iterator().next().getReadset() : null), null, getGenomeId(inputFiles), description);
            ctx.getFileManager().addOutput(action, "Seurat Object", seuratObj);

            if (!outHtml.exists())
            {
                throw new PipelineJobException("Unable to find summary report");
            }
            ctx.getFileManager().addOutput(action, "Seurat Report", outHtml);
            ctx.getFileManager().addSequenceOutput(outHtml, "Seurat Report: " + outPrefix, "Seurat Report", (inputFiles.size() == 1 ? inputFiles.iterator().next().getReadset() : null), null, getGenomeId(inputFiles), description);

            File seuratObjRaw = new File(ctx.getWorkingDirectory(), outPrefix + ".rawData.rds");
            if (seuratObjRaw.exists())
            {
                ctx.getFileManager().addIntermediateFile(seuratObjRaw);
            }

            CellRangerVDJUtils utils = new CellRangerVDJUtils(ctx.getLogger(), ctx.getSourceDirectory());
            if (utils.useCellHashing(ctx.getSequenceSupport()))
            {
                File allCellBarcodes = new File(seuratObj.getParentFile(), seuratObj.getName().replaceAll("seurat.rds", "cellBarcodes.csv"));
                Map<String, File> finalCalls = new HashMap<>();
                if (!allCellBarcodes.exists())
                {
                    throw new PipelineJobException("Unable to find expected cell barcodes file.  This might indicate the seurat object was created with an older version of the pipeline.  Expected: " + allCellBarcodes.getPath());
                }

                for (SequenceOutputFile so : inputFiles)
                {
                    String barcodePrefix = so.getRowid().toString();
                    Readset rs = ctx.getSequenceSupport().getCachedReadset(so.getReadset());
                    if (rs == null)
                    {
                        throw new PipelineJobException("Unable to find readset for outputfile: " + so.getRowid());
                    }
                    else if (rs.getReadsetId() == null)
                    {
                        throw new PipelineJobException("Readset lacks a rowId for outputfile: " + so.getRowid());
                    }

                    //Subset barcodes by dataset:
                    File barcodes = new File(allCellBarcodes.getParentFile(), "cellBarcodeWhitelist." + barcodePrefix + ".txt");
                    try (CSVReader reader = new CSVReader(Readers.getReader(allCellBarcodes), '\t'); CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(barcodes), '\t', CSVWriter.NO_QUOTE_CHARACTER))
                    {
                        String[] line;
                        while ((line = reader.readNext()) != null)
                        {
                            String barcode = line[0];
                            if (barcode.startsWith(barcodePrefix + "_"))
                            {
                                barcode = barcode.split("_")[1];
                                writer.writeNext(new String[]{barcode});
                            }
                        }
                    }
                    catch (IOException e)
                    {
                        throw new PipelineJobException(e);
                    }

                    // write readset-specific HTO list
                    Integer hashingReadsetId = CellRangerVDJUtils.getCachedReadsetMap(ctx.getSequenceSupport()).get(rs.getReadsetId());
                    if (hashingReadsetId == null)
                    {
                        ctx.getLogger().info("No hashing readset for: " + rs.getReadsetId() + ", this probably indicates either hashing is not used or the hashing data is not available.");
                        continue;
                    }

                    File perReadsetHtos = new File(allCellBarcodes.getParentFile(), "allowableHtos." + barcodePrefix + ".txt");
                    int htosForReadset = 0;
                    try (CSVReader reader = new CSVReader(Readers.getReader(utils.getCDNAInfoFile()), '\t'); CSVWriter bcWriter = new CSVWriter(PrintWriters.getPrintWriter(perReadsetHtos), ',', CSVWriter.NO_QUOTE_CHARACTER))
                    {
                        String[] line;
                        while ((line = reader.readNext()) != null)
                        {
                            if (hashingReadsetId.toString().equals(line[7]))
                            {
                                htosForReadset++;
                                bcWriter.writeNext(new String[]{line[6], line[5]});
                            }
                        }
                    }
                    catch (IOException e)
                    {
                        throw new PipelineJobException(e);
                    }

                    if (htosForReadset > 0)
                    {
                        ctx.getLogger().info("Total HTOs for readset: " + htosForReadset);
                        finalCalls.put(barcodePrefix, CellRangerCellHashingHandler.processBarcodeFile(ctx, barcodes, rs, so.getLibrary_id(), action, getClientCommandArgs(ctx.getParams()), false, SeuratCellHashingHandler.CATEGORY, false, perReadsetHtos));
                    }
                    else
                    {
                        ctx.getLogger().info("No HTOs found for readset");
                    }
                }

                if (!finalCalls.isEmpty())
                {
                    ctx.getLogger().info("Storing cell hashing calls in seurat object");
                    appendCallsToSeurat(ctx, seuratObj, finalCalls);
                }
                else
                {
                    ctx.getLogger().info("Cell hashing was not used.  will not append to seurat");
                }
            }
            else
            {
                ctx.getLogger().info("Cell hashing was not used");
            }
        }

        private void appendCallsToSeurat(JobContext ctx, File seuratObj, Map<String, File> finalCalls) throws PipelineJobException
        {
            File rScript = new File(seuratObj.getParentFile(), "appendHashing.R");
            File bashScript = new File(seuratObj.getParentFile(), "runDocker.sh");

            try (PrintWriter rWriter = PrintWriters.getPrintWriter(rScript); PrintWriter bashWriter = PrintWriters.getPrintWriter(bashScript))
            {
                rWriter.println("library(OOSAP)");
                rWriter.println("seuratObj <- readRDS('" + seuratObj.getName() + "')");
                rWriter.println("initialCells <- ncol(seuratObj)");
                rWriter.println("callsFiles <- list(");
                int idx = 0;
                for (String barcodePrefix : finalCalls.keySet()) {
                    idx++;
                    rWriter.println("'" + barcodePrefix + "' = '" + finalCalls.get(barcodePrefix).getName() + "'" + (idx < finalCalls.size() ? "," : ""));
                }

                rWriter.println(")");
                rWriter.println("");
                rWriter.println("for (barcodePrefix in names(callsFiles)) {");
                rWriter.println("   seuratObj <- OOSAP:::AppendCellHashing(seuratObj = seuratObj, barcodeCallFile = callsFiles[[barcodePrefix]], barcodePrefix = barcodePrefix)");
                rWriter.println("}");
                rWriter.println("if (ncol(seuratObj) != initialCells) { stop('Cell count not equal after appending cell hashing calls!') }");
                rWriter.println("saveRDS(seuratObj, file = '" + seuratObj.getName() + "')");

                bashWriter.println("#!/bin/bash");
                bashWriter.println("set -e");
                bashWriter.println("set -x");
                bashWriter.println("DOCKER=/opt/acc/sbin/exadocker");
                bashWriter.println("WD=`pwd`");
                bashWriter.println("HOME=`echo ~/`");

                Integer maxRam = SequencePipelineService.get().getMaxRam();
                String ramOpts = "";
                if (maxRam != null)
                {
                    ramOpts = " --memory=" +maxRam  +"g ";
                }

                bashWriter.println("sudo $DOCKER pull bimberlab/oosap");
                bashWriter.println("sudo $DOCKER run --rm=true " + ramOpts + "-v \"${WD}:/work\" -v \"${HOME}:/homeDir\" -u $UID -e USERID=$UID -w /work -e HOME=/homeDir bimberlab/oosap Rscript --vanilla " + rScript.getName());

                SimpleScriptWrapper wrapper = new SimpleScriptWrapper(ctx.getLogger());
                wrapper.setWorkingDir(seuratObj.getParentFile());
                wrapper.execute(Arrays.asList("/bin/bash", bashScript.getName()));
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }

        @Override
        public void complete(PipelineJob job, List<SequenceOutputFile> inputs, List<SequenceOutputFile> outputsCreated, SequenceAnalysisJobSupport support) throws PipelineJobException
        {
            for (SequenceOutputFile so : outputsCreated)
            {
                if (so.getFile() != null && so.getFile().getPath().endsWith(".seurat.rds"))
                {
                    File metrics = new File(so.getFile().getPath().replaceAll(".seurat.rds", ".summary.txt"));
                    if (metrics.exists())
                    {
                        job.getLogger().info("Loading metrics");
                        TableInfo ti = DbSchema.get("sequenceanalysis", DbSchemaType.Module).getTable("quality_metrics");
                        int total = 0;
                        try (CSVReader reader = new CSVReader(Readers.getReader(metrics), '\t'))
                        {
                            String[] line;
                            while ((line = reader.readNext()) != null)
                            {
                                if ("Category".equals(line[0]))
                                {
                                    continue;
                                }

                                Map<String, Object> r = new HashMap<>();
                                r.put("category", "Seurat");
                                r.put("metricname", line[1]);
                                r.put("metricvalue", line[2]);
                                r.put("analysis_id", so.getAnalysis_id());
                                r.put("dataid", so.getDataId());
                                r.put("readset", so.getReadset());
                                r.put("container", job.getContainer());
                                r.put("createdby", job.getUser().getUserId());

                                Table.insert(job.getUser(), ti, r);
                                total++;
                            }
                        }
                        catch (IOException e)
                        {
                            throw new PipelineJobException(e);
                        }

                        job.getLogger().info("total metrics: " + total);
                    }
                    else
                    {
                        job.getLogger().warn("Unable to find metrics file: " + metrics.getPath());
                    }
                }
            }
        }
    }

    private Integer getGenomeId(List<SequenceOutputFile> inputFiles)
    {
        Set<Integer> genomeIds = new HashSet<>();
        inputFiles.forEach(x -> {
            genomeIds.add(x.getLibrary_id());
        });

        return genomeIds.size() == 1 ? genomeIds.iterator().next() : null;
    }
}
