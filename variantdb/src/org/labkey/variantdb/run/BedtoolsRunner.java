package org.labkey.variantdb.run;

import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.sequenceanalysis.run.AbstractCommandWrapper;

import java.io.File;

/**
 * User: bimber
 * Date: 12/15/12
 * Time: 9:11 PM
 */
public class BedtoolsRunner extends AbstractCommandWrapper
{
    public BedtoolsRunner(@Nullable Logger logger)
    {
        super(logger);
    }

    public File getExe()
    {
        return SequencePipelineService.get().getExeForPackage("BEDTOOLSPATH", "bedtools");
    }
}
