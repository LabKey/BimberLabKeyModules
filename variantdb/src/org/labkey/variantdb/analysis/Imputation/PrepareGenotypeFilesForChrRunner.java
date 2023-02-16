package org.labkey.variantdb.analysis.Imputation;

import au.com.bytecode.opencsv.CSVWriter;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.Interval;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.util.Job;
import org.labkey.api.util.Pair;
import org.labkey.api.writer.PrintWriters;
import org.labkey.variantdb.analysis.ImputationAnalysis;
import org.labkey.variantdb.run.ImputationRunner;
import org.labkey.variantdb.run.MendelianEvaluator;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Created by bimber on 4/28/2016.
 */
public class PrepareGenotypeFilesForChrRunner extends Job
{
    private final ImputationAnalysis.Processor.SampleSet _ss;
    private final String _sampleName;
    private final File _vcf;
    private final ImputationFileUtil.GiGiType _giGiType;
    private final File _outputDir;
    private final Logger _log;
    private final ImputationFileUtil.MarkerType _markerType;
    private final String _chr;
    private final @Nullable Integer _denseMarkerBatchIdx;
    private final List<Interval> _intervalList;
    private final File _gatkPed;
    private final List<List<String>> _alleleNameList;
    private final Map<String, List<Interval>> _genotypeBlacklist;
    private final List<String> _frameworkMarkerNames;
    private final boolean _maskNonFramework;

    private int _minGenotypeQual = 0;
    private int _minGenotypeDepth = 0;

    private final Map<String, List<String>> _messages = new HashMap<>();

    public PrepareGenotypeFilesForChrRunner(ImputationAnalysis.Processor.SampleSet ss, File vcf, String sampleName, ImputationFileUtil.GiGiType giGiType, File outputDir, Logger log, ImputationFileUtil.MarkerType markerType, String chr, @Nullable Integer denseMarkerBatchIdx, List<Interval> intervalList, File gatkPed, List<List<String>> alleleNameList, int minGenotypeQual, int minGenotypeDepth, Map<String, List<Interval>> genotypeBlacklist, List<String> frameworkMarkerNames, boolean maskNonFramework)
    {
        _sampleName = sampleName;
        _vcf = vcf;
        _giGiType = giGiType;
        _outputDir = outputDir;
        _log = log;
        _markerType = markerType;
        _chr = chr;
        _denseMarkerBatchIdx = denseMarkerBatchIdx;
        _intervalList = intervalList;
        _gatkPed = gatkPed;
        _alleleNameList = alleleNameList;
        _minGenotypeQual = minGenotypeQual;
        _minGenotypeDepth = minGenotypeDepth;
        _genotypeBlacklist = genotypeBlacklist;
        _frameworkMarkerNames = frameworkMarkerNames;
        _maskNonFramework = maskNonFramework;
        _ss = ss;
    }

    @Override
    public void run()
    {
        File subDir = new File(_outputDir, _chr);
        if (!subDir.exists())
        {
            subDir.mkdirs();
        }

        List<Interval> genotypeBlacklist;
        if (_genotypeBlacklist != null && _genotypeBlacklist.containsKey(_chr))
        {
            genotypeBlacklist = _genotypeBlacklist.get(_chr);
        }
        else
        {
            genotypeBlacklist = Collections.emptyList();
        }

        //write the output to a set of files we wil later merge
        try
        {
            int idx = 1;

            File vcfIdx = SequenceAnalysisService.get().ensureVcfIndex(_vcf, _log);
            File tmp = ImputationFileUtil.getGiGiGenotypeFile(_markerType, _outputDir, _chr, _sampleName, _giGiType, _denseMarkerBatchIdx);
            if (tmp.exists())
            {
                if (ImputationRunner.hasMinLineCount(tmp, 1))
                {
                    _log.info("genotype files exist for: " + getId() + ", will not recreate");
                    return;
                }
                else
                {
                    _log.info("deleting empty file: " + tmp.getPath());
                    tmp.delete();
                }
            }

            int markersWritten = 0;
            try (VCFFileReader reader = new VCFFileReader(_vcf, vcfIdx, true);PrintWriter writer = PrintWriters.getPrintWriter(tmp))
            {
                INTERVAL: for (Interval i : _intervalList)
                {
                    List<String> knownAlleles = _alleleNameList.get(idx - 1);  //idx is 1-based
                    idx++;
                    if (idx % 5000 == 0)
                    {
                        _log.info("processed " + idx + " loci for sample: " + _sampleName + ": " + _chr + (_denseMarkerBatchIdx == null ? "" : " / batch " + _denseMarkerBatchIdx));
                    }

                    //if this is a blacklist interval, write zeros for all samples
                    for (Interval other : genotypeBlacklist)
                    {
                        if (other.intersects(i))
                        {
                            throw new PipelineJobException("interval is within blacklist, setting genotypes to zeros: " + other.getStart() + ". this should have been filtered upstream");
                        }
                    }

                    try (CloseableIterator<VariantContext> it = reader.query(_chr, i.getStart(), i.getEnd()))
                    {
                        String markerName = ImputationFileUtil.getMarkerName(i);
                        if (!it.hasNext())
                        {
                            //if the subject is being imputed, we can assume the source genotypes are sparse, so this is not really unexpected
                            if (!_ss.imputedSampleIdStrings.contains(_sampleName))
                            {
                                addMsg(markerName, "position not found in: " + _vcf.getName());
                            }

                            //if not found, treat as no call.  this isnt ideal
                            writer.append(" ").append("0");
                            writer.append(" ").append("0");
                            markersWritten++;

                            continue INTERVAL;
                        }

                        MendelianEvaluator me = new MendelianEvaluator(_gatkPed);
                        me.setMinGenotypeQuality(0); //reject all
                        int variantsPerInterval = 0;
                        ITERATOR: while (it.hasNext())
                        {
                            VariantContext ctx = it.next();
                            variantsPerInterval++;

                            if (variantsPerInterval > 1)
                            {
                                addMsg(markerName, "found 2 variants per interval: " + i.getSequence() + "/" + i.getStart() + "/" + i.getEnd() + ", file: " + _vcf.getPath());
                            }

                            if (ctx.getReference().getDisplayString().length() > 1 || ctx.getReference().isSymbolic())
                            {
                                addMsg(markerName, "complex reference allele: " + ctx.getReference().getDisplayString());
                                //writer.append(" ").append("0");
                                //writer.append(" ").append("0");
                                //markersWritten++;
                                //continue ITERATOR;
                            }

                            Genotype g = ctx.getGenotype(_sampleName);
                            if (g.getAlleles().size() != 2)
                            {
                                throw new RuntimeException("More than 2 genotypes found for marker: " + ctx.getChr() + " " + ctx.getStart() + " and subject: " + g.getSampleName() + ". total found: " + g.getAlleles().size() + ", " + g.getGenotypeString());
                            }

                            if (g.isNoCall() || g.isFiltered() || me.isViolation(g.getSampleName(), ctx))
                            {
                                writer.append(" ").append("0");
                                writer.append(" ").append("0");
                                markersWritten++;

                                continue ITERATOR;
                            }

                            if (ctx.isFiltered())
                            {
                                addMsg(markerName, "filtered site, skipping position: " + (idx - 1) + "/" + ctx.getStart() + ". filter: " + StringUtils.join(ctx.getFilters(), ","));

                                writer.append(" ").append("0");
                                writer.append(" ").append("0");
                                markersWritten++;

                                continue ITERATOR;
                            }

                            if (g.getPhredScaledQual() < _minGenotypeQual)
                            {
                                addMsg(markerName, "low quality genotype (min: " + _minGenotypeQual + "), skipping position: " + (idx - 1) + "/" + ctx.getStart() + ". qual: " + g.getPhredScaledQual() + "/" + g.getGenotypeString());
                                writer.append(" ").append("0");
                                writer.append(" ").append("0");
                                markersWritten++;

                                continue ITERATOR;
                            }

                            if (g.getDP() < _minGenotypeDepth)
                            {
                                addMsg(markerName, "genotype DP below " + _minGenotypeDepth + ", skipping position: " + (idx - 1) + "/" + ctx.getStart() + ". DP: " + g.getDP() + "/" + g.getGenotypeString());
                                writer.append(" ").append("0");
                                writer.append(" ").append("0");
                                markersWritten++;

                                continue ITERATOR;
                            }

                            List<String> toAppend = new ArrayList<>();
                            for (Allele a : g.getAlleles())
                            {
                                if (a.isCalled())
                                {
                                    if (!knownAlleles.contains(a.getBaseString()))
                                    {
                                        //see comparable issue in ImputationAnalysis
                                        //this indicates we have alleles in the data not present in our AF source
                                        addMsg(markerName, "encountered allele in VCF (" + _giGiType.name() + ") not found in allele frequency VCF: " + (idx - 1) + ", [" + a.getBaseString() + "]. known alleles are: " + StringUtils.join(knownAlleles, ";") + ".  this will be reported as no call");
                                        toAppend.add("0");
                                        continue;
                                    }
                                    Integer ai = knownAlleles.indexOf(a.getBaseString()) + 1;

                                    if (ai == 1 && !a.isReference())
                                    {
                                        throw new RuntimeException("first allele is non-reference: " + ai + "/" + ctx.getStart() + "/" + a.getBaseString() + "/" + StringUtils.join(knownAlleles, ";"));
                                    }
                                    //else if (ai > 2)
                                    //{
                                    //    addMsg(markerName, "more than 2 alleles at site: " + (idx - 1) + ", " + ai + ", " + a.getBaseString() + ", [" + StringUtils.join(knownAlleles, "/") + "]");
                                    //}

                                    if (_frameworkMarkerNames.contains(markerName) || !_maskNonFramework)
                                    {
                                        toAppend.add(a.isReference() ? "1" : ai.toString());
                                    }
                                    else
                                    {
                                        toAppend.add("0");
                                    }
                                }
                                else
                                {
                                    toAppend.add("0");
                                }
                            }

                            if (toAppend.size() > 2)
                            {
                                throw new RuntimeException("sample: " + _sampleName + ", more than 2 alleles at " + markerName + ": " + StringUtils.join(toAppend, "/"));
                            }
                            else if ((toAppend.contains("0") && new HashSet<>(toAppend).size() > 1))
                            {
                                //gl_auto does not support mix of known/unknown genotypes
                                addMsg(markerName, "mix of known/unknown genotypes: " + StringUtils.join(toAppend, "/"));
                                writer.append(" ").append("0");
                                writer.append(" ").append("0");
                                markersWritten++;
                            }
                            else
                            {
                                writer.append(" ").append(toAppend.get(0));
                                writer.append(" ").append(toAppend.get(1));
                                markersWritten++;
                            }
                        }
                    }
                }
            }

            if (markersWritten != _intervalList.size())
            {
                addMsg("ALL_MARKERS", "The total # of markers written (" + markersWritten + ") does not equal the interval list size: " + _intervalList.size());
            }
        }
        catch (IOException | PipelineJobException e)
        {
            _log.error(e.getMessage(), e);

            throw new RuntimeException(e);
        }
    }

    public String getId()
    {
        return _sampleName + ", " + _chr + (_denseMarkerBatchIdx == null ? "" : " / batch " + _denseMarkerBatchIdx);
    }

    @Override
    protected void done(Throwable t)
    {
        if (null != t)
            _log.error("Uncaught exception in prepare genotype files job: " + getId(), t);
    }

    private void addMsg(String markerName, String msg)
    {
        if (!_messages.containsKey(markerName))
        {
            _messages.put(markerName, new LinkedList<>());
        }

        _messages.get(markerName).add(msg);
    }

    public void logMessages(CSVWriter writer)
    {
        for (String markerName : _messages.keySet())
        {
            for (String msg : _messages.get(markerName))
            {
                List<String> line = new ArrayList<>();
                line.add(_sampleName);
                line.add(_chr);
                line.add(String.valueOf(_denseMarkerBatchIdx));
                line.add(_markerType.name());
                line.add(markerName.split("_")[1]);

                Pair<Integer, String> pair = _ss.getReferenceForImputedSample(_sampleName);
                line.add(String.valueOf(pair.first));

                line.add(msg);

                writer.writeNext(line.toArray(new String[line.size()]));
            }
        }
    }
}
