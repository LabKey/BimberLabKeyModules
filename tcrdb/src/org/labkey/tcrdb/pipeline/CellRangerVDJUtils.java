package org.labkey.tcrdb.pipeline;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.Container;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.exp.api.ExpProtocol;
import org.labkey.api.exp.api.ExperimentService;
import org.labkey.api.iterator.CloseableIterator;
import org.labkey.api.laboratory.LaboratoryService;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.UserSchema;
import org.labkey.api.query.ValidationException;
import org.labkey.api.reader.FastaDataLoader;
import org.labkey.api.reader.FastaLoader;
import org.labkey.api.reader.Readers;
import org.labkey.api.sequenceanalysis.model.AnalysisModel;
import org.labkey.api.sequenceanalysis.model.Readset;
import org.labkey.api.sequenceanalysis.pipeline.AlignmentOutputImpl;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.study.assay.AssayService;
import org.labkey.api.util.FileUtil;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.view.ViewBackgroundInfo;
import org.labkey.api.view.ViewContext;
import org.labkey.api.writer.PrintWriters;
import org.labkey.tcrdb.TCRdbSchema;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class CellRangerVDJUtils
{
    private Logger _log;
    private File _sourceDir;
    
    public static final String READSET_TO_HASHING_MAP = "readsetToHashingMap";
    
    public CellRangerVDJUtils(Logger log, File sourceDir)
    {
        _log = log;    
        _sourceDir = sourceDir;
    }
    
    public void prepareVDJHashingFiles(PipelineJob job, SequenceAnalysisJobSupport support) throws PipelineJobException
    {
        _log.debug("preparing cell hashing files");

        CellRangerVDJUtils.writeAllBarcodes(_sourceDir);

        Container target = job.getContainer().isWorkbook() ? job.getContainer().getParent() : job.getContainer();
        UserSchema tcr = QueryService.get().getUserSchema(job.getUser(), target, TCRdbSchema.NAME);
        TableInfo cDNAs = tcr.getTable(TCRdbSchema.TABLE_CDNAS);

        Map<FieldKey, ColumnInfo> colMap = QueryService.get().getColumns(cDNAs, PageFlowUtil.set(
                FieldKey.fromString("rowid"),
                FieldKey.fromString("sortId/stimId/animalId"),
                FieldKey.fromString("sortId/stimId/stim"),
                FieldKey.fromString("sortId/population"),
                FieldKey.fromString("sortId/hto"),
                FieldKey.fromString("sortId/hto/sequence"),
                FieldKey.fromString("hashingReadsetId"))
        );

        File output = getCDNAInfoFile();
        File barcodeOutput = getValidHashingBarcodeFile();
        HashMap<Integer, Integer> readsetToHashingMap = new HashMap<>();
        try (CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(output), '\t', CSVWriter.NO_QUOTE_CHARACTER); CSVWriter bcWriter = new CSVWriter(PrintWriters.getPrintWriter(barcodeOutput), ',', CSVWriter.NO_QUOTE_CHARACTER))
        {
            writer.writeNext(new String[]{"TCR_ReadsetId", "CDNA_ID", "AnimalId", "Stim", "Population", "HTO_Name", "HTO_Seq", "HashingReadsetId"});
            List<Readset> cachedReadsets = support.getCachedReadsets();
            Set<String> distinctHTOs = new HashSet<>();
            for (Readset rs : cachedReadsets)
            {
                AtomicBoolean hasError = new AtomicBoolean(false);
                //find cDNA records using this as enrichedReadset
                new TableSelector(cDNAs, colMap.values(), new SimpleFilter(FieldKey.fromString("enrichedReadsetId"), rs.getRowId()), null).forEachResults(results -> {
                    writer.writeNext(new String[]{
                            String.valueOf(rs.getRowId()),
                            results.getString(FieldKey.fromString("rowid")),
                            results.getString(FieldKey.fromString("sortId/stimId/animalId")),
                            results.getString(FieldKey.fromString("sortId/stimId/stim")),
                            results.getString(FieldKey.fromString("sortId/population")),
                            results.getString(FieldKey.fromString("sortId/hto")),
                            results.getString(FieldKey.fromString("sortId/hto/sequence")),
                            String.valueOf(results.getInt(FieldKey.fromString("hashingReadsetId")))
                    });

                    if (results.getObject(FieldKey.fromString("hashingReadsetId")) == null)
                    {
                        hasError.set(true);
                    }

                    if (results.getObject(FieldKey.fromString("sortId/hto/sequence")) == null)
                    {
                        hasError.set(true);
                    }

                    support.cacheReadset(results.getInt(FieldKey.fromString("hashingReadsetId")), job.getUser());
                    readsetToHashingMap.put(rs.getReadsetId(), results.getInt(FieldKey.fromString("hashingReadsetId")));

                    String hto = results.getString(FieldKey.fromString("sortId/hto")) + "<>" + results.getString(FieldKey.fromString("sortId/hto/sequence"));
                    if (!distinctHTOs.contains(hto) && !StringUtils.isEmpty(results.getString(FieldKey.fromString("sortId/hto/sequence"))))
                    {
                        distinctHTOs.add(hto);
                        bcWriter.writeNext(new String[]{results.getString(FieldKey.fromString("sortId/hto/sequence")), results.getString(FieldKey.fromString("sortId/hto"))});
                    }
                });

                if (hasError.get())
                {
                    throw new PipelineJobException("No cell hashing readset or HTO found for one or more cDNAs. see the file: " + output.getName());
                }
            }

            if (distinctHTOs.isEmpty())
            {
                throw new PipelineJobException("Cell hashing was selected, but no HTOs were found");
            }

            _log.info("distinct HTOs: " + distinctHTOs.size());

            support.cacheObject(READSET_TO_HASHING_MAP, readsetToHashingMap);
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

    }

    public File getCDNAInfoFile()
    {
        return new File(_sourceDir, "cDNAInfo.txt");
    }

    public File getValidHashingBarcodeFile()
    {
        return new File(_sourceDir, "validHashingBarcodes.csv");
    }

    public File getValidCellIndexFile()
    {
        return new File(_sourceDir, "validCellIndexes.csv");
    }

    public File getPerCellCsv(File outDir)
    {
        return new File(outDir, "all_contig_annotations.csv");
    }

    public File runRemoteCellHashingTasks(AlignmentOutputImpl output, File perCellTsv, Readset rs, SequenceAnalysisJobSupport support, List<String> extraParams, File workingDir, File sourceDir) throws PipelineJobException
    {
        Map<Integer, Integer> readsetToHashing = getCachedReadsetMap(support);
        _log.debug("total cashed readset/HTO pairs: " + readsetToHashing.size());

        //prepare whitelist of cell indexes
        File cellBarcodeWhitelist = getValidCellIndexFile();
        Set<String> uniqueBarcodes = new HashSet<>();
        _log.debug("writing cell barcodes");
        try (CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(cellBarcodeWhitelist), ',', CSVWriter.NO_QUOTE_CHARACTER); CSVReader reader = new CSVReader(Readers.getReader(perCellTsv), ','))
        {
            int rowIdx = 0;
            int noCallRows = 0;
            String[] row;
            while ((row = reader.readNext()) != null)
            {
                //skip header
                rowIdx++;
                if (rowIdx > 1)
                {
                    if (row.length >= 13 && "None".equals(row[12]))
                    {
                        noCallRows++;
                        continue;
                    }

                    //NOTE: 10x appends "-1" to barcodes
                    String barcode = row[0].split("-")[0];
                    if (!uniqueBarcodes.contains(barcode))
                    {
                        writer.writeNext(new String[]{barcode});
                        uniqueBarcodes.add(barcode);
                    }
                }
            }

            _log.debug("rows inspected: " + (rowIdx - 1));
            _log.debug("rows without CDR3: " + noCallRows);
            _log.debug("unique cell barcodes: " + uniqueBarcodes.size());
            output.addIntermediateFile(cellBarcodeWhitelist);
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        //prepare whitelist of barcodes, based on cDNA records
        File htoBarcodeWhitelist = getValidHashingBarcodeFile();
        if (!htoBarcodeWhitelist.exists())
        {
            throw new PipelineJobException("Unable to find file: " + htoBarcodeWhitelist.getPath());
        }

        Readset htoReadset = support.getCachedReadset(readsetToHashing.get(rs.getReadsetId()));
        if (htoReadset == null)
        {
            throw new PipelineJobException("Unable to find HTO readset for readset: " + rs.getRowId());
        }

        //run CiteSeqCount.  this will use Multiseq to make calls per cell
        File cellToHto = getCellToHtoFile();
        File citeSeqCountUnknownOutput = new File(cellToHto.getParentFile(), "citeSeqUnknownBarcodes.txt");

        List<String> args = new ArrayList<>();
        args.add("-u");
        args.add(citeSeqCountUnknownOutput.getPath());

        if (extraParams != null)
        {
            args.addAll(extraParams);
        }

        File hashtagCalls = SequencePipelineService.get().runCiteSeqCount(htoReadset, htoBarcodeWhitelist, cellBarcodeWhitelist, workingDir, FileUtil.getBaseName(FileUtil.getBaseName(cellToHto.getName())), _log, args, false, sourceDir);
        output.addOutput(citeSeqCountUnknownOutput, "CiteSeqCount Unknown Barcodes");
        output.addOutput(hashtagCalls, "Cell Hashing TCR Calls");
        output.addOutput(new File(cellToHto.getParentFile(), FileUtil.getBaseName(cellToHto.getName()) + ".html"), "Cell Hashing TCR Report");

        if (citeSeqCountUnknownOutput.exists())
        {
            Map<String, String> allBarcodes = readAllBarcodes(_sourceDir);
            logTopUnknownBarcodes(citeSeqCountUnknownOutput, _log, allBarcodes);
        }

        return cellToHto;
    }

    public File getCellToHtoFile()
    {
        return new File(_sourceDir, "cellbarcodeToHTO.calls.txt");
    }

    public void importAssayData(PipelineJob job, AnalysisModel model, File outDir, Integer assayId, boolean useCellHashing, SequenceAnalysisJobSupport support) throws PipelineJobException
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

        File clonotypeCsv = new File(outDir, "clonotypes.csv");
        if (!clonotypeCsv .exists())
        {
            _log.warn("unable to find consensus contigs: " + clonotypeCsv .getPath());
            return;
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

        _log.info("loading results into assay: " + assayId);

        Integer runId = SequencePipelineService.get().getExpRunIdForJob(job);
        Readset rs = support.getCachedReadset(model.getReadset());

        //first build map of distinct FL sequences:
        _log.info("processing FASTA: " + consensusFasta.getPath());
        Map<String, String> sequenceMap = new HashMap<>();
        try (FastaDataLoader loader = new FastaDataLoader(consensusFasta, false))
        {
            loader.setCharacterFilter(new FastaLoader.UpperAndLowercaseCharacterFilter());

            try (CloseableIterator<Map<String, Object>> i = loader.iterator())
            {
                while (i.hasNext())
                {
                    Map<String, Object> fastaRecord = i.next();
                    sequenceMap.put((String) fastaRecord.get("header"), (String) fastaRecord.get("sequence"));
                }
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        _log.info("total sequences: " + sequenceMap.size());

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
                    if (line[0].startsWith("TCR_ReadsetId"))
                    {
                        continue;
                    }

                    CDNA cdna = CDNA.getRowId(Integer.parseInt(line[1]));
                    htoNameToCDNAMap.put(line[5], cdna);
                    cDNAMap.put(Integer.parseInt(line[1]), cdna);
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }
        else
        {
            _log.debug("Cell hashing is not used");
        }

        File cellbarcodeToHtoFile = getCellToHtoFile();
        Map<String, Integer> cellBarcodeToCDNAMap = new HashMap<>();
        if (cellbarcodeToHtoFile.exists())
        {
            try (CSVReader reader = new CSVReader(Readers.getReader(cellbarcodeToHtoFile), '\t'))
            {
                //cellbarcode -> HTO name
                String[] line;
                int doublet = 0;
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
                _log.info("total negatives: " + negative);
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            job.getLogger().info("total cell hashing calls found: " + cellBarcodeToCDNAMap.size());
        }
        else if (useCellHashing)
        {
            throw new PipelineJobException("Cell hashing output not found: " + cellbarcodeToHtoFile.getPath());
        }

        Map<String, Map<Integer, Integer>> countMapBySample = new HashMap<>();
        Map<Integer, Integer> totalCellsMapBySample = new HashMap<>();
        _log.info("processing clonotype CSV: " + allCsv.getPath());

        //header: barcode	is_cell	contig_id	high_confidence	length	chain	v_gene	d_gene	j_gene	c_gene	full_length	productive	cdr3	cdr3_nt	reads	umis	raw_clonotype_id	raw_consensus_id
        try (CSVReader reader = new CSVReader(Readers.getReader(allCsv), ','))
        {
            String[] line;
            int idx = 0;
            int noCDR3 = 0;
            int totalSkipped = 0;
            Set<String> knownBarcodes = new HashSet<>();
            while ((line = reader.readNext()) != null)
            {
                idx++;
                if (idx == 1)
                {
                    _log.debug("skipping header, length: " + line.length);
                    continue;
                }

                if ("None".equals(line[12]))
                {
                    noCDR3++;
                    continue;
                }

                //NOTE: 10x appends "-1" to barcode sequences
                String barcode = line[0].split("-")[0];
                Integer cDNA = useCellHashing ? cellBarcodeToCDNAMap.get(barcode) : Integer.valueOf(-1);
                if (cDNA == null)
                {
                    _log.info("skipping cell barcode without HTO call: " + barcode);
                    totalSkipped++;
                    continue;
                }
                knownBarcodes.add(barcode);

                String clontypeId = removeNone(line[16]);
                if (clontypeId != null)
                {
                    Map<Integer, Integer> countMap = countMapBySample.getOrDefault(clontypeId, new HashMap<>());
                    countMap.put(cDNA, 1 + countMap.getOrDefault(cDNA, 0));
                    countMapBySample.put(clontypeId, countMap);

                    totalCellsMapBySample.put(cDNA, 1 + totalCellsMapBySample.getOrDefault(cDNA, 0));
                }
            }

            _log.info("total clonotype rows inspected: " + idx);
            _log.info("total clonotype rows without CDR3: " + noCDR3);
            _log.info("total clonotype rows skipped for unknown barcodes: " + totalSkipped);
            _log.info("unique known cell barcodes: " + knownBarcodes.size());
            _log.info("total clonotypes: " + countMapBySample.size());
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        //header for consensus_annotations.csv
        //clonotype_id	consensus_id	length	chain	v_gene	d_gene	j_gene	c_gene	full_length	productive	cdr3	cdr3_nt	reads	umis
        List<Map<String, Object>> rows = new ArrayList<>();
        int totalCells = 0;
        _log.info("processing consensus CSV: " + consensusCsv.getPath());
        try (CSVReader reader = new CSVReader(Readers.getReader(consensusCsv), ','))
        {
            String[] line;
            int clonesWithoutCounts = 0;
            int idx = 0;
            while ((line = reader.readNext()) != null)
            {
                idx++;
                if (idx == 1)
                {
                    _log.debug("skipping header");
                    continue;
                }

                String cloneId = line[0];
                Map<Integer, Integer> countData = countMapBySample.get(cloneId);
                if (countData == null)
                {
                    _log.warn("No count data for clone: " + cloneId);
                    clonesWithoutCounts++;
                    continue;
                }

                for (Integer cDNA : countData.keySet())
                {
                    CDNA cDNARecord = cDNAMap.get(cDNA);

                    Map<String, Object> row = new CaseInsensitiveHashMap<>();

                    if (cDNA == -1)
                    {
                        row.put("sampleName", rs.getName());
                        row.put("subjectId", rs.getSubjectId());
                        row.put("sampleDate", rs.getSampleDate());
                    }
                    else if (cDNARecord == null)
                    {
                        throw new PipelineJobException("Unable to find cDNA for ID: " + cDNA);
                    }
                    else
                    {
                        row.put("sampleName", cDNARecord.getAssaySampleName());
                        row.put("subjectId", cDNARecord.getSortRecord().getStimRecord().getAnimalId());
                        row.put("sampleDate", cDNARecord.getSortRecord().getStimRecord().getDate());
                        row.put("cDNA", cDNA);
                    }

                    row.put("alignmentId", model.getAlignmentFile());
                    row.put("analysisId", model.getRowId());
                    row.put("pipelineRunId", runId);

                    row.put("cloneId", line[0]);
                    row.put("locus", line[3]);
                    row.put("vHit", removeNone(line[4]));
                    row.put("dHit", removeNone(line[5]));
                    row.put("jHit", removeNone(line[6]));
                    row.put("cHit", removeNone(line[7]));

                    row.put("cdr3", removeNone(line[10]));
                    row.put("cdr3_nt", removeNone(line[11]));

                    row.put("count", countData.get(cDNA));
                    totalCells += countData.get(cDNA);

                    double fraction = countData.get(cDNA).doubleValue() / totalCellsMapBySample.get(cDNA);
                    row.put("fraction", fraction);

                    if (!"None".equals(line[1]) && !sequenceMap.containsKey(line[1]))
                    {
                        _log.warn("Unable to find sequence for: " + line[1]);
                    }
                    else
                    {
                        row.put("sequence", sequenceMap.get(line[1]));
                    }

                    rows.add(row);
                }
            }

            _log.info("total clones without count data: " + clonesWithoutCounts);
        }
        catch (Exception e)
        {
            _log.error(e);
            throw new PipelineJobException(e);
        }

        _log.info("total assay rows: " + rows.size());
        _log.info("total cells: " + totalCells);
        saveRun(job, protocol, model, rows, outDir);
    }

    private String removeNone(String input)
    {
        return "None".equals(input) ? null : input;
    }

    private void saveRun(PipelineJob job, ExpProtocol protocol, AnalysisModel model, List<Map<String, Object>> rows, File outDir) throws PipelineJobException
    {
        ViewBackgroundInfo info = job.getInfo();
        ViewContext vc = ViewContext.getMockViewContext(info.getUser(), info.getContainer(), info.getURL(), false);

        JSONObject runProps = new JSONObject();
        runProps.put("performedby", job.getUser().getDisplayName(job.getUser()));
        runProps.put("assayName", "10x");
        runProps.put("Name", "Analysis: " + model.getAnalysisId());
        runProps.put("analysisId", model.getAnalysisId());

        Integer runId = SequencePipelineService.get().getExpRunIdForJob(job);
        runProps.put("pipelineRunId", runId);

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
                LaboratoryService.get().saveAssayBatch(rows, json, assayTmp, vc, AssayService.get().getProvider(protocol), protocol);
            }
            catch (ValidationException e)
            {
                throw new PipelineJobException(e);
            }
        }
    }

    public static Map<Integer, Integer> getCachedReadsetMap(SequenceAnalysisJobSupport support) throws PipelineJobException
    {
        return support.getCachedObject(CellRangerVDJUtils.READSET_TO_HASHING_MAP, PipelineJob.createObjectMapper().getTypeFactory().constructParametricType(Map.class, Integer.class, Integer.class));
    }

    public static File getAllBarcodesFile(File webserverDir)
    {
        return new File(webserverDir, "allHTOBarcodes.txt");
    }

    public static void writeAllBarcodes(File webserverDir) throws PipelineJobException
    {
        try (CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(getAllBarcodesFile(webserverDir)), '\t', CSVWriter.NO_QUOTE_CHARACTER))
        {
            TableSelector ts = new TableSelector(DbSchema.get(TCRdbSchema.SEQUENCE_ANALYSIS).getTable("barcodes"), PageFlowUtil.set("sequence", "tag_name"), new SimpleFilter(FieldKey.fromString("group_name"), "5p-HTOs"), null);
            ts.forEachResults(rs -> {
                writer.writeNext(new String[]{rs.getString(FieldKey.fromString("sequence")), rs.getString(FieldKey.fromString("tag_name"))});
            });
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }

    public static Map<String, String> readAllBarcodes(File webserverDir) throws PipelineJobException
    {
        File barcodes = getAllBarcodesFile(webserverDir);
        try (CSVReader reader = new CSVReader(Readers.getReader(barcodes), '\t'))
        {
            Map<String, String> ret = new HashMap<>();
            String[] line;
            while ((line = reader.readNext()) != null)
            {
                ret.put(line[0], line[1]);
            }

            return ret;
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }

    public static void logTopUnknownBarcodes(File citeSeqCountUnknownOutput, Logger log, Map<String, String> allBarcodes) throws PipelineJobException
    {
        try (CSVReader reader = new CSVReader(Readers.getReader(citeSeqCountUnknownOutput), ','))
        {
            String[] line;
            int lineIdx = 0;
            log.info("Top unknown barcodes:");
            while ((line = reader.readNext()) != null)
            {
                lineIdx++;
                if (lineIdx == 1)
                {
                    continue;
                }

                String name = allBarcodes.get(line[0]);
                log.info(line[0] + (name == null ? "" : " (" + name + ")") + ": " + line[1]);

                if (lineIdx == 7)
                {
                    break;
                }
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }

    public static void prepareCellHashingFiles(PipelineJob job, SequenceAnalysisJobSupport support, File outputDir, String filterFieldName) throws PipelineJobException
    {
        job.getLogger().debug("preparing cell hashing files");
        Container target = job.getContainer().isWorkbook() ? job.getContainer().getParent() : job.getContainer();
        UserSchema tcr = QueryService.get().getUserSchema(job.getUser(), target, TCRdbSchema.NAME);
        TableInfo cDNAs = tcr.getTable(TCRdbSchema.TABLE_CDNAS);

        Map<FieldKey, ColumnInfo> colMap = QueryService.get().getColumns(cDNAs, PageFlowUtil.set(
                FieldKey.fromString("rowid"),
                FieldKey.fromString("sortId/hto"),
                FieldKey.fromString("sortId/hto/sequence"),
                FieldKey.fromString("hashingReadsetId"))
        );

        CellRangerVDJUtils.writeAllBarcodes(outputDir);

        CellRangerVDJUtils utils = new CellRangerVDJUtils(job.getLogger(), outputDir);
        File barcodeOutput = utils.getValidHashingBarcodeFile();
        HashMap<Integer, Integer> readsetToHashingMap = new HashMap<>();
        try (CSVWriter bcWriter = new CSVWriter(PrintWriters.getPrintWriter(barcodeOutput), ',', CSVWriter.NO_QUOTE_CHARACTER))
        {
            List<Readset> cachedReadsets = support.getCachedReadsets();
            Set<String> distinctHTOs = new HashSet<>();
            for (Readset rs : cachedReadsets)
            {
                AtomicBoolean hasError = new AtomicBoolean(false);
                new TableSelector(cDNAs, colMap.values(), new SimpleFilter(FieldKey.fromString(filterFieldName), rs.getRowId()), null).forEachResults(results -> {

                    if (results.getObject(FieldKey.fromString("hashingReadsetId")) == null || results.getInt(FieldKey.fromString("hashingReadsetId")) == 0)
                    {
                        hasError.set(true);
                    }

                    if (results.getObject(FieldKey.fromString("sortId/hto/sequence")) == null)
                    {
                        hasError.set(true);
                    }

                    support.cacheReadset(results.getInt(FieldKey.fromString("hashingReadsetId")), job.getUser());
                    readsetToHashingMap.put(rs.getReadsetId(), results.getInt(FieldKey.fromString("hashingReadsetId")));

                    String hto = results.getString(FieldKey.fromString("sortId/hto")) + "<>" + results.getString(FieldKey.fromString("sortId/hto/sequence"));
                    if (!distinctHTOs.contains(hto) && !StringUtils.isEmpty(results.getString(FieldKey.fromString("sortId/hto/sequence"))))
                    {
                        distinctHTOs.add(hto);
                        bcWriter.writeNext(new String[]{results.getString(FieldKey.fromString("sortId/hto/sequence")), results.getString(FieldKey.fromString("sortId/hto"))});
                    }
                });

                if (hasError.get())
                {
                    throw new PipelineJobException("No cell hashing readset or HTO found for one or more cDNAs. see the file");
                }
            }

            if (distinctHTOs.isEmpty())
            {
                throw new PipelineJobException("Cell hashing was selected, but no HTOs were found");
            }

            job.getLogger().info("distinct HTOs: " + distinctHTOs.size());

            support.cacheObject(READSET_TO_HASHING_MAP, readsetToHashingMap);
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
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
            return getPlateId() + "_" + getWell() + "_" + getSortRecord().getStimRecord().getAnimalId() + "_" + getSortRecord().getStimRecord().getStim() + "_" + getSortRecord().getPopulation() + "_" + getSortRecord().getHto();
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
