package org.labkey.variantdb.run;

import htsjdk.samtools.util.CloseableIterator;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.Options;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.variantcontext.writer.VariantContextWriterBuilder;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import org.apache.logging.log4j.Logger;
import org.labkey.api.gwt.client.util.StringUtils;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.reader.Readers;
import org.labkey.api.util.Pair;
import org.labkey.api.writer.PrintWriters;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by bimber on 8/23/2015.
 */
public class MendelianEvaluator
{
    private double _minGenotypeQuality = 20;
    private Map<String, Pair<String, String>> _pedigree;

    public MendelianEvaluator(File gatkPedigree) throws IOException
    {
        _pedigree = parsePedigree(gatkPedigree);
    }

    public void checkVcf(File input, File outputPass, File outputFail, File outputBed, Logger log) throws PipelineJobException
    {
        int violations = 0;
        Map<String, Integer> violationsById = new HashMap<>();
        int totalSnps = 0;

        VariantContextWriterBuilder build1 = new VariantContextWriterBuilder();
        build1.setOption(Options.INDEX_ON_THE_FLY);
        build1.setOutputFile(outputPass);

        VariantContextWriterBuilder build2 = new VariantContextWriterBuilder();
        build2.setOption(Options.INDEX_ON_THE_FLY);
        build2.setOutputFile(outputFail);

        try (VariantContextWriter writerPass = build1.build(); VariantContextWriter writerFail = build2.build(); PrintWriter bedWriter = PrintWriters.getPrintWriter(outputBed))
        {
            File idx = new File(input.getPath() + ".tbi");
            if (!idx.exists())
            {
                idx = new File(input.getPath() + ".idx");
            }

            try (VCFFileReader reader = new VCFFileReader(input, idx))
            {
                String MV_ID = "MV_ID";
                VCFHeader header = new VCFHeader(reader.getFileHeader());
                header.addMetaDataLine(new VCFInfoHeaderLine(MV_ID, 1, VCFHeaderLineType.Character, "IDs showing mendelian violations"));
                writerPass.writeHeader(header);
                writerFail.writeHeader(header);

                try (CloseableIterator<VariantContext> it = reader.iterator())
                {
                    OUTER:
                    while (it.hasNext())
                    {
                        VariantContext vc = it.next();
                        totalSnps++;

                        if (totalSnps % 10000 == 0)
                        {
                            log.info("processed " + totalSnps + " loci for mendelian violations");
                        }

                        for (String name : vc.getSampleNames())
                        {
                            if (!_pedigree.containsKey(name))
                            {
                                log.error("ID not in pedigree: " + name);
                                continue;
                            }

                            if (isViolation(_pedigree.get(name).second, _pedigree.get(name).first, name, vc))
                            {
                                VariantContextBuilder vcb = new VariantContextBuilder(vc);
                                vcb.attribute(MV_ID, name);

                                writerFail.add(vcb.make());

                                bedWriter.write(StringUtils.join(Arrays.asList(vc.getChr(), vc.getStart() -1, vc.getEnd(), name), "\t"));
                                bedWriter.write("\n");

                                violations++;
                                Integer i = violationsById.containsKey(name) ? violationsById.get(name) : 0;
                                i++;
                                violationsById.put(name, i);

                                continue OUTER;
                            }
                        }

                        writerPass.add(vc);
                    }
                }
            }

            log.info("total non-mendelian SNPs: " + violations + " (" + (100.0 * (double)violations / totalSnps) + "% of positions)");
            for (String id : violationsById.keySet())
            {
                log.info(id + ": " + violationsById.get(id));
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }

    private int countAllelesShared(Genotype g1, Genotype g2)
    {
        int shared = 0;
        for (Allele a : g1.getAlleles())
        {
            if (a.isCalled() && !a.isSymbolic() && g2.getAlleles().contains(a))
            {
                shared++;
            }
        }

        return shared;
    }

    private Map<String, Pair<String, String>> parsePedigree(File pedigree) throws IOException
    {
        Map<String, Pair<String, String>> ret = new HashMap<>();
        try (BufferedReader reader = Readers.getReader(pedigree))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                String[] tokens = line.split(" ");
                if (tokens.length < 4)
                {
                    continue;
                }

                if (ret.containsKey(tokens[1]))
                {
                    throw new IOException("error in pedigree, ID: " + tokens[1] + " present multiple times");
                }
                ret.put(tokens[1], Pair.of(tokens[2], tokens[3]));
            }
        }

        return ret;
    }

    public void setMinGenotypeQuality(double minGenotypeQuality)
    {
        _minGenotypeQuality = minGenotypeQuality;
    }

    public boolean isViolation(String sampleName, VariantContext vc)
    {
        if (!_pedigree.containsKey(sampleName))
        {
            return false;
        }

        return isViolation(_pedigree.get(sampleName).second, _pedigree.get(sampleName).first, sampleName, vc);
    }

    private boolean isViolation(String motherId, String fatherId, String childId, VariantContext vc)
    {
        Genotype gMom = vc.getGenotype(motherId);
        if (gMom == null)
        {
            gMom = new NoCallGenotype(motherId);
        }
        Genotype gDad = vc.getGenotype(fatherId);
        if (gDad == null)
        {
            gDad = new NoCallGenotype(fatherId);
        }

        Genotype gChild = vc.getGenotype(childId);
        if (gChild == null || gChild.isNoCall()){
            return false;  //cant make call
        }

        //Count lowQual. Note that if min quality is set to 0, even values with no quality associated are returned
        if (_minGenotypeQuality > -1 && gChild.getPhredScaledQual() < _minGenotypeQuality) {
            //cannot make determination
        }
        else
        {
            //If the family is all homref, not too interesting
            if (!(gMom.isHomRef() && gDad.isHomRef() && gChild.isHomRef()))
            {
                if (!gMom.isCalled() && !gDad.isCalled())
                {
                    return false;
                }
                else if (isViolation(gMom, gDad, gChild)){
                    return true;
                }
            }
        }

        return false;
    }

    public boolean isViolation(final Genotype gMom, final Genotype gDad, final Genotype gChild) {
        //1 parent is no "call
        if(!gMom.isCalled()){
            if (gDad.getPhredScaledQual() < _minGenotypeQuality)
            {
                return false;
            }

            if ((gDad.isHomRef() && gChild.isHomVar()) || (gDad.isHomVar() && gChild.isHomRef()) || (countAllelesShared(gChild, gDad) == 0))
            {
                return true;
            }

            return false;
        }
        else if(!gDad.isCalled()){
            if (gMom.getPhredScaledQual() < _minGenotypeQuality)
            {
                return false;
            }

            if ((gMom.isHomRef() && gChild.isHomVar()) || (gMom.isHomVar() && gChild.isHomRef()) || (countAllelesShared(gChild, gMom) == 0))
            {
                return true;
            }

            return false;
        }
        //Both parents have genotype information
        return !(gMom.getAlleles().contains(gChild.getAlleles().get(0)) && gDad.getAlleles().contains(gChild.getAlleles().get(1)) ||
                gMom.getAlleles().contains(gChild.getAlleles().get(1)) && gDad.getAlleles().contains(gChild.getAlleles().get(0)));
    }

    public class NoCallGenotype extends Genotype
    {
        private Genotype _orig = null;
        private List<Allele> _alleles = Arrays.asList(Allele.NO_CALL, Allele.NO_CALL);

        public NoCallGenotype(String sampleName)
        {
            super(sampleName, ".");
        }

        @Override
        public List<Allele> getAlleles()
        {
            return _alleles;
        }

        @Override
        public Allele getAllele(int i)
        {
            return _alleles.get(i);
        }

        @Override
        public boolean isPhased()
        {
            return false;
        }

        @Override
        public int getDP()
        {
            return _orig == null ? 0 : _orig.getDP();
        }

        @Override
        public int[] getAD()
        {
            return _orig == null ? new int[0] : _orig.getAD();
        }

        @Override
        public int getGQ()
        {
            return _orig == null ? 50 : _orig.getGQ();
        }

        @Override
        public int[] getPL()
        {
            return _orig == null ? new int[0] : _orig.getPL();
        }

        @Override
        public Map<String, Object> getExtendedAttributes()
        {
            return _orig == null ? null : _orig.getExtendedAttributes();
        }
    }
}
