package org.labkey.mgap.pipeline;

import htsjdk.samtools.util.Interval;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableSelector;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.pipeline.AbstractVariantProcessingStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStep;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStepOutputImpl;
import org.labkey.api.sequenceanalysis.run.AbstractCommandPipelineStep;
import org.labkey.api.sequenceanalysis.run.AbstractDiscvrSeqWrapper;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.mgap.mGAPSchema;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * User: bimber
 * Date: 6/15/2014
 * Time: 12:39 PM
 */
public class mGapReleaseAnnotateNovelSitesStep extends AbstractCommandPipelineStep<mGapReleaseAnnotateNovelSitesStep.AnnotateNovelSitesWrapper> implements VariantProcessingStep
{
    public static final String VERSION_ROWID = "versionRowId";
    public static final String PRIOR_RELEASE_LABEL = "priorReleaseLabel";
    public static final String SITES_ONLY_DATA = "sitesOnlyVcfData";

    public mGapReleaseAnnotateNovelSitesStep(PipelineStepProvider<?> provider, PipelineContext ctx)
    {
        super(provider, ctx, new AnnotateNovelSitesWrapper(ctx.getLogger()));
    }

    public static class Provider extends AbstractVariantProcessingStepProvider<mGapReleaseAnnotateNovelSitesStep> implements SupportsScatterGather
    {
        public Provider()
        {
            super("mGapAnnotateNovelSites", "Annotate Novel Sites Against mGAP Release", "AnnotateNovelSites", "Compare the VCF to the specified mGAP release VCF, producing TSV/VCF reports with site- and genotype-level concordance.", Arrays.asList(
                    ToolParameterDescriptor.create(VERSION_ROWID, "mGAP Release", "The mGAP release VCF to use for comparison", "ldk-simplelabkeycombo", new JSONObject(){{
                        put("allowBlank", false);
                        put("width", 400);
                        put("schemaName", "mgap");
                        put("queryName", "variantCatalogReleases");
                        put("containerPath", "js:Laboratory.Utils.getQueryContainerPath()");
                        put("displayField", "version");
                        put("valueField", "rowid");
                        put("doNotIncludeInTemplates", true);
                    }}, null),
                    ToolParameterDescriptor.create("releaseVersion", "mGAP Version", "This string will be used to tag novel variants.", "textfield", new JSONObject(){{
                        put("allowBlank", false);
                        put("doNotIncludeInTemplates", true);
                    }}, null)
            ), PageFlowUtil.set("sequenceanalysis/field/SequenceOutputFileSelectorField.js"), null);
        }

        @Override
        public mGapReleaseAnnotateNovelSitesStep create(PipelineContext ctx)
        {
            return new mGapReleaseAnnotateNovelSitesStep(this, ctx);
        }
    }

    @Override
    public Output processVariants(File inputVCF, File outputDirectory, ReferenceGenome genome, @Nullable List<Interval> intervals) throws PipelineJobException
    {
        VariantProcessingStepOutputImpl output = new VariantProcessingStepOutputImpl();
        getPipelineCtx().getLogger().info("Annotating VCF by mGAP Release");

        String releaseVersion = getProvider().getParameterByName("releaseVersion").extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), String.class, "0.0");
        if (releaseVersion.toLowerCase().startsWith("v"))
        {
            releaseVersion = releaseVersion.substring(1);
        }

        if (!NumberUtils.isCreatable(releaseVersion))
        {
            throw new IllegalArgumentException("Expected the release version to be numeric: " + releaseVersion);
        }

        String priorReleaseLabel = getPipelineCtx().getSequenceSupport().getCachedObject(PRIOR_RELEASE_LABEL, String.class);
        int sitesOnlyExpDataId = getPipelineCtx().getSequenceSupport().getCachedObject(SITES_ONLY_DATA, Integer.class);
        File sitesOnlyVcf = getPipelineCtx().getSequenceSupport().getCachedData(sitesOnlyExpDataId);
        if (!sitesOnlyVcf.exists())
        {
            throw new PipelineJobException("Unable to find file: " + sitesOnlyVcf);
        }

        List<String> extraArgs = new ArrayList<>();
        if (intervals != null)
        {
            intervals.forEach(interval -> {
                extraArgs.add("-L");
                extraArgs.add(interval.getContig() + ":" + interval.getStart() + "-" + interval.getEnd());
            });

            extraArgs.add("--ignore-variants-starting-outside-interval");
        }

        extraArgs.add("-dv");
        extraArgs.add(priorReleaseLabel);

        File annotatedVCF = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".comparison.vcf.gz");
        getWrapper().execute(inputVCF, sitesOnlyVcf, genome.getWorkingFastaFile(), releaseVersion, annotatedVCF, extraArgs);
        if (!annotatedVCF.exists())
        {
            throw new PipelineJobException("Unable to find output: " + annotatedVCF.getPath());
        }

        output.addInput(inputVCF, "Input VCF");
        output.addInput(sitesOnlyVcf, "Reference VCF");

        output.addOutput(annotatedVCF, "VCF Annotated by mGAP Version");
        output.setVcf(annotatedVCF);

        return output;
    }

    @Override
    public void init(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles) throws PipelineJobException
    {
        Integer versionRowId = getProvider().getParameterByName(VERSION_ROWID).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        String version = new TableSelector(mGAPSchema.getInstance().getSchema().getTable(mGAPSchema.TABLE_VARIANT_CATALOG_RELEASES), PageFlowUtil.set("version"), new SimpleFilter(FieldKey.fromString("rowId"), versionRowId), null).getObject(String.class);
        if (version == null)
        {
            throw new PipelineJobException("Unable to find release for release: " + versionRowId);
        }

        Integer referenceVcfOutputId = new TableSelector(mGAPSchema.getInstance().getSchema().getTable(mGAPSchema.TABLE_VARIANT_CATALOG_RELEASES), PageFlowUtil.set("sitesOnlyVcfId"), new SimpleFilter(FieldKey.fromString("rowId"), versionRowId), null).getObject(Integer.class);
        if (referenceVcfOutputId == null)
        {
            getPipelineCtx().getLogger().debug("Sites-only VCF not found, using primary VCF");
            referenceVcfOutputId = new TableSelector(mGAPSchema.getInstance().getSchema().getTable(mGAPSchema.TABLE_VARIANT_CATALOG_RELEASES), PageFlowUtil.set("vcfId"), new SimpleFilter(FieldKey.fromString("rowId"), versionRowId), null).getObject(Integer.class);
        }

        if (referenceVcfOutputId == null)
        {
            throw new PipelineJobException("Unable to find sites-only VCF for release: " + versionRowId);
        }

        SequenceOutputFile sitesOnly = SequenceOutputFile.getForId(referenceVcfOutputId);
        if (sitesOnly == null)
        {
            throw new PipelineJobException("Unable to find sites-only VCF output file for fileId: " + referenceVcfOutputId);
        }

        support.cacheExpData(sitesOnly.getExpData());

        support.cacheObject(SITES_ONLY_DATA, sitesOnly.getDataId());
        support.cacheObject(PRIOR_RELEASE_LABEL, version);
    }

    public static class AnnotateNovelSitesWrapper extends AbstractDiscvrSeqWrapper
    {
        public AnnotateNovelSitesWrapper(Logger log)
        {
            super(log);
        }

        public File execute(File vcf, File referenceVcf, File fasta, String versionString, File vcfOutput, List<String> extraArgs) throws PipelineJobException
        {
            List<String> args = new ArrayList<>(getBaseArgs());
            args.add("AnnotateNovelSites");
            args.add("-R");
            args.add(fasta.getPath());

            args.add("-V");
            args.add(vcf.getPath());
            args.add("-rv");
            args.add(referenceVcf.getPath());

            args.add("-an");
            args.add("mGAPV");
            args.add("-ad");
            args.add("The first mGAP version where variants at this site appeared");
            args.add("-av");
            args.add(versionString);

            args.add("-O");
            args.add(vcfOutput.getPath());

            if (extraArgs != null)
            {
                args.addAll(extraArgs);
            }

            execute(args);

            return vcfOutput;
        }
    }
}
