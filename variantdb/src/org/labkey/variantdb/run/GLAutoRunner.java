package org.labkey.variantdb.run;

import org.apache.logging.log4j.Logger;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.sequenceanalysis.run.AbstractCommandWrapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by bimber on 2/25/2015.
 */
public class GLAutoRunner extends AbstractCommandWrapper
{
    public GLAutoRunner(Logger log)
    {
        super(log);
    }

    public void execute(File paramFile) throws PipelineJobException
    {
        List<String> args = new ArrayList<>();
        args.add(getExe());
        args.add(paramFile.getPath());

        execute(args);
    }

    private String getExe()
    {
        File folder = SequencePipelineService.get().getExeForPackage("MORGANPATH", "MORGAN");

        return new File(folder, "Autozyg/gl_auto").getPath();
    }
}
