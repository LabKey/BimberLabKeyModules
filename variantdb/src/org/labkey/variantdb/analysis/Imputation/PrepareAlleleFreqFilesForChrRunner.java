package org.labkey.variantdb.analysis.Imputation;

import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.Interval;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.util.Job;
import org.labkey.api.writer.PrintWriters;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Created by bimber on 4/28/2016.
 */
public class PrepareAlleleFreqFilesForChrRunner extends Job
{
    private final File _alleleFreqVcf;
    private final ImputationFileUtil.MarkerType _markerType;
    private final String _chr;
    private final @Nullable Integer _denseMarkerBatchIdx;
    private final File _alleleFreqDir;
    private final Logger _log;
    private final List<Interval> _intervalList;
    private final String _freqFieldName = "AF";

    private final List<List<String>> _markerToBaseList = new ArrayList<>();

    public PrepareAlleleFreqFilesForChrRunner(File alleleFreqVcf, ImputationFileUtil.MarkerType markerType, String chr, @Nullable Integer denseMarkerBatchIdx, File alleleFreqDir, Logger log, List<Interval> intervalList)
    {
        _alleleFreqVcf = alleleFreqVcf;
        _markerType = markerType;
        _chr = chr;
        _denseMarkerBatchIdx = denseMarkerBatchIdx;
        _alleleFreqDir = alleleFreqDir;
        _log = log;
        _intervalList = intervalList;
    }

    @Override
    public void run()
    {
        _log.info("preparing " + _markerType.name() + " frequency files for: " + _chr + (_denseMarkerBatchIdx == null ? "" : ", batch: " + _denseMarkerBatchIdx));

        File frequencyFile = ImputationFileUtil.getAlleleFreqGenotypesFile(_alleleFreqDir, _markerType, _chr, _denseMarkerBatchIdx);
        File alleleFrequencyFile = ImputationFileUtil.getAlleleFreqFile(_alleleFreqDir, _markerType, _chr, _denseMarkerBatchIdx);

        //note: dont shortcut this step on resume since we need to populate our maps
        try (PrintWriter frequencyWriter = PrintWriters.getPrintWriter(frequencyFile); PrintWriter frequencyWriter2 = PrintWriters.getPrintWriter(alleleFrequencyFile))
        {
            int idx = 0;
            for (Interval i : _intervalList)
            {
                idx++;
                if (idx % 5000 == 0)
                {
                    _log.info("processed " + idx + " allele freq loci for: " + _chr + (_denseMarkerBatchIdx == null ? "" : " / batch " + _denseMarkerBatchIdx));
                }

                File vcfIdx = SequenceAnalysisService.get().ensureVcfIndex(_alleleFreqVcf, _log);
                try (VCFFileReader reader = new VCFFileReader(_alleleFreqVcf, vcfIdx, true))
                {
                    try (CloseableIterator<VariantContext> it = reader.query(_chr, i.getStart(), i.getEnd()))
                    {
                        String markerName = ImputationFileUtil.getMarkerName(i);
                        if (!it.hasNext())
                        {
                            if (_markerType == ImputationFileUtil.MarkerType.framework)
                            {
                                throw new RuntimeException("position not found: " + markerName + " for allele frequencies in file: " + _alleleFreqVcf.getName());
                            }
                            else
                            {
                                _log.warn("position not found: " + markerName + " for allele frequencies in file: " + _alleleFreqVcf.getName());
                            }
                        }

                        while (it.hasNext())
                        {
                            VariantContext ctx = it.next();
                            if (ctx.getAttribute(_freqFieldName) == null)
                            {
                                throw new RuntimeException("No allele frequency found for marker: " + ctx.getChr() + " " + ctx.getStart());
                            }

                            List<String> afs;
                            if (ctx.getAttribute(_freqFieldName) instanceof List)
                            {
                                afs = (List) ctx.getAttribute(_freqFieldName);
                            }
                            else
                            {
                                afs = Arrays.asList(ctx.getAttributeAsString(_freqFieldName, null).split(","));
                            }

                            if (ctx.isFiltered())
                            {
                                _log.info(_chr + " " + markerName + ": site is filtered: " + StringUtils.join(ctx.getFilters(), ","));
                            }

                            DecimalFormat fmt = new DecimalFormat("0.000");
                            fmt.setRoundingMode(RoundingMode.HALF_UP);

                            Double totalNonRef = 0.0;
                            List<Double> nonRefs = new ArrayList<>();
                            for (String d : afs)
                            {
                                Double dd = Double.parseDouble(d);
                                dd = Math.round(dd * 1000d) / 1000d;  //round to 3 decimals

                                //if the non-ref AF is 1.0, we need to adjust to add something for the ref
                                if (dd == 1.0)
                                {
                                    dd = 0.99;
                                }

                                totalNonRef += dd;
                                nonRefs.add(dd);
                            }

                            if (totalNonRef >= 1.0)
                            {
                                _log.error(_chr + " " + markerName + ", " + idx + ": total non-ref AF is " + totalNonRef + " (alternates: " + nonRefs.size() + ").  Lowering all values by 0.001 so MORGAN/GIGI will run.");
                                List<Double> adjustedVals = new ArrayList<>();
                                totalNonRef = 0.0;
                                for (Double d : nonRefs)
                                {
                                    _log.debug("old: " + d + ", new: " + (d - 0.001));
                                    adjustedVals.add(d - 0.001);
                                    totalNonRef += (d - 0.001);
                                }

                                nonRefs = adjustedVals;
                            }

                            if (totalNonRef > 1.0)
                            {
                                _log.info(_chr + " " + markerName + ", " + idx + ": total non-ref AFs GT zero, " + totalNonRef);
                                _log.info(StringUtils.join(nonRefs, ";"));
                            }

                            String roundedTotalRef = fmt.format(Math.max(1 - totalNonRef, 0));
                            frequencyWriter.append("set markers " + idx + " allele freqs " + roundedTotalRef);
                            frequencyWriter2.append(roundedTotalRef);
                            for (Double af : nonRefs)
                            {
                                frequencyWriter.append(" ").append(fmt.format(af));
                                frequencyWriter2.append(" ").append(fmt.format(af));
                            }
                            frequencyWriter.append("\n");
                            frequencyWriter2.append("\n");

                            List<String> alleles = new ArrayList<>();
                            for (Allele a : ctx.getAlleles())
                            {
                                if (a.getBases().length > 1 || a.isSymbolic())
                                {
                                    _log.warn("complex reference allele: " + _chr + ctx.getStart() + ". " + a.getDisplayString());
                                    alleles.add(a.getBaseString());
                                }
                                else
                                {
                                    alleles.add(a.getBaseString());
                                }
                            }

                            if (!alleles.contains(ctx.getReference().getBaseString()))
                            {
                                _log.warn("adding reference: " + markerName);
                                alleles.add(0, ctx.getReference().getBaseString());
                            }

                            if (!alleles.get(0).equals(ctx.getReference().getBaseString()))
                            {
                                throw new RuntimeException("first allele is non-reference: " + markerName + "/" + ctx.getReference().getBaseString() + "/" + StringUtils.join(alleles, ";"));
                            }

                            _markerToBaseList.add(alleles);
                        }
                    }
                }
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public String getChr()
    {
        return _chr;
    }

    public List<List<String>> getMarkerToBaseList()
    {
        return Collections.unmodifiableList(_markerToBaseList);
    }

    @Override
    protected void done(Throwable t)
    {
        if (null != t)
            _log.error("Uncaught exception in allele freq job: " + _chr, t);
    }
}
