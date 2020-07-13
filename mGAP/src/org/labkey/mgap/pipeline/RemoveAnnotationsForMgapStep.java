package org.labkey.mgap.pipeline;

import htsjdk.samtools.util.Interval;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.pipeline.AbstractVariantProcessingStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStep;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStep;
import org.labkey.api.sequenceanalysis.pipeline.VariantProcessingStepOutputImpl;
import org.labkey.api.sequenceanalysis.run.AbstractCommandPipelineStep;
import org.labkey.api.sequenceanalysis.run.AbstractDiscvrSeqWrapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RemoveAnnotationsForMgapStep extends AbstractCommandPipelineStep<RemoveAnnotationsForMgapStep.RemoveAnnotationsWrapper> implements VariantProcessingStep
{
    public static List<String> ALLOWABLE_ANNOTATIONS = Collections.unmodifiableList(Arrays.asList("AF", "AC", "END", "ANN", "LOF", "MAF", "CADD_PH", "CADD_RS", "CCDS", "ENC", "ENCDNA_CT", "ENCDNA_SC", "ENCSEG_CT", "ENCSEG_NM", "ENCTFBS_CL", "ENCTFBS_SC", "ENCTFBS_TF", "ENN", "ERBCTA_CT", "ERBCTA_NM", "ERBCTA_SC", "ERBSEG_CT", "ERBSEG_NM", "ERBSEG_SC", "ERBSUM_NM", "ERBSUM_SC", "ERBTFBS_PB", "ERBTFBS_TF", "FC", "FE", "FS_EN", "FS_NS", "FS_SC", "FS_SN", "FS_TG", "FS_US", "FS_WS", "GRASP_AN", "GRASP_P", "GRASP_PH", "GRASP_PL", "GRASP_PMID", "GRASP_RS", "LOF", "NC", "NE", "NF", "NG", "NH", "NJ", "NK", "NL", "NM", "NMD", "OMIMC", "OMIMD", "OMIMM", "OMIMMUS", "OMIMN", "OMIMS", "OMIMT", "OREGANNO_PMID", "OREGANNO_TYPE", "PC_PL", "PC_PR", "PC_VB", "PP_PL", "PP_PR", "PP_VB", "RDB_MF", "RDB_WS", "RFG", "RSID", "SCSNV_ADA", "SCSNV_RS", "SD", "SF", "SM", "SP_SC", "SX", "TMAF", "LF", "CLN_ALLELE", "CLN_ALLELEID", "CLN_DN", "CLN_DNINCL", "CLN_DISDB", "CLN_DISDBINCL", "CLN_HGVS", "CLN_REVSTAT", "CLN_SIG", "CLN_SIGINCL", "CLN_VC", "CLN_VCSO", "CLN_VI", "CLN_DBVARID", "CLN_GENEINFO", "CLN_MC", "CLN_ORIGIN", "CLN_RS", "CLN_SSR", "ReverseComplementedAlleles", "LiftedContig", "LiftedStart", "LiftedStop"));

    public RemoveAnnotationsForMgapStep(PipelineStepProvider provider, PipelineContext ctx)
    {
        super(provider, ctx, new RemoveAnnotationsWrapper(ctx.getLogger()));
    }

    public static class Provider extends AbstractVariantProcessingStepProvider<RemoveAnnotationsForMgapStep> implements VariantProcessingStep.SupportsScatterGather
    {
        public Provider()
        {
            super("RemoveAnnotations", "Remove Annotations For mGAP", "RemoveAnnotations", "This will remove annotations from the selected VCF, limiting to only those expected for mGAP.", Arrays.asList(

            ), null, null);
        }

        @Override
        public PipelineStep create(PipelineContext context)
        {
            return new RemoveAnnotationsForMgapStep(this, context);
        }
    }

    @Override
    public Output processVariants(File inputVCF, File outputDirectory, ReferenceGenome genome, @Nullable List<Interval> intervals) throws PipelineJobException
    {
        VariantProcessingStepOutputImpl output = new VariantProcessingStepOutputImpl();

        File outputFile = new File(outputDirectory, SequenceAnalysisService.get().getUnzippedBaseName(inputVCF.getName()) + ".noAnnotations.vcf.gz");
        if (indexExists(outputFile))
        {
            getPipelineCtx().getLogger().info("re-using existing output: " + outputFile.getPath());
        }
        else
        {
            getWrapper().execute(inputVCF, outputFile, genome.getWorkingFastaFile(), intervals);
        }

        output.setVcf(outputFile);
        output.addIntermediateFile(outputFile);
        output.addIntermediateFile(new File(outputFile.getPath() + ".tbi"));

        return output;
    }

    private boolean indexExists(File vcf)
    {
        return new File(vcf.getPath() + ".tbi").exists();
    }

    public static class RemoveAnnotationsWrapper extends AbstractDiscvrSeqWrapper
    {
        public RemoveAnnotationsWrapper(Logger log)
        {
            super(log);
        }

        public void execute(File input, File outputFile, File referenceFasta, @Nullable List<Interval> intervals) throws PipelineJobException
        {
            List<String> args = new ArrayList<>(getBaseArgs());
            args.add("RemoveAnnotations");
            args.add("-R");
            args.add(referenceFasta.getPath());
            args.add("-V");
            args.add(input.getPath());
            args.add("-O");
            args.add(outputFile.getPath());

            if (intervals != null)
            {
                intervals.forEach(interval -> {
                    args.add("-L");
                    args.add(interval.getContig() + ":" + interval.getStart() + "-" + interval.getEnd());
                });
            }

            for (String key : ALLOWABLE_ANNOTATIONS)
            {
                args.add("-A");
                args.add(key);
            }

            //for (String key : Arrays.asList("DP", "AD"))
            //{
            //    args.add("-GA");
            //    args.add(key);
            //}

            args.add("-ef");
            args.add("--clearGenotypeFilter");

            super.execute(args);
        }
    }
}
