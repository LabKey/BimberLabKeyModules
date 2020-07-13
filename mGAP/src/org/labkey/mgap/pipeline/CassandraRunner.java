package org.labkey.mgap.pipeline;

import org.apache.logging.log4j.Logger;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.PipelineJobService;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.sequenceanalysis.run.AbstractCommandWrapper;
import org.labkey.api.sequenceanalysis.run.SimpleScriptWrapper;
import org.labkey.api.util.FileUtil;
import org.labkey.api.writer.PrintWriters;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CassandraRunner extends AbstractCommandWrapper
{
    private Integer _maxRamOverride = null;

    public CassandraRunner(Logger log)
    {
        super(log);
    }

    public void setMaxRamOverride(Integer maxRamOverride)
    {
        _maxRamOverride = maxRamOverride;
    }

    public File execute(File inputVcfUnzipped, File outputVcfZipped, List<String> extraArgs) throws PipelineJobException
    {
        if (inputVcfUnzipped.getPath().endsWith(".gz"))
        {
            throw new PipelineJobException("Expected input VCF to be unzipped:" + inputVcfUnzipped.getPath());
        }

        if (!outputVcfZipped.getPath().endsWith(".gz"))
        {
            throw new PipelineJobException("Expected output VCF to be gzipped:" + outputVcfZipped.getPath());
        }

        List<String> args = new ArrayList<>();
        args.add(SequencePipelineService.get().getJavaFilepath());
        args.addAll(SequencePipelineService.get().getJavaOpts(_maxRamOverride));
        args.add("-jar");
        args.add(getJar().getPath());
        args.add("-t");
        args.add("Annotate");

        args.add("--annovarPath");
        args.add(getAnnovarPath().getPath());

        args.add("--annovarDB");
        args.add(getAnnovarPath().getPath() + "/humandb");

        args.add("--annotationSources");
        args.add(getCassandraData().getPath());

        if (extraArgs != null && !extraArgs.isEmpty())
        {
            args.addAll(extraArgs);
        }

        args.add("-i");
        args.add(inputVcfUnzipped.getPath());

        File outputVcfUnzipped = new File(outputVcfZipped.getParentFile(), FileUtil.getBaseName(outputVcfZipped));
        args.add("-o");
        args.add(outputVcfUnzipped.getPath());

        args.add("--maxRecords");
        args.add("250000");

        execute(args);

        if (!outputVcfUnzipped.exists())
        {
            throw new PipelineJobException("Unable to find file: " + outputVcfUnzipped.getPath());
        }

        correctHeaderAndBGzip(outputVcfUnzipped, outputVcfZipped);
        if (!outputVcfZipped.exists())
        {
            throw new PipelineJobException("Unable to find file: " + outputVcfZipped.getPath());
        }

        outputVcfUnzipped.delete();

        return outputVcfUnzipped;
    }

    private void correctHeaderAndBGzip(File inputUnzip, File outputGzip) throws PipelineJobException
    {
        try
        {
            File bashTmp = new File(outputGzip.getParentFile(), "cassandraCombine.sh");
            try (PrintWriter writer = PrintWriters.getPrintWriter(bashTmp))
            {
                writer.write("#!/bin/bash\n");
                writer.write("set -x\n");
                writer.write("set -e\n");
                writer.write("{\n");
                writer.write("cat " + inputUnzip.getPath() + " | head -n 50000 | grep -e '^#' | grep -v '^##META' | sed 's/Number=0,Type=String/Number=1,Type=String/';\n");
                writer.write("cat " + inputUnzip.getPath() + " | grep -v '^#' |  sort -V -k1,1 -k2,2n;\n");

                Integer threads = SequencePipelineService.get().getMaxThreads(getLogger());
                if (threads != null)
                {
                    threads = Math.max(1, threads - 1);
                }
                writer.write("} | bgzip -f" + (threads == null ? "" : " --threads " + threads) + " > " + outputGzip + "\n");
            }

            SimpleScriptWrapper wrapper = new SimpleScriptWrapper(getLogger());
            wrapper.execute(Arrays.asList("/bin/bash", bashTmp.getPath()));

            SequenceAnalysisService.get().ensureVcfIndex(outputGzip, getLogger());

            bashTmp.delete();
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }

    private File getCassandraData()
    {
        return new File(getJar().getParentFile(), "cassandraData");
    }

    private File getAnnovarPath()
    {
        return new File(getJar().getParentFile(), "annovar");
    }

    private File getJar()
    {
        String path = PipelineJobService.get().getConfigProperties().getSoftwarePackagePath("CASSANDRAPATH");
        if (path != null)
        {
            return new File(path);
        }

        path = PipelineJobService.get().getConfigProperties().getSoftwarePackagePath(SequencePipelineService.SEQUENCE_TOOLS_PARAM);
        if (path == null)
        {
            path = PipelineJobService.get().getAppProperties().getToolsDirectory();
        }

        return path == null ? new File("Cassandra.jar") : new File(path, "Cassandra.jar");
    }
}
