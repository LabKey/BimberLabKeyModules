package org.labkey.variantdb.run;

import org.apache.logging.log4j.Logger;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.run.AbstractDiscvrSeqWrapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by bimber on 8/8/2014.
 */
public class MergeVcfsAndGenotypesWrapper extends AbstractDiscvrSeqWrapper
{
    public MergeVcfsAndGenotypesWrapper(Logger log)
    {
        super(log);
    }

    public void execute(File referenceFasta, List<File> inputVcfs, File outputVcf) throws PipelineJobException
    {
        getLogger().info("Running MergeVcfsAndGenotypes");

        ensureDictionary(referenceFasta);

        List<String> args = new ArrayList<>();
        args.addAll(getBaseArgs());
        args.add("MergeVcfsAndGenotypes");
        args.add("-R");
        args.add(referenceFasta.getPath());

        for (File f : inputVcfs)
        {
            args.add("-V");
            args.add(f.getPath());
        }

        args.add("-O");
        args.add(outputVcf.getPath());

        args.add("--ignore-variants-starting-outside-interval");

        execute(args);
        if (!outputVcf.exists())
        {
            throw new PipelineJobException("Expected output not found: " + outputVcf.getPath());
        }
    }
}
