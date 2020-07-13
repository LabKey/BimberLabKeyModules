package org.labkey.mgap.pipeline;

import org.apache.logging.log4j.Logger;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.run.DISCVRSeqRunner;

import java.io.File;
import java.util.List;

public class BackportLiftedVcfRunner extends DISCVRSeqRunner
{
    public BackportLiftedVcfRunner(Logger log)
    {
        super(log);
    }

    public File execute(File inputVcf, File targetGenome, File currentGenome, File outputVcf) throws PipelineJobException
    {
        List<String> args = getBaseArgs("BackportLiftedVcf");

        args.add("-V");
        args.add(inputVcf.getPath());

        args.add("-R");
        args.add(currentGenome.getPath());

        args.add("--targetFasta");
        args.add(targetGenome.getPath());

        args.add("-O");
        args.add(outputVcf.getPath());

        execute(args);

        if (!outputVcf.exists())
        {
            throw new PipelineJobException("Unable to find file: " + outputVcf.getPath());
        }

        return outputVcf;
    }
}
