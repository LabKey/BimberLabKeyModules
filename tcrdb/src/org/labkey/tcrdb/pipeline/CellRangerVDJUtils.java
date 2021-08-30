package org.labkey.tcrdb.pipeline;

import au.com.bytecode.opencsv.CSVReader;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.labkey.api.assay.AssayProtocolSchema;
import org.labkey.api.assay.AssayProvider;
import org.labkey.api.assay.AssayService;
import org.labkey.api.collections.CaseInsensitiveHashMap;
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
import org.labkey.api.query.ValidationException;
import org.labkey.api.reader.FastaDataLoader;
import org.labkey.api.reader.FastaLoader;
import org.labkey.api.reader.Readers;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.model.AnalysisModel;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.singlecell.CellHashingService;
import org.labkey.api.singlecell.model.CDNA_Library;
import org.labkey.api.util.FileUtil;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.view.ViewBackgroundInfo;
import org.labkey.api.view.ViewContext;
import org.labkey.tcrdb.TCRdbSchema;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CellRangerVDJUtils
{
    public static final String TCR_HASHING_CALLS = "Cell Hashing TCR Calls";

    private Logger _log;

    public CellRangerVDJUtils(Logger log)
    {
        _log = log;
    }

    public void importAssayData(PipelineJob job, AnalysisModel model, File vLoupeFile, File outDir, Integer assayId, @Nullable Integer runId, boolean deleteExisting, boolean allowGDRecovery) throws PipelineJobException
    {
        File cellRangerOutDir = vLoupeFile.getParentFile();

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

        File allCsv = getPerCellCsv(cellRangerOutDir);
        if (!allCsv.exists())
        {
            _log.warn("unable to find consensus contigs: " + allCsv .getPath());
            return;
        }

        File consensusCsv = new File(cellRangerOutDir, "consensus_annotations.csv");
        if (!consensusCsv .exists())
        {
            throw new PipelineJobException("unable to find consensus contigs: " + consensusCsv .getPath());
        }

        File consensusFasta = new File(cellRangerOutDir, "consensus.fasta");
        if (!consensusFasta.exists())
        {
            throw new PipelineJobException("unable to find FASTA: " + consensusFasta.getPath());
        }

        File allFasta = new File(cellRangerOutDir, "all_contig.fasta");
        if (!allFasta.exists())
        {
            throw new PipelineJobException("unable to find FASTA: " + allFasta.getPath());
        }

        _log.info("loading results into assay: " + assayId);
        _log.info("allow gamma/delta recovery: " + allowGDRecovery);

        if (runId == null)
        {
            runId = SequencePipelineService.get().getExpRunIdForJob(job);
        }
        else
        {
            job.getLogger().debug("Using supplied runId: " + runId);
        }

        File cDNAFile = CellHashingService.get().getCDNAInfoFile(outDir);
        Map<String, CDNA_Library> htoNameToCDNAMap = new HashMap<>();
        Map<Integer, CDNA_Library> cDNAMap = new HashMap<>();
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

                    CDNA_Library cdna = CellHashingService.get().getLibraryById(Integer.parseInt(line[1]));
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

                int consensusIdx = -1;
                while ((line = reader.readNext()) != null)
                {
                    if (line.length < 3)
                    {
                        throw new PipelineJobException("Line too short");
                    }

                    //header
                    if ("cellbarcode".equalsIgnoreCase(line[0]))
                    {
                        consensusIdx = Arrays.asList(line).indexOf("consensuscall");
                        continue;
                    }

                    if (consensusIdx == -1)
                    {
                        throw new PipelineJobException("consensuscall column not found");
                    }

                    String hto = line[consensusIdx];
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

                    CDNA_Library cDNA = htoNameToCDNAMap.get(hto);
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
            int recoveredGD = 0;
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
                    //NOTE: cellranger marks TRG/TRD rows as non-productive. therefore, gamma/delta cells will tend to be marked non-cell, since the cell lacks productive A/B
                    // Allow recovery of these cells if the row is TRD/TRG, has a CDR3 and is full-length:
                    if (allowGDRecovery && shouldRecoverGammaDeltaRow(line))
                    {
                        recoveredGD++;
                    }
                    else
                    {
                        nonCell++;
                        continue;
                    }
                }

                if ("None".equals(line[12]))
                {
                    noCDR3++;
                    continue;
                }

                String cGene = removeNone(line[9]);
                if (cGene == null)
                {
                    // Only discard these if chain type doesnt match between JGene and VGene.
                    if (!line[8].substring(0, 3).equals(line[6].substring(0,3)))
                    {
                        noCGene++;
                        continue;
                    }
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
                if (locus.equals("Multi") && cGene != null && removeNone(line[8]) != null && removeNone(line[6]) != null)
                {
                    if (cGene.contains("TRAC") && removeNone(line[8]).contains("TRAJ") && removeNone(line[6]).contains("TRDV"))
                    {
                        locus = "TRA";
                        multiChainConverted++;
                    }
                }

                // Aggregate by: cDNA_ID, cdr3, chain, raw_clonotype_id, sequenceContigName, vHit, dHit, jHit, cHit, cdr3_nt
                String key = StringUtils.join(new String[]{cDNA.toString(), line[12], locus, clonotypeId, sequenceContigName, removeNone(line[6]), removeNone(line[7]), removeNone(line[8]), cGene, removeNone(line[13])}, "<>");
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
            _log.info("total gamma/delta clonotype rows recovered: " + recoveredGD);
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
        List<? extends ExpData> datas = run.getInputDatas(TCR_HASHING_CALLS, ExpProtocol.ApplicationType.ExperimentRunOutput);
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

    private Map<String, Object> processRow(AssayModel assayModel, AnalysisModel model, Map<Integer, CDNA_Library> cDNAMap, Integer runId, Map<Integer, Set<String>> totalCellsBySample, Map<String, String> sequenceMap) throws PipelineJobException
    {
        CDNA_Library cDNARecord = cDNAMap.get(assayModel.cdna);
        if (cDNARecord == null)
        {
            throw new PipelineJobException("Unable to find cDNA for ID: " + assayModel.cdna);
        }

        Map<String, Object> row = new CaseInsensitiveHashMap<>();

        row.put("sampleName", cDNARecord.getAssaySampleName());
        row.put("subjectId", cDNARecord.getSortRecord().getSampleRecord().getSubjectId());
        row.put("sampleDate", cDNARecord.getSortRecord().getSampleRecord().getSampledate());
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
            TableSelector ts = new TableSelector(TCRdbSchema.getInstance().getSchema().getTable(TCRdbSchema.TABLE_MIXCR_LIBRARIES), PageFlowUtil.set("rowid"), new SimpleFilter(FieldKey.fromString("libraryId"), model.getLibraryId()), null);
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

    public static File getPerCellCsv(File cellRangerOutDir)
    {
        return new File(cellRangerOutDir, "all_contig_annotations.csv");
    }

    public static boolean shouldRecoverGammaDeltaRow(String[] line)
    {
        return ("TRD".equals(line[5]) || "TRG".equals(line[5])) && !"None".equals(line[12]) && !"False".equals(line[10]);
    }
}
