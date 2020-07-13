package org.labkey.tcrdb.pipeline;

import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.reader.Readers;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.sequenceanalysis.run.AbstractCommandWrapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by bimber on 6/16/2016.
 */
public class MiXCRWrapper extends AbstractCommandWrapper
{
    private File _libraryPath = null;

    public MiXCRWrapper(Logger log)
    {
        super(log);
    }

    public File doAnalyze(File fq1, @Nullable File fq2, String outputPrefix, String species, List<String> generalParams, List<String> alignParams, List<String> assembleParams, List<String> extendParams) throws PipelineJobException
    {
        List<String> args = new ArrayList<>();
        args.addAll(getBaseArgs());

        args.add("analyze");
        args.add("shotgun");

        //args.add("--overwrite-if-required");
        args.add("-f");
        args.add("--starting-material");
        args.add("rna");

        args.add("--report");
        File logFile = new File(getOutputDir(fq1), outputPrefix + ".log.txt");
        args.add(logFile.getPath());

        args.add("--contig-assembly");

        args.add("-s");
        args.add(species);

        args.addAll(generalParams);

        alignParams.add("-OallowPartialAlignments=true");
        alignParams.add("-OsaveOriginalReads=true");
        alignParams.forEach(x -> addStepArg("--align", x, args));

        //--assemblePartial

        //--extend
        extendParams.forEach(x -> addStepArg("--extend", x, args));

        assembleParams.add("-OaddReadsCountOnClustering=true");
        assembleParams.add("-ObadQualityThreshold=10");
        assembleParams.forEach(x -> addStepArg("--assemble", x, args));

        //--assembleContigs


        //--export


        args.add(fq1.getPath());
        if (fq2 != null)
        {
            args.add(fq2.getPath());
        }

        File alignOut = new File(getOutputDir(fq1), outputPrefix);
        args.add(alignOut.getPath());

        execute(args);

        writeLogToLog(logFile);

        for (String token : Arrays.asList("ALL", "IGH", "IGK", "IGL", "TRA", "TRB", "TRD", "TRG"))
        {
            File text = new File(getOutputDir(fq1), outputPrefix + ".clonotypes." + token + ".txt");
            if (text.exists())
            {
                text.delete();
            }
            else
            {
                getLogger().warn("unable to find expected output: " + text.getPath());
            }
        }

        return getAssembleContigsOut(outputPrefix, getOutputDir(fq1));
    }

    public static String getExtendedName(String outputPrefix)
    {
        return outputPrefix + ".extended.vdjca";
    }

    public File getAssemblePartialFile(String basename, File outdir, String step)
    {
        return new File(outdir, basename + ".rescued_" + step + ".vdjca");
    }

    public File getAlignFile(String basename, File outdir)
    {
        return new File(outdir, basename + ".vdjca");
    }

    public static String getFinalVDJFileName(String basename)
    {
        return getExtendedName(basename);
    }

    public File getExtendedFile(String basename, File outdir)
    {
        return new File(outdir, getExtendedName(basename));
    }

    public File getAssembleOut(String basename, File outdir)
    {
        return new File(outdir, getAssembledFileName(basename));
    }

    public File getAssembleContigsOut(String basename, File outdir)
    {
        return new File(outdir, basename + ".contigs.clns");
    }

    public static String getAssembledFileName(String outputPrefix)
    {
        return outputPrefix + ".clna";
    }

    private void addStepArg(String prefix, String val, List<String> args)
    {
        args.add(prefix);
        args.add("\"" + val + "\"");
    }

    private void writeLogToLog(File log) throws PipelineJobException
    {
        if (!log.exists())
        {
            getLogger().warn("expected log file does not exist: " + log.getPath());
            return;
        }

        try (BufferedReader reader = Readers.getReader(log))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                getLogger().info(line);
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        log.delete();
    }

    public void doExportClones(File clones, File output, @Nullable String locus, List<String> extraParams) throws PipelineJobException
    {
        List<String> args = new ArrayList<>();
        args.addAll(getBaseArgs());
        args.add("exportClones");

        if (locus != null)
        {
            args.add("-c");
            args.add(locus);
        }

        if (extraParams != null)
        {
            args.addAll(extraParams);
        }

        args.add("-cloneId");
        args.add("-vHit");
        args.add("-dHit");
        args.add("-jHit");
        args.add("-cHit");
        args.add("-aaFeature");
        args.add("CDR3");
        args.add("-lengthOf");
        args.add("CDR3");
        args.add("-count");
        args.add("-fraction");
        args.add("-targets");
        args.add("-vHits");
        args.add("-dHits");
        args.add("-jHits");
        args.add("-cHits");
        args.add("-vGene");
        args.add("-vGenes");
        args.add("-dGene");
        args.add("-dGenes");
        args.add("-jGene");
        args.add("-jGenes");
        args.add("-cGene");
        args.add("-cGenes");
        args.add("-vBestIdentityPercent");
        args.add("-dBestIdentityPercent");
        args.add("-jBestIdentityPercent");
        args.add("-nFeature");
        args.add("CDR3");
        args.add("-qFeature");
        args.add("CDR3");
        args.add("-nFeatureImputed");
        args.add("VDJRegion");
        args.add("-f");
        args.add(clones.getPath());
        args.add(output.getPath());

        execute(args);
    }

    public void doExportReadsForClones(File cloneAln, File outputFq, List<String> extraArgs) throws PipelineJobException
    {
        List<String> args = new ArrayList<>();
        args.addAll(getBaseArgs());
        args.add("exportReadsForClones");
        args.add("-f");

        if (extraArgs != null)
        {
            args.addAll(extraArgs);
        }
        args.add(cloneAln.getPath());
        args.add(outputFq.getPath());

        StringBuffer stdErr = new StringBuffer();
        try
        {
            executeWithOutput(args, stdErr);
        }
        catch (Exception e)
        {
            getLogger().error("MiXCR exportReadsForClones error:");
            getLogger().error(stdErr);

            throw e;
        }
    }

    public void doExportAlignmentsPretty(File align, File output, List<String> extraArgs) throws PipelineJobException
    {
        List<String> args = new ArrayList<>();
        args.addAll(getBaseArgs());
        args.add("exportAlignmentsPretty");
        args.add("-f");

        if (extraArgs != null)
        {
            args.addAll(extraArgs);
        }
        args.add(align.getPath());
        args.add(output.getPath());

        StringBuffer stdErr = new StringBuffer();
        try
        {
            executeWithOutput(args, stdErr);
        }
        catch (Exception e)
        {
            getLogger().error("MiXCR exportAlignmentsPretty error:");
            getLogger().error(stdErr);

            throw e;
        }
    }

    public void doExportAlignments(File align, File output, List<String> extraArgs) throws PipelineJobException
    {
        List<String> args = new ArrayList<>();
        args.addAll(getBaseArgs());
        args.add("exportAlignments");
        args.add("-f");
        args.add("-vGene");
        args.add("-dGene");
        args.add("-jGene");
        args.add("-cGene");

        args.add("-vHit");
        args.add("-dHit");
        args.add("-jHit");
        args.add("-cHit");

        args.add("-vHitScore");
        args.add("-dHitScore");
        args.add("-jHitScore");
        args.add("-cHitScore");

        args.add("-readIds");
        args.add("-targetSequences");

        args.add("-positionOf");
        args.add("VEndTrimmed");
        args.add("-positionOf");
        args.add("JBeginTrimmed");
        args.add("-positionOf");
        args.add("CBegin");
        args.add("-descrsR1");
        args.add("-descrsR2");

        args.add("-nFeature");
        args.add("{JBegin(-25):JBegin(-5)}");

        args.add("-nFeature");
        args.add("{VEnd(5):VEnd(25)}");

        if (extraArgs != null)
        {
            args.addAll(extraArgs);
        }
        args.add(align.getPath());
        args.add(output.getPath());

        execute(args);
    }

    private List<String> getBaseArgs()
    {
        List<String> args = new ArrayList<>();
        args.add(SequencePipelineService.get().getJavaFilepath());
        //args.addAll(SequencePipelineService.get().getJavaOpts());
        File jar = new File(getJAR().getPath());
        args.add("-Dmixcr.path=" + jar.getParentFile().getPath());
        args.add("-Dmixcr.command=mixcr");

        if (_libraryPath != null)
        {
            args.add("-Dlibrary.path=" + _libraryPath.getPath());
        }

        args.add("-jar");
        args.add(jar.getPath());

        return args;
    }

    private File getJAR()
    {
        File jar = new File(ModuleLoader.getInstance().getWebappDir(), "WEB-INF/lib");
        jar = new File(jar, "mixcr.jar");
        if (jar.exists())
        {
            return jar;
        }

        return SequencePipelineService.get().getExeForPackage("mixcr", "mixcr.jar");
    }

    public String getVersionString() throws PipelineJobException
    {
        List<String> args = getBaseArgs();
        args.add("-v");

        String ret = executeWithOutput(args);
        String[] lines = ret.split("\\n");
        if (lines.length == 0)
        {
            return "Unknown";
        }

        String[] tokens = lines[0].split(" \\(");

        return tokens.length > 0 ? tokens[0] : "Unable to determine";
    }

    public void setLibraryPath(File libraryPath)
    {
        _libraryPath = libraryPath;
    }

    public File getAlignOutputFile(File outDir, String outputPrefix)
    {
        return new File(outDir, outputPrefix + ".vdjca");
    }
}
