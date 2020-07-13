package org.labkey.mgap.pipeline;

import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.run.DISCVRSeqRunner;

import java.io.File;
import java.util.List;

public class MultiSourceAnnotatorRunner extends DISCVRSeqRunner
{
    public MultiSourceAnnotatorRunner(Logger log)
    {
        super(log);
    }

    public File execute(File inputVcf, File cassandraVcf, File clinvarVcf, File liftoverRejects, File outputVcf, @Nullable List<String> options)  throws PipelineJobException
    {
        List<String> args = getBaseArgs("MultiSourceAnnotator");

        args.add("-V");
        args.add(inputVcf.getPath());

        args.add("-cv");
        args.add(clinvarVcf.getPath());

        if (cassandraVcf != null)
        {
            args.add("-c");
            args.add(cassandraVcf.getPath());
        }

        args.add("-lr");
        args.add(liftoverRejects.getPath());

        args.add("-O");
        args.add(outputVcf.getPath());

        if (options != null)
        {
            args.addAll(options);
        }
        execute(args);

        if (!outputVcf.exists())
        {
            throw new PipelineJobException("Unable to find file: " + outputVcf.getPath());
        }

        return outputVcf;
    }
}
