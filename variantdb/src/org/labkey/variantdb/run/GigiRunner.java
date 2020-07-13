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
public class GigiRunner extends AbstractCommandWrapper
{
    public GigiRunner(Logger log)
    {
        super(log);
    }

    public void execute(File paramFile, File outDir) throws PipelineJobException
    {
        List<String> args = new ArrayList<>();
        args.add(getExe());
        args.add(paramFile.getPath());
        args.add("-outD=" + outDir.getPath());
        args.add("-long");
        args.add("-verbose");

        //TODO: consider whitelisting specific codes?  134 seems permissible?  currently we'll just fail one step downstream
        setWarnNonZeroExits(true);
        setThrowNonZeroExits(false);

        execute(args);
    }

    private String getExe()
    {
        return SequencePipelineService.get().getExeForPackage("GIGIPATH", "GIGI").getPath();
    }
}
