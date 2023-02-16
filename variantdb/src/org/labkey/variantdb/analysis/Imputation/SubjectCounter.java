package org.labkey.variantdb.analysis.Imputation;

import au.com.bytecode.opencsv.CSVWriter;
import htsjdk.variant.variantcontext.Genotype;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.labkey.variantdb.analysis.ImputationAnalysis;
import org.labkey.variantdb.run.ImputationRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by bimber on 4/28/2016.
 */
public class SubjectCounter
{
    private final String _subject;
    private final CSVWriter _afWriter;
    public int totalGenotypesInspected = 0;
    public int genotypesMatchingRef = 0;
    public int incorrectImputation = 0;
    public int verifiableGenotypesNotImputed = 0;
    public int unverifiableGenotypesNotImputed = 0;
    public int genotypeWithRefNotImputed = 0;

    public int unverifiableGenotypes = 0;  //no reference value
    public int refGenotypeNotFound = 0;
    public int genotypesOverlappingFrameworkWithInputValues = 0;
    public int genotypesOverlappingFrameworkWithoutInputValues = 0;

    public int totalLowFreqHetMatching = 0;
    public int totalLowFreqHetErrors = 0;
    public int sitesHalfImputed = 0;

    public SubjectCounter(String subject, CSVWriter afWriter)
    {
        _afWriter = afWriter;
        _subject = subject;
    }

    public SiteSummary addGenos(List<String> imputedGenos, List<String> trueGenos, int markerNumber, double lowFreqThreshold, Set<Integer> distinctLowAfMarkers, List<List<Double>> alleleFreqs, boolean overlapsFramework, Genotype refGenotype, Genotype imputationInputGenotype, String markerName, String subject, Logger log, List<String> alleleToBase)
    {
        // if this genotype overlaps the framework, and the reference is called, we would have written the true genotype out to GIGI, and therefore
        // it wasnt actually imputed
        if (overlapsFramework)
        {
            if (imputationInputGenotype == null)
            {
                log.warn("dense/framework overlap, but input imputation input genotype was not found: " + markerName + ", " + subject + ", " + StringUtils.join(imputedGenos, ";") + ", bases: " + StringUtils.join(alleleToBase, ";"));
            }
            else if (!imputationInputGenotype.isNoCall())
            {
                genotypesOverlappingFrameworkWithInputValues++;
                genotypesOverlappingFrameworkWithInputValues++;

                for (String imputedGeno : imputedGenos)
                {
                    if (!trueGenos.contains(imputedGeno) && !"0".equals(imputedGeno) && (!isUncalled(trueGenos, log, markerName, subject)))
                    {
                        log.info("dense/framework overlap, but imputed value doesn't match the input: " + markerName + ", " + subject + ". input genotype: " + imputationInputGenotype.getGenotypeString() + ", " + StringUtils.join(imputedGeno, ";") + ", bases: " + StringUtils.join(alleleToBase, ";") + ", true genotypes: " + StringUtils.join(trueGenos, ";"));
                    }
                }

                return new SiteSummary();
            }
            else
            {
                //continue to score these
                genotypesOverlappingFrameworkWithoutInputValues++;
                genotypesOverlappingFrameworkWithoutInputValues++;
            }
        }

        Boolean isHeterozygous = imputedGenos.contains("0") ? null : !(imputedGenos.get(0).equals(imputedGenos.get(1)));
        List<String> trueGenosToTest = new ArrayList<>();
        trueGenosToTest.addAll(trueGenos);

        //update stats for reference genos
        for (Integer i : Arrays.asList(0, 1))
        {
            if (trueGenos.get(i).equals("-1"))
            {
                unverifiableGenotypes++;

                //no value imputed
                if (imputedGenos.get(i).equals("0"))
                {
                    unverifiableGenotypesNotImputed++;
                }
            }
        }

        SiteSummary ss = new SiteSummary();
        addGeno(ss, imputedGenos.get(0), trueGenosToTest, markerNumber, lowFreqThreshold, distinctLowAfMarkers, isHeterozygous, alleleFreqs, overlapsFramework, imputationInputGenotype, markerName, subject, log, alleleToBase, 1);
        addGeno(ss, imputedGenos.get(1), trueGenosToTest, markerNumber, lowFreqThreshold, distinctLowAfMarkers, isHeterozygous, alleleFreqs, overlapsFramework, imputationInputGenotype, markerName, subject, log, alleleToBase, 2);

        return ss;
    }

    public static class SiteSummary
    {
        public int matching = 0;
        public int errors = 0;
        public int notImputed = 0;
        public int uncalledRef = 0;
    }

    private boolean isUncalled(List<String> genos, Logger log, String markerName, String subject)
    {
        int i = 0;
        for (String g : genos)
        {
            i++;
            if (!"-1".equals(g))
            {
                if (i != 1)
                {
                    log.info("mix of known/unknown reference genotypes: " + subject + ", " + markerName + ", " + StringUtils.join(genos, ";"));
                }

                return false;
            }
        }

        return true;
    }

    private void addGeno(SiteSummary ss, String imputedGeno, List<String> trueGenos, int markerNumber, double lowFreqThreshold, Set<Integer> distinctLowAfMarkers, Boolean isHeterozygous, List<List<Double>> alleleFreqs, boolean overlapsFramework, Genotype imputationInputGenotype, String markerName, String subject, Logger log, List<String> alleleToBase, int genotypeNum)
    {
        totalGenotypesInspected++;

        boolean isError = false;
        boolean isMatch = false;
        boolean isMissing = false;
        boolean isUncalledRef = false;

        //reference is no call
        if (trueGenos.contains(imputedGeno))
        {
            trueGenos.remove(imputedGeno);
            this.genotypesMatchingRef++;
            ss.matching++;
            isMatch = true;
        }
        else if (isUncalled(trueGenos, log, markerName, subject))
        {
            //counted above
            ss.uncalledRef++;
            isUncalledRef = true;
        }
        else if (imputedGeno.equals("0"))
        {
            this.genotypeWithRefNotImputed++;
            ss.notImputed++;
            isMissing = true;
        }
        else
        {
            incorrectImputation++;
            isError = true;
            ss.errors++;
        }

        //TODO: remove
        //find heterozygous sites below this threshold
        Integer geno = Integer.parseInt(imputedGeno);
        Double af = geno > 0 ? alleleFreqs.get(markerNumber - 1).get(geno - 1) : null;
        if (isHeterozygous != null && isHeterozygous)
        {
            if (af != null && af <= lowFreqThreshold)
            {
                distinctLowAfMarkers.add(markerNumber);

                if (imputedGeno.equals("0"))
                {
                    //ignore
                }
                else if (isError)
                {
                    totalLowFreqHetErrors++;
                }
                else
                {
                    totalLowFreqHetMatching++;
                }
            }
        }

        //non-called imputed genotypes dont have an AF, so use the reference allele's
        if (imputedGeno.equals("0") && af == null)
        {
            af = alleleFreqs.get(markerNumber - 1).get(genotypeNum - 1);
        }

        writeAfLine(markerName, trueGenos, imputedGeno, af, isMatch, isError, isMissing, isUncalledRef, isHeterozygous, genotypeNum);
    }
    
    public void writeSummary(CSVWriter writer, ImputationRunner runner, ImputationAnalysis.Processor.SampleSet ss, String chr, String subject, Integer idx, String callMethod, Map<String, Set<String>> relativesPresent, Map<String, Set<String>> wgsRelativesPresent, String jobDescription)
    {
        writer.writeNext(new String[]{
                idx.toString(),
                jobDescription,
                String.valueOf(runner.getFrameworkIntervalMap().get(chr).size()),
                String.valueOf(runner.getDenseIntervalMap().get(chr).size()),
                StringUtils.join(ss.wgsSampleIdStrings, ";"),
                StringUtils.join(ss.imputedSampleIdStrings, ";"),
                subject,
                callMethod,
                String.valueOf(runner.getMinGenotypeQual()),
                String.valueOf(runner.getMinGenotypeDepth()),
                chr,
                String.valueOf(this.totalGenotypesInspected),
                String.valueOf(this.totalGenotypesInspected - this.unverifiableGenotypes),
                String.valueOf(this.genotypesMatchingRef),
                String.valueOf(this.incorrectImputation),
                String.valueOf(this.genotypeWithRefNotImputed),
                String.valueOf(this.unverifiableGenotypes),
                String.valueOf(this.unverifiableGenotypesNotImputed),
                String.valueOf(getAccuracy()),
                String.valueOf((double) this.genotypeWithRefNotImputed / (this.totalGenotypesInspected - this.unverifiableGenotypes)),
                String.valueOf((double) (this.unverifiableGenotypesNotImputed + this.genotypeWithRefNotImputed) / this.totalGenotypesInspected),
                String.valueOf(this.genotypesOverlappingFrameworkWithInputValues),
                String.valueOf(this.genotypesOverlappingFrameworkWithoutInputValues),
                String.valueOf(wgsRelativesPresent.get(subject).size()),
                String.valueOf(relativesPresent.get(subject).size()),
                StringUtils.join(wgsRelativesPresent.get(subject), ";"),
                StringUtils.join(relativesPresent.get(subject), ";"),
                String.valueOf(ss.imputedSampleIds.size()),
                String.valueOf(this.totalLowFreqHetMatching),
                String.valueOf(this.totalLowFreqHetErrors),
                String.valueOf(this.refGenotypeNotFound),
                String.valueOf(this.sitesHalfImputed)
        });        
    }

    public Double getAccuracy()
    {
        if ((this.totalGenotypesInspected - this.unverifiableGenotypes - this.genotypeWithRefNotImputed) == 0)
        {
            return null;
        }

        return (double) this.genotypesMatchingRef / (this.totalGenotypesInspected - this.unverifiableGenotypes - this.genotypeWithRefNotImputed);
    }

    private void writeAfLine(String markerName, List<String> trueGenos, String imputedGeno, Double af, boolean isMatch, boolean isError, boolean isMissing, boolean isUncalledRef, Boolean isHeterozygous, Integer genotypeNum)
    {
        _afWriter.writeNext(new String[]{
                _subject,
                markerName,
                trueGenos != null ? StringUtils.join(trueGenos, ";") : "null",
                imputedGeno,
                String.valueOf(isMatch),
                String.valueOf(isError),
                String.valueOf(isMissing),
                String.valueOf(isUncalledRef),
                String.valueOf(af),
                String.valueOf(isHeterozygous),
                String.valueOf(genotypeNum)
        });
    }
}
