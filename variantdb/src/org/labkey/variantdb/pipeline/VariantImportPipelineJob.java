package org.labkey.variantdb.pipeline;

import org.labkey.api.data.Container;
import org.labkey.api.pipeline.PipeRoot;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobService;
import org.labkey.api.pipeline.TaskId;
import org.labkey.api.pipeline.TaskPipeline;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.util.FileUtil;
import org.labkey.api.view.ActionURL;
import org.labkey.api.view.ViewBackgroundInfo;

import java.io.File;
import java.util.List;

/**
 * Created by bimber on 1/5/2015.
 */
public class VariantImportPipelineJob extends PipelineJob
{
    private final List<SequenceOutputFile> _outputFiles;
    private final List<Integer> _liftOverTargetGenomes;

    public VariantImportPipelineJob(Container c, User user, ActionURL url, PipeRoot pipeRoot, List<SequenceOutputFile> outputFiles, List<Integer> liftOverTargetGenomes)
    {
        super(DbSnpImportPipelineProvider.NAME, new ViewBackgroundInfo(c, user, url), pipeRoot);
        _outputFiles = outputFiles;
        _liftOverTargetGenomes = liftOverTargetGenomes;

        File outputDir = new File(pipeRoot.getRootPath(), VariantImportPipelineProvider.NAME);
        if (!outputDir.exists())
        {
            outputDir.mkdirs();
        }
        setLogFile(new File(outputDir, FileUtil.makeFileNameWithTimestamp("variantImport", "log")));
    }

    @Override
    public String getDescription()
    {
        return "Import variants";
    }

    @Override
    public ActionURL getStatusHref()
    {
        return null;
    }

    @Override
    public TaskPipeline getTaskPipeline()
    {
        return PipelineJobService.get().getTaskPipeline(new TaskId(VariantImportPipelineJob.class));
    }

    public List<SequenceOutputFile> getOutputFiles()
    {
        return _outputFiles;
    }

    public List<Integer> getLiftOverTargetGenomes()
    {
        return _liftOverTargetGenomes;
    }
}
