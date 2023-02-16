package org.labkey.variantdb.pipeline;

import org.labkey.api.data.Container;
import org.labkey.api.pipeline.PipeRoot;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobService;
import org.labkey.api.pipeline.TaskId;
import org.labkey.api.pipeline.TaskPipeline;
import org.labkey.api.security.User;
import org.labkey.api.util.FileUtil;
import org.labkey.api.view.ActionURL;
import org.labkey.api.view.ViewBackgroundInfo;

import java.io.File;

/**
 * Created by bimber on 1/5/2015.
 */
public class DbSnpImportPipelineJob extends PipelineJob
{
    private final String _remoteDirName;
    private final int _genomeId;

    public DbSnpImportPipelineJob(Container c, User user, ActionURL url, PipeRoot pipeRoot, String remoteDirName, int genomeId)
    {
        super(DbSnpImportPipelineProvider.NAME, new ViewBackgroundInfo(c, user, url), pipeRoot);
        _remoteDirName = remoteDirName;
        _genomeId = genomeId;

        File outputDir = new File(pipeRoot.getRootPath(), DbSnpImportPipelineProvider.NAME);
        if (!outputDir.exists())
        {
            outputDir.mkdirs();
        }
        setLogFile(new File(outputDir, FileUtil.makeFileNameWithTimestamp("dbSnpImport", "log")));
    }

    @Override
    public String getDescription()
    {
        return "Load data from NCBI/dbSNP";
    }

    @Override
    public ActionURL getStatusHref()
    {
//        if (_blastJob != null && _blastJob.getDatabaseId() != null)
//        {
//            ActionURL ret = QueryService.get().urlFor(getUser(), getContainer(), QueryAction.executeQuery, BLASTSchema.NAME, BLASTSchema.TABLE_DATABASES);
//            ret.addParameter("query.objectid~eq", _blastJob.getDatabaseId());
//
//            return ret;
//        }
        return null;
    }

    @Override
    public TaskPipeline getTaskPipeline()
    {
        return PipelineJobService.get().getTaskPipeline(new TaskId(DbSnpImportPipelineJob.class));
    }

    public String getRemoteDirName()
    {
        return _remoteDirName;
    }

    public int getGenomeId()
    {
        return _genomeId;
    }
}
