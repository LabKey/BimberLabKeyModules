package org.labkey.primeseq.pipeline;

import org.labkey.api.data.Container;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.sequenceanalysis.pipeline.JobResourceSettings;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.primeseq.PrimeseqModule;

import java.util.Arrays;
import java.util.List;

/**
 * Created by bimber on 9/30/2016.
 */
public class ExacloudResourceSettings implements JobResourceSettings
{
    @Override
    public List<ToolParameterDescriptor> getParams()
    {
        return Arrays.asList(
                ToolParameterDescriptor.create("cpus", "CPUs", "The number of CPUs requested for this job", "ldk-integerfield", null, null),
                ToolParameterDescriptor.create("ram", "RAM (GB)", "The RAM requested for this job", "ldk-integerfield", null, null),
                ToolParameterDescriptor.create("weekLongJob", "Expect To Run More Than 24H", "Check this if you expect the job to run more than 24H.  This will add the long_jobs flag to the submit script", "checkbox", null, null),
                ToolParameterDescriptor.create("veryLongJob", "Expect To Run More Than 10 Days", "Check this if you expect the job to run more than 10 Days.  This will add the very_long_jobs flag to the submit script", "checkbox", null, null),
                ToolParameterDescriptor.create("time", "Requested Time (days/hours)", "If provided, this is passed to the --time argument.  This cannot be higher than the limit for your requested partition.  Examples are: '2124', '1-0' (1 day, 0 hours), '10-0' (10 days), and '0-72' (72 hours).  If left blank, this will be automatically assigned.", "textfield", null, null),
                ToolParameterDescriptor.create("javaProcessXmx", "Java Process Xmx (GB)", "The value to be used as -Xmx for the LabKey remote java process.  Unless you have a good reason, do not change this", "ldk-integerfield", null, null)
        );
    }

    @Override
    public boolean isAvailable(Container c)
    {
        return c.getActiveModules().contains(ModuleLoader.getInstance().getModule(PrimeseqModule.class));
    }
}
