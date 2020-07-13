package org.labkey.tcrdb.pipeline;

import au.com.bytecode.opencsv.CSVReader;
import org.apache.commons.beanutils.ConversionException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.json.JSONObject;
import org.labkey.api.data.ConvertHelper;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.DbSchemaType;
import org.labkey.api.data.Table;
import org.labkey.api.data.TableInfo;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedAction;
import org.labkey.api.reader.Readers;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.model.AnalysisModel;
import org.labkey.api.sequenceanalysis.model.Readset;
import org.labkey.api.sequenceanalysis.pipeline.AbstractParameterizedOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.AlignmentOutputImpl;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.util.FileType;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.tcrdb.TCRdbModule;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.labkey.tcrdb.pipeline.CellRangerVDJWrapper.DELETE_EXISTING_ASSAY_DATA;
import static org.labkey.tcrdb.pipeline.CellRangerVDJWrapper.TARGET_ASSAY;

public class CellRangerVDJCellHashingHandler extends AbstractParameterizedOutputHandler<SequenceOutputHandler.SequenceOutputProcessor>
{
    private FileType _fileType = new FileType("vloupe", false);
    public static final String CATEGORY = "Cell Hashing Calls (VDJ)";

    public CellRangerVDJCellHashingHandler()
    {
        super(ModuleLoader.getInstance().getModule(TCRdbModule.class), "CellRanger VDJ Import", "This will either directly import data (if cell hashing is not used), or run CiteSeqCount/MultiSeqClassifier to generate a sample-to-cellbarcode TSV based on the filtered barcodes from CellRanger VDJ and then import.", new LinkedHashSet<>(PageFlowUtil.set("tcrdb/field/AssaySelectorField.js")), getDefaultParams());
    }

    private static List<ToolParameterDescriptor> getDefaultParams()
    {
        List<ToolParameterDescriptor> ret = new ArrayList<>(Arrays.asList(
                ToolParameterDescriptor.create(TARGET_ASSAY, "Target Assay", "Results will be loaded into this assay.  If no assay is selected, a table will be created with nothing in the DB.", "tcr-assayselectorfield", null, null),
                ToolParameterDescriptor.create(DELETE_EXISTING_ASSAY_DATA, "Delete Any Existing Assay Data", "If selected, prior to importing assay data, and existing assay runs in the target container from this readset will be deleted.", "checkbox", new JSONObject(){{
                    put("checked", true);
                }}, true),
                ToolParameterDescriptor.create("useOutputFileContainer", "Submit to Source File Workbook", "If checked, each job will be submitted to the same workbook as the input file, as opposed to submitting all jobs to the same workbook.  This is primarily useful if submitting a large batch of files to process separately. This only applies if 'Run Separately' is selected.", "checkbox", new JSONObject(){{
                    put("checked", true);
                }}, false)
        ));

        ret.addAll(CellRangerCellHashingHandler.getDefaultHashingParams(true));

        return ret;
    }

    @Override
    public boolean canProcess(SequenceOutputFile o)
    {
        return o.getFile() != null && _fileType.isType(o.getFile());
    }

    @Override
    public boolean doRunRemote()
    {
        return true;
    }

    @Override
    public boolean doRunLocal()
    {
        return true;
    }

    @Override
    public SequenceOutputProcessor getProcessor()
    {
        return new CellRangerVDJCellHashingHandler.Processor();
    }

    @Override
    public boolean doSplitJobs()
    {
        return true;
    }

    @Override
    public boolean requiresSingleGenome()
    {
        return false;
    }

    public class Processor implements SequenceOutputHandler.SequenceOutputProcessor
    {
        @Override
        public void init(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles, JSONObject params, File outputDir, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {
            CellRangerVDJUtils utils = new CellRangerVDJUtils(job.getLogger(), outputDir);
            utils.prepareHashingAndCiteSeqFilesIfNeeded(job, support, "enrichedReadsetId", params.optBoolean("excludeFailedcDNA", true), true, false);
        }

        @Override
        public void processFilesOnWebserver(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles, JSONObject params, File outputDir, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {

        }

        @Override
        public void complete(PipelineJob job, List<SequenceOutputFile> inputFiles, List<SequenceOutputFile> outputsCreated, SequenceAnalysisJobSupport support) throws PipelineJobException
        {
            for (SequenceOutputFile so : outputsCreated)
            {
                if (CATEGORY.equals(so.getCategory()))
                {
                    processMetrics(so, job, true);
                }
            }

            CellRangerVDJUtils utils = new CellRangerVDJUtils(job.getLogger(), job.getLogFile().getParentFile());
            if (StringUtils.trimToNull(job.getParameters().get(TARGET_ASSAY)) == null)
            {
                job.getLogger().info("No assay selected, will not import");
            }
            else
            {
                Integer assayId = ConvertHelper.convert(job.getParameters().get(TARGET_ASSAY), Integer.class);
                if (assayId == null)
                {
                    throw new PipelineJobException("Invalid assay Id, cannot import: " + job.getParameters().get(TARGET_ASSAY));
                }

                Boolean deleteExistingData = false;
                if (job.getParameters().get(DELETE_EXISTING_ASSAY_DATA) != null)
                {
                    deleteExistingData = ConvertHelper.convert(job.getParameters().get(DELETE_EXISTING_ASSAY_DATA), Boolean.class);
                }

                for (SequenceOutputFile so : inputFiles)
                {
                    AnalysisModel model = support.getCachedAnalysis(so.getAnalysis_id());
                    utils.importAssayData(job, model, so.getFile().getParentFile(), assayId, null, deleteExistingData);
                }
            }
        }

        @Override
        public void processFilesRemote(List<SequenceOutputFile> inputFiles, JobContext ctx) throws UnsupportedOperationException, PipelineJobException
        {
            CellRangerVDJUtils utils = new CellRangerVDJUtils(ctx.getLogger(), ctx.getSourceDirectory());
            RecordedAction action = new RecordedAction(getName());

            for (SequenceOutputFile so : inputFiles)
            {
                ctx.getLogger().info("processing file: " + so.getName());

                //find TSV:
                File perCellTsv = utils.getPerCellCsv(so.getFile().getParentFile());
                if (!perCellTsv.exists())
                {
                    throw new PipelineJobException("Unable to find file: " + perCellTsv.getPath());
                }

                Readset rs = ctx.getSequenceSupport().getCachedReadset(so.getReadset());
                if (rs == null)
                {
                    throw new PipelineJobException("Unable to find readset for outputfile: " + so.getRowid());
                }
                else if (rs.getReadsetId() == null)
                {
                    throw new PipelineJobException("Readset lacks a rowId for outputfile: " + so.getRowid());
                }

                processVloupeFile(ctx, perCellTsv, rs, action, so.getLibrary_id());
            }

            ctx.addActions(action);
        }

        private void processVloupeFile(JobContext ctx, File perCellTsv, Readset rs, RecordedAction action, Integer genomeId) throws PipelineJobException
        {
            CellRangerVDJUtils utils = new CellRangerVDJUtils(ctx.getLogger(), ctx.getSourceDirectory());

            List<String> extraParams = new ArrayList<>();
            extraParams.addAll(getClientCommandArgs(ctx.getParams()));

            //prepare whitelist of cell indexes
            AlignmentOutputImpl output = new AlignmentOutputImpl();
            boolean scanEditDistances = ctx.getParams().optBoolean("scanEditDistances", false);
            int minCountPerCell = ctx.getParams().optInt("minCountPerCell", 3);
            int editDistance = ctx.getParams().optInt("editDistance", 2);

            File cellToHto = utils.runRemoteCellHashingTasks(output, CATEGORY, perCellTsv, rs, ctx.getSequenceSupport(), extraParams, ctx.getWorkingDirectory(), ctx.getSourceDirectory(), editDistance, scanEditDistances, genomeId, minCountPerCell);
            if (utils.useCellHashing(ctx.getSequenceSupport()) && cellToHto == null)
            {
                throw new PipelineJobException("Missing cell to HTO file");

            }

            ctx.getFileManager().addStepOutputs(action, output);

        }
    }

    private static File getMetricsFile(File callFile)
    {
        return new File(callFile.getPath().replaceAll(".calls.txt", ".metrics.txt"));
    }

    public static void processMetrics(SequenceOutputFile so, PipelineJob job, boolean updateDescription) throws PipelineJobException
    {
        if (so.getFile() != null)
        {
            Map<String, String> valueMap = new HashMap<>();

            File metrics = getMetricsFile(so.getFile());
            if (metrics.exists())
            {
                job.getLogger().info("Loading metrics");
                int total = 0;
                TableInfo ti = DbSchema.get("sequenceanalysis", DbSchemaType.Module).getTable("quality_metrics");
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
                        r.put("category", line[0]);
                        r.put("metricname", line[1]);

                        //NOTE: R saves NaN as NA.  This is fixed in the R code, but add this check here to let existing jobs import
                        String value = line[2];
                        if ("NA".equals(value))
                        {
                            value = "0";
                        }

                        String fieldName = NumberUtils.isCreatable(value) ? "metricvalue" : "qualvalue";
                        r.put(fieldName, value);

                        r.put("analysis_id", so.getAnalysis_id());
                        r.put("dataid", so.getDataId());
                        r.put("readset", so.getReadset());
                        r.put("container", job.getContainer());
                        r.put("createdby", job.getUser().getUserId());

                        Table.insert(job.getUser(), ti, r);
                        total++;

                        valueMap.put(line[1], value);
                    }

                    job.getLogger().info("total metrics: " + total);

                    if (updateDescription)
                    {
                        job.getLogger().debug("Updating description");
                        StringBuilder description = new StringBuilder();
                        if (StringUtils.trimToNull(so.getDescription()) != null)
                        {
                            description.append(StringUtils.trimToNull(so.getDescription()));
                        }

                        String delim = description.length() > 0 ? "\n" : "";

                        DecimalFormat fmt = new DecimalFormat("##.##%");
                        for (String metricName : Arrays.asList("InputBarcodes", "TotalCalled", "TotalCounts", "TotalSinglet", "FractionOfInputCalled", "FractionOfInputSinglet", "FractionOfInputDoublet", "FractionOfInputDiscordant", "FractionCalledNotInInput", "SeuratNonNegative", "MultiSeqNonNegative", "UniqueHtos", "UnknownTagMatchingKnown"))
                        {
                            if (valueMap.get(metricName) != null)
                            {
                                Double d = null;
                                if (metricName.startsWith("Fraction"))
                                {
                                    try
                                    {
                                        d = ConvertHelper.convert(valueMap.get(metricName), Double.class);
                                    }
                                    catch (ConversionException | IllegalArgumentException e)
                                    {
                                        job.getLogger().error("Unable to convert to double: " + valueMap.get(metricName));
                                        throw e;
                                    }
                                }

                                description.append(delim).append(metricName).append(": ").append(d == null ? valueMap.get(metricName) : fmt.format(d));
                                delim = ",\n";
                            }
                        }

                        so.setDescription(description.toString());

                        TableInfo tableOutputs = DbSchema.get("sequenceanalysis", DbSchemaType.Module).getTable("outputfiles");
                        Table.update(job.getUser(), tableOutputs, so, so.getRowid());
                    }
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }
            }
            else
            {
                job.getLogger().warn("Unable to find metrics file: " + metrics.getPath());
            }
        }
        else
        {
            job.getLogger().warn("Unable to update metrics, file id is null: " + so.getName());
        }
    }
}