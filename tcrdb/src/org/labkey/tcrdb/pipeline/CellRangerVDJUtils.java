package org.labkey.tcrdb.pipeline;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.labkey.api.assay.AssayProtocolSchema;
import org.labkey.api.assay.AssayProvider;
import org.labkey.api.assay.AssayService;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Container;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.exp.api.ExpProtocol;
import org.labkey.api.exp.api.ExpRun;
import org.labkey.api.exp.api.ExperimentService;
import org.labkey.api.iterator.CloseableIterator;
import org.labkey.api.laboratory.LaboratoryService;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.InvalidKeyException;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.QueryUpdateServiceException;
import org.labkey.api.query.UserSchema;
import org.labkey.api.query.ValidationException;
import org.labkey.api.reader.FastaDataLoader;
import org.labkey.api.reader.FastaLoader;
import org.labkey.api.reader.Readers;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.model.AnalysisModel;
import org.labkey.api.sequenceanalysis.model.Readset;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepOutput;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.util.FileUtil;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.view.ViewBackgroundInfo;
import org.labkey.api.view.ViewContext;
import org.labkey.api.writer.PrintWriters;
import org.labkey.tcrdb.TCRdbSchema;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class CellRangerVDJUtils
{
    private Logger _log;
    private File _sourceDir;

    public static final String READSET_TO_HASHING_MAP = "readsetToHashingMap";
    public static final String READSET_TO_CITESEQ_MAP = "readsetToCiteSeqMap";
    private static final String HASHING_CALLS = "Cell Hashing TCR Calls";

    public CellRangerVDJUtils(Logger log, File sourceDir)
    {
        _log = log;
        _sourceDir = sourceDir;
    }

    public void prepareHashingAndCiteSeqFilesIfNeeded(PipelineJob job, SequenceAnalysisJobSupport support, String filterField, final boolean skipFailedCdna, boolean failIfNoHashing, boolean failIfNoCiteSeq) throws PipelineJobException
    {
        Container target = job.getContainer().isWorkbook() ? job.getContainer().getParent() : job.getContainer();
        UserSchema tcr = QueryService.get().getUserSchema(job.getUser(), target, TCRdbSchema.NAME);
        TableInfo cDNAs = tcr.getTable(TCRdbSchema.TABLE_CDNAS, null);

        _log.debug("preparing cDNA and cell hashing files");

        SequenceAnalysisService.get().writeAllCellHashingBarcodes(_sourceDir, job.getUser(), job.getContainer());
        SequenceAnalysisService.get().writeAllCiteSeqBarcodes(_sourceDir, job.getUser(), job.getContainer());

        Map<FieldKey, ColumnInfo> colMap = QueryService.get().getColumns(cDNAs, PageFlowUtil.set(
                FieldKey.fromString("rowid"),
                FieldKey.fromString("sortId/stimId/animalId"),
                FieldKey.fromString("sortId/stimId/stim"),
                FieldKey.fromString("sortId/population"),
                FieldKey.fromString("sortId/hto"),
                FieldKey.fromString("sortId/hto/sequence"),
                FieldKey.fromString("hashingReadsetId"),
                FieldKey.fromString("hashingReadsetId/totalFiles"),
                FieldKey.fromString("citeseqReadsetId"),
                FieldKey.fromString("citeseqReadsetId/totalFiles"),
                FieldKey.fromString("citeseqPanel"),
                FieldKey.fromString("status"))
        );

        File output = getCDNAInfoFile();
        File barcodeOutput = getValidHashingBarcodeFile();
        HashMap<Integer, Integer> readsetToHashingMap = new HashMap<>();
        HashMap<Integer, Integer> readsetToCiteSeqMap = new HashMap<>();
        HashMap<Integer, Set<String>> gexToPanels = new HashMap<>();

        try (CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(output), '\t', CSVWriter.NO_QUOTE_CHARACTER); CSVWriter bcWriter = new CSVWriter(PrintWriters.getPrintWriter(barcodeOutput), ',', CSVWriter.NO_QUOTE_CHARACTER))
        {
            writer.writeNext(new String[]{"ReadsetId", "CDNA_ID", "AnimalId", "Stim", "Population", "HashingReadsetId", "HasHashingReads", "HTO_Name", "HTO_Seq", "CiteSeqReadsetId", "HasCiteSeqReads", "CiteSeqPanel"});
            List<Readset> cachedReadsets = support.getCachedReadsets();
            Set<String> distinctHTOs = new HashSet<>();
            Set<Boolean> hashingStatus = new HashSet<>();
            Set<Boolean> citeseqStatus = new HashSet<>();
            AtomicInteger totalWritten = new AtomicInteger(0);
            for (Readset rs : cachedReadsets)
            {
                AtomicBoolean hasError = new AtomicBoolean(false);
                //find cDNA records using this readset
                new TableSelector(cDNAs, colMap.values(), new SimpleFilter(FieldKey.fromString(filterField), rs.getRowId()), null).forEachResults(results -> {
                    if (skipFailedCdna && results.getObject(FieldKey.fromString("status")) != null)
                    {
                        _log.info("skipping cDNA with non-null status: " + results.getString(FieldKey.fromString("rowid")));
                        return;
                    }

                    writer.writeNext(new String[]{
                            String.valueOf(rs.getRowId()),
                            results.getString(FieldKey.fromString("rowid")),
                            results.getString(FieldKey.fromString("sortId/stimId/animalId")),
                            results.getString(FieldKey.fromString("sortId/stimId/stim")),
                            results.getString(FieldKey.fromString("sortId/population")),
                            String.valueOf(results.getObject(FieldKey.fromString("hashingReadsetId")) == null ? "" : results.getInt(FieldKey.fromString("hashingReadsetId"))),
                            String.valueOf(results.getObject(FieldKey.fromString("hashingReadsetId/totalFiles")) != null && results.getInt(FieldKey.fromString("hashingReadsetId/totalFiles")) > 0),
                            results.getString(FieldKey.fromString("sortId/hto")),
                            results.getString(FieldKey.fromString("sortId/hto/sequence")),
                            String.valueOf(results.getObject(FieldKey.fromString("citeseqReadsetId")) == null ? "" : results.getInt(FieldKey.fromString("citeseqReadsetId"))),
                            String.valueOf(results.getObject(FieldKey.fromString("citeseqReadsetId/totalFiles")) != null && results.getInt(FieldKey.fromString("citeseqReadsetId/totalFiles")) > 0),
                            results.getString(FieldKey.fromString("citeseqPanel"))
                    });
                    totalWritten.getAndIncrement();

                    boolean useCellHashing = results.getObject(FieldKey.fromString("sortId/hto")) != null;
                    hashingStatus.add(useCellHashing);
                    if (useCellHashing)
                    {
                        if (results.getObject(FieldKey.fromString("hashingReadsetId")) == null)
                        {
                            job.getLogger().error("cDNA specifies HTO, but does not list a hashing readset: " + results.getString(FieldKey.fromString("rowid")));
                            hasError.set(true);
                        }
                        else
                        {
                            readsetToHashingMap.put(rs.getReadsetId(), results.getInt(FieldKey.fromString("hashingReadsetId")));

                            String hto = results.getString(FieldKey.fromString("sortId/hto")) + "<>" + results.getString(FieldKey.fromString("sortId/hto/sequence"));
                            if (!distinctHTOs.contains(hto) && !StringUtils.isEmpty(results.getString(FieldKey.fromString("sortId/hto/sequence"))))
                            {
                                distinctHTOs.add(hto);
                                bcWriter.writeNext(new String[]{results.getString(FieldKey.fromString("sortId/hto/sequence")), results.getString(FieldKey.fromString("sortId/hto"))});
                            }

                            if (results.getObject(FieldKey.fromString("sortId/hto/sequence")) == null)
                            {
                                job.getLogger().error("Unable to find sequence for HTO: " + results.getString(FieldKey.fromString("sortId/hto")));
                                hasError.set(true);
                            }
                        }
                    }

                    boolean useCiteSeq = results.getObject(FieldKey.fromString("citeseqPanel")) != null;
                    citeseqStatus.add(useCiteSeq);
                    if (useCiteSeq)
                    {
                        if (results.getObject(FieldKey.fromString("citeseqReadsetId")) == null)
                        {
                            job.getLogger().error("cDNA specifies cite-seq readset but does not list panel: " + results.getString(FieldKey.fromString("rowid")));
                            hasError.set(true);
                        }
                        else
                        {
                            Set<String> panels = gexToPanels.getOrDefault(rs.getRowId(), new HashSet<>());
                            panels.add(results.getString(FieldKey.fromString("citeseqPanel")));
                            gexToPanels.put(rs.getRowId(), panels);

                            readsetToCiteSeqMap.put(rs.getReadsetId(), results.getInt(FieldKey.fromString("citeseqReadsetId")));
                        }
                    }
                });

                if (hasError.get())
                {
                    throw new PipelineJobException("No cell hashing readset or HTO found for one or more cDNAs. see the file: " + output.getName());
                }

                if (hashingStatus.size() > 1)
                {
                    _log.info("The selected readsets/cDNA records use a mixture of cell hashing and non-hashing.");
                }

                //NOTE: hashingStatus.isEmpty() indicates there are no cDNA records associated with the data
            }

            // if distinct HTOs is 1, no point in running hashing.  note: presence of hashing readsets is a trigger downstream
            if (distinctHTOs.size() > 1)
            {
                readsetToHashingMap.forEach((readsetId, hashingReadsetId) -> support.cacheReadset(hashingReadsetId, job.getUser()));
            }
            else if (distinctHTOs.size() == 1)
            {
                job.getLogger().info("There is only a single HTO in this pool, will not use hashing");
            }

            if (totalWritten.get() == 0)
            {
                throw new PipelineJobException("No matching cDNA records found");
            }

            boolean useCellHashing = hashingStatus.isEmpty() ? false : hashingStatus.size() > 1 ? true : hashingStatus.iterator().next();
            if (useCellHashing && distinctHTOs.isEmpty())
            {
                throw new PipelineJobException("Cell hashing was selected, but no HTOs were found");
            }
            else
            {
                _log.info("distinct HTOs: " + distinctHTOs.size());
            }

            support.cacheObject(READSET_TO_HASHING_MAP, readsetToHashingMap);
            support.cacheObject(READSET_TO_CITESEQ_MAP, readsetToCiteSeqMap);
            readsetToCiteSeqMap.forEach((readsetId, citeseqReadsetId) -> support.cacheReadset(citeseqReadsetId, job.getUser()));
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        writeCiteSeqBarcodes(job, gexToPanels, _sourceDir);

        if (failIfNoHashing && readsetToHashingMap.isEmpty())
        {
            throw new PipelineJobException("Readsets do not use cell hashing");
        }

        if (failIfNoCiteSeq && readsetToCiteSeqMap.isEmpty())
        {
            throw new PipelineJobException("Readsets do not use CITE-seq");
        }
    }

    public static File getValidCiteSeqBarcodeFile(File sourceDir, int gexReadsetId)
    {
        return new File(sourceDir, "validADTS." + gexReadsetId + ".csv");
    }

    public static File getValidCiteSeqBarcodeMetadataFile(File sourceDir, int gexReadsetId)
    {
        return new File(sourceDir, "validADTS." + gexReadsetId + ".metadata.txt");
    }

    private void writeCiteSeqBarcodes(PipelineJob job, Map<Integer, Set<String>> gexToPanels, File outputDir) throws PipelineJobException
    {
        Container target = job.getContainer().isWorkbook() ? job.getContainer().getParent() : job.getContainer();
        UserSchema tcr = QueryService.get().getUserSchema(job.getUser(), target, TCRdbSchema.NAME);
        TableInfo panels = tcr.getTable(TCRdbSchema.TABLE_CITE_SEQ_PANELS, null);

        Map<FieldKey, ColumnInfo> barcodeColMap = QueryService.get().getColumns(panels, PageFlowUtil.set(
                FieldKey.fromString("antibody"),
                FieldKey.fromString("antibody/markerName"),
                FieldKey.fromString("antibody/markerLabel"),
                FieldKey.fromString("markerLabel"),
                FieldKey.fromString("antibody/adaptersequence")
        ));

        for (int gexReadsetId : gexToPanels.keySet())
        {
            job.getLogger().info("Writing all unique ADTs for readset: " + gexReadsetId);
            File barcodeOutput = getValidCiteSeqBarcodeFile(outputDir, gexReadsetId);
            File metadataOutput = getValidCiteSeqBarcodeMetadataFile(outputDir, gexReadsetId);
            try (CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(barcodeOutput), ',', CSVWriter.NO_QUOTE_CHARACTER);CSVWriter metaWriter = new CSVWriter(PrintWriters.getPrintWriter(metadataOutput), '\t', CSVWriter.NO_QUOTE_CHARACTER))
            {
                metaWriter.writeNext(new String[]{"tagname", "sequence", "markername", "markerlabel"});
                AtomicInteger barcodeCount = new AtomicInteger();
                Set<String> found = new HashSet<>();
                new TableSelector(panels, barcodeColMap.values(), new SimpleFilter(FieldKey.fromString("name"), gexToPanels.get(gexReadsetId), CompareType.IN), new org.labkey.api.data.Sort("antibody")).forEachResults(results -> {
                    if (found.contains(results.getString(FieldKey.fromString("antibody/adaptersequence"))))
                    {
                        return;
                    }

                    found.add(results.getString(FieldKey.fromString("antibody/adaptersequence")));
                    barcodeCount.getAndIncrement();

                    writer.writeNext(new String[]{results.getString(FieldKey.fromString("antibody/adaptersequence")), results.getString(FieldKey.fromString("antibody"))});

                    //allow aliasing based on DB
                    String label = StringUtils.trimToNull(results.getString(FieldKey.fromString("markerLabel"))) == null ? results.getString(FieldKey.fromString("antibody/markerLabel")) : results.getString(FieldKey.fromString("markerLabel"));
                    String name = StringUtils.trimToNull(results.getString(FieldKey.fromString("markerLabel"))) != null ? results.getString(FieldKey.fromString("markerLabel")) :
                            StringUtils.trimToNull(results.getString(FieldKey.fromString("antibody/markerName"))) != null ? results.getString(FieldKey.fromString("antibody/markerName")) : results.getString(FieldKey.fromString("antibody"));
                    metaWriter.writeNext(new String[]{results.getString(FieldKey.fromString("antibody")), results.getString(FieldKey.fromString("antibody/adaptersequence")), name, label});
                });

                job.getLogger().info("Total CITE-seq barcodes written: " + barcodeCount.get());
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }
    }

    public File getCDNAInfoFile()
    {
        return getCDNAInfoFile(_sourceDir);
    }

    public static File getCDNAInfoFile(File sourceDir)
    {
        return new File(sourceDir, "cDNAInfo.txt");
    }

    public File getValidHashingBarcodeFile()
    {
        return getValidHashingBarcodeFile(_sourceDir);
    }

    public static File getValidHashingBarcodeFile(File sourceDir)
    {
        return new File(sourceDir, "validHashingBarcodes.csv");
    }

    public File getValidCellIndexFile()
    {
        return new File(_sourceDir, "validCellIndexes.csv");
    }

    public File getPerCellCsv(File outDir)
    {
        return new File(outDir, "all_contig_annotations.csv");
    }

    public File runRemoteVdjCellHashingTasks(PipelineStepOutput output, String outputCategory, File perCellTsv, Readset rs, SequenceAnalysisJobSupport support, List<String> extraParams, File workingDir, File sourceDir, Integer editDistance, boolean scanEditDistances, Integer genomeId, Integer minCountPerCell, boolean useSeurat, boolean useMultiSeq) throws PipelineJobException
    {
        Map<Integer, Integer> readsetToHashing = getCachedHashingReadsetMap(support);
        if (readsetToHashing.isEmpty())
        {
            _log.info("No cached hashing readsets, skipping");
            return null;
        }

        //prepare whitelist of barcodes, based on cDNA records
        File htoBarcodeWhitelist = getValidHashingBarcodeFile();
        if (!htoBarcodeWhitelist.exists())
        {
            throw new PipelineJobException("Unable to find file: " + htoBarcodeWhitelist.getPath());
        }

        long lineCount = SequencePipelineService.get().getLineCount(htoBarcodeWhitelist);
        if (lineCount == 1)
        {
            _log.info("Only one HTO is used, will not use hashing");
            return null;
        }

        _log.debug("total cached readset/hashing readset pairs: " + readsetToHashing.size());
        _log.debug("unique HTOs: " + lineCount);

        //prepare whitelist of cell indexes
        File cellBarcodeWhitelist = getValidCellIndexFile();
        Set<String> uniqueBarcodes = new HashSet<>();
        Set<String> uniqueBarcodesIncludingNoCDR3 = new HashSet<>();
        _log.debug("writing cell barcodes, using file: " + perCellTsv.getPath());
        try (CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(cellBarcodeWhitelist), ',', CSVWriter.NO_QUOTE_CHARACTER); CSVReader reader = new CSVReader(Readers.getReader(perCellTsv), ','))
        {
            int rowIdx = 0;
            int noCallRows = 0;
            int nonCell = 0;
            String[] row;
            while ((row = reader.readNext()) != null)
            {
                //skip header
                rowIdx++;
                if (rowIdx > 1)
                {
                    if ("False".equalsIgnoreCase(row[1]))
                    {
                        nonCell++;
                        continue;
                    }

                    //NOTE: allow these to pass for cell-hashing under some conditions
                    boolean hasCDR3 = !"None".equals(row[12]);
                    if (!hasCDR3)
                    {
                        noCallRows++;
                    }

                    //NOTE: 10x appends "-1" to barcodes
                    String barcode = row[0].split("-")[0];
                    if (hasCDR3 && !uniqueBarcodes.contains(barcode))
                    {
                        writer.writeNext(new String[]{barcode});
                        uniqueBarcodes.add(barcode);
                    }

                    uniqueBarcodesIncludingNoCDR3.add(barcode);
                }
            }

            _log.debug("rows inspected: " + (rowIdx - 1));
            _log.debug("rows without CDR3: " + noCallRows);
            _log.debug("rows not called as cells: " + nonCell);
            _log.debug("unique cell barcodes (with CDR3): " + uniqueBarcodes.size());
            _log.debug("unique cell barcodes (including no CDR3): " + uniqueBarcodesIncludingNoCDR3.size());
            output.addIntermediateFile(cellBarcodeWhitelist);
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        if (uniqueBarcodes.size() < 500 && uniqueBarcodesIncludingNoCDR3.size() > uniqueBarcodes.size())
        {
            _log.info("Total cell barcodes with CDR3s is low, so cell hashing will be performing using an input that includes valid cells that lacked CDR3 data.");
            try (CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(cellBarcodeWhitelist), ',', CSVWriter.NO_QUOTE_CHARACTER))
            {
                for (String barcode : uniqueBarcodesIncludingNoCDR3)
                {
                    writer.writeNext(new String[]{barcode});
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }

        Readset htoReadset = support.getCachedReadset(readsetToHashing.get(rs.getReadsetId()));
        if (htoReadset == null)
        {
            throw new PipelineJobException("Unable to find HTO readset for readset: " + rs.getRowId());
        }

        //run CiteSeqCount.  this will use Multiseq to make calls per cell
        String basename = FileUtil.makeLegalName(rs.getName());
        File hashtagCalls = SequencePipelineService.get().runCiteSeqCount(output, outputCategory, htoReadset, htoBarcodeWhitelist, cellBarcodeWhitelist, workingDir, basename, _log, extraParams, false, minCountPerCell, sourceDir, editDistance, scanEditDistances, rs, genomeId, true, true, useSeurat, useMultiSeq);
        if (!hashtagCalls.exists())
        {
            throw new PipelineJobException("Unable to find expected file: " + hashtagCalls.getPath());
        }
        output.addOutput(hashtagCalls, HASHING_CALLS);

        File html = new File(hashtagCalls.getParentFile(), FileUtil.getBaseName(FileUtil.getBaseName(hashtagCalls.getName())) + ".html");
        if (!html.exists())
        {
            throw new PipelineJobException("Unable to find HTML file: " + html.getPath());
        }

        output.addOutput(html, "Cell Hashing TCR Report");

        return hashtagCalls;
    }

    public void importAssayData(PipelineJob job, AnalysisModel model, File outDir, Integer assayId, @Nullable Integer runId, boolean deleteExisting) throws PipelineJobException
    {
        if (assayId == null)
        {
            _log.info("No assay selected, will not import");
            return;
        }

        ExpProtocol protocol = ExperimentService.get().getExpProtocol(assayId);
        if (protocol == null)
        {
            throw new PipelineJobException("Unable to find protocol: " + assayId);
        }

        File allCsv = getPerCellCsv(outDir);
        if (!allCsv.exists())
        {
            _log.warn("unable to find consensus contigs: " + allCsv .getPath());
            return;
        }

        File consensusCsv = new File(outDir, "consensus_annotations.csv");
        if (!consensusCsv .exists())
        {
            _log.warn("unable to find consensus contigs: " + consensusCsv .getPath());
            return;
        }

        File consensusFasta = new File(outDir, "consensus.fasta");
        if (!consensusFasta.exists())
        {
            _log.warn("unable to find FASTA: " + consensusFasta.getPath());
            return;
        }

        File allFasta = new File(outDir, "all_contig.fasta");
        if (!allFasta.exists())
        {
            _log.warn("unable to find FASTA: " + allFasta.getPath());
            return;
        }

        _log.info("loading results into assay: " + assayId);

        if (runId == null)
        {
            runId = SequencePipelineService.get().getExpRunIdForJob(job);
        }
        else
        {
            job.getLogger().debug("Using supplied runId: " + runId);
        }

        File cDNAFile = getCDNAInfoFile();
        Map<String, CDNA> htoNameToCDNAMap = new HashMap<>();
        Map<Integer, CDNA> cDNAMap = new HashMap<>();
        if (cDNAFile.exists())
        {
            try (CSVReader reader = new CSVReader(Readers.getReader(cDNAFile), '\t'))
            {
                String[] line;
                while ((line = reader.readNext()) != null)
                {
                    //header
                    if (line[0].startsWith("ReadsetId"))
                    {
                        continue;
                    }

                    String htoName = StringUtils.trimToNull(line[7]);

                    CDNA cdna = CDNA.getRowId(Integer.parseInt(line[1]));
                    cDNAMap.put(Integer.parseInt(line[1]), cdna);
                    if (htoName != null)
                    {
                        htoNameToCDNAMap.put(htoName, cdna);
                    }

                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }
        else
        {
            throw new PipelineJobException("Unable to find cDNA info file, expected: " + cDNAFile.getPath());
        }

        boolean useCellHashing = !htoNameToCDNAMap.isEmpty();
        if (htoNameToCDNAMap.size() == 1)
        {
            _log.debug("There is only one HTO in this pool, cell hashing will not be used");
            useCellHashing = false;
        }

        Integer defaultCDNA = null;
        if (!useCellHashing)
        {
            if (cDNAMap.size() > 1)
            {
                throw new PipelineJobException("More than one cDNA record found, but cell hashing is not used");
            }

            defaultCDNA = cDNAMap.keySet().iterator().next();
        }

        ExpRun run = ExperimentService.get().getExpRun(runId);
        if (run == null)
        {
            throw new PipelineJobException("Unable to find ExpRun: " + runId);
        }

        Map<String, Integer> cellBarcodeToCDNAMap = new HashMap<>();
        Set<String> doubletBarcodes = new HashSet<>();
        Set<String> discordantBarcodes = new HashSet<>();
        if (useCellHashing)
        {
            File cellbarcodeToHtoFile = getCellToHtoFile(run);
            if (!cellbarcodeToHtoFile.exists())
            {
                throw new PipelineJobException("Cell hashing output not found: " + cellbarcodeToHtoFile.getPath());
            }

            try (CSVReader reader = new CSVReader(Readers.getReader(cellbarcodeToHtoFile), '\t'))
            {
                //cellbarcode -> HTO name
                String[] line;
                int doublet = 0;
                int discordant = 0;
                int negative = 0;
                while ((line = reader.readNext()) != null)
                {
                    //header
                    if ("CellBarcode".equals(line[0]))
                    {
                        continue;
                    }

                    String hto = line[1];
                    if ("Doublet".equals(hto))
                    {
                        doublet++;
                        doubletBarcodes.add(line[0]);
                        continue;
                    }
                    else if ("Discordant".equals(hto))
                    {
                        discordant++;
                        discordantBarcodes.add(line[0]);
                        continue;
                    }
                    else if ("Negative".equals(hto))
                    {
                        negative++;
                        continue;
                    }

                    CDNA cDNA = htoNameToCDNAMap.get(hto);
                    if (cDNA == null)
                    {
                        _log.warn("Unable to find cDNA record for hto: " + hto);
                        continue;
                    }

                    cellBarcodeToCDNAMap.put(line[0], cDNA.getRowId());
                }

                _log.info("total doublets: " + doublet);
                _log.info("total discordant: " + discordant);
                _log.info("total negatives: " + negative);
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            job.getLogger().info("total cell hashing calls found: " + cellBarcodeToCDNAMap.size());
        }
        else
        {
            job.getLogger().debug("Cell hashing is not used");
        }

        Map<String, AssayModel> rows = new HashMap<>();
        Map<Integer, Set<String>> totalCellsBySample = new HashMap<>();
        Set<String> uniqueContigNames = new HashSet<>();
        _log.info("processing clonotype CSV: " + allCsv.getPath());

        // use unfiltered data so we can apply demultiplex and also apply alternate filtering logic.
        // also, 10x no longer reports TRD/TRG data in their consensus file
        //header: barcode	is_cell	contig_id	high_confidence	length	chain	v_gene	d_gene	j_gene	c_gene	full_length	productive	cdr3	cdr3_nt	reads	umis	raw_clonotype_id	raw_consensus_id
        try (CSVReader reader = new CSVReader(Readers.getReader(allCsv), ','))
        {
            String[] line;
            int idx = 0;
            int noCDR3 = 0;
            int noCGene = 0;
            int notFullLength = 0;
            int nonCell = 0;
            int totalSkipped = 0;
            int doubletSkipped = 0;
            int discordantSkipped = 0;
            int hasCDR3NoClonotype = 0;
            int multiChainConverted = 0;
            Set<String> knownBarcodes = new HashSet<>();
            while ((line = reader.readNext()) != null)
            {
                idx++;
                if (idx == 1)
                {
                    _log.debug("skipping header, length: " + line.length);
                    continue;
                }

                if ("False".equalsIgnoreCase(line[1]))
                {
                    nonCell++;
                    continue;
                }

                if ("None".equals(line[12]))
                {
                    noCDR3++;
                    continue;
                }

                if ("None".equals(line[9]))
                {
                    noCGene++;
                    continue;
                }

                if ("False".equals(line[10]))
                {
                    notFullLength++;
                    continue;
                }

                //NOTE: 10x appends "-1" to barcode sequences
                String barcode = line[0].split("-")[0];
                Integer cDNA = useCellHashing ? cellBarcodeToCDNAMap.get(barcode) : defaultCDNA;
                if (cDNA == null)
                {
                    if (doubletBarcodes.contains(barcode))
                    {
                        doubletSkipped++;
                    }
                    else if (discordantBarcodes.contains(barcode))
                    {
                        discordantSkipped++;
                    }
                    else
                    {
                        //_log.info("skipping cell barcode without HTO call: " + barcode);
                        totalSkipped++;
                    }
                    continue;
                }
                knownBarcodes.add(barcode);

                String clonotypeId = removeNone(line[16]);
                String cdr3 = removeNone(line[12]);
                if (clonotypeId == null && cdr3 != null && "TRUE".equalsIgnoreCase(line[10]))
                {
                    hasCDR3NoClonotype++;
                }

                if (clonotypeId == null)
                {
                    continue;
                }

                //Preferentially use raw_consensus_id, but fall back to contig_id
                String sequenceContigName = removeNone(line[17]) == null ? removeNone(line[2]) : removeNone(line[17]);

                //NOTE: chimeras with a TRDV / TRAJ / TRAC are relatively common. categorize as TRA for reporting ease
                String locus = line[5];
                if (locus.equals("Multi") && removeNone(line[9]) != null && removeNone(line[8]) != null && removeNone(line[6]) != null)
                {
                    if (removeNone(line[9]).contains("TRAC") && removeNone(line[8]).contains("TRAJ") && removeNone(line[6]).contains("TRDV"))
                    {
                        locus = "TRA";
                        multiChainConverted++;
                    }
                }

                // Aggregate by: cDNA_ID, cdr3, chain, raw_clonotype_id, sequenceContigName, vHit, dHit, jHit, cHit, cdr3_nt
                String key = StringUtils.join(new String[]{cDNA.toString(), line[12], locus, clonotypeId, sequenceContigName, removeNone(line[6]), removeNone(line[7]), removeNone(line[8]), removeNone(line[9]), removeNone(line[13])}, "<>");
                AssayModel am;
                if (!rows.containsKey(key))
                {
                    am = createForRow(line, sequenceContigName, cDNA, clonotypeId, locus);
                }
                else
                {
                    am = rows.get(key);
                }

                uniqueContigNames.add(am.sequenceContigName);
                am.barcodes.add(barcode);
                rows.put(key, am);

                Set<String> cellbarcodesPerSample = totalCellsBySample.getOrDefault(cDNA, new HashSet<>());
                cellbarcodesPerSample.add(barcode);
                totalCellsBySample.put(cDNA, cellbarcodesPerSample);
            }

            int totalCells = idx - nonCell;
            _log.info("total clonotype rows inspected: " + idx);
            _log.info("total rows not cells: " + nonCell);
            _log.info("total rows marked as cells: " + totalCells);
            _log.info("total clonotype rows without CDR3: " + noCDR3);
            _log.info("total clonotype rows discarded for no C-gene: " + noCGene);
            _log.info("total clonotype rows discarded for not full length: " + notFullLength);
            _log.info("total clonotype rows skipped for unknown barcodes: " + totalSkipped + " (" + (NumberFormat.getPercentInstance().format(totalSkipped / (double)totalCells)) + ")");
            _log.info("total clonotype rows skipped because they are doublets: " + doubletSkipped + " (" + (NumberFormat.getPercentInstance().format(doubletSkipped / (double)totalCells)) + ")");
            _log.info("total clonotype rows skipped because they are discordant calls: " + discordantSkipped + " (" + (NumberFormat.getPercentInstance().format(discordantSkipped / (double)totalCells)) + ")");
            _log.info("unique known cell barcodes: " + knownBarcodes.size());
            _log.info("total clonotypes: " + rows.size());
            _log.info("total sequences: " + uniqueContigNames.size());
            _log.info("total cells with CDR3, lacking clonotype: " + hasCDR3NoClonotype);
            _log.info("total rows converted from Multi to TRA: " + multiChainConverted);

        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        //build map of distinct FL sequences:
        Map<String, String> sequenceMap = new HashMap<>();
        for (File f : Arrays.asList(consensusFasta, allFasta))
        {
            _log.info("processing FASTA: " + f.getPath());
            try (FastaDataLoader loader = new FastaDataLoader(f, false))
            {
                loader.setCharacterFilter(new FastaLoader.UpperAndLowercaseCharacterFilter());
                try (CloseableIterator<Map<String, Object>> i = loader.iterator())
                {
                    while (i.hasNext())
                    {
                        Map<String, Object> fastaRecord = i.next();
                        String header = (String) fastaRecord.get("header");
                        if (uniqueContigNames.contains(header))
                        {
                            sequenceMap.put(header, (String) fastaRecord.get("sequence"));
                        }
                    }
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            _log.info("total sequences: " + sequenceMap.size());
        }

        List<Map<String, Object>> assayRows = new ArrayList<>();
        int totalCells = 0;
        Set<String> clonesInspected = new HashSet<>();
        for (AssayModel m : rows.values())
        {
            clonesInspected.add(m.cloneId);
            totalCells += m.barcodes.size();

            assayRows.add(processRow(m, model, cDNAMap, runId, totalCellsBySample, sequenceMap));
        }

        _log.info("total added: " + clonesInspected.size());
        _log.info("total assay rows: " + assayRows.size());
        _log.info("total cells: " + totalCells);
        saveRun(job, protocol, model, assayRows, outDir, runId, deleteExisting);
    }

    private AssayModel createForRow(String[] line, String sequenceContigName, Integer cDNA, String clonotypeId, String locus)
    {
        AssayModel am = new AssayModel();
        am.cdna = cDNA;
        am.cdr3 = removeNone(line[12]);
        am.locus = locus;
        am.cloneId = clonotypeId;
        am.sequenceContigName = sequenceContigName;

        am.vHit = removeNone(line[6]);
        am.dHit = removeNone(line[7]);
        am.jHit = removeNone(line[8]);
        am.cHit = removeNone(line[9]);
        am.cdr3Nt = removeNone(line[13]);

        return am;
    }

    private File getCellToHtoFile(ExpRun run) throws PipelineJobException
    {
        List<? extends ExpData> datas = run.getInputDatas(HASHING_CALLS, ExpProtocol.ApplicationType.ExperimentRunOutput);
        if (datas.isEmpty())
        {
            throw new PipelineJobException("Unable to find hashing calls output");
        }

        if (datas.size() > 1)
        {
            throw new PipelineJobException("More than one cell hashing calls output found");
        }

        File ret = datas.get(0).getFile();
        if (ret == null || !ret.exists())
        {
            throw new PipelineJobException("Unable to find file: " + (ret == null ? "null" : ret.getPath()));
        }

        return ret;
    }

    private static class AssayModel
    {
        private String cloneId;
        private String locus;
        private String cdr3;
        private String cdr3Nt;
        private String vHit;
        private String dHit;
        private String jHit;
        private String cHit;
        private int cdna;

        private Set<String> barcodes = new HashSet<>();
        private String sequenceContigName;
    }

    private Map<String, Object> processRow(AssayModel assayModel, AnalysisModel model, Map<Integer, CDNA> cDNAMap, Integer runId, Map<Integer, Set<String>> totalCellsBySample, Map<String, String> sequenceMap) throws PipelineJobException
    {
        CDNA cDNARecord = cDNAMap.get(assayModel.cdna);
        if (cDNARecord == null)
        {
            throw new PipelineJobException("Unable to find cDNA for ID: " + assayModel.cdna);
        }

        Map<String, Object> row = new CaseInsensitiveHashMap<>();

        row.put("sampleName", cDNARecord.getAssaySampleName());
        row.put("subjectId", cDNARecord.getSortRecord().getStimRecord().getAnimalId());
        row.put("sampleDate", cDNARecord.getSortRecord().getStimRecord().getDate());
        row.put("cDNA", assayModel.cdna);

        row.put("alignmentId", model.getAlignmentFile());
        row.put("analysisId", model.getRowId());
        row.put("pipelineRunId", runId);

        row.put("cloneId", assayModel.cloneId == null || assayModel.cloneId.contains("<>") ? null : assayModel.cloneId);
        row.put("locus", assayModel.locus);
        row.put("vHit", assayModel.vHit);
        row.put("dHit", assayModel.dHit);
        row.put("jHit", assayModel.jHit);
        row.put("cHit", assayModel.cHit);

        row.put("cdr3", assayModel.cdr3);
        row.put("cdr3_nt", assayModel.cdr3Nt);
        row.put("count", assayModel.barcodes.size());

        double fraction = (double)assayModel.barcodes.size() / totalCellsBySample.get(assayModel.cdna).size();
        row.put("fraction", fraction);

        if (!sequenceMap.containsKey(assayModel.sequenceContigName))
        {
            throw new PipelineJobException("Unable to find sequence for: " + assayModel.sequenceContigName);
        }

        row.put("sequence", sequenceMap.get(assayModel.sequenceContigName));

        return row;
    }

    private String removeNone(String input)
    {
        return "None".equals(input) ? null : input;
    }

    private void saveRun(PipelineJob job, ExpProtocol protocol, AnalysisModel model, List<Map<String, Object>> rows, File outDir, Integer runId, boolean deleteExisting) throws PipelineJobException
    {
        ViewBackgroundInfo info = job.getInfo();
        ViewContext vc = ViewContext.getMockViewContext(info.getUser(), info.getContainer(), info.getURL(), false);

        JSONObject runProps = new JSONObject();
        runProps.put("performedby", job.getUser().getDisplayName(job.getUser()));
        runProps.put("assayName", "10x");
        runProps.put("Name", "Analysis: " + model.getAnalysisId());
        runProps.put("analysisId", model.getAnalysisId());
        runProps.put("pipelineRunId", runId);

        if (model.getLibraryId() != null)
        {
            TableSelector ts = new TableSelector(TCRdbSchema.getInstance().getSchema().getTable(TCRdbSchema.TABLE_LIBRARIES), PageFlowUtil.set("rowid"), new SimpleFilter(FieldKey.fromString("libraryId"), model.getLibraryId()), null);
            if (ts.exists())
            {
                int mixcrId = ts.getObject(Integer.class);
                _log.debug("adding mixcr library id: " + mixcrId);
                for (Map<String, Object> row : rows)
                {
                    row.put("libraryId", mixcrId);
                }
            }
            else
            {
                _log.debug("Unable to find MiXCR library for genome: " + model.getLibraryId());
            }
        }

        JSONObject json = new JSONObject();
        json.put("Run", runProps);

        File assayTmp = new File(outDir, FileUtil.makeLegalName("10x-assay-upload_" + FileUtil.getTimestamp() + ".txt"));
        if (assayTmp.exists())
        {
            assayTmp.delete();
        }

        _log.info("total rows imported: " + rows.size());
        if (!rows.isEmpty())
        {
            _log.debug("saving assay file to: " + assayTmp.getPath());
            try
            {
                AssayProvider ap = AssayService.get().getProvider(protocol);
                if (deleteExisting)
                {
                    if (model.getReadset() == null)
                    {
                        _log.info("No readset found for this sample, cannot delete existing runs");
                    }
                    else
                    {
                        deleteExistingData(ap, protocol, info.getContainer(), info.getUser(), _log, model.getReadset());
                    }
                }

                LaboratoryService.get().saveAssayBatch(rows, json, assayTmp, vc, ap, protocol);
            }
            catch (ValidationException e)
            {
                throw new PipelineJobException(e);
            }
        }
    }

    public static void deleteExistingData(AssayProvider ap, ExpProtocol protocol, Container c, User u, Logger log, int readsetId) throws PipelineJobException
    {
        log.info("Preparing to delete any existing runs from this container for the same readset: " + readsetId);

        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("analysisId/readset"), readsetId);
        filter.addCondition(FieldKey.fromString("Folder"), c.getId());

        AssayProtocolSchema aps = ap.createProtocolSchema(u, c, protocol, null);
        TableInfo runsTable = QueryService.get().getUserSchema(u, c, aps.getSchemaPath()).getTable(AssayProtocolSchema.RUNS_TABLE_NAME);

        TableSelector ts = new TableSelector(runsTable, PageFlowUtil.set("RowId"), filter, null);
        if (ts.exists())
        {
            Collection<Integer> toDelete = ts.getArrayList(Integer.class);
            if (!toDelete.isEmpty())
            {
                log.info("Deleting existing runs: " + StringUtils.join(toDelete, ";"));
                List<Map<String, Object>> keys = new ArrayList<>();
                toDelete.forEach(x -> {
                    Map<String, Object> row = new CaseInsensitiveHashMap<>();
                    row.put("rowid", x);
                    keys.add(row);
                });

                try
                {
                    runsTable.getUpdateService().deleteRows(u, c, keys, null, null);
                }
                catch (BatchValidationException | SQLException | QueryUpdateServiceException | InvalidKeyException e)
                {
                    throw new PipelineJobException(e);
                }
            }
        }
    }

    public static Map<Integer, Integer> getCachedHashingReadsetMap(SequenceAnalysisJobSupport support) throws PipelineJobException
    {
        return support.getCachedObject(CellRangerVDJUtils.READSET_TO_HASHING_MAP, PipelineJob.createObjectMapper().getTypeFactory().constructParametricType(Map.class, Integer.class, Integer.class));
    }

    public static Map<Integer, Integer> getCachedCiteSeqReadsetMap(SequenceAnalysisJobSupport support) throws PipelineJobException
    {
        return support.getCachedObject(CellRangerVDJUtils.READSET_TO_CITESEQ_MAP, PipelineJob.createObjectMapper().getTypeFactory().constructParametricType(Map.class, Integer.class, Integer.class));
    }

    //NOTE: if readset ID is null, this will be interpreted as any readset using hashing
    public boolean useCellHashing(SequenceAnalysisJobSupport support) throws PipelineJobException
    {
        Map<Integer, Integer> gexToHashingMap = getCachedHashingReadsetMap(support);
        if (gexToHashingMap == null || gexToHashingMap.isEmpty())
            return false;

        File htoBarcodeWhitelist = getValidHashingBarcodeFile();
        if (!htoBarcodeWhitelist.exists())
        {
            throw new PipelineJobException("Unable to find file: " + htoBarcodeWhitelist.getPath());
        }

        return SequencePipelineService.get().getLineCount(htoBarcodeWhitelist) > 1;
    }

    //NOTE: if readset ID is null, this will be interpreted as any readset using hashing
    public boolean useCiteSeq(SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles) throws PipelineJobException
    {
        Map<Integer, Integer> gexToCiteMap = getCachedCiteSeqReadsetMap(support);
        if (gexToCiteMap == null || gexToCiteMap.isEmpty())
            return false;

        for (SequenceOutputFile so : inputFiles)
        {
            if (gexToCiteMap.containsKey(so.getReadset()))
            {
                return true;
            }
        }

        return false;
    }

    public static class CDNA
    {
        private int _rowId;
        private Integer _sortId;
        private String _chemistry;
        private Double _concentration;
        private String _plateId;
        private String _well;

        private Integer _readsetId;
        private Integer _enrichedReadsetId;
        private Integer _hashingReadsetId;
        private String _container;

        private Sort _sortRecord;

        public int getRowId()
        {
            return _rowId;
        }

        public void setRowId(int rowId)
        {
            _rowId = rowId;
        }

        public Integer getSortId()
        {
            return _sortId;
        }

        public void setSortId(Integer sortId)
        {
            _sortId = sortId;
        }

        public String getChemistry()
        {
            return _chemistry;
        }

        public void setChemistry(String chemistry)
        {
            _chemistry = chemistry;
        }

        public Double getConcentration()
        {
            return _concentration;
        }

        public void setConcentration(Double concentration)
        {
            _concentration = concentration;
        }

        public String getPlateId()
        {
            return _plateId;
        }

        public void setPlateId(String plateId)
        {
            _plateId = plateId;
        }

        public String getWell()
        {
            return _well;
        }

        public void setWell(String well)
        {
            _well = well;
        }

        public Integer getReadsetId()
        {
            return _readsetId;
        }

        public void setReadsetId(Integer readsetId)
        {
            _readsetId = readsetId;
        }

        public Integer getEnrichedReadsetId()
        {
            return _enrichedReadsetId;
        }

        public void setEnrichedReadsetId(Integer enrichedReadsetId)
        {
            _enrichedReadsetId = enrichedReadsetId;
        }

        public Integer getHashingReadsetId()
        {
            return _hashingReadsetId;
        }

        public void setHashingReadsetId(Integer hashingReadsetId)
        {
            _hashingReadsetId = hashingReadsetId;
        }

        public String getContainer()
        {
            return _container;
        }

        public void setContainer(String container)
        {
            _container = container;
        }

        public Sort getSortRecord()
        {
            if (_sortRecord == null)
            {
                _sortRecord = Sort.getRowId(_sortId);
            }

            return _sortRecord;
        }

        public String getAssaySampleName()
        {
            return getPlateId() + "_" + getWell() + "_" + getSortRecord().getStimRecord().getAnimalId() + "_" + getSortRecord().getStimRecord().getStim() + "_" + getSortRecord().getPopulation() + (getSortRecord().getHto() == null ? "" : "_" + getSortRecord().getHto());
        }

        public static CDNA getRowId(int rowId)
        {
            return new TableSelector(TCRdbSchema.getInstance().getSchema().getTable(TCRdbSchema.TABLE_CDNAS)).getObject(rowId, CDNA.class);
        }
    }

    public static class Sort
    {
        private int _rowId;
        private Integer _stimId;
        private String _population;
        private String _hto;

        private Stim _stimRecord;

        public Stim getStimRecord()
        {
            if (_stimRecord == null)
            {
                _stimRecord = Stim.getRowId(_stimId);
            }

            return _stimRecord;
        }

        public int getRowId()
        {
            return _rowId;
        }

        public void setRowId(int rowId)
        {
            _rowId = rowId;
        }

        public Integer getStimId()
        {
            return _stimId;
        }

        public void setStimId(Integer stimId)
        {
            _stimId = stimId;
        }

        public String getPopulation()
        {
            return _population;
        }

        public void setPopulation(String population)
        {
            _population = population;
        }

        public String getHto()
        {
            return _hto;
        }

        public void setHto(String hto)
        {
            _hto = hto;
        }

        public void setStimRecord(Stim stimRecord)
        {
            _stimRecord = stimRecord;
        }

        public static Sort getRowId(int rowId)
        {
            return new TableSelector(TCRdbSchema.getInstance().getSchema().getTable(TCRdbSchema.TABLE_SORTS)).getObject(rowId, Sort.class);
        }
    }

    public static class Stim
    {
        private int _rowId;
        private String _animalId;
        private String _stim;
        private Date _date;

        public int getRowId()
        {
            return _rowId;
        }

        public void setRowId(int rowId)
        {
            _rowId = rowId;
        }

        public String getAnimalId()
        {
            return _animalId;
        }

        public void setAnimalId(String animalId)
        {
            _animalId = animalId;
        }

        public String getStim()
        {
            return _stim;
        }

        public void setStim(String stim)
        {
            _stim = stim;
        }

        public Date getDate()
        {
            return _date;
        }

        public void setDate(Date date)
        {
            _date = date;
        }

        public static Stim getRowId(int rowId)
        {
            return new TableSelector(TCRdbSchema.getInstance().getSchema().getTable(TCRdbSchema.TABLE_STIMS)).getObject(rowId, Stim.class);
        }
    }
}
