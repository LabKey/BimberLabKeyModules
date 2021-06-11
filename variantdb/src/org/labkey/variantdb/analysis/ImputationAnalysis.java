package org.labkey.variantdb.analysis;

import au.com.bytecode.opencsv.CSVWriter;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.Interval;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFFileReader;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.labkey.api.data.Container;
import org.labkey.api.data.ConvertHelper;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.exp.api.ExperimentService;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedAction;
import org.labkey.api.pipeline.file.FileAnalysisJobSupport;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.reader.Readers;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.PedigreeRecord;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.sequenceanalysis.run.SelectVariantsWrapper;
import org.labkey.api.util.Compress;
import org.labkey.api.util.FileType;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.Pair;
import org.labkey.api.view.ActionURL;
import org.labkey.api.writer.PrintWriters;
import org.labkey.variantdb.VariantDBModule;
import org.labkey.variantdb.analysis.Imputation.ImputationFileUtil;
import org.labkey.variantdb.analysis.Imputation.SubjectCounter;
import org.labkey.variantdb.run.MergeVcfsAndGenotypesWrapper;
import org.labkey.variantdb.run.ImputationRunner;
import org.labkey.variantdb.run.MendelianEvaluator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Created by bimber on 2/22/2015.
 */
public class ImputationAnalysis implements SequenceOutputHandler<SequenceOutputHandler.SequenceOutputProcessor>
{
    private final FileType _vcfType = new FileType(Arrays.asList(".vcf"), ".vcf", false, FileType.gzSupportLevel.SUPPORT_GZ);

    public ImputationAnalysis()
    {

    }

    @Override
    public String getName()
    {
        return "GIGIv3 Imputation";
    }

    @Override
    public String getDescription()
    {
        return "This will use GIGI v3 to impute genotypes given an input VCF file.  It will automatically generate many of the required files for GIGI and MORGAN.  Note: the VCFs must have either been created through LabKey, or be compliant.  This means all sample names must match readsets.  This is necessary because that is how the system looks up SubjectIds and pedigree information.";
    }

    @Override
    public String getButtonJSHandler()
    {
        return null;
    }

    @Override
    public ActionURL getButtonSuccessUrl(Container c, User u, List<Integer> outputFileIds)
    {
        return DetailsURL.fromString("/variantdb/imputationAnalysis.view?outputFileIds=" + StringUtils.join(outputFileIds, ";"), c).getActionURL();
    }

    @Override
    public boolean useWorkbooks()
    {
        return true;
    }

    @Override
    public Module getOwningModule()
    {
        return ModuleLoader.getInstance().getModule(VariantDBModule.class);
    }

    @Override
    public LinkedHashSet<String> getClientDependencies()
    {
        return null;
    }

    @Override
    public boolean canProcess(SequenceOutputFile f)
    {
        return f.getFile() != null && (_vcfType.isType(f.getFile()));
    }

    @Override
    public boolean doRunRemote()
    {
        return true;
    }

    @Override
    public boolean doRunLocal()
    {
        return false;
    }

    @Override
    public SequenceOutputProcessor getProcessor()
    {
        return new Processor();
    }

    @Override
    public boolean doSplitJobs()
    {
        return false;
    }

    public class Processor implements SequenceOutputProcessor
    {
        @Override
        public void init(JobContext ctx, List<SequenceOutputFile> inputFiles, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {
            //find genome
            Set<Integer> ids = new HashSet<>();
            for (SequenceOutputFile f : inputFiles)
            {
                ids.add(f.getLibrary_id());

                try
                {
                    SequenceAnalysisService.get().ensureVcfIndex(f.getFile(), ctx.getJob().getLogger());
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }
            }

            if (ids.size() != 1)
            {
                throw new PipelineJobException("The selected files use more than 1 genome.  All VCFs must use the same genome");
            }

            ctx.getSequenceSupport().cacheGenome(SequenceAnalysisService.get().getReferenceGenome(ids.iterator().next(), ctx.getJob().getUser()));

            //make ped file
            List<PedigreeRecord> pedigreeRecords = generatePedigree(ctx.getJob(), ctx.getParams());

            File gatkPed = new File(ctx.getJob().getJobSupport(FileAnalysisJobSupport.class).getAnalysisDirectory(), "gatkPed.ped");
            File morganPed = new File(ctx.getSourceDirectory(), "morgan.ped");
            try (PrintWriter gatkWriter = PrintWriters.getPrintWriter(gatkPed); PrintWriter morganWriter = PrintWriters.getPrintWriter(morganPed))
            {
                morganWriter.write("input pedigree size " + pedigreeRecords.size() + '\n');
                morganWriter.write("input pedigree record names 3 integers 2\n");
                morganWriter.write("input pedigree record trait 1 integer 2\n"); //necessary to get gl_auto to run
                morganWriter.write("*****" + '\n');
                for (PedigreeRecord pd : pedigreeRecords)
                {
                    List<String> vals = Arrays.asList(pd.getSubjectName(), (StringUtils.isEmpty(pd.getFather()) ? "0" : pd.getFather()), (StringUtils.isEmpty(pd.getMother()) ? "0" : pd.getMother()), ("m".equals(pd.getGender()) ? "1" : "f".equals(pd.getGender()) ? "2" : "0"), "0");
                    morganWriter.write(StringUtils.join(vals, " ") + '\n');
                    gatkWriter.write("FAM01 " + StringUtils.join(vals, " ") + '\n');
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            ExpData frameworkMarkers = ExperimentService.get().getExpData(ctx.getParams().getInt("frameworkFile"));
            if (frameworkMarkers == null || !frameworkMarkers.getFile().exists())
            {
                throw new PipelineJobException("Unable to find framework markers file: " + ctx.getParams().getInt("frameworkFile"));
            }
            ctx.getJob().getLogger().info("using framework markers file: " + frameworkMarkers.getFile().getPath());
            ctx.getSequenceSupport().cacheExpData(frameworkMarkers);

            ExpData denseMarkers = ExperimentService.get().getExpData(ctx.getParams().getInt("denseFile"));
            if (denseMarkers == null || !denseMarkers.getFile().exists())
            {
                throw new PipelineJobException("Unable to find dense markers file: " + ctx.getParams().getInt("denseFile"));
            }
            ctx.getJob().getLogger().info("using dense markers file: " + denseMarkers.getFile().getPath());
            ctx.getSequenceSupport().cacheExpData(denseMarkers);

            ExpData alleleFrequencyFile = ExperimentService.get().getExpData(ctx.getParams().getInt("alleleFrequencyFile"));
            if (alleleFrequencyFile == null || !alleleFrequencyFile.getFile().exists())
            {
                throw new PipelineJobException("Unable to find allele frequency file: " + ctx.getParams().getInt("alleleFrequencyFile"));
            }
            ctx.getJob().getLogger().info("using allele frequency file: " + alleleFrequencyFile.getFile().getPath());
            ctx.getSequenceSupport().cacheExpData(alleleFrequencyFile);

            if (StringUtils.trimToNull(ctx.getParams().getString("blacklistFile")) != null)
            {
                ExpData blacklist = ExperimentService.get().getExpData(ctx.getParams().getInt("blacklistFile"));
                if (blacklist == null || !blacklist.getFile().exists())
                {
                    throw new PipelineJobException("Unable to find blacklist file: " + ctx.getParams().getInt("blacklistFile"));
                }
                ctx.getJob().getLogger().info("using blacklist: " + blacklist.getFile().getPath());
                ctx.getSequenceSupport().cacheExpData(blacklist);
            }
        }

        @Override
        public void processFilesOnWebserver(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles, JSONObject params, File outputDir, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {

        }

        @Override
        public void processFilesRemote(List<SequenceOutputFile> inputFiles, JobContext ctx) throws UnsupportedOperationException, PipelineJobException
        {
            PipelineJob job = ctx.getJob();
            JSONObject params = ctx.getParams();

            for (Integer i : ctx.getSequenceSupport().getAllCachedData().keySet())
            {
                job.getLogger().debug("cached data: " + i + "/" + ctx.getSequenceSupport().getAllCachedData().get(i));
            }

            if (ctx.getSequenceSupport().getAllCachedData().isEmpty())
            {
                job.getLogger().error("there are no cached ExpDatas");
            }

            Set<Integer> genomeIds = new HashSet<>();
            for (SequenceOutputFile o : inputFiles)
            {
                genomeIds.add(o.getLibrary_id());
            }
            ReferenceGenome genome = ctx.getSequenceSupport().getCachedGenome(genomeIds.iterator().next());

            File gatkPed = new File(job.getJobSupport(FileAnalysisJobSupport.class).getAnalysisDirectory(), "gatkPed.ped");
            List<SampleSet> sets = getSampleSets(params);
            RecordedAction action = new RecordedAction(getName());
            ctx.addActions(action);
            action.addInputIfNotPresent(gatkPed, "Pedigree File");
            File denseMarkers = ctx.getSequenceSupport().getCachedData(params.getInt("denseFile"));
            action.addInputIfNotPresent(denseMarkers, "Dense Markers");
            File frameworkMarkers = ctx.getSequenceSupport().getCachedData(params.getInt("frameworkFile"));
            action.addInputIfNotPresent(frameworkMarkers, "Framework Markers");
            File alleleFreqVcf = ctx.getSequenceSupport().getCachedData(params.getInt("alleleFrequencyFile"));
            action.addInputIfNotPresent(alleleFreqVcf, "Allele Frequency File");

            File blackList = null;
            if (StringUtils.trimToNull(params.getString("blacklistFile")) != null)
            {
                blackList = ctx.getSequenceSupport().getCachedData(params.getInt("blacklistFile"));
                action.addInputIfNotPresent(blackList, "Genotype Blacklist");
            }

            //copy locally to retain a record.  we expect these files to be of reasonable size
            File copiedBlackList = null;
            try
            {
                FileUtils.copyFile(denseMarkers, new File(ctx.getOutputDir(), denseMarkers.getName()));
                FileUtils.copyFile(frameworkMarkers, new File(ctx.getOutputDir(), frameworkMarkers.getName()));

                if (blackList != null)
                {
                    copiedBlackList = new File(job.getJobSupport(FileAnalysisJobSupport.class).getAnalysisDirectory(), "genotypeBlacklist.bed");
                    FileUtils.copyFile(blackList, copiedBlackList);
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            if (copiedBlackList != null)
            {
                job.getLogger().info("genotype blacklist found, using: " + copiedBlackList.getName());
            }

            ImputationRunner runner = new ImputationRunner(denseMarkers, frameworkMarkers, copiedBlackList, job.getLogger(), job);
            File alleleFreqDir = new File(job.getJobSupport(FileAnalysisJobSupport.class).getAnalysisDirectory(), "alleleFreqs");
            alleleFreqDir.mkdirs();

            if (params.get("minGenotypeQual") != null)
            {
                job.getLogger().info("setting minGenotypeQual: " + params.get("minGenotypeQual"));
                runner.setMinGenotypeQual(params.getInt("minGenotypeQual"));
            }

            if (params.get("minGenotypeDepth") != null)
            {
                job.getLogger().info("setting minGenotypeDepth: " + params.get("minGenotypeDepth"));
                runner.setMinGenotypeDepth(params.getInt("minGenotypeDepth"));
            }

            if (StringUtils.trimToNull(params.getString("denseMarkerBatchSize")) != null)
            {
                job.getLogger().info("using batch size of: " + params.getInt("denseMarkerBatchSize"));
                runner.setDenseMarkerBatchSize(params.getInt("denseMarkerBatchSize"));
            }

            //write allele frequency data and make map of marker/NT
            job.setStatus(PipelineJob.TaskStatus.running, "Preparing Allele Frequency Files");
            runner.prepareFrequencyFiles(alleleFreqVcf, alleleFreqDir, job.getLogger());

            File summary = new File(job.getJobSupport(FileAnalysisJobSupport.class).getAnalysisDirectory(), "summary.txt");
            File afSummary = new File(job.getJobSupport(FileAnalysisJobSupport.class).getAnalysisDirectory(), "afSummary.txt");
            action.addOutputIfNotPresent(summary, "Summary Table", false);

            try (CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(summary), '\t', CSVWriter.NO_QUOTE_CHARACTER);CSVWriter afWriter = new CSVWriter(PrintWriters.getPrintWriter(afSummary), '\t', CSVWriter.NO_QUOTE_CHARACTER))
            {
                writer.writeNext(new String[]{"SetName", "JobName", "TotalFramework", "TotalDense", "CompleteGenotypes", "ImputedSubjects", "Subject", "CallMethod", "MinGenotypeQual", "MinGenotypeDepth", "Chr", "GenotypesInspected", "WithReferenceData", "GenotypesMatchingRef", "Errors", "GenotypesWithReferenceNotImputed", "UnverifiableGenotypes", "UnverifiableGenotypesNotImputed", "PctMatching", "PctNotImputedOfVerifiable", "PctNotImputedOfTotal", "GenotypesOverlappingFrameworkWithImputationInput", "GenotypesOverlappingFrameworkWithoutImputationInput", "NumFirstOrderRelativesWithWGS", "NumFirstOrderRelativesPresent", "FirstOrderRelativesWithWGS", "FirstOrderRelativesPresent", "TotalSubjectsImputed", "TotalLowFreqHetMatching", "TotalLowFreqHetErrors", "RefGenotypeNotFound", "SitesHalfImputed"});
                afWriter.writeNext(new String[]{"Subject", "MarkerName", "TrueGenos", "ImputedGeno", "IsMatch", "IsError", "IsMissing", "IsNonCalledRef", "AF", "IsHet", "GenotypeNumber"});

                Integer idx = 0;
                for (SampleSet ss : sets)
                {
                    idx++;
                    job.getLogger().info("Starting set " + idx + " of " + sets.size());
                    job.setStatus(PipelineJob.TaskStatus.running, "Set " + idx + " of " + sets.size());

                    File baseDir = new File(job.getJobSupport(FileAnalysisJobSupport.class).getAnalysisDirectory(), "Set-" + idx.toString());
                    if (!baseDir.exists())
                    {
                        baseDir.mkdirs();
                    }

                    //TODO: consider skipping this
                    buildCombinedVcf(runner, ss, inputFiles, ctx.getSequenceSupport(), job.getLogger(), baseDir, params, action, gatkPed);

                    job.setStatus(PipelineJob.TaskStatus.running, "Prepare Framework Marker Resources: " + idx + " of " + sets.size());
                    runner.prepareFrameworkResources(baseDir, alleleFreqDir, job.getLogger(), ss.wgsSampleIds, ss.imputedSampleIds);

                    job.setStatus(PipelineJob.TaskStatus.running, "Prepare Dense Marker Resources: " + idx + " of " + sets.size());
                    runner.prepareDenseResources(baseDir, alleleFreqDir, job.getLogger(), ss.wgsSampleIds, ss.imputedSampleIds);

                    Map<String, PedigreeRecord> pedigreeRecordMap = parsePedigree(gatkPed);
                    job.getLogger().debug("pedigree size: " + pedigreeRecordMap.size());

                    //now actually perform imputation
                    job.setStatus(PipelineJob.TaskStatus.running, "Imputing: " + idx + " of " + sets.size());
                    String callMethod = params.get("callMethod") != null ? params.getString("callMethod") : "1";
                    runner.processSet(baseDir, alleleFreqDir, job.getLogger(), ss.wgsSampleIds, ss.imputedSampleIds, callMethod);

                    job.setStatus(PipelineJob.TaskStatus.running, "Making VCF / Evaluating Accuracy");

                    //calculate relatives present in WGS per subject:
                    Map<String, Set<String>> relativesPresent = new TreeMap<>();
                    Map<String, Set<String>> wgsRelativesPresent = new TreeMap<>();
                    Set<PedigreeRecord> wgsSubjects = new HashSet<>();
                    Set<PedigreeRecord> allSubjects = new HashSet<>();
                    for (Pair<Integer, String> id : ss.wgsSampleIds)
                    {
                        wgsSubjects.add(pedigreeRecordMap.get(id.second));
                        allSubjects.add(pedigreeRecordMap.get(id.second));
                    }

                    for (Pair<Integer, String> id : ss.imputedSampleIds)
                    {
                        allSubjects.add(pedigreeRecordMap.get(id.second));
                    }

                    for (Pair<Integer, String> imputedSubj : ss.imputedSampleIds)
                    {
                        PedigreeRecord pr = pedigreeRecordMap.get(imputedSubj.second);
                        if (pr == null)
                        {
                            throw new PipelineJobException("unable to find pedigree record for id: [" + imputedSubj.second + "]");
                        }

                        relativesPresent.put(imputedSubj.second, pr.getRelativesPresent(allSubjects));
                        wgsRelativesPresent.put(imputedSubj.second, pr.getRelativesPresent(wgsSubjects));
                    }

                    job.setStatus(PipelineJob.TaskStatus.running, "Creating Combined VCF: " + idx + " of " + sets.size());

                    //TODO: make into a parameter
                    final double lowFreqThreshold = 0.05;
                    File vcf = new File(baseDir, job.getDescription() + ".imputed.vcf.gz");
                    if (vcf.exists())
                    {
                        vcf.delete();
                        File vcfIdx = new File(vcf.getPath() + ".tbi");
                        if (vcfIdx.exists())
                        {
                            vcfIdx.delete();
                        }
                    }

                    List<String> imputedIds = new ArrayList<String>();
                    for (Pair<Integer, String> pair : ss.imputedSampleIds)
                    {
                        imputedIds.add(pair.second);
                    }

                    //TODO: split into one VCF per sample
                    try (VariantContextWriter vcfWriter = ImputedVCFGenerator.getVariantWriter(vcf, genome, imputedIds))
                    {
                        for (String chr : runner.getDenseChrs())
                        {
                            appendToVCF(inputFiles, vcfWriter, job, runner, baseDir, ss, alleleFreqDir, lowFreqThreshold, idx, callMethod, relativesPresent, wgsRelativesPresent, writer, afWriter, chr);
                        }
                    }
                    action.addOutputIfNotPresent(vcf, "VCF File", false);

                    SequenceOutputFile so = new SequenceOutputFile();
                    so.setFile(vcf);
                    so.setName(vcf.getName());
                    so.setDescription("Imputed genotypes generated by GIGI, using call method: " + callMethod + ", job: " + job.getDescription());
                    so.setCategory("Imputed VCF");
                    ctx.addSequenceOutput(so);

                    runner.doCleanup(job.getLogger(), baseDir);
                }

                job.getLogger().info("cleaning up alleleFreqDir dir");
                File[] rawData = alleleFreqDir.listFiles();
                if (rawData != null && rawData.length > 0)
                {
                    for (int i = 0; i < rawData.length; i++)
                    {
                        File f = rawData[i];
                        Compress.compressGzip(f);
                        f.delete();
                    }
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }

        private File buildCombinedVcf(ImputationRunner runner, SampleSet ss, List<SequenceOutputFile> inputFiles, SequenceAnalysisJobSupport support, Logger log, File setBaseDir, JSONObject params, RecordedAction action, File gatkPed) throws PipelineJobException
        {
            File genotypeDir = new File(setBaseDir, "genotypes");
            if (!genotypeDir.exists())
            {
                genotypeDir.mkdirs();
            }

            Set<File> toDelete = new HashSet<>();

            Set<Integer> genomeIds = new HashSet<>();
            for (SequenceOutputFile o : inputFiles)
            {
                genomeIds.add(o.getLibrary_id());
            }
            ReferenceGenome genome = support.getCachedGenome(genomeIds.iterator().next());

            //this essentially allows resume mid-job
            File mergedVcf = new File(genotypeDir, "merged.vcf.gz");
            if (!mergedVcf.exists())
            {
                //subset input VCFs taking only samples needed per
                Map<Integer, Set<String>> samplesNeeded = new HashMap<>();
                for (Pair<Integer, String> pair : ss.wgsSampleIds)
                {
                    Set<String> samples = samplesNeeded.get(pair.first);
                    if (samples == null)
                    {
                        samples = new HashSet<>();
                    }

                    samples.add(pair.second);
                    samplesNeeded.put(pair.first, samples);
                }

                for (Pair<Integer, String> pair : ss.imputedSampleIds)
                {
                    Set<String> samples = samplesNeeded.get(pair.first);
                    if (samples == null)
                    {
                        samples = new HashSet<>();
                    }

                    samples.add(pair.second);
                    samplesNeeded.put(pair.first, samples);
                }

                createMergedVcfForSamples(samplesNeeded, genome, inputFiles, support, genotypeDir, toDelete, params, log, mergedVcf, null);
            }
            else
            {
                log.info("reusing existing file: " + mergedVcf.getName());
            }

            //then perform mendelian check
            File ret;
            if (params.optBoolean("skipMendelianCheck", false))
            {
                ret = mergedVcf;
            }
            else
            {
                try
                {
                    MendelianEvaluator me = new MendelianEvaluator(gatkPed);
                    File mendelianPass = new File(mergedVcf.getParentFile(), "merged.mendelianPass.vcf.gz");
                    File nonMendelianVcf = new File(mergedVcf.getParentFile(), "merged.mendelianViolations.vcf.gz");
                    File nonMendelianBed = new File(mergedVcf.getParentFile(), "merged.mendelianViolations.bed");
                    action.addOutputIfNotPresent(nonMendelianBed, "Mendelian Violations", false);

                    //essentially allows resume on failed jobs
                    if (!mendelianPass.exists())
                    {
                        log.info("identifying non-mendelian SNPs");
                        me.checkVcf(mergedVcf, mendelianPass, nonMendelianVcf, nonMendelianBed, log);
                    }
                    else
                    {
                        log.info("reusing existing file: " + mendelianPass.getName());
                    }

                    ret = mendelianPass;
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }
            }

            prepareGenotypeData(runner, ss, setBaseDir, log, ret, gatkPed);

            //TODO
//            for (File vcf : toDelete)
//            {
//                if (vcf != null)
//                {
//                    File index = new File(vcf.getPath() + ".idx");
//                    if (index.exists())
//                    {
//                        index.delete();
//                    }
//
//                    index = new File(vcf.getPath() + ".tbi");
//                    if (index.exists())
//                    {
//                        index.delete();
//                    }
//
//                    log.info("deleting temporary file: " + vcf.getPath());
//                    vcf.delete();
//                }
//            }

            return ret;
        }

        private void createMergedVcfForSamples(Map<Integer, Set<String>> samplesNeeded, ReferenceGenome genome, List<SequenceOutputFile> inputFiles, SequenceAnalysisJobSupport support, File outputDir, Set<File> toDelete, JSONObject params, Logger log, File mergedVcf, String suffix) throws PipelineJobException
        {
            if (mergedVcf.exists())
            {
                log.info("using existing merged VCF: " + mergedVcf.getPath());
                return;
            }

            List<File> subsetVcfs = new ArrayList<>();
            log.info("creating merged vcf: " + (suffix == null ? "" : suffix));
            for (Integer i : samplesNeeded.keySet())
            {
                log.info("\tfile id: " + i);
                for (String sample : samplesNeeded.get(i))
                {
                    log.info("\t\tsample name: " + sample);
                }
            }

            if (samplesNeeded.isEmpty())
            {
                log.warn("no samples found, cannot make merged vcf: " + suffix);
                return;
            }

            for (Integer rowId : samplesNeeded.keySet())
            {
                File inputVcf = null;
                for (SequenceOutputFile o : inputFiles)
                {
                    if (o.getRowid().equals(rowId))
                    {
                        inputVcf = o.getFile();
                        break;
                    }
                }

                if (inputVcf == null)
                {
                    throw new PipelineJobException("unable to find output file: " + rowId);
                }

                SelectVariantsWrapper sv = new SelectVariantsWrapper(log);
                File output = new File(outputDir, rowId + ".subset" + (suffix == null ? "" : "." + suffix) + ".vcf.gz");
                subsetVcfs.add(output);
                //todo
                //toDelete.add(output);
                List<String> args = new ArrayList<>();
                File denseMarkers = support.getCachedData(params.getInt("denseFile"));
                File frameworkMarkers = support.getCachedData(params.getInt("frameworkFile"));
                args.add("-L");
                args.add(denseMarkers.getPath());
                args.add("-L");
                args.add(frameworkMarkers.getPath());

                args.add("--exclude-filtered");
                args.add("--select-type-to-exclude");
                args.add("INDEL");

                for (String sn : samplesNeeded.get(rowId))
                {
                    args.add("-sn");
                    args.add(sn);
                }
                sv.execute(genome.getWorkingFastaFile(), inputVcf, output, args);
            }

            MergeVcfsAndGenotypesWrapper wrapper = new MergeVcfsAndGenotypesWrapper(log);
            wrapper.execute(genome.getWorkingFastaFile(), subsetVcfs, mergedVcf);
        }

        private void prepareGenotypeData(ImputationRunner runner, SampleSet ss, File setBaseDir, Logger log, File mergedVcf, File gatkPed) throws PipelineJobException
        {
            //create any resources needed per sample:
            Set<String> allIds = new HashSet<>();
            List<String> imputed = new ArrayList<>();

            for (Pair<Integer, String> pair : ss.wgsSampleIds)
            {
                allIds.add(pair.second);
            }

            for (Pair<Integer, String> pair : ss.imputedSampleIds)
            {
                imputed.add(pair.second);
                allIds.add(pair.second);
            }

            //write individual genotype data
            runner.prepareDenseGenotypeFiles(ss, mergedVcf, allIds, imputed, ImputationFileUtil.GiGiType.experimental, setBaseDir, log, gatkPed);
            runner.prepareFrameworkGenotypeFiles(ss, mergedVcf, allIds, ImputationFileUtil.GiGiType.experimental, setBaseDir, log, gatkPed);
        }

        public class SampleSet
        {
            public List<String> wgsSampleIdStrings;
            public List<String> imputedSampleIdStrings;
            public Map<String, Integer> sampleFileMap;
            public List<Pair<Integer, String>> wgsSampleIds;
            public List<Pair<Integer, String>> imputedSampleIds;
            public List<Pair<Integer, String>> referenceSamples;

            public SampleSet(JSONArray arr)
            {
                sampleFileMap = new HashMap<>();
                wgsSampleIds = new ArrayList<>();
                wgsSampleIdStrings = new ArrayList<>();
                JSONArray completeSet = arr.getJSONArray(0);
                for (int j = 0; j < completeSet.length(); j++)
                {
                    String[] s = completeSet.getString(j).split("\\|\\|");
                    wgsSampleIds.add(Pair.of(Integer.parseInt(s[0]), s[1]));
                    wgsSampleIdStrings.add(s[1]);
                    sampleFileMap.put(s[1], Integer.parseInt(s[0]));
                }

                referenceSamples = new ArrayList<>();
                imputedSampleIds = new ArrayList<>();
                imputedSampleIdStrings = new ArrayList<>();
                JSONArray imputeSet = arr.getJSONArray(1);

                for (int j = 0; j < imputeSet.length(); j++)
                {
                    String[] s = imputeSet.getString(j).split("\\|\\|");
                    imputedSampleIds.add(Pair.of(Integer.parseInt(s[0]), s[1]));
                    imputedSampleIdStrings.add(s[1]);
                    sampleFileMap.put(s[1], Integer.parseInt(s[0]));
                }

                JSONArray refSet = arr.length() > 2 ? arr.getJSONArray(2) : null;
                if (refSet != null)
                {
                    for (int j = 0; j < refSet.length(); j++)
                    {
                        String[] i = refSet.getString(j).split("\\|\\|");
                        referenceSamples.add(Pair.of(Integer.parseInt(i[0]), i[1]));
                    }
                }

                wgsSampleIdStrings.removeAll(imputedSampleIdStrings);
            }

            public Pair<Integer, String> getImputationInputForImputedSample(String sampleName)
            {
                for (Pair<Integer, String> p : imputedSampleIds)
                {
                    if (p.second.equals(sampleName))
                    {
                        return p;
                    }
                }

                return null;
            }

            public Pair<Integer, String> getReferenceForImputedSample(String sampleName)
            {
                for (Pair<Integer, String> p : referenceSamples)
                {
                    if (p.second.equals(sampleName))
                    {
                        return p;
                    }
                }

                for (Pair<Integer, String> p : wgsSampleIds)
                {
                    if (p.second.equals(sampleName))
                    {
                        return p;
                    }
                }

                return null;
            }
        }

        private List<SampleSet> getSampleSets(JSONObject params)
        {
            List<SampleSet> ret = new ArrayList<>();

            if (params.containsKey("sampleSets"))
            {
                for (Object o : params.getJSONArray("sampleSets").toArray())
                {
                    ret.add(new SampleSet((JSONArray) o));
                }
            }

            return ret;
        }

        private Map<String, PedigreeRecord> parsePedigree(File ped) throws PipelineJobException
        {
            try (BufferedReader reader = Readers.getReader(ped))
            {
                Map<String, PedigreeRecord> ret = new HashMap<>();
                String line;
                while ((line = reader.readLine()) != null)
                {
                    String[] tokens = line.split(" ");
                    if (tokens.length < 2)
                    {
                        continue;
                    }

                    PedigreeRecord r = new PedigreeRecord();
                    r.setSubjectName(tokens[1]);
                    r.setFather("0".equals(tokens[2]) ? null : tokens[2]);
                    r.setMother("0".equals(tokens[3]) ? null : tokens[3]);

                    ret.put(r.getSubjectName(), r);
                }

                return ret;
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }

        private void addReader(Map<Integer, VCFFileReader> readerMap, String sampleName, Pair<Integer, String> pair, List<SequenceOutputFile> inputFiles, Map<Integer, String> referenceVcfNameMap) throws PipelineJobException
        {
            if (readerMap.containsKey(pair.first))
            {
                return;
            }

            File referenceVCF = null;
            for (SequenceOutputFile o : inputFiles)
            {
                if (o.getRowid().equals(pair.first))
                {
                    referenceVCF = o.getFile();
                    referenceVcfNameMap.put(pair.first, referenceVCF.getName());
                    break;
                }
            }

            if (referenceVCF == null)
            {
                throw new PipelineJobException("Unable to find VCF for sample: " + sampleName);
            }

            File referenceVCFIdx = new File(referenceVCF.getPath() + ".tbi");
            if (!referenceVCFIdx.exists())
            {
                referenceVCFIdx = new File(referenceVCF.getPath() + ".idx");
                if (!referenceVCFIdx.exists())
                {
                    throw new PipelineJobException("Unable to find VCF index for: " + referenceVCF.getPath());
                }
            }

            readerMap.put(pair.first, new VCFFileReader(referenceVCF, referenceVCFIdx, true));
        }

        private void appendToVCF(List<SequenceOutputFile> inputFiles, VariantContextWriter vcfWriter, PipelineJob job, ImputationRunner runner, File baseDir, SampleSet ss, File alleleFreqDir, double lowFreqThreshold, Integer idx, String callMethod, Map<String, Set<String>> relativesPresent, Map<String, Set<String>> wgsRelativesPresent, CSVWriter writer, CSVWriter afWriter, String chr) throws PipelineJobException, IOException
        {
            job.getLogger().info("processing results into VCF: " + chr);
            Set<Integer> distinctLowAfMarkers = new HashSet<>();

            int denseIntervalIdx = -1;
            Map<String, SubjectCounter> counterMap = new HashMap<>();

            for (List<Interval> denseIntervalList : runner.getDenseIntervalMapBatched().get(chr))
            {
                denseIntervalIdx++;
                job.getLogger().info("processing dense marker batch " + denseIntervalIdx + " of " + runner.getDenseIntervalMapBatched().get(chr).size());
                File gigiOutDir = new File(baseDir, chr);
                gigiOutDir = new File(gigiOutDir, String.valueOf(denseIntervalIdx));

                File imputed = new File(gigiOutDir, "impute-" + denseIntervalIdx + ".geno");
                if (!imputed.exists())
                {
                    job.getLogger().error("Unable to find imputed genotypes for batch: " + denseIntervalIdx + ", skipping");
                    job.getLogger().error("start: " + runner.getDenseIntervalMapBatched().get(chr).get(denseIntervalIdx).get(0).getStart());
                    job.getLogger().error("end: " + runner.getDenseIntervalMapBatched().get(chr).get(denseIntervalIdx).get(runner.getDenseIntervalMapBatched().get(chr).get(denseIntervalIdx).size() - 1).getStart());
                    continue;
                }

                File imputedProbabilities = new File(gigiOutDir, "impute-" + denseIntervalIdx + ".prob");
                if (!imputedProbabilities.exists())
                {
                    job.getLogger().error("Unable to find imputed probabilities for batch: " + denseIntervalIdx + ", skipping.  expected: " + imputedProbabilities.getPath());
                    job.getLogger().error("start: " + runner.getDenseIntervalMapBatched().get(chr).get(denseIntervalIdx).get(0).getStart());
                    job.getLogger().error("end: " + runner.getDenseIntervalMapBatched().get(chr).get(denseIntervalIdx).get(runner.getDenseIntervalMapBatched().get(chr).get(denseIntervalIdx).size() - 1).getStart());
                    continue;
                }

                File consistentIV = new File(gigiOutDir, "impute-" + denseIntervalIdx + ".consistentIV");
                if (!consistentIV.exists())
                {
                    job.getLogger().error("Unable to find imputed consistentIV for batch: " + denseIntervalIdx + ", skipping.  expected: " + consistentIV.getPath());
                    job.getLogger().error("start: " + runner.getDenseIntervalMapBatched().get(chr).get(denseIntervalIdx).get(0).getStart());
                    job.getLogger().error("end: " + runner.getDenseIntervalMapBatched().get(chr).get(denseIntervalIdx).get(runner.getDenseIntervalMapBatched().get(chr).get(denseIntervalIdx).size() - 1).getStart());
                    continue;
                }

                //gather allele freqs
                File freqs = ImputationFileUtil.getAlleleFreqFile(alleleFreqDir, ImputationFileUtil.MarkerType.dense, chr, denseIntervalIdx);
                if (!freqs.exists())
                {
                    throw new PipelineJobException("Unable to find frequency file: " + freqs.getPath());
                }

                List<List<Double>> alleleFreqs = new ArrayList<>();
                try (BufferedReader freqReader = Readers.getReader(freqs))
                {
                    String line;
                    while ((line = freqReader.readLine()) != null)
                    {
                        String[] split = line.split(" ");
                        List<Double> list = new ArrayList<>();
                        for (String token : split)
                        {
                            list.add(ConvertHelper.convert(token, Double.class));
                        }

                        alleleFreqs.add(list);
                    }
                }

                try (BufferedReader imputedReader = new BufferedReader(Readers.getReader(imputed)); BufferedReader probabilityReader = Readers.getReader(imputedProbabilities); BufferedReader consistentIVReader = Readers.getReader(consistentIV))
                {
                    String imputedLine;
                    String probabilityLine;
                    String consistentIVLine;
                    int markerNumber1Based = -1;  //1-based
                    List<String> subjectOrder = new ArrayList<>();

                    Map<Integer, VCFFileReader> readerMap = new HashMap<>();
                    Map<Integer, String> referenceVcfNameMap = new HashMap<>();
                    try
                    {
                        for (String sampleName : ss.imputedSampleIdStrings)
                        {
                            Pair<Integer, String> pair = ss.getReferenceForImputedSample(sampleName);

                            int fileId = ss.sampleFileMap.get(sampleName);
                            if (!pair.first.equals(fileId))
                            {
                                job.getLogger().info("using alternate reference data for sample: " + sampleName);
                            }

                            addReader(readerMap, sampleName, pair, inputFiles, referenceVcfNameMap);

                            //also add reader for raw imputation data
                            addReader(readerMap, sampleName, ss.getImputationInputForImputedSample(sampleName), inputFiles, referenceVcfNameMap);
                        }

                        OUTER:
                        while ((imputedLine = imputedReader.readLine()) != null)
                        {
                            markerNumber1Based++;

                            probabilityLine = probabilityReader.readLine();
                            if (probabilityLine == null)
                            {
                                throw new PipelineJobException("Too few lines in probability file: " + imputedProbabilities.getPath());
                            }

                            consistentIVLine = consistentIVReader.readLine();
                            if (consistentIVLine == null)
                            {
                                throw new PipelineJobException("Too few lines in consistentIV file: " + consistentIV.getPath());
                            }

                            if (markerNumber1Based == 0)
                            {
                                //handle header
                                String[] headerLine = imputedLine.split("( )+");
                                for (String token : headerLine)
                                {
                                    if ("id".equals(token))
                                    {
                                        continue;
                                    }

                                    if (!subjectOrder.contains(token))
                                    {
                                        subjectOrder.add(token);
                                    }
                                }

                                continue;
                            }

                            String[] markerData = imputedLine.split("( )+");
                            String[] probabilityData = probabilityLine.split("(\\t)+");
                            //account for sample name
                            List<String> tokens = new ArrayList<>(Arrays.asList(probabilityData[0].trim().split(" ")));
                            String markerName = tokens.remove(0);
                            probabilityData[0] = StringUtils.join(tokens, " ");

                            Interval denseMarker = runner.getDensePositionByIndex(chr, denseIntervalIdx, markerNumber1Based);

                            boolean overlapsFramework = false;
                            if (runner.getFrameworkIntervalMap().containsKey(chr))
                            {
                                for (Interval bl : runner.getFrameworkIntervalMap().get(chr))
                                {
                                    if (bl.intersects(denseMarker))
                                    {
                                        overlapsFramework = true;
                                        break;
                                    }
                                }
                            }

                            Map<Integer, VariantContext> variantContextMap = new HashMap<>();
                            for (Integer fileId : readerMap.keySet())
                            {
                                try (CloseableIterator<VariantContext> it = readerMap.get(fileId).query(chr, denseMarker.getStart(), denseMarker.getEnd()))
                                {
                                    if (it.hasNext())
                                    {
                                        variantContextMap.put(fileId, it.next());
                                    }
                                }
                            }

                            VariantContextBuilder vcb = new VariantContextBuilder();
                            vcb.chr(chr).start(denseMarker.getStart()).stop(denseMarker.getEnd());
                            List<Allele> alleles = new ArrayList<>();
                            int alleleIdx = 0;
                            List<String> alleleToBase = runner.getDenseAlleleToBaseMap(chr, denseIntervalIdx, markerNumber1Based);
                            for (String base : alleleToBase)
                            {
                                alleles.add(Allele.create(base, alleleIdx == 0));
                                alleleIdx++;
                            }
                            vcb.alleles(alleles);

                            List<Genotype> genotypes = new ArrayList<>();

                            int genotypePosition = 1;
                            int incorrectImputations = 0;
                            int notImputed = 0;
                            int correctImputations = 0;
                            int totalNonCallRef = 0;
                            int subjectNumber = 0;
                            int consistentIVs = Integer.parseInt(consistentIVLine);
                            double ivPct = (double)consistentIVs / 10000.0;
                            if (ivPct < 0.75)
                            {
                                job.getLogger().warn("low fraction of consistent IVs: " + ivPct + " at " + chr + " " + markerName);
                            }

                            for (String subject : subjectOrder)
                            {
                                if (!ss.imputedSampleIdStrings.contains(subject))
                                {
                                   // job.getLogger().info("skipping non-imputed subject: " + subject);
                                    genotypePosition += 2;
                                    subjectNumber++;

                                    continue;
                                }

                                Integer fileId = ss.sampleFileMap.get(subject);
                                if (fileId == null)
                                {
                                    throw new PipelineJobException("unable to find file matching sample: " + subject);
                                }

                                Pair<Integer, String> refPair = ss.getReferenceForImputedSample(subject);
                                if (refPair == null)
                                {
                                    job.getLogger().info("no reference available for sample: " + subject + ", skipping");
                                    genotypePosition += 2;
                                    subjectNumber++;

                                    continue;
                                }

                                Pair<Integer, String> imputedPair = ss.getImputationInputForImputedSample(subject);
                                Genotype refGenotype = variantContextMap.containsKey(refPair.first) ? variantContextMap.get(refPair.first).getGenotype(subject) : null;
                                boolean isSiteFiltered = variantContextMap.containsKey(refPair.first) && variantContextMap.get(refPair.first).isFiltered();

                                Genotype imputationInputGenotype = variantContextMap.containsKey(imputedPair.first) ? variantContextMap.get(imputedPair.first).getGenotype(subject) : null;

                                if (!counterMap.containsKey(subject))
                                {
                                    job.getLogger().info("validating imputed subject: " + subject);
                                    counterMap.put(subject, new SubjectCounter(subject, afWriter));
                                }

                                SubjectCounter counter = counterMap.get(subject);

                                List<String> imputedGenos = new ArrayList<>(Arrays.asList(markerData[genotypePosition], markerData[genotypePosition + 1]));
                                Collections.sort(imputedGenos);
                                genotypePosition++;
                                genotypePosition++;

                                List<Double> probabilities = new ArrayList<>();

                                String[] splitProbs = probabilityData[subjectNumber].trim().split(" ");
                                subjectNumber++;
                                //if (splitProbs.length != expectedProbabilities)
                                //{
                                //    job.getLogger().error(subject + ": unexpected probability count. " + splitProbs.length + ", expected: " + expectedProbabilities + ", marker: " + markerName + ". [" + StringUtils.join(splitProbs, ",") + "]"+ ", alleles: [" + StringUtils.join(alleleToBase, ",") + "]");
                                //}

                                for (String p : splitProbs)
                                {
                                    probabilities.add(Double.parseDouble(StringUtils.trimToNull(p)));
                                }

                                List<String> trueGenos = new ArrayList<>();
                                if (refGenotype == null || refGenotype.isNoCall())
                                {
                                    trueGenos.add("-1");
                                    trueGenos.add("-1");
                                }
                                else if (refGenotype.isFiltered() || isSiteFiltered)
                                {
                                    trueGenos.add("-1");
                                    trueGenos.add("-1");
                                }
                                else
                                {
                                    //NOTE: the reference might have indels, but we filtered these out already
                                    for (Allele rAllele : refGenotype.getAlleles())
                                    {
                                        if (!alleleToBase.contains(rAllele.getBaseString()))
                                        {
                                            //see comparable issue in PrepareGenotypeFilesForChrRunner
                                            //this indicates we have alleles in the data not present in our AF source
                                            job.getLogger().info(subject + ": reference genotype base not found in reference allele set: " + rAllele.getBaseString() + ".  reference set: " + StringUtils.join(alleleToBase, ";") + ".  marker: " + markerData[0] + ", vcf: " + referenceVcfNameMap.get(refPair.first));
                                            counter.refGenotypeNotFound++;
                                            trueGenos.add("-1");

                                            List<Allele> al = new ArrayList<>(vcb.getAlleles());
                                            boolean found = false;
                                            for (Allele a : al)
                                            {
                                                if (rAllele.getBaseString().equals(a.getBaseString()))
                                                {
                                                    found = true;
                                                    break;
                                                }
                                            }

                                            if (!found)
                                            {
                                                al.add(Allele.create(rAllele.getBaseString(), false));
                                                vcb.alleles(al);
                                            }
                                        }
                                        else
                                        {
                                            Integer refIdx = alleleToBase.indexOf(rAllele.getBaseString());
                                            refIdx++; //1-based

                                            trueGenos.add(refIdx.toString());
                                        }
                                    }

                                    //if homozygous, duplicate genotype
                                    if (trueGenos.size() == 1)
                                    {
                                        trueGenos.add(trueGenos.get(0));
                                    }

                                    Collections.sort(trueGenos);
                                }

                                boolean isIncorrect = false;
                                for (String g : trueGenos)
                                {
                                    if (g.equals("-1"))
                                    {
                                        totalNonCallRef++;
                                    }
                                }

                                if (imputedGenos.contains("0") && !"0".equals(imputedGenos.get(1)))
                                {
                                    counter.sitesHalfImputed++;
                                }

                                SubjectCounter.SiteSummary siteSummary = counter.addGenos(imputedGenos, trueGenos, markerNumber1Based, lowFreqThreshold, distinctLowAfMarkers, alleleFreqs, overlapsFramework, refGenotype, imputationInputGenotype, markerName, subject, job.getLogger(), alleleToBase);
                                correctImputations += siteSummary.matching;
                                incorrectImputations += siteSummary.errors;
                                notImputed += siteSummary.notImputed;

                                //write to VCF
                                genotypes.add(ImputedVCFGenerator.generateGenotype(subject, probabilities, imputedGenos, alleleToBase, refGenotype, imputationInputGenotype, trueGenos, referenceVcfNameMap.get(refPair.first), isIncorrect));
                            }

                            vcb.genotypes(genotypes);
                            vcb.attribute(ImputedVCFGenerator.IMPUTATION_SKIPPED_GENOTYPES, notImputed);
                            vcb.attribute(ImputedVCFGenerator.INCORRECT_IMPUTATION_GENOTYPES, incorrectImputations);
                            vcb.attribute(ImputedVCFGenerator.CORRECT_IMPUTATION_GENOTYPES, correctImputations);
                            vcb.attribute(ImputedVCFGenerator.TOTAL_NON_CALLED_REF, totalNonCallRef);

                            vcb.attribute(ImputedVCFGenerator.CONSISTENT_IVs, consistentIVs);

                            for (Genotype g : genotypes)
                            {
                                for (Allele a : g.getAlleles())
                                {
                                    if (!vcb.getAlleles().contains(a) && !a.isNoCall())
                                    {
                                        job.getLogger().error("allele not found: " + a.getBaseString() + " at position " + chr + ": " + runner.getDensePositionByIndex(chr, denseIntervalIdx, markerNumber1Based).getStart());
                                        job.getLogger().error(StringUtils.join(alleleToBase, ";"));
                                        throw new PipelineJobException("allele not found");
                                    }
                                }
                            }

                            vcfWriter.add(vcb.make());

                            if (markerNumber1Based % 5000 == 0)
                            {
                                job.getLogger().info("processed " + markerNumber1Based + " markers");
                            }
                        }
                    }
                    finally
                    {
                        for (VCFFileReader r : readerMap.values())
                        {
                            r.close();
                        }
                    }
                }

                imputed.delete();
                imputedProbabilities.delete();
            }

            job.getLogger().info("Accuracies:");

            List<Double> accuracies = new ArrayList<>();
            for (String subject : counterMap.keySet())
            {
                SubjectCounter counter = counterMap.get(subject);
                counter.writeSummary(writer, runner, ss, chr, subject, idx, callMethod, relativesPresent, wgsRelativesPresent, job.getDescription());

                job.getLogger().info(subject + ":" + counter.getAccuracy() + " (" + counter.genotypesMatchingRef + " / " + counter.incorrectImputation + " / " + counter.genotypeWithRefNotImputed + ")");
                Double a = counter.getAccuracy();
                if (a != null)
                {
                    accuracies.add(a);
                }
            }

            job.getLogger().info("average accuracy: " + StatUtils.mean(ArrayUtils.toPrimitive(accuracies.toArray(new Double[accuracies.size()]))));
            job.getLogger().info("distinct low AF het markers with data for " + chr + ": " + distinctLowAfMarkers.size());
        }
    }

    private static List<PedigreeRecord> generatePedigree(PipelineJob job, JSONObject params)
    {
        Set<String> sampleNames = new HashSet<>();
        Map<String, String> subjectToReadsetNameMap = new HashMap<>();
        TableInfo subjectTable = QueryService.get().getUserSchema(job.getUser(), (job.getContainer().isWorkbook() ? job.getContainer().getParent() : job.getContainer()), "laboratory").getTable("subjects");
        TableInfo readsetTable = QueryService.get().getUserSchema(job.getUser(), job.getContainer(), "sequenceanalysis").getTable("sequence_readsets");
        if (params.containsKey("sampleSets"))
        {
            for (Object o : params.getJSONArray("sampleSets").toArray())
            {
                JSONArray arr = (JSONArray) o;
                for (int i = 0; i < arr.length(); i++)
                {
                    JSONArray set = arr.getJSONArray(i);
                    for (int j = 0; j < set.length(); j++)
                    {
                        //try to find this in the subjects table
                        String[] tokens = set.getString(j).split("\\|\\|");
                        resolveSubject(tokens[0], set.getString(j), sampleNames, subjectToReadsetNameMap, subjectTable, readsetTable, job);
                    }
                }
            }
        }

        String additionalSubjects = StringUtils.trimToNull(params.optString("additionalSubjects"));
        if (additionalSubjects != null)
        {
            job.getLogger().info("will attempt to include the following additional IDs in the pedigree: " + additionalSubjects);
            String[] subjects = additionalSubjects.split(",");
            for (String s : subjects)
            {
                resolveSubject(s, s, sampleNames, subjectToReadsetNameMap, subjectTable, readsetTable, job);
            }
        }

        List<PedigreeRecord> ret = SequenceAnalysisService.get().generatePedigree(sampleNames, job.getContainer(), job.getUser());
        job.getLogger().info("total subjects: " + ret.size());

        return ret;
    }

    private static void resolveSubject(String id, String setName, Set<String> sampleNames, Map<String, String> subjectToReadsetNameMap, TableInfo subjectTable, TableInfo readsetTable, PipelineJob job)
    {
        if (new TableSelector(subjectTable, new SimpleFilter(FieldKey.fromString("subjectname"), id), null).exists())
        {
            sampleNames.add(id);
        }
        else
        {
            //if not, see if it matches a readset and resolve subject from there
            TableSelector ts = new TableSelector(readsetTable, PageFlowUtil.set("subjectId"), new SimpleFilter(FieldKey.fromString("name"), id), null);
            String subjectId = ts.getObject(String.class);
            if (subjectId != null)
            {
                job.getLogger().info("resolving readset: " + setName + " to subject: " + subjectId);
                sampleNames.add(subjectId);
                if (subjectToReadsetNameMap.containsKey(subjectId) && !subjectToReadsetNameMap.get(subjectId).equals(setName))
                {
                    job.getLogger().error("more than one readset present using the same subject ID.  this will cause an inaccurate or ineference genotype base not foucomplete pedigree.");
                }

                subjectToReadsetNameMap.put(subjectId, setName);
            }
        }
    }
}