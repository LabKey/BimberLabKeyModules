package org.labkey.primeseq.pipeline;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.labkey.api.cluster.ClusterResourceAllocator;
import org.labkey.api.data.ConvertHelper;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.RemoteExecutionEngine;
import org.labkey.api.pipeline.TaskFactory;
import org.labkey.api.pipeline.TaskId;
import org.labkey.api.reader.Readers;
import org.labkey.api.sequenceanalysis.pipeline.HasJobParams;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStep;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.util.FileUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * Created by bbimber
 *
 */
public class SequenceJobResourceAllocator implements ClusterResourceAllocator
{
    public static class Factory implements ClusterResourceAllocator.Factory
    {
        @Override
        public ClusterResourceAllocator getAllocator()
        {
            return new SequenceJobResourceAllocator();
        }

        @Override
        public Integer getPriority(TaskId taskId)
        {
            return (taskId.getNamespaceClass() != null && (
                    taskId.getNamespaceClass().getName().startsWith("org.labkey.sequenceanalysis.pipeline")
            )) ? 50 : null;
        }
    }

    private boolean isSequenceNormalizationTask(PipelineJob job)
    {
        return (job.getActiveTaskId() != null && job.getActiveTaskId().getNamespaceClass().getName().endsWith("SequenceNormalizationTask"));
    }

    private boolean isSequenceAlignmentTask(PipelineJob job)
    {
        return (job.getActiveTaskId() != null && job.getActiveTaskId().getNamespaceClass().getName().endsWith("SequenceAlignmentTask"));
    }

    private boolean isCacheAlignerIndexesTask(PipelineJob job)
    {
        return (job.getActiveTaskId() != null && job.getActiveTaskId().getNamespaceClass().getName().endsWith("CacheAlignerIndexesTask"));
    }

    private boolean isSequenceSequenceOutputHandlerTask(PipelineJob job)
    {
        return (job.getActiveTaskId() != null && job.getActiveTaskId().getNamespaceClass().getName().endsWith("SequenceOutputHandlerRemoteTask"));
    }

    private Long _totalFileSize = null;
    private static final Long UNABLE_TO_DETERMINE = -1L;

    @Override
    public Integer getMaxRequestCpus(PipelineJob job)
    {
        if (job instanceof HasJobParams)
        {
            Map<String, String> params = ((HasJobParams)job).getJobParams();
            if (params.get("resourceSettings.resourceSettings.cpus") != null)
            {
                Integer cpus = ConvertHelper.convert(params.get("resourceSettings.resourceSettings.cpus"), Integer.class);
                job.getLogger().debug("using CPUs supplied by job: " + cpus);
                return cpus;
            }
        }

        if (isSequenceNormalizationTask(job))
        {
            job.getLogger().debug("setting max CPUs to 8");
            return 8;
        }

        Long totalFileSize = getFileSize(job);
        if (UNABLE_TO_DETERMINE.equals(totalFileSize))
        {
            return null;
        }

        if (isSequenceAlignmentTask(job))
        {
            //10gb
            if (totalFileSize < 10e9)
            {
                job.getLogger().debug("file size less than 10gb, lowering CPUs to 8");

                return 8;
            }
            else if (totalFileSize < 20e9)
            {
                job.getLogger().debug("file size less than 20gb, lowering CPUs to 16");

                return 16;
            }

            job.getLogger().debug("file size greater than 20gb, using 24 CPUs");

            return 24;
        }

        return null;
    }

    @Override
    public Integer getMaxRequestMemory(PipelineJob job)
    {
        Integer ret = null;
        if (job instanceof HasJobParams)
        {
            Map<String, String> params = ((HasJobParams) job).getJobParams();
            if (params.get("resourceSettings.resourceSettings.ram") != null)
            {
                Integer ram = ConvertHelper.convert(params.get("resourceSettings.resourceSettings.ram"), Integer.class);
                job.getLogger().debug("using RAM supplied by job: " + ram);
                ret = ram;
            }
        }

        if (isSequenceNormalizationTask(job))
        {
            job.getLogger().debug("setting memory to 48");
            return 48;
        }

        if (isCacheAlignerIndexesTask(job))
        {
            job.getLogger().debug("setting memory to 48");
            return 48;
        }

        Long totalFileSize = getFileSize(job);
        if (UNABLE_TO_DETERMINE.equals(totalFileSize))
        {
            return null;
        }

        boolean hasHaplotypeCaller = false;
        boolean hasStar = false;
        boolean hasBismark = false;
        boolean hasBowtie2 = false;

        if (isSequenceSequenceOutputHandlerTask(job))
        {
            File jobXml = new File(job.getLogFile().getParentFile(), FileUtil.getBaseName(job.getLogFile()) + ".job.json.txt");
            if (jobXml.exists())
            {
                try (BufferedReader reader = Readers.getReader(jobXml))
                {
                    String line;
                    while ((line = reader.readLine()) != null)
                    {
                        if (line.contains("HaplotypeCallerHandler"))
                        {
                            hasHaplotypeCaller = true;
                            break;
                        }
                    }
                }
                catch (IOException e)
                {
                    job.getLogger().error(e.getMessage(), e);
                }
            }
        }

        if (isSequenceAlignmentTask(job))
        {
            if (ret == null)
            {
                if (totalFileSize <= 30e9)
                {
                    job.getLogger().debug("file size less than 30gb, setting memory to 24");

                    ret = 24;
                }
                else
                {
                    job.getLogger().debug("file size greater than 30gb, setting memory to 48");

                    ret = 48;
                }
            }

            Map<String, String> params = job.getParameters();
            if (params != null)
            {
                if (params.containsKey(PipelineStep.CorePipelineStepTypes.analysis.name()) && params.get(PipelineStep.CorePipelineStepTypes.analysis.name()).contains("HaplotypeCallerAnalysis"))
                {
                    hasHaplotypeCaller = true;
                }

                if (params.containsKey(PipelineStep.CorePipelineStepTypes.alignment.name()) && params.get(PipelineStep.CorePipelineStepTypes.alignment.name()).contains("STAR"))
                {
                    hasStar = true;
                }

                if (params.containsKey(PipelineStep.CorePipelineStepTypes.alignment.name()) && params.get(PipelineStep.CorePipelineStepTypes.alignment.name()).contains("Bismark"))
                {
                    hasBismark = true;
                }

                if (params.containsKey(PipelineStep.CorePipelineStepTypes.alignment.name()) && params.get(PipelineStep.CorePipelineStepTypes.alignment.name()).contains("Bowtie2"))
                {
                    hasBowtie2 = true;
                }
            }
        }

        if (hasHaplotypeCaller)
        {
            Integer orig = ret;
            ret = ret == null ? 48 : Math.max(ret, 48);
            if (!ret.equals(orig))
            {
                job.getLogger().debug("adjusting RAM for HaplotypeCaller to: " + ret);
            }
        }

        if (hasStar)
        {
            Integer orig = ret;
            ret = ret == null ? 48 : Math.max(ret, 48);
            if (!ret.equals(orig))
            {
                job.getLogger().debug("adjusting RAM for STAR to: " + ret);
            }
        }

        if (hasBismark)
        {
            Integer orig = ret;
            ret = ret == null ? 48 : Math.max(ret, 48);
            if (!ret.equals(orig))
            {
                job.getLogger().debug("adjusting RAM for Bismark to: " + ret);
            }
        }

        if (hasBowtie2)
        {
            Integer orig = ret;
            ret = ret == null ? 48 : Math.max(ret, 48);
            if (!ret.equals(orig))
            {
                job.getLogger().debug("adjusting RAM for bowtie2 to: " + ret);
            }
        }

        return ret;
    }

    @Override
    public void addExtraSubmitScriptLines(PipelineJob job, RemoteExecutionEngine engine, List<String> lines)
    {
        if (job instanceof HasJobParams)
        {
            possiblyAddQOS(job, engine, lines);
            possiblyAddHighIO(job, engine, lines);
            possiblyAddDisk(job, engine, lines);
            possiblyAddSSD(job, engine, lines);
            possiblyAddGpus(job, engine, lines);
            possiblyAddExclusive(job, engine, lines);
            possiblyAddInfiniband(job, engine, lines);
            possiblyAddCOVID(job, lines);
        }
    }

    private void possiblyAddCOVID(PipelineJob job, List<String> lines)
    {
        Map<String, String> params = ((HasJobParams)job).getJobParams();
        String val = StringUtils.trimToNull(params.get("resourceSettings.resourceSettings.covidRelated"));
        if (val == null)
        {
            return;
        }

        boolean parsed = Boolean.parseBoolean(val);
        if (parsed)
        {
            job.getLogger().info("Flagging as COVID-related");
            String line = "#SBATCH --comment=COVID";
            if (!lines.contains(line))
            {
                lines.add(line);
            }
        }
    }

    @Override
    public Map<String, Object> getEnvironmentVars(PipelineJob job, RemoteExecutionEngine engine)
    {
        Map<String, Object> ret = new HashMap<>();

        if (job instanceof HasJobParams && getUseLustreValue((HasJobParams)job))
        {
            job.getLogger().info("Requiring using original lustre as working space");
            ret.put("USE_LUSTRE", "1");
        }

        return ret;
    }

    private void removeQueueLines(List<String> lines)
    {
        lines.removeIf(line -> line.contains("#SBATCH --partition="));
        lines.removeIf(line -> line.contains("#SBATCH --qos="));
        lines.removeIf(line -> line.contains("#SBATCH --time="));
    }

    private String getTime(PipelineJob job)
    {
        Map<String, String> params = ((HasJobParams)job).getJobParams();
        if (params.get("resourceSettings.resourceSettings.time") != null)
        {
            return StringUtils.trimToNull(params.get("resourceSettings.resourceSettings.time"));
        }

        return null;
    }

    private void possiblyAddHighIO(PipelineJob job, RemoteExecutionEngine engine, List<String> lines)
    {
        Map<String, String> params = ((HasJobParams)job).getJobParams();
        String val = StringUtils.trimToNull(params.get("resourceSettings.resourceSettings.highIO"));
        if (val == null)
        {
            return;
        }

        boolean highIO = Boolean.parseBoolean(val);
        if (highIO)
        {
            job.getLogger().info("Adding QOS HighIO");
            String line = "#SBATCH --qos=highio";
            if (!lines.contains(line))
            {
                lines.add(line);
            }
        }
    }

    private void possiblyAddDisk(PipelineJob job, RemoteExecutionEngine engine, List<String> lines)
    {
        Map<String, String> params = ((HasJobParams) job).getJobParams();
        String val = StringUtils.trimToNull(params.get("resourceSettings.resourceSettings.localDisk"));
        if (val == null)
        {
            return;
        }

        lines.removeIf(line -> line.contains("#SBATCH --gres=disk:"));

        job.getLogger().debug("Adding local disk (mb): " + val);
        lines.add("#SBATCH --gres=disk:" + val);
    }

    private boolean needsGPUs(PipelineJob job)
    {
        Map<String, String> params = ((HasJobParams) job).getJobParams();
        return hasCellBender(job) || StringUtils.trimToNull(params.get("resourceSettings.resourceSettings.gpus")) != null;
    }

    private boolean hasCellBender(PipelineJob job)
    {
        if (!isSequenceSequenceOutputHandlerTask(job))
        {
            return false;
        }

        File jobXml = new File(job.getLogFile().getParentFile(), FileUtil.getBaseName(job.getLogFile()) + ".job.json.txt");
        if (jobXml.exists())
        {
            try (BufferedReader reader = Readers.getReader(jobXml))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    if (line.contains("CellBenderLoupeHandler"))
                    {
                        job.getLogger().debug("Forcing the GPU partition for CellBenderLoupeHandler");
                        return true;
                    }
                }
            }
            catch (IOException e)
            {
                job.getLogger().error(e.getMessage(), e);
            }
        }

        return false;
    }

    private void possiblyAddGpus(PipelineJob job, RemoteExecutionEngine engine, List<String> lines)
    {
        Map<String, String> params = ((HasJobParams) job).getJobParams();
        String val = StringUtils.trimToNull(params.get("resourceSettings.resourceSettings.gpus"));
        if (val == null && hasCellBender(job))
        {
            job.getLogger().debug("Setting GPUs to one since cellbender is used");
            val = "1";
        }

        if (val == null)
        {
            return;
        }

        lines.removeIf(line -> line.contains("#SBATCH --gres=gpu:"));

        job.getLogger().debug("Adding gpus: " + val);
        lines.add("#SBATCH --gres=gpu:" + val);
    }

    private void possiblyAddExclusive(PipelineJob job, RemoteExecutionEngine engine, List<String> lines)
    {
        Map<String, String> params = ((HasJobParams)job).getJobParams();
        String val = StringUtils.trimToNull(params.get("resourceSettings.resourceSettings.useExclusive"));
        if (val == null)
        {
            return;
        }

        boolean parsed = Boolean.parseBoolean(val);
        if (parsed)
        {
            job.getLogger().info("Adding --exclusive flag");
            String line = "#SBATCH --exclusive";
            if (!lines.contains(line))
            {
                lines.add(line);
            }
        }
    }

    private void possiblyAddSSD(PipelineJob job, RemoteExecutionEngine engine, List<String> lines)
    {
        Map<String, String> params = ((HasJobParams)job).getJobParams();
        String val = StringUtils.trimToNull(params.get("resourceSettings.resourceSettings.localSSD"));
        if (val == null)
        {
            return;
        }

        boolean parsed = Boolean.parseBoolean(val);
        if (parsed)
        {
            job.getLogger().info("Requiring local SSD scratch space");
            String line = "#SBATCH -C ssdscratch";
            if (!lines.contains(line))
            {
                lines.add(line);
            }
        }
    }

    private void possiblyAddInfiniband(PipelineJob job, RemoteExecutionEngine engine, List<String> lines)
    {
        Map<String, String> params = ((HasJobParams)job).getJobParams();
        String val = StringUtils.trimToNull(params.get("resourceSettings.resourceSettings.requireInfiniband"));
        if (val == null)
        {
            return;
        }

        boolean parsed = Boolean.parseBoolean(val);
        if (parsed)
        {
            job.getLogger().info("Requiring node with infiniband");
            String line = "#SBATCH -C IB";
            if (!lines.contains(line))
            {
                lines.add(line);
            }
        }
    }

    private boolean getUseLustreValue(HasJobParams job)
    {
        Map<String, String> params = (job).getJobParams();
        String val = StringUtils.trimToNull(params.get("resourceSettings.resourceSettings.useLustre"));
        if (val == null)
        {
            return false;
        }

        return Boolean.parseBoolean(val);
    }

    private void possiblyAddQOS(PipelineJob job, RemoteExecutionEngine engine, List<String> lines)
    {
        //first remove existing
        removeQueueLines(lines);

        String time = getTime(job);
        if (time != null)
        {
            job.getLogger().debug("adding user-supplied time to job: " + time);
        }

        Map<String, String> params = ((HasJobParams)job).getJobParams();
        String qos = null;
        if (params.get("resourceSettings.resourceSettings.qos") != null)
        {
            //exacloud: 36 hours
            //long_jobs: 10 days (max 60 jobs currently)
            //very_long_jobs: 30 days (suspends when node is busy)
            qos = StringUtils.trimToNull(ConvertHelper.convert(params.get("resourceSettings.resourceSettings.qos"), String.class));
        }

        if (qos != null)
        {
            if (engine.getType().equals("SlurmEngine"))
            {
                job.getLogger().debug("qos as supplied by job: " + qos);

                //Exempt specific task types:
                TaskFactory factory = job.getActiveTaskFactory();
                String activeTask = factory == null ? null : factory.getId().getNamespaceClass().getSimpleName();
                job.getLogger().debug("Active task simplename: " + activeTask);
                if (Arrays.asList("PrepareAlignerIndexesTask", "CacheAlignerIndexesTask", "AlignmentInitTask", "VariantProcessingScatterRemotePrepareTask").contains(activeTask))
                {
                    job.getLogger().info("Using default queue for task: " + activeTask);
                    qos = "Default";
                    time = null;
                }

                String qosName;
                switch (qos)
                {
                    case "Default":
                        qosName = null;
                        time = time == null ? "0-36" : time;
                        break;
                    case "LongJobs":
                        qosName = "long_jobs";
                        time = time == null ? "10-0" : time;
                        break;
                    case "VeryLongJobs":
                        qosName = "very_long_jobs";
                        time = time == null ? "30-0" : time;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown QOS: " + qos);
                }

                //then add
                lines.add("#SBATCH --partition=" + getPartition(job));
                if (qosName != null)
                {
                    lines.add("#SBATCH --qos=" + qosName);
                }
                lines.add("#SBATCH --time=" + time);
            }
            else
            {
                job.getLogger().warn("QOS not supported with this engine type: " + engine.getType());
            }
        }
        else
        {
            //otherwise add defaults
            lines.add("#SBATCH --partition=" + getPartition(job));
            lines.add("#SBATCH --time=" + (time == null ? "0-36" : time));
        }
    }

    private String getPartition(PipelineJob job)
    {
        return needsGPUs(job) ? "gpu" : "exacloud";
    }

    private Long getFileSize(PipelineJob job)
    {
        if (_totalFileSize != null)
        {
            return _totalFileSize;
        }

        List<File> files = SequencePipelineService.get().getSequenceJobInputFiles(job);
        if (files != null && !files.isEmpty())
        {
            long total = 0;
            for (File f : files)
            {
                if (f.exists())
                {
                    total += f.length();
                }
            }

            job.getLogger().info("total input files: " + files.size());
            job.getLogger().info("total size of input files: " + FileUtils.byteCountToDisplaySize(total));

            _totalFileSize = total;
        }
        else
        {
            _totalFileSize = UNABLE_TO_DETERMINE;
        }

        return _totalFileSize;
    }

    @Override
    public void processJavaOpts(PipelineJob job, RemoteExecutionEngine engine, @NotNull List<String> existingJavaOpts)
    {
        if (job instanceof HasJobParams)
        {
            Map<String, String> params = ((HasJobParams) job).getJobParams();
            if (params.get("resourceSettings.resourceSettings.javaProcessXmx") != null && !StringUtils.isEmpty(params.get("resourceSettings.resourceSettings.javaProcessXmx")))
            {
                Integer xmx = ConvertHelper.convert(params.get("resourceSettings.resourceSettings.javaProcessXmx"), Integer.class);
                if (xmx != null)
                {
                    job.getLogger().debug("using java process -xmx supplied by job: " + xmx);
                    existingJavaOpts.removeIf(x -> x.startsWith("-Xmx"));

                    existingJavaOpts.add("-Xmx" + xmx + "g");
                }
            }
        }
    }
}