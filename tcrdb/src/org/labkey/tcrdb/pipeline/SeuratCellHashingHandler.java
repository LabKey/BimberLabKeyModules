package org.labkey.tcrdb.pipeline;

import org.json.JSONObject;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedAction;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.model.Readset;
import org.labkey.api.sequenceanalysis.pipeline.AbstractParameterizedOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.CommandLineParam;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.util.FileType;
import org.labkey.tcrdb.TCRdbModule;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class SeuratCellHashingHandler extends AbstractParameterizedOutputHandler<SequenceOutputHandler.SequenceOutputProcessor>
{
    private FileType _fileType = new FileType(".seurat.rds", false);
    public static final String CATEGORY = "Seurat Cell Hashing Calls";

    public SeuratCellHashingHandler()
    {
        super(ModuleLoader.getInstance().getModule(TCRdbModule.class), "Seurat GEX/Cell Hashing", "This will run CiteSeqCount/MultiSeqClassifier to generate a sample-to-cellbarcode TSV based on the cell barcodes present in the saved Seurat object.", null, Arrays.asList(
                ToolParameterDescriptor.create("scanEditDistances", "Scan Edit Distances", "If checked, CITE-seq-count will be run using edit distances from 0-3 and the iteration with the highest singlets will be used.", "checkbox", new JSONObject(){{
                    put("checked", true);
                }}, true),
                ToolParameterDescriptor.create("editDistance", "Edit Distance", null, "ldk-integerfield", null, 1),
                ToolParameterDescriptor.create("minCountPerCell", "Min Reads/Cell (Cell Hashing)", null, "ldk-integerfield", null, 3),
                ToolParameterDescriptor.create("useOutputFileContainer", "Submit to Source File Workbook", "If checked, each job will be submitted to the same workbook as the input file, as opposed to submitting all jobs to the same workbook.  This is primarily useful if submitting a large batch of files to process separately..", "checkbox", new JSONObject()
                {{
                    put("checked", true);
                }}, false)
        ));
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
        return false;
    }

    @Override
    public SequenceOutputProcessor getProcessor()
    {
        return new Processor();
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
            new CellRangerVDJUtils(job.getLogger(), outputDir).prepareHashingFilesIfNeeded(job, support, "readsetId");
        }

        @Override
        public void processFilesOnWebserver(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles, JSONObject params, File outputDir, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {

        }

        @Override
        public void processFilesRemote(List<SequenceOutputFile> inputFiles, SequenceOutputHandler.JobContext ctx) throws UnsupportedOperationException, PipelineJobException
        {
            RecordedAction action = new RecordedAction(getName());

            for (SequenceOutputFile so : inputFiles)
            {
                ctx.getLogger().info("processing file: " + so.getName());

                File barcodes = new File(so.getFile().getParentFile(), so.getFile().getName().replaceAll("seurat.rds", "cellBarcodes.csv"));
                if (!barcodes.exists())
                {
                    throw new PipelineJobException("Unable to find expected cell barcodes file.  This might indicate the seurat object was created with an older version of the pipeline.  Expected: " + barcodes.getPath());
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

                CellRangerCellHashingHandler.processBarcodeFile(ctx, barcodes, rs, so.getLibrary_id(), action, getClientCommandArgs(ctx.getParams()), false, CATEGORY);
            }

            ctx.addActions(action);
        }

        @Override
        public void complete(PipelineJob job, List<SequenceOutputFile> inputs, List<SequenceOutputFile> outputsCreated, SequenceAnalysisJobSupport support) throws PipelineJobException
        {
            for (SequenceOutputFile so : outputsCreated)
            {
                if (so.getCategory().equals(CATEGORY))
                {
                    CellRangerVDJCellHashingHandler.processMetrics(so, job, true);
                }
            }
        }
    }
}
