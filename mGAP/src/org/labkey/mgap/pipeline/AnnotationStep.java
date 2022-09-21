package org.labkey.mgap.pipeline;

import htsjdk.samtools.util.Interval;
import htsjdk.variant.vcf.VCFFileReader;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.json.old.JSONObject;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.DbSchemaType;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.Sort;
import org.labkey.api.data.TableSelector;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.exp.api.ExperimentService;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.pipeline.AbstractVariantProcessingStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStep;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStep;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStepOutputImpl;
import org.labkey.api.sequenceanalysis.run.AbstractCommandPipelineStep;
import org.labkey.api.sequenceanalysis.run.SelectVariantsWrapper;
import org.labkey.api.sequenceanalysis.run.SimpleScriptWrapper;
import org.labkey.api.util.FileUtil;
import org.labkey.api.util.PageFlowUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by bimber on 5/2/2017.
 */
public class AnnotationStep extends AbstractCommandPipelineStep<CassandraRunner> implements VariantProcessingStep
{
    public static final String GRCH37 = "genome37";
    private static final String CLINVAR_VCF = "clinvar37";
    public static final String CHAIN_FILE = "CHAIN_FILE";

    public AnnotationStep(PipelineStepProvider provider, PipelineContext ctx)
    {
        super(provider, ctx, new CassandraRunner(ctx.getLogger()));
    }

    public static class Provider extends AbstractVariantProcessingStepProvider<AnnotationStep> implements VariantProcessingStep.SupportsScatterGather
    {
        public Provider()
        {
            super("AnnotateVariants", "Annotate VCF for mGAP", "VCF Annotation", "This will annotate an input NHP VCF using human ClinVar and Cassandra annotations.  This jobs will automatically look for chain files based on the source VCF genome and GRCh37/38 targets and will fail if these are not found.", Arrays.asList(
                    ToolParameterDescriptor.createExpDataParam(CLINVAR_VCF, "Clinvar 2.0 VCF (GRCh37)", "This is the DataId of the VCF containing human Clinvar variants, which should use the GRCh37 genome. After liftover of the rhesus data, any matching variants are annotated.", "ldk-expdatafield", new JSONObject()
                    {{
                        put("allowBlank", false);
                    }}, null),
                    ToolParameterDescriptor.create(GRCH37, "GRCh37 Genome", "The genome that matches human GRCh37.", "ldk-simplelabkeycombo", new JSONObject()
                    {{
                        put("width", 400);
                        put("schemaName", "sequenceanalysis");
                        put("queryName", "reference_libraries");
                        put("containerPath", "js:Laboratory.Utils.getQueryContainerPath()");
                        put("filterArray", "js:[LABKEY.Filter.create('datedisabled', null, LABKEY.Filter.Types.ISBLANK)]");
                        put("displayField", "name");
                        put("valueField", "rowid");
                        put("allowBlank", false);
                    }}, null),
                    ToolParameterDescriptor.create("dropFiltered", "Drop Filtered Sites", "If checked, filtered sites will be discarded, which can substantially improve speed.", "checkbox", new JSONObject()
                    {{
                        put("checked", true);
                    }}, true)
            ), new LinkedHashSet<String>(Arrays.asList("ldk/field/ExpDataField.js")), null);
        }

        @Override
        public PipelineStep create(PipelineContext context)
        {
            return new AnnotationStep(this, context);
        }
    }


    @Override
    public void init(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles) throws UnsupportedOperationException, PipelineJobException
    {
        Set<Integer> genomeIds = new HashSet<>();
        for (SequenceOutputFile so : inputFiles)
        {
            genomeIds.add(so.getLibrary_id());
        }

        if (genomeIds.size() != 1)
        {
            throw new PipelineJobException("All inputs must be from the same genome");
        }

        int sourceGenome = genomeIds.iterator().next();

        //cache references:
        job.getLogger().debug("Caching references");
        support.cacheGenome(SequenceAnalysisService.get().getReferenceGenome(sourceGenome, job.getUser()));
        int genomeId = getProvider().getParameterByName(GRCH37).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        support.cacheGenome(SequenceAnalysisService.get().getReferenceGenome(genomeId, job.getUser()));

        //find chain files:
        job.getLogger().debug("Caching chain file");
        findChainFile(sourceGenome, genomeId, support, job);
    }

    @Override
    public Output processVariants(File inputVCF, File outputDirectory, ReferenceGenome genome, @Nullable List<Interval> intervals) throws PipelineJobException
    {
        VariantProcessingStepOutputImpl output = new VariantProcessingStepOutputImpl();

        File clinvarVCF = getPipelineCtx().getSequenceSupport().getCachedData(getProvider().getParameterByName(CLINVAR_VCF).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class));
        ReferenceGenome grch37Genome = getPipelineCtx().getSequenceSupport().getCachedGenome(getProvider().getParameterByName(GRCH37).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class));
        Integer chainFileId = getPipelineCtx().getSequenceSupport().getCachedObject(CHAIN_FILE, Integer.class);
        File chainFile = getPipelineCtx().getSequenceSupport().getCachedData(chainFileId);

        getPipelineCtx().getLogger().info("processing file: " + inputVCF.getName());

        ReferenceGenome originalGenome = getPipelineCtx().getSequenceSupport().getCachedGenome(genome.getGenomeId());

        output.addInput(inputVCF, "Input VCF");
        output.addInput(new File(inputVCF.getPath() + ".tbi"), "Input VCF Index");

        //drop genotypes so all subsequent steps are faster
        int totalSubjects;
        try (VCFFileReader reader = new VCFFileReader(inputVCF))
        {
            totalSubjects = reader.getFileHeader().getSampleNamesInOrder().size();
        }

        boolean needToSubsetToInterval = intervals != null && !intervals.isEmpty();
        boolean dropGenotypes = totalSubjects > 10;
        boolean dropFiltered = getProvider().getParameterByName("dropFiltered").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Boolean.class);

        //This flag exists to allow in-flight jobs to be reworked to include a sample.  it should eventually be removed.
        boolean forceRecreate = false;

        File currentVcf = inputVCF;
        if (dropGenotypes || dropFiltered)
        {
            if (dropGenotypes)
                getPipelineCtx().getLogger().info("dropping most genotypes prior to liftover for performance reasons.  a single is retained since cassandra requires one.");
            if (dropFiltered)
                getPipelineCtx().getLogger().info("dropping filtered sites");

            File subset = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".subset.vcf.gz");

            //NOTE: this check exists to correct in-flight jobs created using --sites-only-vcf-output.  It should eventually be removed.
            if (subset.exists())
            {
                try (VCFFileReader reader = new VCFFileReader(subset))
                {
                    if (reader.getFileHeader().getGenotypeSamples().isEmpty())
                    {
                        getPipelineCtx().getLogger().info("A VCF appears to have been created with --sites-only.  Will overwrite these using an output with a single sample for Cassandra");
                        forceRecreate = true;
                    }
                }
            }

            List<String> selectArgs = new ArrayList<>();
            if (dropGenotypes)
            {
                //NOTE: Cassandra requires at least one genotype, so instead of --sites-only-vcf-output, subset to first sample only
                String firstSample;
                try (VCFFileReader reader = new VCFFileReader(inputVCF))
                {
                    firstSample = reader.getFileHeader().getGenotypeSamples().get(0);
                }

                selectArgs.add("-sn");
                selectArgs.add(firstSample);
            }

            if (dropFiltered)
            {
                selectArgs.add("--exclude-filtered");
            }

            if (needToSubsetToInterval)
            {
                for (Interval interval : intervals)
                {
                    selectArgs.add("-L");
                    selectArgs.add(interval.getContig() + ":" + interval.getStart() + "-" + interval.getEnd());
                }
                needToSubsetToInterval = false;
            }

            if (forceRecreate || !indexExists(subset))
            {
                SelectVariantsWrapper wrapper = new SelectVariantsWrapper(getPipelineCtx().getLogger());
                wrapper.execute(originalGenome.getWorkingFastaFile(), inputVCF, subset, selectArgs);
            }
            else
            {
                getPipelineCtx().getLogger().info("resuming with existing file: " + subset.getPath());
            }

            output.addOutput(subset, "VCF Subset");
            output.addIntermediateFile(subset);
            output.addIntermediateFile(new File(subset.getPath() + ".tbi"));

            currentVcf = subset;

            getPipelineCtx().getJob().getLogger().info("total variants: " + SequenceAnalysisService.get().getVCFLineCount(currentVcf, getPipelineCtx().getJob().getLogger(), false));
            getPipelineCtx().getJob().getLogger().info("passing variants: " + SequenceAnalysisService.get().getVCFLineCount(currentVcf, getPipelineCtx().getJob().getLogger(), true));
        }
        else
        {
            getPipelineCtx().getLogger().info("no subsetting of genotypes or filtered sites necessary");

            if (needToSubsetToInterval)
            {
                List<String> selectArgs = new ArrayList<>();
                getPipelineCtx().getLogger().info("subsetting VCF by interval");
                for (Interval interval : intervals)
                {
                    selectArgs.add("-L");
                    selectArgs.add(interval.getContig() + ":" + interval.getStart() + "-" + interval.getEnd());
                }
                needToSubsetToInterval = false;

                File intervalSubset = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".intervalSubset.vcf.gz");
                if (forceRecreate || !indexExists(intervalSubset))
                {
                    SelectVariantsWrapper wrapper = new SelectVariantsWrapper(getPipelineCtx().getLogger());
                    wrapper.execute(originalGenome.getWorkingFastaFile(), inputVCF, intervalSubset, selectArgs);
                }
                else
                {
                    getPipelineCtx().getLogger().info("resuming with existing file: " + intervalSubset.getPath());
                }

                output.addOutput(intervalSubset, "VCF Subset");
                output.addIntermediateFile(intervalSubset);
                output.addIntermediateFile(new File(intervalSubset.getPath() + ".tbi"));

                currentVcf = intervalSubset;

                getPipelineCtx().getJob().getLogger().info("total variants: " + SequenceAnalysisService.get().getVCFLineCount(currentVcf, getPipelineCtx().getJob().getLogger(), false));
                getPipelineCtx().getJob().getLogger().info("passing variants: " + SequenceAnalysisService.get().getVCFLineCount(currentVcf, getPipelineCtx().getJob().getLogger(), true));
            }
        }

        //lift to target genome
        getPipelineCtx().getLogger().info("lift to genome: " + grch37Genome.getGenomeId());

        File liftedToGRCh37 = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(currentVcf.getName()) + ".liftTo" + grch37Genome.getGenomeId() + ".vcf.gz");
        File liftoverRejects = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(currentVcf.getName()) + ".liftoverReject" + grch37Genome.getGenomeId() + ".vcf.gz");
        if (forceRecreate || !indexExists(liftoverRejects) || !indexExists(liftedToGRCh37))
        {
            LiftoverVcfRunner liftoverVcfRunner = new LiftoverVcfRunner(getPipelineCtx().getLogger());
            liftoverVcfRunner.doLiftover(currentVcf, chainFile, grch37Genome.getWorkingFastaFile(), liftoverRejects, liftedToGRCh37, 0.95);
        }
        else
        {
            getPipelineCtx().getLogger().info("resuming with existing file: " + liftedToGRCh37.getPath());
        }
        output.addOutput(liftedToGRCh37, "VCF Lifted to GRCh37");
        output.addIntermediateFile(liftedToGRCh37);
        output.addIntermediateFile(new File(liftedToGRCh37.getPath() + ".tbi"));

        //annotate with clinvar
        getPipelineCtx().getLogger().info("annotating with ClinVar 2.0");
        File clinvarAnnotated = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(liftedToGRCh37.getName()) + ".cv.vcf.gz");
        if (forceRecreate || !indexExists(clinvarAnnotated))
        {
            ClinvarAnnotatorRunner cvRunner = new ClinvarAnnotatorRunner(getPipelineCtx().getLogger());
            cvRunner.execute(liftedToGRCh37, clinvarVCF, clinvarAnnotated);
        }
        else
        {
            getPipelineCtx().getLogger().info("resuming with existing file: " + clinvarAnnotated.getPath());
        }
        output.addOutput(clinvarAnnotated, "VCF Annotated With ClinVar2.0");
        output.addIntermediateFile(clinvarAnnotated);
        output.addIntermediateFile(new File(clinvarAnnotated.getPath() + ".tbi"));

        //annotate with cassandra
        getPipelineCtx().getLogger().info("annotating with Cassandra");
        String basename = SequenceAnalysisService.get().getUnzippedBaseName(liftedToGRCh37.getName()) + ".cassandra";
        File cassandraAnnotated = new File(outputDirectory, basename + ".vcf.gz");
        if (forceRecreate || !indexExists(cassandraAnnotated))
        {
            //we can assume splitting happened upstream, so run over the full VCF
            cassandraAnnotated = runCassandra(liftedToGRCh37, cassandraAnnotated, output, forceRecreate);
        }
        else
        {
            getPipelineCtx().getLogger().info("resuming with existing file: " + cassandraAnnotated.getPath());
        }

        if (cassandraAnnotated != null)
        {
            output.addOutput(cassandraAnnotated, "VCF Annotated With Cassandra");
            output.addIntermediateFile(cassandraAnnotated);
            output.addIntermediateFile(new File(cassandraAnnotated.getPath() + ".tbi"));
        }

        //backport ClinVar
        getPipelineCtx().getLogger().info("backport ClinVar 2.0 to source genome");
        File clinvarAnnotatedBackport = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(clinvarAnnotated.getName()) + ".bp.vcf.gz");
        if (forceRecreate || !indexExists(clinvarAnnotatedBackport ))
        {
            BackportLiftedVcfRunner bpRunner = new BackportLiftedVcfRunner(getPipelineCtx().getLogger());
            bpRunner.execute(clinvarAnnotated, originalGenome.getWorkingFastaFile(), grch37Genome.getWorkingFastaFile(), clinvarAnnotatedBackport);
        }
        else
        {
            getPipelineCtx().getLogger().info("resuming with existing file: " + clinvarAnnotatedBackport.getPath());
        }
        output.addOutput(clinvarAnnotatedBackport, "VCF Annotated With Clinvar, Backported");
        output.addIntermediateFile(clinvarAnnotatedBackport);
        output.addIntermediateFile(new File(clinvarAnnotatedBackport.getPath() + ".tbi"));

        //backport Cassandra
        getPipelineCtx().getLogger().info("backport Cassandra to source genome");
        File cassandraAnnotatedBackport = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(cassandraAnnotated.getName()) + ".bp.vcf.gz");
        if (cassandraAnnotated != null)
        {
            if (forceRecreate || !indexExists(cassandraAnnotatedBackport))
            {
                BackportLiftedVcfRunner bpRunner = new BackportLiftedVcfRunner(getPipelineCtx().getLogger());
                bpRunner.execute(cassandraAnnotated, originalGenome.getWorkingFastaFile(), grch37Genome.getWorkingFastaFile(), cassandraAnnotatedBackport);
            }
            else
            {
                getPipelineCtx().getLogger().info("resuming with existing file: " + cassandraAnnotatedBackport.getPath());
            }
            output.addOutput(cassandraAnnotatedBackport, "VCF Annotated With Cassandra, Backported");
            output.addIntermediateFile(cassandraAnnotatedBackport);
            output.addIntermediateFile(new File(cassandraAnnotatedBackport.getPath() + ".tbi"));
        }
        else
        {
            getPipelineCtx().getLogger().info("No cassandra output, will not backport");
            cassandraAnnotatedBackport = null;
        }

        //multiannotator
        getPipelineCtx().getLogger().info("Running MultiSourceAnnotator");
        File multiAnnotated = new File(getPipelineCtx().getWorkingDirectory(), SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".ma.vcf.gz");
        if (forceRecreate || !indexExists(multiAnnotated))
        {
            MultiSourceAnnotatorRunner maRunner = new MultiSourceAnnotatorRunner(getPipelineCtx().getLogger());

            List<String> options = new ArrayList<>();
            if (needToSubsetToInterval)
            {
                for (Interval interval : intervals)
                {
                    options.add("-L");
                    options.add(interval.getContig() + ":" + interval.getStart() + "-" + interval.getEnd());
                }
                needToSubsetToInterval = false;
            }

            maRunner.execute(inputVCF, cassandraAnnotatedBackport, clinvarAnnotatedBackport, liftoverRejects, multiAnnotated, options);
        }
        else
        {
            getPipelineCtx().getLogger().info("resuming with existing file: " + multiAnnotated.getPath());
        }
        output.addOutput(multiAnnotated, "VCF Multi-Annotated");

        getPipelineCtx().getJob().getLogger().info("total variants: " + SequenceAnalysisService.get().getVCFLineCount(multiAnnotated, getPipelineCtx().getJob().getLogger(), false));
        getPipelineCtx().getJob().getLogger().info("passing variants: " + SequenceAnalysisService.get().getVCFLineCount(multiAnnotated, getPipelineCtx().getJob().getLogger(), true));

        //final output
        output.setVcf(multiAnnotated);

        return output;
    }

    private File runCassandra(File liftedToGRCh37, File finalOutput, VariantProcessingStepOutputImpl output, boolean forceRecreate) throws PipelineJobException
    {
        List<String> extraArgs = new ArrayList<>();

        //NOTE: Cassandra will not sort the output when multithreaded, so the extra sorting we would need to do negates any benefit here
        String tmpDir = SequencePipelineService.get().getJavaTempDir();
        if (!StringUtils.isEmpty(tmpDir))
        {
            File tmpDirFile = new File(tmpDir, "cassandra");
            if (!tmpDirFile.exists())
            {
                tmpDirFile.mkdirs();
            }

            extraArgs.add("--tempDir");
            extraArgs.add(tmpDirFile.getPath());
        }

        CassandraRunner cassRunner = new CassandraRunner(getPipelineCtx().getLogger());

        Integer maxRam = SequencePipelineService.get().getMaxRam();
        cassRunner.setMaxRamOverride(maxRam);

        //Cassandra requires unzipped files
        File liftedToGRCh37Unzipped = new File(liftedToGRCh37.getParentFile(), FileUtil.getBaseName(liftedToGRCh37.getName()));
        File liftedToGRCh37UnzippedDone = new File(liftedToGRCh37Unzipped.getPath() + ".done");
        if (forceRecreate || !liftedToGRCh37UnzippedDone.exists())
        {
            SimpleScriptWrapper wrapper = new SimpleScriptWrapper(getPipelineCtx().getLogger());
            wrapper.execute(Arrays.asList("gunzip", liftedToGRCh37.getPath()));
            try
            {
                FileUtils.touch(liftedToGRCh37UnzippedDone);
                if (!liftedToGRCh37.exists() && indexExists(liftedToGRCh37))
                {
                    File idx = new File(liftedToGRCh37.getPath() + ".tbi");
                    idx.delete();
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }
        else
        {
            getPipelineCtx().getLogger().info("Resuming from file: " + liftedToGRCh37Unzipped.getPath());
        }

        output.addIntermediateFile(liftedToGRCh37Unzipped);
        output.addIntermediateFile(new File(liftedToGRCh37Unzipped.getPath() + ".idx"));
        output.addIntermediateFile(liftedToGRCh37UnzippedDone);

        cassRunner.execute(liftedToGRCh37Unzipped, finalOutput, extraArgs);
        if (!finalOutput.exists())
        {
            throw new PipelineJobException("Unable to find output");
        }

        try
        {
            SequenceAnalysisService.get().ensureVcfIndex(finalOutput, getPipelineCtx().getLogger());
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        return finalOutput;
    }

    protected static boolean indexExists(File vcf)
    {
        File idx = new File(vcf.getPath() + ".tbi");
        return idx.exists();
    }

    public static void findChainFile(int sourceGenome, int targetGenome, SequenceAnalysisJobSupport support, PipelineJob job) throws PipelineJobException
    {
        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("genomeId1"), sourceGenome);
        filter.addCondition(FieldKey.fromString("genomeId2"), targetGenome);
        filter.addCondition(FieldKey.fromString("dateDisabled"), null, CompareType.ISBLANK);

        TableSelector ts = new TableSelector(DbSchema.get("sequenceanalysis", DbSchemaType.Module).getTable("chain_files"), PageFlowUtil.set("chainFile"), filter, new Sort("-version"));
        if (!ts.exists())
        {
            throw new PipelineJobException("Unable to find chain file from genome " + sourceGenome + " to " + targetGenome);
        }

        if (ts.getRowCount() > 1)
        {
            job.getLogger().warn("more than one active chain file found from genome " + sourceGenome + " to " + targetGenome);
        }

        Integer chainId = ts.getObject(Integer.class);
        ExpData data = ExperimentService.get().getExpData(chainId);
        if (data == null)
        {
            throw new PipelineJobException("Unable to find ExpData chain file from genome " + sourceGenome + " to " + targetGenome + " with id: " + chainId);
        }

        support.cacheExpData(data);
        support.cacheObject(CHAIN_FILE, chainId);
    }
}
