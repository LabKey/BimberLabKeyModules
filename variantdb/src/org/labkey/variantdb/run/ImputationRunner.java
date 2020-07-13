package org.labkey.variantdb.run;

import au.com.bytecode.opencsv.CSVWriter;
import htsjdk.samtools.util.Interval;
import htsjdk.tribble.AbstractFeatureReader;
import htsjdk.tribble.CloseableTribbleIterator;
import htsjdk.tribble.bed.BEDCodec;
import htsjdk.tribble.bed.BEDFeature;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.PipelineJobService;
import org.labkey.api.reader.Readers;
import org.labkey.api.util.Compress;
import org.labkey.api.util.FileType;
import org.labkey.api.util.JobRunner;
import org.labkey.api.util.Pair;
import org.labkey.api.util.StringUtilsLabKey;
import org.labkey.api.writer.PrintWriters;
import org.labkey.variantdb.analysis.Imputation.GLAutoJob;
import org.labkey.variantdb.analysis.Imputation.GiGiJob;
import org.labkey.variantdb.analysis.Imputation.ImputationFileUtil;
import org.labkey.variantdb.analysis.Imputation.PrepareAlleleFreqFilesForChrRunner;
import org.labkey.variantdb.analysis.Imputation.PrepareGenotypeFilesForChrRunner;
import org.labkey.variantdb.analysis.ImputationAnalysis;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Created by bimber on 2/25/2015.
 */
public class ImputationRunner
{
    //maps of the intervals (single positions) for the dense or framework markers.  dense markers are split into batches for error protection and multi-threading
    private Map<String, List<Interval>> _denseIntervalMap;
    private Map<String, List<List<Interval>>> _denseIntervalMapBatched;
    private Map<String, List<Interval>> _frameworkIntervalMap;
    private List<String> _frameworkMarkerNames;
    private boolean _skipFrameworksAsDenseMarkers = false;

    //maps listing the bases that correspond to markers/genotypes in GIGI
    private Map<String, List<List<List<String>>>> _denseMarkerBaseList;
    private Map<String, List<List<String>>> _frameworkMarkerBaseList;
    
    private Map<String, List<Interval>> _genotypeBlacklist;

    private int _minGenotypeQual = 5;
    private int _minGenotypeDepth = 0;

    private int _denseMarkerBatchSize = 2500;
    private PipelineJob _job;

    public ImputationRunner(File denseBedFile, File frameworkBedFile, @Nullable File genotypeBlacklist, Logger log, PipelineJob job) throws PipelineJobException
    {
        _job = job;

        //blacklist
        _genotypeBlacklist = new HashMap<>();
        if (genotypeBlacklist != null)
        {
            try (AbstractFeatureReader reader = AbstractFeatureReader.getFeatureReader(genotypeBlacklist.getPath(), new BEDCodec(), false))
            {
                try (CloseableTribbleIterator<BEDFeature> it = reader.iterator())
                {
                    while (it.hasNext())
                    {
                        BEDFeature f = it.next();
                        if (!_genotypeBlacklist.containsKey(f.getChr()))
                        {
                            _genotypeBlacklist.put(f.getChr(), new LinkedList<>());
                        }

                        _genotypeBlacklist.get(f.getChr()).add(new Interval(f.getChr(), f.getStart(), f.getEnd()));
                    }
                }

                //NOTE: convert this to an ArrayList, since we are going to access specific positions downstream:
                for (String chr : _genotypeBlacklist.keySet())
                {
                    _genotypeBlacklist.put(chr, new ArrayList<>(_genotypeBlacklist.get(chr)));
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }

        //framework
        _frameworkIntervalMap = new HashMap<>();
        _frameworkMarkerNames = new LinkedList<>();
        int blackListedFrameworks = 0;
        try (AbstractFeatureReader reader = AbstractFeatureReader.getFeatureReader(frameworkBedFile.getPath(), new BEDCodec(), false))
        {
            try (CloseableTribbleIterator<BEDFeature> it = reader.iterator())
            {
                while (it.hasNext())
                {
                    BEDFeature f = it.next();
                    if (!_frameworkIntervalMap.containsKey(f.getChr()))
                    {
                        _frameworkIntervalMap.put(f.getChr(), new LinkedList<>());
                    }

                    boolean blackListed = false;
                    Interval i = new Interval(f.getChr(), f.getStart(), f.getEnd());
                    if (_genotypeBlacklist.containsKey(f.getChr()))
                    {
                        for (Interval bl : _genotypeBlacklist.get(f.getChr()))
                        {
                            if (bl.intersects(i))
                            {
                                log.info("framework interval is in blacklist, skipping: " + f.getChr() + ": " + f.getStart());
                                blackListed = true;
                                blackListedFrameworks++;
                                break;
                            }
                        }
                    }

                    if (!blackListed)
                    {
                        _frameworkIntervalMap.get(f.getChr()).add(i);
                        _frameworkMarkerNames.add(ImputationFileUtil.getMarkerName(f));
                    }
                }
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        log.info("total framework intervals blacklisted: " + blackListedFrameworks);

        //then build list of dense intervals by chromosome
        _denseIntervalMap = new HashMap<>();
        int blackListedDenseMarkers = 0;
        int denseOverlappingFramework = 0;
        try (AbstractFeatureReader reader = AbstractFeatureReader.getFeatureReader(denseBedFile.getPath(), new BEDCodec(), false))
        {
            try (CloseableTribbleIterator<BEDFeature> it = reader.iterator())
            {
                while (it.hasNext())
                {
                    BEDFeature f = it.next();
                    if (!_denseIntervalMap.containsKey(f.getChr()))
                    {
                        _denseIntervalMap.put(f.getChr(), new LinkedList<>());
                    }

                    boolean blackListed = false;
                    Interval i = new Interval(f.getChr(), f.getStart(), f.getEnd());
                    if (_genotypeBlacklist.containsKey(f.getChr()))
                    {
                        for (Interval bl : _genotypeBlacklist.get(f.getChr()))
                        {
                            if (bl.intersects(i))
                            {
                                log.info("dense interval is in blacklist, skipping: " + f.getChr() + ": " + f.getStart());
                                blackListed = true;
                                blackListedDenseMarkers++;
                                break;
                            }
                        }
                    }

                    if (blackListed)
                    {
                        continue;
                    }

                    if (_frameworkIntervalMap.containsKey(f.getChr()))
                    {
                        for (Interval bl : _frameworkIntervalMap.get(f.getChr()))
                        {
                            if (bl.intersects(i))
                            {
                                if (_skipFrameworksAsDenseMarkers)
                                {
                                    log.info("dense interval overlaps framework, skipping: " + f.getChr() + ": " + f.getStart());
                                    blackListed = true;
                                }

                                denseOverlappingFramework++;
                                break;
                            }
                        }
                    }

                    if (!blackListed)
                    {
                        _denseIntervalMap.get(f.getChr()).add(i);
                    }
                }
            }

            //NOTE: convert this to an ArrayList, since we are going to access specific positions downstream:
            for (String chr : _denseIntervalMap.keySet())
            {
                _denseIntervalMap.put(chr, new ArrayList<>(_denseIntervalMap.get(chr)));
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        log.info("total dense intervals blacklisted: " + blackListedDenseMarkers);
        log.info("total dense intervals overlapping frameworks: " + denseOverlappingFramework);

        //then batch dense into subsets by batch size
        _denseIntervalMapBatched = new HashMap<>();
        for (String chr : _denseIntervalMap.keySet())
        {
            if (!_denseIntervalMapBatched.containsKey(chr))
            {
                _denseIntervalMapBatched.put(chr, new LinkedList<>());
            }

            int start = 0;
            int size = _denseIntervalMap.get(chr).size();
            while (start < size)
            {
                _denseIntervalMapBatched.get(chr).add(_denseIntervalMap.get(chr).subList(start, Math.min(size, start + _denseMarkerBatchSize)));
                start = start + _denseMarkerBatchSize;
            }
        }

        //summarize distances
        for  (String chr : _denseIntervalMap.keySet())
        {
            double totalDist = 0.0;
            double denseTotalDist = 0.0;
            Interval previousDense = null;
            List<Interval> framework = _frameworkIntervalMap.get(chr);
            for (Interval dense : _denseIntervalMap.get(chr))
            {
                Interval previous = null;
                for (Interval f : framework)
                {
                    //track for dense
                    if (f.getStart() >= dense.getStart())
                    {
                        if (previous == null)
                        {
                            totalDist += dense.getStart() - f.getStart();
                        }
                        else
                        {
                            totalDist += Math.min(f.getStart() - dense.getStart(), f.getStart() - previous.getStart());
                        }

                        break;
                    }

                    previous = f;
                }

                if (previousDense != null)
                {
                    denseTotalDist += dense.getStart() - previousDense.getStart();
                }
                else
                {
                    denseTotalDist += dense.getStart();
                }

                previousDense = dense;
            }

            //and framework spacing
            Interval previous = null;
            double frameworkTotalDist = 0.0;
            for (Interval f : framework)
            {
                if (previous != null)
                {
                    frameworkTotalDist += f.getStart() - previous.getStart();
                }
                else
                {
                    frameworkTotalDist += f.getStart();
                }

                previous = f;
            }

            log.info("using batch size: " + _denseMarkerBatchSize);
            log.info("chr: " + chr + ", avg dense marker distance from framework: " + (totalDist / _denseIntervalMap.get(chr).size()));
            log.info("chr: " + chr + ", total framework markers: " + _frameworkIntervalMap.get(chr).size());
            log.info("chr: " + chr + ", total dense markers: " + _denseIntervalMap.get(chr).size() + ", batches: " + _denseIntervalMapBatched.get(chr).size());
            log.info("chr: " + chr + ", avg framework marker spacing: " + (frameworkTotalDist/ _frameworkIntervalMap.get(chr).size()));
            log.info("chr: " + chr + ", avg dense marker spacing: " + (denseTotalDist/ _denseIntervalMap.get(chr).size()));
        }
    }

    public void processSet(File outDir, File alleleFreqDir, Logger log, List<Pair<Integer, String>> completeGenotypes, List<Pair<Integer, String>> imputed, String callMethod) throws PipelineJobException, IOException
    {
        log.info("processing set, complete genotypes:");
        StringBuilder sb = new StringBuilder();
        String delim = "";
        for (Pair<Integer, String> pair : completeGenotypes)
        {
            sb.append(delim).append(pair.first).append("||").append(pair.second);
            delim = ";";
        }
        log.info(sb.toString());

        sb = new StringBuilder();
        sb.append("imputing: ");
        delim = "";
        for (Pair<Integer, String> pair : imputed)
        {
            sb.append(delim).append(pair.first).append("||").append(pair.second);
            delim = ";";
        }
        log.info(sb.toString());

        int mcIteractions = 300000;
        int mcScoreInterval = 30;
        JobRunner jobRunner = null;
        _job.setStatus(PipelineJob.TaskStatus.running, "Running GL_AUTO");
        try
        {
            jobRunner = getJobRunner();
            for (String chr : _frameworkIntervalMap.keySet())
            {
                log.info("processing chromosome: " + chr + " for gl_auto");
                File basedir = new File(outDir, chr);

                File ivFile = new File(basedir, "framework.IVs");
                if (!ivFile.exists())
                {
                    GLAutoJob job = new GLAutoJob(basedir, new File(basedir, "framework.glauto.geno"), log, chr);
                    job.setMcIterations(mcIteractions);
                    job.setMcStoreInterval(mcScoreInterval);
                    jobRunner.execute(job);
                }
                else
                {
                    log.info("IV file exists, skipping GL_AUTO");
                }
            }

            log.info("waiting for GL_AUTO jobs: " + jobRunner.getJobCount());
            jobRunner.waitForCompletion();
            log.info("GL_AUTO jobs complete");
        }
        finally
        {
            if (jobRunner != null)
                jobRunner.shutdown();
        }

        //next run GIGI
        jobRunner = null;
        _job.setStatus(PipelineJob.TaskStatus.running, "Running GIGI");
        try
        {
            jobRunner = getJobRunner();
            for (String chr : _frameworkIntervalMap.keySet())
            {
                if (!_denseIntervalMapBatched.containsKey(chr))
                {
                    throw new PipelineJobException("unable to find chr: " + chr);
                }

                int denseMarkerIdx = -1;
                for (List<Interval> il : _denseIntervalMapBatched.get(chr))
                {
                    denseMarkerIdx++;
                    log.info("processing chromosome: " + chr + " with GIGI for batch: " + denseMarkerIdx + " of " + _denseIntervalMapBatched.get(chr).size());

                    File glAutoBaseDir = new File(outDir, chr);
                    File gigiOutDir = new File(glAutoBaseDir, String.valueOf(denseMarkerIdx));
                    if (!gigiOutDir.exists())
                    {
                        gigiOutDir.mkdirs();
                    }

                    File ivFile = new File(glAutoBaseDir, "framework.IVs");
                    File imputeOutput = new File(gigiOutDir, "impute-" + denseMarkerIdx + ".geno");
                    if (imputeOutput.exists())
                    {
                        log.info("GIGI output already exists for batch, skipping: " + imputeOutput.getPath());
                        continue;
                    }

                    jobRunner.execute(new GiGiJob(log, chr, denseMarkerIdx, gigiOutDir, alleleFreqDir, callMethod, ivFile, glAutoBaseDir, (mcIteractions / mcScoreInterval)));
                }
            }

            log.info("waiting for GIGI jobs: " + jobRunner.getJobCount());
            jobRunner.waitForCompletion();
            log.info("GIGI jobs complete");
        }
        finally
        {
            if (jobRunner != null)
                jobRunner.shutdown();
        }
    }

    public Map<String, List<Interval>> getDenseIntervalMap()
    {
        return Collections.unmodifiableMap(_denseIntervalMap);
    }

    public Map<String, List<List<Interval>>> getDenseIntervalMapBatched()
    {
        return Collections.unmodifiableMap(_denseIntervalMapBatched);
    }

    public Map<String, List<Interval>> getFrameworkIntervalMap()
    {
        return Collections.unmodifiableMap(_frameworkIntervalMap);
    }

    public void prepareFrequencyFiles(File alleleFreqVcf, File alleleFreqDir, Logger log) throws PipelineJobException
    {
        //first dense
        JobRunner jobRunner = null;
        _job.setStatus(PipelineJob.TaskStatus.running, "Preparing Dense Allele Frequency Files");

        try
        {
            jobRunner = getJobRunner();
            _denseMarkerBaseList = new HashMap<>();
            List<PrepareAlleleFreqFilesForChrRunner> denseJobs = new ArrayList<>();
            for (String chr : _denseIntervalMapBatched.keySet())
            {
                log.info("preparing frequency files for " + chr + ".  total batches: " + _denseIntervalMapBatched.get(chr).size());

                int denseMarkerIdx = -1;
                for (List<Interval> il : _denseIntervalMapBatched.get(chr))
                {
                    denseMarkerIdx++;

                    PrepareAlleleFreqFilesForChrRunner r = new PrepareAlleleFreqFilesForChrRunner(alleleFreqVcf, ImputationFileUtil.MarkerType.dense, chr, denseMarkerIdx, alleleFreqDir, log, il);
                    denseJobs.add(r);
                    jobRunner.execute(r);
                }
            }

            log.info("total jobs: " + jobRunner.getJobCount());
            jobRunner.waitForCompletion();
            log.info("job runner complete");

            for (PrepareAlleleFreqFilesForChrRunner r : denseJobs)
            {
                if (!_denseMarkerBaseList.containsKey(r.getChr()))
                {
                    _denseMarkerBaseList.put(r.getChr(), new ArrayList<>());
                }

                _denseMarkerBaseList.get(r.getChr()).add(r.getMarkerToBaseList());
            }
        }
        finally
        {
            if (jobRunner != null)
                jobRunner.shutdown();
        }

        //then framework
        jobRunner = null;
        _job.setStatus(PipelineJob.TaskStatus.running, "Preparing Framework Allele Frequency Files");
        try
        {
            jobRunner = getJobRunner();
            List<PrepareAlleleFreqFilesForChrRunner> frameworkJobs = new ArrayList<>();
            _frameworkMarkerBaseList = new HashMap<>();
            for (String chr : _frameworkIntervalMap.keySet())
            {
                PrepareAlleleFreqFilesForChrRunner r = new PrepareAlleleFreqFilesForChrRunner(alleleFreqVcf, ImputationFileUtil.MarkerType.framework, chr, null, alleleFreqDir, log, _frameworkIntervalMap.get(chr));
                frameworkJobs.add(r);
                jobRunner.execute(r);
            }

            log.info("total jobs: " + jobRunner.getJobCount());
            jobRunner.waitForCompletion();
            log.info("job runner complete");
            for (PrepareAlleleFreqFilesForChrRunner r : frameworkJobs)
            {
                _frameworkMarkerBaseList.put(r.getChr(), r.getMarkerToBaseList());
            }
        }
        finally
        {
            if (jobRunner != null)
                jobRunner.shutdown();
        }
    }

    public void prepareDenseGenotypeFiles(ImputationAnalysis.Processor.SampleSet ss, File inputVCF, Collection<String> sampleNames, List<String> imputationTargets, ImputationFileUtil.GiGiType giGiType, File outputDir, Logger log, File gatkPed) throws PipelineJobException
    {
        JobRunner jobRunner = null;
        try
        {
            _job.setStatus(PipelineJob.TaskStatus.running, "Preparing Dense Genotype Files");

            jobRunner = getJobRunner();
            List<PrepareGenotypeFilesForChrRunner> jobs = new ArrayList<>();
            for (String chr : _denseIntervalMapBatched.keySet())
            {
                log.info("processing chromosome: " + chr + ".  for marker set: dense.  to build data of type: " + giGiType.name() + ".  total batches: " + _denseIntervalMapBatched.get(chr).size());
                int denseMarkerBatchIdx = -1;
                for (List<Interval> il : _denseIntervalMapBatched.get(chr))
                {
                    denseMarkerBatchIdx++;
                    for (String sampleName : sampleNames)
                    {
                        PrepareGenotypeFilesForChrRunner r = new PrepareGenotypeFilesForChrRunner(ss, inputVCF, sampleName, giGiType, outputDir, log, ImputationFileUtil.MarkerType.dense, chr, denseMarkerBatchIdx, il, gatkPed, _denseMarkerBaseList.get(chr).get(denseMarkerBatchIdx), _minGenotypeQual, _minGenotypeDepth, _genotypeBlacklist, _frameworkMarkerNames, imputationTargets.contains(sampleName));
                        jobs.add(r);
                        jobRunner.execute(r);
                    }
                }
            }

            log.info("total jobs: " + jobRunner.getJobCount());
            jobRunner.waitForCompletion();
            log.info("job runner complete");

            if (!jobs.isEmpty())
            {
                log.info("writing consolidated error/warning messages");
                try (CSVWriter writer = new CSVWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(outputDir, "CombinedDenseMarkerErrors.txt"), true), StringUtilsLabKey.DEFAULT_CHARSET)), '\t', CSVWriter.NO_QUOTE_CHARACTER))
                {
                    writer.writeNext(new String[]{"SampleName", "Chr", "DenseMarkerBatchIdx", "MarkerType", "MarkerName", "Ref_VCF_ID", "Message"});
                    for (PrepareGenotypeFilesForChrRunner j : jobs)
                    {
                        j.logMessages(writer);
                    }
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }
            }
        }
        finally
        {
            if (jobRunner != null)
                jobRunner.shutdown();
        }

    }

    public void prepareFrameworkGenotypeFiles(ImputationAnalysis.Processor.SampleSet ss, File vcf, Collection<String> sampleNames, ImputationFileUtil.GiGiType giGiType, File outputDir, Logger log, File gatkPed) throws PipelineJobException
    {
        JobRunner jobRunner = null;
        _job.setStatus(PipelineJob.TaskStatus.running, "Preparing Framework Genotype Files");

        try
        {
            jobRunner = getJobRunner();
            List<PrepareGenotypeFilesForChrRunner> jobs = new ArrayList<>();
            for (String chr : _frameworkIntervalMap.keySet())
            {
                log.info("processing chromosome: " + chr + ".  for marker set: framework.  to build data of type: " + giGiType.name() + ".  total intervals: " + _frameworkIntervalMap.get(chr).size());
                for (String sampleName : sampleNames)
                {
                    PrepareGenotypeFilesForChrRunner r = new PrepareGenotypeFilesForChrRunner(ss, vcf, sampleName, giGiType, outputDir, log, ImputationFileUtil.MarkerType.framework, chr, null, _frameworkIntervalMap.get(chr), gatkPed, _frameworkMarkerBaseList.get(chr), _minGenotypeQual, _minGenotypeDepth, _genotypeBlacklist, _frameworkMarkerNames, false);
                    jobs.add(r);
                    jobRunner.execute(r);
                }
            }

            log.info("total jobs: " + jobRunner.getJobCount());
            jobRunner.waitForCompletion();
            log.info("job runner complete");

            if (!jobs.isEmpty())
            {
                log.info("writing consolidated error/warning messages");
                try (CSVWriter writer = new CSVWriter(new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(outputDir, "CombinedFrameworkMarkerErrors.txt"), true), StringUtilsLabKey.DEFAULT_CHARSET)), '\t', CSVWriter.NO_QUOTE_CHARACTER))
                {
                    writer.writeNext(new String[]{"SampleName", "Chr", "DenseMarkerBatchIdx", "MarkerType", "MarkerName", "Ref_VCF_ID", "Message"});
                    for (PrepareGenotypeFilesForChrRunner j : jobs)
                    {
                        j.logMessages(writer);
                    }
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }
            }
        }
        finally
        {
            if (jobRunner != null)
                jobRunner.shutdown();
        }
    }

    public void prepareFrameworkResources(File setBaseDir, File alleleFreqDir, Logger log, List<Pair<Integer, String>> completeGenotypes, List<Pair<Integer, String>> imputed) throws PipelineJobException
    {
        for (String chr : _frameworkIntervalMap.keySet())
        {
            prepareResourcesForChr(setBaseDir, alleleFreqDir, log, completeGenotypes, imputed, ImputationFileUtil.MarkerType.framework, chr, null, _frameworkIntervalMap.get(chr));
        }
    }

    public void prepareDenseResources(File setBaseDir, File alleleFreqDir, Logger log, List<Pair<Integer, String>> completeGenotypes, List<Pair<Integer, String>> imputed) throws PipelineJobException
    {
        for (String chr : _denseIntervalMapBatched.keySet())
        {
            int denseMarkerIdx = -1;
            for (List<Interval> il : _denseIntervalMapBatched.get(chr))
            {
                denseMarkerIdx++;
                prepareResourcesForChr(setBaseDir, alleleFreqDir, log, completeGenotypes, imputed, ImputationFileUtil.MarkerType.dense, chr, denseMarkerIdx, il);
            }
        }
    }

    private void prepareResourcesForChr(File setBaseDir, File alleleFreqDir, Logger log, List<Pair<Integer, String>> completeGenotypes, List<Pair<Integer, String>> imputed, ImputationFileUtil.MarkerType markerType, String chr, @Nullable Integer denseMarkerBatchIdx, List<Interval> intervalList) throws PipelineJobException
    {
        //TODO: convert to runnable
        try
        {
            log.info("preparing resources for GIGI: " + chr);
            File basedir = new File(setBaseDir, chr);
            if (!basedir.exists())
            {
                basedir.mkdir();
            }

            String separator = denseMarkerBatchIdx == null ? "" : "-" + denseMarkerBatchIdx;
            File markerNamesFile = new File(basedir, "markers" + separator + ".tmp");
            File markerPosFile = new File(basedir, "markerPositions" + separator + ".tmp");
            File mapFile = new File(basedir, markerType.name() + separator + "_map.txt");
            File gigiGenoFile = new File(basedir, markerType.name() + separator + ".gigi.geno");
            File glautoGenoFile = new File(basedir, markerType.name() + separator + ".glauto.geno");

            if (mapFile.exists() && gigiGenoFile.exists() && (markerType != ImputationFileUtil.MarkerType.framework || glautoGenoFile.exists()))
            {
                log.info("all resources exist for: " + chr + ", re-using");
                return;
            }

            try (
                    PrintWriter markerNameLineWriter = PrintWriters.getPrintWriter(markerNamesFile);
                    PrintWriter markerPosLineWriter = PrintWriters.getPrintWriter(markerPosFile);
                    PrintWriter mapWriter = PrintWriters.getPrintWriter(mapFile)
            )
            {
                markerNameLineWriter.write("set marker names");
                markerPosLineWriter.write("map marker positions");

                for (Interval i : intervalList)
                {
                    String markerName = ImputationFileUtil.getMarkerName(i);

                    mapWriter.write((i.getStart() / 1000000.0) + "\n");
                    markerNameLineWriter.append(" ").append(markerName);
                    markerPosLineWriter.append(" " + i.getStart() / 1000000.0);
                }
            }

            File frequencyFile = ImputationFileUtil.getAlleleFreqGenotypesFile(alleleFreqDir, markerType, chr, denseMarkerBatchIdx);

            //now write each version of the marker files for GIGI or GL_AUTO.  These are basically the same file, except the GL_AUTO version includes frequency info
            log.info("writing " + markerType.name() + " genotype files for: " + chr + (denseMarkerBatchIdx == null ? "" : ", batch: " + denseMarkerBatchIdx));
            log.debug("using basedir: " + basedir.getPath());

            //first gl_auto, which uses the wide format
            if (markerType == ImputationFileUtil.MarkerType.framework)
            {
                writeGlAutoFiles(markerNamesFile, markerPosFile, frequencyFile, glautoGenoFile, chr, denseMarkerBatchIdx, intervalList, completeGenotypes, imputed, setBaseDir, log);
            }
            else
            {
                writeGigiGenoFile(gigiGenoFile, chr, denseMarkerBatchIdx, intervalList, completeGenotypes, imputed, setBaseDir, log);
            }

        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }

    private void writeGlAutoFiles(File markerNamesFile, File markerPosFile, File frequencyFile, File glautoGenoFile, String chr, @Nullable Integer denseMarkerBatchIdx, List<Interval> intervalList, List<Pair<Integer, String>> completeGenotypes, List<Pair<Integer, String>> imputed, File setBaseDir, Logger log) throws IOException
    {
        try (PrintWriter glautoGenoWriter = PrintWriters.getPrintWriter(glautoGenoFile))
        {
            //note: skipping markerNamesFile
            for (File file : Arrays.asList(markerPosFile, frequencyFile))
            {
                try (BufferedReader reader = Readers.getReader(file))
                {
                    IOUtils.copy(reader, glautoGenoWriter);
                    glautoGenoWriter.write("\n\n");
                }

                //file.delete();
            }

            glautoGenoWriter.write("set marker " + intervalList.size() + " data\n");

            Set<String> distinctImputed = new HashSet<>();
            for (Pair<Integer, String> pair : imputed)
            {
                File file = getGiGiExperimentalGenotypeFile(ImputationFileUtil.MarkerType.framework, setBaseDir, chr, pair.second, denseMarkerBatchIdx);
                appendGenotypeLine(file, pair.second, glautoGenoWriter);
                distinctImputed.add(pair.second);
            }

            for (Pair<Integer, String> pair : completeGenotypes)
            {
                if (distinctImputed.contains(pair.second))
                {
                    log.warn("this set already has an imputed sample for: " + pair.second + ", skipping repeat of complete genome data (sampleId: " + pair.first + "|" + pair.second + ")");
                    continue;
                }

                File file = getGiGiExperimentalGenotypeFile(ImputationFileUtil.MarkerType.framework, setBaseDir, chr, pair.second, denseMarkerBatchIdx);
                appendGenotypeLine(file, pair.second, glautoGenoWriter);
            }
        }
    }

    private void writeGigiGenoFile(File gigiGenoFile, String chr, @Nullable Integer denseMarkerBatchIdx, List<Interval> intervalList, List<Pair<Integer, String>> completeGenotypes, List<Pair<Integer, String>> imputed, File setBaseDir, Logger log) throws IOException
    {
        try (PrintWriter gigiGenoWriter = PrintWriters.getPrintWriter(gigiGenoFile))
        {
            Set<String> distinctSamples = new HashSet<>();
            Map<String, String[]> genoMap = new HashMap<>();
            for (Pair<Integer, String> pair : imputed)
            {
                File file = getGiGiExperimentalGenotypeFile(ImputationFileUtil.MarkerType.dense, setBaseDir, chr, pair.second, denseMarkerBatchIdx);
                distinctSamples.add(pair.second);

                genoMap.put(pair.second, readGenotypeLine(file));
            }

            for (Pair<Integer, String> pair : completeGenotypes)
            {
                if (distinctSamples.contains(pair.second))
                {
                    log.warn("this set already has an imputed sample for: " + pair.second + ", skipping repeat of complete genome data (sampleId: " + pair.first + "|" + pair.second + ")");
                    continue;
                }

                distinctSamples.add(pair.second);

                File file = getGiGiExperimentalGenotypeFile(ImputationFileUtil.MarkerType.dense, setBaseDir, chr, pair.second, denseMarkerBatchIdx);
                genoMap.put(pair.second, readGenotypeLine(file));
            }

            //now make combined file.  first header
            StringBuilder line = new StringBuilder();
            line.append("id");
            for (String sn : distinctSamples)
            {
                line.append(" ").append(sn);
                line.append(" ").append(sn);
            }
            line.append("\n");
            gigiGenoWriter.write(line.toString());

            //then append all markers
            int markerNumber = 0;
            for (Interval i : intervalList)
            {
                String markerName = ImputationFileUtil.getMarkerName(i);

                line = new StringBuilder();
                line.append(markerName);

                for (String sn : distinctSamples)
                {
                    String[] arr = genoMap.get(sn);
                    line.append(" ").append(arr[(markerNumber * 2)]);
                    line.append(" ").append(arr[(markerNumber * 2) + 1]);
                }

                line.append("\n");
                gigiGenoWriter.write(line.toString());

                markerNumber++;
            }
        }
    }

    private String[] readGenotypeLine(File file) throws IOException
    {
        try (BufferedReader reader = Readers.getReader(file))
        {
            String line = reader.readLine();
            line = line.trim();

            return line.split(" ");
        }
    }

    private void appendGenotypeLine(File file, String sampleName, Writer writer) throws IOException
    {
        try (BufferedReader reader = Readers.getReader(file))
        {
            writer.write(sampleName + " ");
            IOUtils.copy(reader, writer);
            writer.write("\n");
        }
    }

    //this is the file holding the genotypes we expect to give to GIGI.  depending on the sample type, this might be
    private File getGiGiExperimentalGenotypeFile(ImputationFileUtil.MarkerType type, File setBaseDir, String chr, String sampleName, Integer denseMarkerBatchIdx)
    {
        return ImputationFileUtil.getGiGiGenotypeFile(type, setBaseDir, chr, sampleName, ImputationFileUtil.GiGiType.experimental, denseMarkerBatchIdx);
    }

    public Set<String> getDenseChrs()
    {
        return _denseIntervalMapBatched.keySet();
    }

    public List<Interval> getFrameworkIntervals(String chr)
    {
        return _frameworkIntervalMap.get(chr);
    }

    //expects 1-based markerNumber
    public Interval getDensePositionByIndex(String chr, Integer denseMarkerIndex, int markerNumber)
    {
        return _denseIntervalMapBatched.get(chr).get(denseMarkerIndex).get(markerNumber - 1);
    }

    //expects 1-based markerNumber
    public List<String> getDenseAlleleToBaseMap(String chr, int denseMarkerIndex, int markerNumber)
    {
        return _denseMarkerBaseList.get(chr).get(denseMarkerIndex).get(markerNumber - 1);
    }

    public int getMinGenotypeQual()
    {
        return _minGenotypeQual;
    }

    public void setMinGenotypeQual(int minGenotypeQual)
    {
        _minGenotypeQual = minGenotypeQual;
    }

    public int getMinGenotypeDepth()
    {
        return _minGenotypeDepth;
    }

    public void setMinGenotypeDepth(int minGenotypeDepth)
    {
        _minGenotypeDepth = minGenotypeDepth;
    }

    public void setDenseMarkerBatchSize(int denseMarkerBatchSize)
    {
        _denseMarkerBatchSize = denseMarkerBatchSize;
    }

    public void doCleanup(Logger log, File baseDir) throws IOException
    {
        for (String chr : _denseIntervalMapBatched.keySet())
        {
            _job.setStatus(PipelineJob.TaskStatus.running, "Cleaning up files for: " + chr);
            log.info("cleaning up tmp files for: " + chr);

            File dir = new File(baseDir, chr);
            File[] files = dir.listFiles();
            if (files != null && files.length > 0)
            {
                for (int i=0;i<files.length;i++)
                {
                    File f = files[i];
                    if (f.getName().endsWith(".tmp") || f.getName().endsWith("_map.txt") || f.getName().endsWith(".gigi.geno"))
                    {
                        f.delete();
                    }
                    else
                    {
                        if (!f.isDirectory())
                        {
                            Compress.compressGzip(f);
                            f.delete();
                        }
                    }
                }
            }

            log.info("cleaning up genotypes dir");
            File genotypes = new File(dir, "genotypes");
            File[] genoFiles = genotypes.listFiles();
            if (genoFiles != null && genoFiles.length > 0)
            {
                for (int i=0;i<genoFiles.length;i++)
                {
                    File f = genoFiles[i];
                    //TODO: consider deleting VCFs too
                    if (f.getName().endsWith(".geno"))
                    {
                        f.delete();
                    }
                    else
                    {
                        if (!f.isDirectory())
                        {
                            Compress.compressGzip(f);
                            f.delete();
                        }
                    }
                }
            }

            genoFiles = genotypes.listFiles();
            if (genoFiles == null || genoFiles.length == 0)
            {
                FileUtils.deleteDirectory(genotypes);
            }

            log.info("done");
        }
    }

    private JobRunner getJobRunner()
    {
        return new JobRunner("ImputationRunner", getMaxThreads());
    }

    private Integer getMaxThreads()
    {
        String threads = PipelineJobService.get().getConfigProperties().getSoftwarePackagePath("SEQUENCEANALYSIS_MAX_THREADS");
        if (StringUtils.trimToNull(threads) != null && NumberUtils.isCreatable(threads))
        {
            try
            {
                return Integer.parseInt(threads);
            }
            catch (NumberFormatException e)
            {
                //ignore
            }
        }

        return 1;
    }

    public static boolean hasMinLineCount(File f, long minLines) throws PipelineJobException
    {
        FileType gz = new FileType(".gz");
        try (InputStream is = gz.isType(f) ? new GZIPInputStream(new FileInputStream(f)) : new FileInputStream(f);BufferedReader reader = new BufferedReader(new InputStreamReader(is, StringUtilsLabKey.DEFAULT_CHARSET));)
        {
            long lineNo = 0;
            while (reader.readLine() != null)
            {
                lineNo++;
                if (lineNo >= minLines)
                {
                    return true;
                }
            }

            return false;
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }
}
