package org.labkey.mgap.pipeline;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.IOUtil;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Container;
import org.labkey.api.data.ConvertHelper;
import org.labkey.api.data.DbScope;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.exp.api.ExperimentService;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedAction;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.query.DuplicateKeyException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.InvalidKeyException;
import org.labkey.api.query.QueryException;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.QueryUpdateService;
import org.labkey.api.query.QueryUpdateServiceException;
import org.labkey.api.query.UserSchema;
import org.labkey.api.reader.Readers;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.pipeline.AbstractParameterizedOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.SequenceAnalysisJobSupport;
import org.labkey.api.sequenceanalysis.pipeline.SequenceOutputHandler;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.sequenceanalysis.run.GeneToNameTranslator;
import org.labkey.api.sequenceanalysis.run.SelectVariantsWrapper;
import org.labkey.api.util.FileType;
import org.labkey.api.util.FileUtil;
import org.labkey.api.util.GUID;
import org.labkey.api.util.JsonUtil;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.writer.PrintWriters;
import org.labkey.mgap.mGAPManager;
import org.labkey.mgap.mGAPModule;
import org.labkey.mgap.mGAPSchema;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by bimber on 5/2/2017.
 */
public class mGapReleaseGenerator extends AbstractParameterizedOutputHandler<SequenceOutputHandler.SequenceOutputProcessor>
{
    private final FileType _vcfType = new FileType(Arrays.asList(".vcf"), ".vcf", false, FileType.gzSupportLevel.SUPPORT_GZ);
    public static final String MMUL_GENOME = "mmulGenome";

    public mGapReleaseGenerator()
    {
        super(ModuleLoader.getInstance().getModule(mGAPModule.class), "Create mGAP Release", "This will prepare an input VCF for use as an mGAP public release.  This will optionally include: removing excess annotations and program records, limiting to SNVs (optional) and removing genotype data (optional).  If genotypes are retained, the subject names will be checked for mGAP aliases and replaced as needed.", new LinkedHashSet<>(PageFlowUtil.set("sequenceanalysis/field/GenomeFileSelectorField.js")), Arrays.asList(
                ToolParameterDescriptor.create("releaseVersion", "Version", "This string will be used as the version when published.", "textfield", new JSONObject(){{
                    put("allowBlank", false);
                }}, null),
                ToolParameterDescriptor.createExpDataParam("gtfFile", "GTF File", "The gene file used to create these annotations.", "sequenceanalysis-genomefileselectorfield", new JSONObject()
                {{
                    put("extensions", Arrays.asList("gtf"));
                    put("width", 400);
                    put("allowBlank", false);
                }}, null),
                ToolParameterDescriptor.create(AnnotationStep.GRCH37, "GRCh37 Genome", "The genome that matches human GRCh37.", "ldk-simplelabkeycombo", new JSONObject()
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
                ToolParameterDescriptor.create("testOnly", "Test Only", "If selected, the various files will be created, but a record will not be created in the releases table, meaning it will not be synced to mGAP.", "checkbox", new JSONObject()
                {{
                    put("checked", false);
                }}, false)
        ));
    }

    @Override
    public boolean canProcess(SequenceOutputFile o)
    {
        return o.getFile() != null && o.getFile().exists() && _vcfType.isType(o.getFile());
    }

    @Override
    public boolean isVisible()
    {
        return false;
    }

    @Override
    public boolean doRunRemote()
    {
        return true;
    }

    @Override
    public boolean doRunLocal()
    {
        return false;
    }

    @Override
    public SequenceOutputProcessor getProcessor()
    {
        return new Processor();
    }

    public static class Processor implements SequenceOutputProcessor
    {
        private Set<String> _omimWarnings = new HashSet<>();

        public Processor()
        {

        }

        @Override
        public void init(JobContext ctx, List<SequenceOutputFile> inputFiles, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {
            ctx.getJob().getLogger().info("writing track/subset data to file");
            Container target = ctx.getJob().getContainer().isWorkbook() ? ctx.getJob().getContainer().getParent() : ctx.getJob().getContainer();
            TableInfo releaseTracks = QueryService.get().getUserSchema(ctx.getJob().getUser(), target, mGAPSchema.NAME).getTable(mGAPSchema.TABLE_RELEASE_TRACKS);

            Set<FieldKey> toSelect = new HashSet<>();
            toSelect.add(FieldKey.fromString("trackName"));
            toSelect.add(FieldKey.fromString("mergepriority"));
            toSelect.add(FieldKey.fromString("skipvalidation"));
            toSelect.add(FieldKey.fromString("isprimarytrack"));
            toSelect.add(FieldKey.fromString("vcfId"));
            toSelect.add(FieldKey.fromString("vcfId/dataId"));
            Map<FieldKey, ColumnInfo> colMap = QueryService.get().getColumns(releaseTracks, toSelect);

            Set<File> allVcfs = new HashSet<>();
            Set<String> distinctTracks = new HashSet<>();
            File trackFile = getTrackListFile(ctx.getOutputDir());
            try (CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(trackFile), '\t', CSVWriter.NO_QUOTE_CHARACTER))
            {
                new TableSelector(releaseTracks, colMap.values(), null, null).forEachResults(rs -> {
                    if (rs.getObject(FieldKey.fromString("vcfId")) == null)
                    {
                        throw new SQLException("No VCF found for track: " + rs.getObject(FieldKey.fromString("trackName")));
                    }

                    SequenceOutputFile so = SequenceOutputFile.getForId(rs.getInt(FieldKey.fromString("vcfId")));
                    ExpData d = ExperimentService.get().getExpData(so.getDataId());
                    ctx.getSequenceSupport().cacheExpData(d);

                    allVcfs.add(d.getFile());

                    writer.writeNext(new String[]{
                            rs.getString(FieldKey.fromString("trackName")),
                            String.valueOf(rs.getInt(FieldKey.fromString("vcfId/dataId"))),
                            String.valueOf(rs.getObject(FieldKey.fromString("mergepriority")) == null ? 999 : rs.getInt(FieldKey.fromString("mergepriority"))),
                            String.valueOf(rs.getObject(FieldKey.fromString("skipvalidation")) != null && rs.getBoolean(FieldKey.fromString("skipvalidation"))),
                            String.valueOf(rs.getObject(FieldKey.fromString("isprimarytrack")) != null && rs.getBoolean(FieldKey.fromString("isprimarytrack")))
                    });

                    distinctTracks.add(rs.getString(FieldKey.fromString("trackName")));
                });
            }
            catch (IOException | RuntimeException e)
            {
                throw new PipelineJobException(e);
            }

            ctx.getJob().getLogger().info("total tracks: " + distinctTracks.size());

            AtomicBoolean hasNovelSites = new AtomicBoolean(false);
            distinctTracks.forEach(tn -> {
                if (tn.contains("Novel Sites"))
                {
                    hasNovelSites.getAndSet(true);
                }
            });

            if (!hasNovelSites.get())
            {
                throw new PipelineJobException("Expected this release to contain one track with Novel Sites in the name");
            }

            ctx.getSequenceSupport().cacheGenome(SequenceAnalysisService.get().getReferenceGenome(ctx.getParams().getInt(AnnotationStep.GRCH37), ctx.getJob().getUser()));

            //find chain files:
            Set<Integer> genomeIds = new HashSet<>();
            inputFiles.forEach(so -> genomeIds.add(so.getLibrary_id()));
            if (genomeIds.size() != 1)
            {
                throw new PipelineJobException("Expected all inputs to use the same genome");
            }
            int sourceGenome = genomeIds.iterator().next();
            ctx.getSequenceSupport().cacheGenome(SequenceAnalysisService.get().getReferenceGenome(sourceGenome, ctx.getJob().getUser()));
            ctx.getSequenceSupport().cacheObject(MMUL_GENOME, sourceGenome);

            AnnotationStep.findChainFile(genomeIds.iterator().next(), ctx.getParams().getInt(AnnotationStep.GRCH37), ctx.getSequenceSupport(), ctx.getJob());

            //Read inputs, find all unique IDs.  Determine if we have data in mgap.subjectsSource for each mgap ID
            Set<String> ids = new HashSet<>();
            for (File vcf : allVcfs)
            {
                checkVcfAnnotationsAndSamples(vcf, true);

                try (VCFFileReader reader = new VCFFileReader(vcf))
                {
                    ids.addAll(reader.getFileHeader().getSampleNamesInOrder());
                }
            }

            TableInfo ti = QueryService.get().getUserSchema(ctx.getJob().getUser(), target, mGAPSchema.NAME).getTable("subjectsSource", null);
            List<String> idsWithRecord = new TableSelector(ti, PageFlowUtil.set("subjectname"), new SimpleFilter(FieldKey.fromString("subjectname"), ids, CompareType.IN), null).getArrayList(String.class);

            ids.removeAll(idsWithRecord);
            if (!ids.isEmpty())
            {
                throw new PipelineJobException("Some ids are missing demographics data: " + StringUtils.join(ids, ","));
            }
        }

        private File getTrackListFile(File outputDir)
        {
            return new File(outputDir, "releaseTracks.txt");
        }

        @Override
        public void complete(PipelineJob job, List<SequenceOutputFile> inputs, List<SequenceOutputFile> outputsCreated, SequenceAnalysisJobSupport support) throws PipelineJobException
        {
            if (outputsCreated.isEmpty())
            {
                job.getLogger().error("no outputs found");
            }


            String releaseVersion = job.getParameters().get("releaseVersion");

            Map<String, SequenceOutputFile> outputVCFMap = new HashMap<>();
            Map<String, SequenceOutputFile> outputTableMap = new HashMap<>();
            Map<String, SequenceOutputFile> liftedVcfMap = new HashMap<>();
            Map<String, SequenceOutputFile> sitesOnlyVcfMap = new HashMap<>();
            Map<String, SequenceOutputFile> novelSitesVcfMap = new HashMap<>();
            Map<String, SequenceOutputFile> trackVCFMap = new HashMap<>();

            for (SequenceOutputFile so : outputsCreated)
            {
                if (so.getRowid() == null || so.getRowid() == 0)
                {
                    throw new PipelineJobException("No rowId found for sequence output");
                }

                if (so.getCategory().endsWith("Table"))
                {
                    String name = so.getName().replaceAll(" Variant Table", "");
                    outputTableMap.put(name, so);
                }
                else if (so.getCategory().contains("Lifted"))
                {
                    String name = so.getName().replaceAll(" Lifted to Human", "");
                    liftedVcfMap.put(name, so);
                }
                else if (so.getCategory().contains("mGAP Release: Sites Only"))
                {
                    sitesOnlyVcfMap.put("mGAP Release: " + releaseVersion, so);
                }
                else if (so.getCategory().contains("Release Track") && so.getName().contains("Novel Sites"))
                {
                    novelSitesVcfMap.put("mGAP Release: " + releaseVersion, so);
                    trackVCFMap.put(so.getName(), so);
                }
                else if (so.getCategory().endsWith("Release"))
                {
                    outputVCFMap.put(so.getName(), so);
                }
                else if (so.getCategory().endsWith("Release Track"))
                {
                    trackVCFMap.put(so.getName(), so);
                }
                else
                {
                    throw new PipelineJobException("Unexpected output: " + so.getCategory());
                }
            }

            //save all DB inserts for list:
            List<Map<String, Object>> variantReleaseRows = new ArrayList<>();
            List<Map<String, Object>> variantTableRows = new ArrayList<>();
            List<Map<String, Object>> releaseStatsRows = new ArrayList<>();
            List<Map<String, Object>> tracksPerReleaseRows = new ArrayList<>();

            boolean testOnly = StringUtils.isEmpty(job.getParameters().get("testOnly")) ? false : ConvertHelper.convert(job.getParameters().get("testOnly"), boolean.class);

            if (outputVCFMap.isEmpty())
            {
                throw new PipelineJobException("No releases were found");
            }

            String releaseId = new GUID().toString();
            for (String release : outputVCFMap.keySet())
            {
                SequenceOutputFile so = outputVCFMap.get(release);
                SequenceOutputFile so2 = outputTableMap.get(release);
                if (so2 == null)
                {
                    throw new PipelineJobException("Unable to find table output for release: " + release);
                }

                SequenceOutputFile liftedVcf = liftedVcfMap.get(release);
                if (liftedVcf == null)
                {
                    throw new PipelineJobException("Unable to find lifted VCF for release: " + release);
                }

                SequenceOutputFile sitesOnlyVcf = sitesOnlyVcfMap.get(release);
                if (sitesOnlyVcf == null)
                {
                    throw new PipelineJobException("Unable to find sites-only VCF for release: " + release);
                }

                SequenceOutputFile novelSitesVcf = novelSitesVcfMap.get(release);
                if (novelSitesVcf == null)
                {
                    throw new PipelineJobException("Unable to find novel sites VCF for release: " + release);
                }

                //find basic stats:
                job.getLogger().info("inspecting file: " + so.getName());
                int totalSubjects;
                try (VCFFileReader reader = new VCFFileReader(so.getFile()))
                {
                    totalSubjects = reader.getFileHeader().getSampleNamesInOrder().size();
                }

                // NOTE: this can be rather slow. Consider caching remotely or using VCF index?
                String totalVariants = null;
                try
                {
                    File releaseStats = new File(so.getFile().getParentFile(), SequenceAnalysisService.get().getUnzippedBaseName(so.getFile().getName()) + ".summaryByField.txt");
                    if (releaseStats.exists())
                    {
                        try (CSVReader reader = new CSVReader(IOUtil.openFileForBufferedReading(releaseStats), '\t'))
                        {
                            String[] line;
                            int lineNo = 0;
                            while ((line = reader.readNext()) != null)
                            {
                                lineNo++;
                                if (lineNo == 1)
                                {
                                    continue; //header
                                }

                                if ("TotalVariants".equals(line[1]))
                                {
                                    totalVariants = line[2];
                                }

                                Map<String, Object> map = new CaseInsensitiveHashMap<>();
                                map.put("releaseId", releaseId);
                                map.put("category", line[0]);
                                map.put("metricName", line[1]);
                                map.put("value", line[2]);
                                map.put("objectId", new GUID().toString());
                                releaseStatsRows.add(map);
                            }
                        }
                    }
                    else
                    {
                        job.getLogger().error("unable to find release stats file: " + releaseStats.getPath());
                    }
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }

                if (totalVariants == null)
                {
                    throw new PipelineJobException("Unable to find total variant from stats file!");
                }

                //actually create release record
                Map<String, Object> row = new CaseInsensitiveHashMap<>();
                row.put("version", job.getParameters().get("releaseVersion"));
                row.put("releaseDate", new Date());
                row.put("vcfId", so.getRowid());
                row.put("liftedVcfId", liftedVcf.getRowid());
                row.put("sitesOnlyVcfId", sitesOnlyVcf.getRowid());
                row.put("novelSitesVcfId", novelSitesVcf.getRowid());
                row.put("variantTable", so2.getRowid());
                row.put("genomeId", so.getLibrary_id());
                row.put("totalSubjects", totalSubjects);
                row.put("totalVariants", totalVariants);
                row.put("objectId", releaseId);

                try
                {
                    variantReleaseRows.add(row);

                    File variantTable = so2.getFile();
                    if (variantTable.exists())
                    {
                        try (CSVReader reader = new CSVReader(IOUtil.openFileForBufferedReading(variantTable), '\t'))
                        {
                            String[] line;
                            int lineNo = 0;
                            while ((line = reader.readNext()) != null)
                            {
                                lineNo++;
                                if (lineNo == 1)
                                {
                                    continue; //header
                                }

                                Map<String, Object> map = new CaseInsensitiveHashMap<>();
                                map.put("releaseId", releaseId);
                                map.put("contig", line[0]);
                                map.put("position", line[1]);
                                map.put("reference", line[2]);
                                map.put("allele", line[3]);
                                map.put("source", line[4]);
                                map.put("reason", line[5]);
                                map.put("description", line[6]);
                                map.put("overlappingGenes", line[7]);
                                map.put("omim", queryOmim(line[8], job.getContainer(), job.getLogger()));
                                map.put("omim_phenotype", queryOmim(line[9], job.getContainer(), job.getLogger()));
                                map.put("af", line[10]);
                                map.put("identifier", line[11]);
                                Double cadd = StringUtils.trimToNull(line[12]) == null ? null : Double.parseDouble(line[12]);
                                map.put("cadd", cadd);
                                map.put("objectId", new GUID().toString());

                                variantTableRows.add(map);
                            }
                        }
                    }
                    else
                    {
                        job.getLogger().error("unable to find release stats file: " + variantTable.getPath());
                    }

                    //also tracks:
                    UserSchema us = QueryService.get().getUserSchema(job.getUser(), job.getContainer().isWorkbook() ? job.getContainer().getParent() : job.getContainer(), mGAPSchema.NAME);
                    new TableSelector(us.getTable(mGAPSchema.TABLE_RELEASE_TRACKS), null, null).forEachResults(rs -> {
                        SequenceOutputFile so3 = trackVCFMap.get(rs.getString(FieldKey.fromString("trackName")));
                        if (so3 == null && rs.getBoolean(FieldKey.fromString("isprimarytrack")))
                        {
                            //this is the primary track
                            so3 = so;
                        }

                        if (so3 == null && rs.getObject("vcfId") == null)
                        {
                            job.getLogger().error("unable to find track with name: " + rs.getString(FieldKey.fromString("trackName")));
                            return;
                        }

                        if (so3 == null)
                        {
                            throw new SQLException("Unable to find sequence output for track: " + rs.getString(FieldKey.fromString("trackName")));
                        }

                        File vcf = so3.getFile();
                        if (!vcf.exists())
                        {
                            job.getLogger().error("Unable to find file: " + vcf.getPath());
                            return;
                        }

                        int totalSamples = -1;
                        try (VCFFileReader reader = new VCFFileReader(vcf))
                        {
                            totalSamples = reader.getFileHeader().getNGenotypeSamples();
                        }

                        Map<String, Object> map = new CaseInsensitiveHashMap<>();
                        map.put("trackName", rs.getString(FieldKey.fromString("trackName")));
                        map.put("label", rs.getString(FieldKey.fromString("label")));
                        map.put("category", rs.getString(FieldKey.fromString("category")));
                        map.put("source", rs.getString(FieldKey.fromString("source")));
                        map.put("totalSamples", totalSamples);
                        map.put("description", rs.getString(FieldKey.fromString("description")));
                        map.put("isprimarytrack", rs.getBoolean(FieldKey.fromString("isprimarytrack")));
                        map.put("url", rs.getString(FieldKey.fromString("url")));
                        map.put("releaseId", releaseId);
                        map.put("vcfId", so3.getRowid());
                        map.put("objectId", new GUID().toString());

                        tracksPerReleaseRows.add(map);
                    });
                }
                catch (Exception e)
                {
                    throw new PipelineJobException("Error parsing data: " + e.getMessage(), e);
                }
            }

            //finally do actual DB inserts, if successful
            if (!testOnly)
            {
                try (DbScope.Transaction transaction = DbScope.getLabKeyScope().ensureTransaction())
                {
                    job.getLogger().info("Publishing release to variant catalog table");
                    TableInfo variantReleaseTable = QueryService.get().getUserSchema(job.getUser(), job.getContainer(), mGAPSchema.NAME).getTable(mGAPSchema.TABLE_VARIANT_CATALOG_RELEASES);
                    BatchValidationException errors = new BatchValidationException();
                    variantReleaseTable.getUpdateService().insertRows(job.getUser(), job.getContainer(), variantReleaseRows, errors, null, new HashMap<>());
                    if (errors.hasErrors())
                    {
                        throw errors;
                    }

                    TableInfo variantTableInfo = QueryService.get().getUserSchema(job.getUser(), job.getContainer(), mGAPSchema.NAME).getTable(mGAPSchema.TABLE_VARIANT_TABLE);
                    BatchValidationException errors2 = new BatchValidationException();
                    variantTableInfo.getUpdateService().insertRows(job.getUser(), job.getContainer(), variantTableRows, errors2, null, new HashMap<>());
                    if (errors2.hasErrors())
                    {
                        throw errors2;
                    }

                    job.getLogger().info("total variant table records: " + variantTableRows.size());

                    TableInfo releaseStatsTable = QueryService.get().getUserSchema(job.getUser(), job.getContainer(), mGAPSchema.NAME).getTable(mGAPSchema.TABLE_RELEASE_STATS);
                    BatchValidationException errors3 = new BatchValidationException();
                    releaseStatsTable.getUpdateService().insertRows(job.getUser(), job.getContainer(), releaseStatsRows, errors3, null, new HashMap<>());
                    if (errors3.hasErrors())
                    {
                        throw errors3;
                    }

                    job.getLogger().info("total release stat records: " + releaseStatsRows.size());

                    TableInfo tracksPerReleaseTable = QueryService.get().getUserSchema(job.getUser(), job.getContainer(), mGAPSchema.NAME).getTable(mGAPSchema.TABLE_TRACKS_PER_RELEASE);
                    BatchValidationException errors4 = new BatchValidationException();
                    tracksPerReleaseTable.getUpdateService().insertRows(job.getUser(), job.getContainer(), tracksPerReleaseRows, errors4, null, new HashMap<>());
                    if (errors4.hasErrors())
                    {
                        throw errors4;
                    }

                    job.getLogger().info("total tracks per release records: " + tracksPerReleaseRows.size());

                    //finally phenotypes:
                    int phenotypes = updatePhenotypes(releaseId, job.getLogger(), job.getContainer(), job.getUser());
                    job.getLogger().info("total phenotypes: " + phenotypes);

                    transaction.commit();
                }
                catch (Exception e)
                {
                    throw new PipelineJobException("Error saving data: " + e.getMessage(), e);
                }
            }
            else
            {
                job.getLogger().info("This was selected as a test-only run, so skipping creation of release record");
            }
        }

        Map<String, String> omimMap = new HashMap<>();

        public String queryOmim(String orig, Container c, Logger log)
        {
            String[] elements = orig.split(";");
            List<String> retList = new ArrayList<>();
            for (String input : elements)
            {
                if (input.contains("<>"))
                {
                    String[] parts = input.split("<>");
                    input = parts.length == 1 ? parts[0] : parts[1];
                }

                if (omimMap.containsKey(input))
                {
                    retList.add(omimMap.get(input));
                    continue;
                }

                String ret = StringUtils.trimToNull(input);
                if (ret == null)
                {
                    continue;
                }

                String apiKey = mGAPManager.get().getOmimApiKey(c);
                if (apiKey == null)
                {
                    log.error("OMIM APIKey not set");
                    omimMap.put(input, input);
                    retList.add(input);
                    continue;
                }

                try
                {
                    String resolved = getOmimJson(input, apiKey, log, orig);
                    if (resolved != null && !resolved.equals(input))
                    {
                        //OMIM gene entries default to containing semicolons, which is our delimiter in this field
                        resolved = resolved.replaceAll(";", ",");
                        ret = resolved + "<>" + input;
                    }
                }
                catch (IOException e)
                {
                    log.error(e + ", orig value: " + orig);
                }

                omimMap.put(input, ret);
                retList.add(ret);
            }

            return StringUtils.join(retList, ";");
        }

        private String getOmimJson(String input, String apiKey, Logger log, String orig) throws IOException
        {
            if (input == null || input.length() < 4)
            {
                log.error("bad omim value: " + input);
                return null;
            }

            String url = "https://api.omim.org/api/entry?mimNumber=" + input + "&apiKey=" + apiKey + "&format=json";
            URL obj = new URL(url);
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();
            if (con.getResponseCode() != HttpURLConnection.HTTP_OK)
            {
                log.error("bad request: " + url + ", orig: " + orig);
                return null;
            }

            try (BufferedReader in = Readers.getReader(con.getInputStream()))
            {
                String inputLine;
                StringBuilder response = new StringBuilder();
                while ((inputLine = in.readLine()) != null)
                {
                    response.append(inputLine);
                }

                JSONObject json = new JSONObject(response.toString());
                if (json.has("omim"))
                {
                    json = json.getJSONObject("omim");
                    if (json.has("entryList"))
                    {
                        for (JSONObject j : JsonUtil.toJSONObjectList(json.getJSONArray("entryList")))
                        {
                            String val = j.getJSONObject("entry").getJSONObject("titles").optString("preferredTitle", input);
                            if (val.contains(";"))
                            {
                                String[] tokens = val.split(";");
                                String id = StringUtils.trimToNull(tokens[tokens.length - 1]);
                                String name = StringUtils.join(Arrays.asList(Arrays.copyOf(tokens, tokens.length-1)), ";");
                                val = name + " (" + id + ")";
                            }

                            return val;
                        }
                    }
                }
            }

            return null;
        }


        @Override
        public void processFilesOnWebserver(PipelineJob job, SequenceAnalysisJobSupport support, List<SequenceOutputFile> inputFiles, JSONObject params, File outputDir, List<RecordedAction> actions, List<SequenceOutputFile> outputsToCreate) throws UnsupportedOperationException, PipelineJobException
        {

        }

        public static class TrackDescriptor
        {
            String _trackName;
            Integer _dataId;
            Integer _mergePriority;
            boolean _skipValidation;
            boolean _isPrimary;

            public TrackDescriptor(String[] vals)
            {
                _trackName = vals[0];
                _dataId = Integer.parseInt(vals[1]);
                _mergePriority = Integer.parseInt(vals[2]);
                _skipValidation = Boolean.parseBoolean(vals[3]);
                _isPrimary = Boolean.parseBoolean(vals[4]);
            }

            public String getTrackName()
            {
                return _trackName;
            }

            public Integer getDataId()
            {
                return _dataId;
            }

            public Integer getMergePriority()
            {
                return _mergePriority;
            }

            public boolean isSkipValidation()
            {
                return _skipValidation;
            }

            public boolean isPrimary()
            {
                return _isPrimary;
            }
        }

        private List<TrackDescriptor> getTracks(File webserverDir) throws PipelineJobException
        {
            try (CSVReader reader = new CSVReader(Readers.getReader(getTrackListFile(webserverDir)), '\t'))
            {
                List<TrackDescriptor> ret = new ArrayList<>();
                String[] line;
                while ((line = reader.readNext()) != null)
                {
                    ret.add(new TrackDescriptor(line));
                }

                ret.sort(new Comparator<TrackDescriptor>()
                {
                    @Override
                    public int compare(TrackDescriptor o1, TrackDescriptor o2)
                    {
                        return o1.getMergePriority().compareTo(o2.getMergePriority());
                    }
                });

                return ret;
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }

        @Override
        public void processFilesRemote(List<SequenceOutputFile> inputFiles, JobContext ctx) throws UnsupportedOperationException, PipelineJobException
        {
            //first build map of gene to geneName:
            ctx.getLogger().info("building map of gene symbol to name:");
            int gtfId = ctx.getParams().getInt("gtfFile");
            File gtf = ctx.getSequenceSupport().getCachedData(gtfId);
            if (gtf == null || !gtf.exists())
            {
                throw new PipelineJobException("Unable to find file: " + gtf);
            }

            GeneToNameTranslator translator = new GeneToNameTranslator(gtf, ctx.getLogger());
            ReferenceGenome grch37Genome = ctx.getSequenceSupport().getCachedGenome(ctx.getParams().getInt(AnnotationStep.GRCH37));
            int genomeId = ctx.getSequenceSupport().getCachedObject(MMUL_GENOME, Integer.class);
            ReferenceGenome genome = ctx.getSequenceSupport().getCachedGenome(genomeId);
            boolean testOnly = ctx.getParams().optBoolean("testOnly", false);

            String releaseVersion = ctx.getParams().optString("releaseVersion", "0.0");
            File primaryTrackVcf = new File(ctx.getOutputDir(), "mGap.v" + FileUtil.makeLegalName(releaseVersion).replaceAll(" ", "_") + ".vcf.gz");

            try
            {
                RecordedAction action = new RecordedAction();
                for (TrackDescriptor track : getTracks(ctx.getSourceDirectory(true)))
                {
                    ctx.getLogger().info("inspecting track: " + track.getTrackName());

                    File vcf = ctx.getSequenceSupport().getCachedData(track.getDataId());
                    action.addInput(vcf, "Input VCF");
                    action.addInput(new File(vcf.getPath() + ".tbi"), "Input VCF Index");

                    //sanity check annotations exist/dont
                    checkVcfAnnotationsAndSamples(vcf, track.isSkipValidation());

                    File renamedVcf = track.isPrimary() ? primaryTrackVcf : new File(ctx.getOutputDir(), FileUtil.makeLegalName(track.getTrackName()).replaceAll(" ", "_") + ".vcf.gz");
                    File renamedVcfIdx = new File(renamedVcf.getPath() + ".tbi");
                    File renamedVcfDone = new File(renamedVcf.getPath() + ".done");
                    if (renamedVcfDone.exists())
                    {
                        ctx.getLogger().info("File already present: " + renamedVcf.getPath());
                    }
                    else
                    {
                        ctx.getLogger().info("Copying VCF: " + renamedVcf.getPath());
                        if (renamedVcf.exists())
                        {
                            renamedVcf.delete();
                        }
                        FileUtils.copyFile(vcf, renamedVcf);

                        if (renamedVcfIdx.exists())
                        {
                            renamedVcfIdx.delete();
                        }
                        FileUtils.copyFile(new File(vcf.getPath() + ".tbi"), renamedVcfIdx);

                        FileUtils.touch(renamedVcfDone);
                    }

                    ctx.getFileManager().removeIntermediateFile(renamedVcf);
                    ctx.getFileManager().removeIntermediateFile(renamedVcfIdx);
                    ctx.getFileManager().addIntermediateFile(renamedVcfDone);

                    if (!track.isPrimary())
                    {
                        SequenceOutputFile output = new SequenceOutputFile();
                        output.setFile(renamedVcf);
                        output.setName(track.getTrackName());
                        output.setCategory("Release Track");
                        output.setLibrary_id(genome.getGenomeId());
                        ctx.getFileManager().addSequenceOutput(output);
                    }
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            if (!primaryTrackVcf.exists())
            {
                throw new PipelineJobException("Unable to find primary track VCF, expected: " + primaryTrackVcf.getPath());
            }

            //Then summarize:
            ctx.getLogger().info("inspecting primary VCF and creating summary table");
            inspectAndSummarizeVcf(ctx, primaryTrackVcf, translator, genome, true);

            SequenceOutputFile output = new SequenceOutputFile();
            output.setFile(primaryTrackVcf);
            output.setName("mGAP Release: " + releaseVersion);
            output.setCategory((testOnly ? "Test " : "") + "mGAP Release");
            output.setLibrary_id(genome.getGenomeId());
            ctx.getFileManager().addSequenceOutput(output);

            File interestingVariantTable = getVariantTableName(ctx, primaryTrackVcf);
            SequenceOutputFile output2 = new SequenceOutputFile();
            output2.setFile(interestingVariantTable);
            output2.setName("mGAP Release: " + releaseVersion + " Variant Table");
            output2.setCategory((testOnly ? "Test " : "") + "mGAP Release Variant Table");
            output2.setLibrary_id(genome.getGenomeId());
            ctx.getFileManager().addSequenceOutput(output2);

            File sitesOnlyVcf = getSitesOnlyVcf(ctx, primaryTrackVcf, genome);

            File lifted = liftToHuman(ctx, primaryTrackVcf, sitesOnlyVcf, grch37Genome);
            SequenceOutputFile output3 = new SequenceOutputFile();
            output3.setFile(lifted);
            output3.setName("mGAP Release: " + releaseVersion + " Lifted to Human");
            output3.setCategory((testOnly ? "Test " : "") + "mGAP Release Lifted to Human");
            output3.setLibrary_id(grch37Genome.getGenomeId());
            ctx.getFileManager().addSequenceOutput(output3);
        }

        private void checkVcfAnnotationsAndSamples(File vcfInput, boolean skipAnnotationChecks) throws PipelineJobException
        {
            try (VCFFileReader reader = new VCFFileReader(vcfInput))
            {
                VCFHeader header = reader.getFileHeader();

                if (!header.getSampleNamesInOrder().isEmpty())
                {
                    Set<String> nonCompliant = new HashSet<>();
                    for (String subject : header.getSampleNamesInOrder())
                    {
                        if (!subject.matches("^m[0-9]+$"))
                        {
                            nonCompliant.add(subject);
                        }
                    }

                    if (!nonCompliant.isEmpty())
                    {
                        throw new PipelineJobException("Names do not conform to format: " + StringUtils.join(nonCompliant, ","));
                    }
                }

                if (!skipAnnotationChecks)
                {
                    for (String info : Arrays.asList("CADD_PH", "OMIMN", "CLN_ALLELE", "AF"))
                    {
                        if (!header.hasInfoLine(info))
                        {
                            throw new PipelineJobException("VCF missing expected header line: " + info);
                        }
                    }
                }
            }
        }

        private File getSitesOnlyVcf(JobContext ctx, File primaryTrackVcf, ReferenceGenome sourceGenome) throws PipelineJobException
        {
            //drop genotypes for performance:
            ctx.getLogger().info("creating VCF without genotypes");
            File noGenotypes = getSitesOnlyVcfName(ctx.getOutputDir(), primaryTrackVcf);
            if (indexExists(noGenotypes))
            {
                ctx.getLogger().info("resuming from file: " + noGenotypes.getPath());
            }
            else
            {
                SelectVariantsWrapper wrapper = new SelectVariantsWrapper(ctx.getLogger());
                wrapper.execute(sourceGenome.getWorkingFastaFile(), primaryTrackVcf, noGenotypes, Arrays.asList("--sites-only-vcf-output"));
            }

            SequenceOutputFile output = new SequenceOutputFile();
            output.setFile(noGenotypes);
            output.setName(primaryTrackVcf.getName() + ": Sites Only");
            output.setCategory("mGAP Release: Sites Only");
            output.setLibrary_id(sourceGenome.getGenomeId());
            ctx.getFileManager().addSequenceOutput(output);

            return noGenotypes;
        }

        private File liftToHuman(JobContext ctx, File primaryTrackVcf, File noGenotypes, ReferenceGenome grch37Genome) throws PipelineJobException
        {
            //lift to target genome
            Integer chainFileId = ctx.getSequenceSupport().getCachedObject(AnnotationStep.CHAIN_FILE, Integer.class);
            File chainFile = ctx.getSequenceSupport().getCachedData(chainFileId);

            ctx.getLogger().info("lift to genome: " + grch37Genome.getGenomeId());

            File liftedToGRCh37 = getLiftedVcfName(ctx.getOutputDir(), primaryTrackVcf);
            File liftoverRejects = new File(ctx.getOutputDir(), SequenceAnalysisService.get().getUnzippedBaseName(primaryTrackVcf.getName()) + ".liftoverRejectGRCh37.vcf.gz");
            if (!indexExists(liftoverRejects))
            {
                LiftoverVcfRunner liftoverVcfRunner = new LiftoverVcfRunner(ctx.getLogger());
                liftoverVcfRunner.doLiftover(noGenotypes, chainFile, grch37Genome.getWorkingFastaFile(), liftoverRejects, liftedToGRCh37, 0.95);
            }
            else
            {
                ctx.getLogger().info("resuming with existing file: " + liftedToGRCh37.getPath());
            }
            ctx.getFileManager().addIntermediateFile(liftoverRejects);
            ctx.getFileManager().addIntermediateFile(new File(liftoverRejects.getPath() + ".tbi"));

            return liftedToGRCh37;
        }

        private File getSitesOnlyVcfName(File outDir, File primaryTrackVcf)
        {
            return new File(outDir, SequenceAnalysisService.get().getUnzippedBaseName(primaryTrackVcf.getName()) + ".sitesOnly.vcf.gz");
        }

        private File getDroppedSitesVcfName(File outDir, File primaryTrackVcf)
        {
            return new File(outDir, SequenceAnalysisService.get().getUnzippedBaseName(primaryTrackVcf.getName()) + ".droppedFromPriorRelease.vcf.gz");
        }

        private File getNovelSitesVcfName(File outDir, File primaryTrackVcf)
        {
            return new File(outDir, SequenceAnalysisService.get().getUnzippedBaseName(primaryTrackVcf.getName()) + ".newToRelease.vcf.gz");
        }

        private File getLiftedVcfName(File outDir, File primaryTrackVcf)
        {
            return new File(outDir, SequenceAnalysisService.get().getUnzippedBaseName(primaryTrackVcf.getName()) + ".liftToGRCh37.vcf.gz");
        }

        private File getVariantTableName(JobContext ctx, File vcfInput)
        {
            return new File(ctx.getOutputDir(), SequenceAnalysisService.get().getUnzippedBaseName(vcfInput.getName()) + ".variants.txt");
        }

        private void inspectAndSummarizeVcf(JobContext ctx, File vcfInput, GeneToNameTranslator translator, ReferenceGenome genome, boolean generateSummaries) throws PipelineJobException
        {
            long sitesInspected = 0L;
            long totalVariants = 0L;
            long totalPrivateVariants = 0L;
            Map<VariantContext.Type, Long> typeCounts = new HashMap<>();

            File interestingVariantTable = getVariantTableName(ctx, vcfInput);
            try (VCFFileReader reader = new VCFFileReader(vcfInput); CloseableIterator<VariantContext> it = reader.iterator(); CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(interestingVariantTable), '\t', CSVWriter.NO_QUOTE_CHARACTER))
            {
                writer.writeNext(new String[]{"Chromosome", "Position", "Reference", "Allele", "Source", "Reason", "Description", "Overlapping Gene(s)", "OMIM Entries", "OMIM Phenotypes", "AF", "Identifier", "CADD_PH"});
                while (it.hasNext())
                {
                    Set<List<String>> queuedLines = new LinkedHashSet<>();

                    sitesInspected++;

                    if (sitesInspected % 1000000 == 0)
                    {
                        ctx.getLogger().info("inspected " + sitesInspected + " variants");
                    }

                    VariantContext vc = it.next();
                    if (vc.isFiltered())
                    {
                        continue;
                    }

                    totalVariants++;

                    //track total by variant type
                    Long typeCount = typeCounts.get(vc.getType());
                    if (typeCount == null)
                    {
                        typeCount = 0L;
                    }
                    typeCount++;
                    typeCounts.put(vc.getType(), typeCount);

                    //count private alleles.  note: this is counting alleles, not sites
                    for (Allele a : vc.getAlleles())
                    {
                        int sampleCount = 0;
                        for (Genotype g : vc.getGenotypes())
                        {
                            if (g.getAlleles().contains(a))
                            {
                                sampleCount++;
                                if (sampleCount > 1)
                                {
                                    break;
                                }
                            }
                        }

                        if (sampleCount == 1)
                        {
                            totalPrivateVariants++;
                        }
                    }

                    Set<String> omims = new LinkedHashSet<>();
                    Set<String> omimds = new LinkedHashSet<>();
                    if (vc.getAttribute("OMIMN") != null)
                    {
                        omims.add(vc.getAttributeAsString("OMIMN", null));
                    }

                    if (vc.getAttribute("OMIMD") != null)
                    {
                        omimds.addAll(parseRawOmimd(vc, ctx.getLogger()));
                    }

                    Set<String> overlappingGenes = new TreeSet<>();
                    if (vc.getAttribute("ANN") != null)
                    {
                        List<String> anns = vc.getAttributeAsStringList("ANN", "");

                        //find overlapping genes first
                        for (String ann : anns)
                        {
                            if (StringUtils.isEmpty(ann))
                            {
                                continue;
                            }

                            String[] tokens = ann.split("\\|");
                            if (tokens.length < 4)
                            {
                                //intergenic modifiers
                                continue;
                            }

                            if (!StringUtils.isEmpty(tokens[3]))
                            {
                                String geneName = tokens[3];
                                if (geneName.startsWith("ENSMMUE"))
                                {
                                    //exons
                                    continue;
                                }

                                if (geneName.startsWith("gene:"))
                                {
                                    geneName = geneName.replaceAll("gene:", "");
                                }

                                if (translator.getGeneMap().containsKey(geneName) && translator.getGeneMap().get(geneName).get("gene_name") != null)
                                {
                                    geneName = translator.getGeneMap().get(geneName).get("gene_name");
                                }

                                overlappingGenes.add(geneName);
                            }
                        }

                        Set<String> overlappingGenesReported = new HashSet<>();
                        for (String ann : anns)
                        {
                            if (StringUtils.isEmpty(ann))
                            {
                                continue;
                            }

                            String[] tokens = ann.split("\\|");

                            if ("HIGH".equals(tokens[2]))
                            {
                                if (tokens.length < 10)
                                {
                                    ctx.getLogger().error("unexpected ANN line at pos: " + vc.getContig() + " " + vc.getStart() + "[" + tokens + "]");
                                    continue;
                                }

                                String description = "Type: " + (tokens[1].replaceAll("&", ", ")) + "; Gene: " + tokens[3];
                                if (tokens.length > 10 && !StringUtils.isEmpty(tokens[10]))
                                {
                                    description += "; AA Change: " + tokens[10];
                                }

                                String overlappingGenesJoin = StringUtils.join(overlappingGenes, ",");
                                if (!overlappingGenesReported.contains(overlappingGenesJoin))
                                {
                                    maybeWriteVariantLine(queuedLines, vc, tokens[0], "SNPEff", "Predicted High Impact", description, overlappingGenes, omims, omimds, ctx.getLogger(), null);

                                    // NOTE: a given site could have multiple overlapping ORFs (usually different isoforms), so if we hit one allow this to tag that site and skip the remaining.
                                    overlappingGenesReported.add(overlappingGenesJoin);
                                }
                            }
                        }
                    }

                    if (vc.getAttribute("CLN_SIG") != null)
                    {
                        List<String> clnAlleles = vc.getAttributeAsStringList("CLN_ALLELE", "");
                        List<String> clnSigs = vc.getAttributeAsStringList("CLN_SIG", "");
                        List<String> clnDisease = vc.getAttributeAsStringList("CLN_DN", "");
                        List<String> clnAlleleIds = vc.getAttributeAsStringList("CLN_ALLELEID", "");
                        int i = -1;
                        for (String sigList : clnSigs)
                        {
                            i++;

                            List<String> sigSplit = Arrays.asList(sigList.split("\\|"));
                            List<String> diseaseSplit = Arrays.asList(clnDisease.get(i).split("\\|"));
                            int j = 0;
                            for (String sig : sigSplit)
                            {
                                //TODO: consider disease = not_provided
                                if (isAllowableClinVarSig(sig))
                                {
                                    String description = StringUtils.join(new String[]{
                                            "Significance: " + sig
                                    }, ",");

                                    try
                                    {
                                        String allele = clnAlleles.get(i);
                                        maybeWriteVariantLine(queuedLines, vc, allele, "ClinVar", diseaseSplit.get(j), description, overlappingGenes, omims, omimds, ctx.getLogger(), "ClinVar:" + clnAlleleIds.get(i));

                                    }
                                    catch (IndexOutOfBoundsException e)
                                    {
                                        ctx.getLogger().warn("Problem parsing line: " + vc.toStringWithoutGenotypes());
                                        ctx.getLogger().warn("Significance: " + sig + " / " + j);
                                        ctx.getLogger().warn("Allele IDs: " + StringUtils.join(clnAlleleIds, ";"));
                                        ctx.getLogger().warn("Disease: " + StringUtils.join(diseaseSplit, ";"));
                                    }
                                }

                                j++;
                            }
                        }
                    }

                    //NE: nsdb Polyphen2_HVAR_score: Polyphen2 score based on HumVar, i.e. hvar_prob. The score ranges from 0 to 1, and the corresponding prediction is 'probably damaging' if it is in [0.909,1], 'possibly damaging' if it is in [0.447,0.908], 'benign' if it is in [0,0.446]. Score cutoff for binary classification is 0.5, i.e. the prediction is 'neutral' if the score is smaller than 0.5 and 'deleterious' if the score is larger than 0.5. Multiple entries separated by
                    //NF: nsdb Polyphen2_HVAR_pred: Polyphen2 prediction based on HumVar, 'D' ('probably damaging'),'P' ('possibly damaging') and 'B' ('benign'). Multiple entries separated by
                    if (vc.getAttribute("NF") != null && !".".equals(vc.getAttribute("NF")))
                    {
                        Set<String> polyphenPredictions = new HashSet<>(vc.getAttributeAsStringList("NF", null));
                        polyphenPredictions.remove("B");
                        polyphenPredictions.remove("P");

                        String description = null;
                        if (!polyphenPredictions.isEmpty())
                        {
                            try
                            {
                                Double maxScore = Collections.max(vc.getAttributeAsDoubleList("NE", 0.0));
                                description = StringUtils.join(new String[]{
                                        "Score: " + String.valueOf(maxScore)
                                }, ",");
                            }
                            catch (NumberFormatException e)
                            {
                                ctx.getLogger().warn("Unable to parse NE attribute decimal (" + vc.getAttribute("NE") + ") for variant at position: " + vc.toStringWithoutGenotypes());
                            }

                            maybeWriteVariantLine(queuedLines, vc, null, "Polyphen2", "Prediction: " + StringUtils.join(polyphenPredictions, ","), description, overlappingGenes, omims, omimds, ctx.getLogger(), null);
                        }
                    }

                    for (List<String> line : queuedLines)
                    {
                        writer.writeNext(line.toArray(new String[line.size()]));
                    }
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            if (generateSummaries)
            {
                int totalSubjects;
                try (VCFFileReader reader = new VCFFileReader(vcfInput))
                {
                    totalSubjects = reader.getFileHeader().getSampleNamesInOrder().size();
                }

                generateSummaries(ctx, vcfInput, genome, totalVariants, totalPrivateVariants, totalSubjects, typeCounts);
            }
        }

        public Collection<String> parseRawOmimd(VariantContext vc, Logger log)
        {
            //NOTE: because this field can have internal commas, this can be parsed incorrectly, so ignore this and re-join
            String rawVal;
            if (vc.getAttribute("OMIMD") instanceof Collection)
            {
                rawVal = StringUtils.join(vc.getAttributeAsStringList("OMIMD", null), ",");
            }
            else
            {
                rawVal = vc.getAttributeAsString("OMIMD", null);
            }

            if (!StringUtils.isEmpty(rawVal) && ! ".".equals(rawVal))
            {
                Collection<String> vals = parseOmim(rawVal, log);
                if (vals != null)
                {
                    return vals;
                }
            }

            return Collections.emptySet();
        }

        private boolean isAllowableClinVarSig(String x)
        {
            return !(StringUtils.isEmpty(x) || x.toLowerCase().contains("benign") || x.toLowerCase().contains("unknown") || x.toLowerCase().contains("uncertain") || x.contains("not_specified") || x.contains("not_provided"));
        }

        private void generateSummaries(JobContext ctx, File vcf, ReferenceGenome genome, long totalVariants, long totalPrivateVariants, int totalSubjects, Map<VariantContext.Type, Long> typeCounts) throws PipelineJobException
        {
            //variants to table
            ctx.getLogger().info("Running VariantsToTable");
            File variantsToTable = new File(ctx.getOutputDir(), SequenceAnalysisService.get().getUnzippedBaseName(vcf.getName()) + ".variantsToTable.txt");
            File tableCheck = new File(variantsToTable.getPath() + ".done");
            if (!tableCheck.exists())
            {
                VariantsToTableRunner vtt = new VariantsToTableRunner(ctx.getLogger());
                List<String> fields = new ArrayList<>(Arrays.asList("POS", "REF", "ALT", "FILTER"));
                fields.addAll(allowedFields);
                vtt.execute(vcf, variantsToTable, genome.getWorkingFastaFile(), fields);

                try
                {
                    FileUtils.touch(tableCheck);
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }
            }
            else
            {
                ctx.getLogger().info("resuming with existing file: " + variantsToTable.getPath());
            }

            ctx.getFileManager().addIntermediateFile(variantsToTable);
            ctx.getFileManager().addIntermediateFile(tableCheck);

            //generate stats
            ctx.getLogger().info("Generating summary stats from: " + variantsToTable.getName());
            File summaryTable = new File(vcf.getParentFile(), SequenceAnalysisService.get().getUnzippedBaseName(vcf.getName()) + ".summary.txt");
            File summaryTableByField = new File(vcf.getParentFile(), SequenceAnalysisService.get().getUnzippedBaseName(vcf.getName()) + ".summaryByField.txt");
            generateSummary(ctx, variantsToTable, summaryTable, summaryTableByField, totalVariants, totalPrivateVariants, totalSubjects, typeCounts);
        }

        private static final List<String> allowedFields = Arrays.asList("CHROM", "ANN", "CLN_SIG", "GRASP_PH", "ENCTFBS_TF", "FE", "NF", "ENCSEG_NM");

        private class FieldTracker
        {
            private Map<String, FieldData> perField;

            public FieldTracker(int size)
            {
                perField = new HashMap<>(size);
            }

            public void add(String fieldName, String val)
            {
                if (!allowedFields.contains(fieldName))
                {
                    return;
                }

                if (!StringUtils.isEmpty(val) && !"NA".equals(val))
                {
                    if ("ANN".equals(fieldName))
                    {
                        boolean isHighImpact = false;
                        Set<String> codingPotential = new HashSet<>();
                        String[] tokens = val.split(",");
                        for (String v : tokens)
                        {
                            String[] split = v.split("\\|");
                            if ("HIGH".equals(split[2]))
                            {
                                isHighImpact = true;
                            }

                            String[] types = split[1].split("&");
                            codingPotential.addAll(Arrays.asList(types));
                        }

                        if (isHighImpact)
                        {
                            addForValue("AnnotationSummary", "Predicted High Impact (SnpEff)");
                        }

                        filterCodingPotential(codingPotential);
                        String type = StringUtils.join(new TreeSet<>(codingPotential), ";");
                        addForValue("CodingPotential", type);
                    }
                    else if ("CLN_SIG".equals(fieldName))
                    {
                        boolean hasOverlap = false;
                        boolean hasAnyOverlap = false;
                        String[] tokens = val.split(",");
                        for (String v : tokens)
                        {
                            if (StringUtils.isEmpty(v))
                            {
                                continue;
                            }

                            hasAnyOverlap = true;

                            String[] split = v.split("\\|");
                            for (String sig : split)
                            {
                                //TODO: consider disease = not_provided
                                if (isAllowableClinVarSig(sig))
                                {
                                    hasOverlap = true;
                                    break;
                                }
                            }
                        }

                        if (hasOverlap)
                        {
                            addForValue("AnnotationSummary", "ClinVar Overlap (Pathogenic)");
                        }

                        if (hasAnyOverlap)
                        {
                            addForValue("AnnotationSummary", "ClinVar Overlap");
                        }
                    }
                    else if ("GRASP_PH".equals(fieldName))
                    {
                        addForValue("AnnotationSummary", "GWAS Associations (GRASP)");
                    }
                    else if ("FE".equals(fieldName) && "Y".equals(val))
                    {
                        addForValue("AnnotationSummary", "Enhancer Region (FANTOM5)");
                    }
                    else if ("ENCTFBS_TF".equals(fieldName))
                    {
                        addForValue("AnnotationSummary", "Transcription Factor Binding (ENCODE)");
                    }
                    else if ("NF".equals(fieldName) && val.contains("D"))
                    {
                        addForValue("AnnotationSummary", "Damaging (Polyphen2)");
                    }
                    else if ("ENCSEG_NM".equals(fieldName))
                    {
                        List<String> values = Arrays.asList(val.split(","));
                        if (values.contains("E"))
                        {
                            addForValue("AnnotationSummary", "Predicted Enhancer (ENCODE)");
                        }
                    }
                    else if ("CHROM".equals(fieldName))
                    {
                        addForValue("PerChromosome", val);
                    }
                }
            }

            private void addForValue(String fieldName, String val)
            {
                if (allowForLevel(val))
                {
                    FieldData data = perField.get(fieldName);
                    if (data == null)
                    {
                        data = new FieldData();
                    }

                    Integer count = data.countsPerLevel.get(val);
                    if (count == null)
                    {
                        count = 0;
                    }
                    count++;

                    data.countsPerLevel.put(val, count);

                    perField.put(fieldName, data);
                }
            }

            private boolean allowForLevel(String val)
            {
                //if not numeric, accept it
                if (!NumberUtils.isCreatable(val))
                {
                    return true;
                }

                //otherwise allow only integers
                return NumberUtils.isDigits(val);
            }
        }

        private class FieldData
        {
            int nonNull = 0;
            Map<String, Integer> countsPerLevel = new HashMap<>(20);
        }

        public static void filterCodingPotential(Set<String> codingPotential)
        {
            //due to overlapping transcripts, this is often added.  remove these less-specific terms in order
            for (String type : Arrays.asList("intragenic_variant", "non_coding_transcript_variant", "intron_variant"))
            {
                if (codingPotential.size() > 1 && codingPotential.contains(type))
                {
                    codingPotential.remove(type);
                }
            }

            if (codingPotential.contains("synonymous_variant") || codingPotential.contains("missense_variant"))
            {
                codingPotential.remove("intragenic_variant");
                codingPotential.remove("non_coding_transcript_variant");
                codingPotential.contains("intron_variant");
            }
        }

        private void generateSummary(JobContext ctx, File variantsToTable, File output, File outputPerValue, long totalVariants, long totalPrivateVariants, int totalSubjects, Map<VariantContext.Type, Long> typeCounts) throws PipelineJobException
        {
            ctx.getLogger().info("reading variant table");
            int lineNo = 0;
            FieldTracker tracker = new FieldTracker(130);
            try (BufferedReader reader = Readers.getReader(variantsToTable))
            {
                lineNo++;
                if (lineNo % 500000 == 0)
                {
                    ctx.getLogger().info("processed " + lineNo + " lines");
                }

                String lineStr;
                List<String> header = new ArrayList<>();
                int lineCount = 0;
                while ((lineStr = reader.readLine()) != null)
                {
                    String[] line = lineStr.split("\t");
                    lineCount++;
                    if (lineCount == 1)
                    {
                        //skip header
                        header = Arrays.asList(line);
                        continue;
                    }

                    //skip basic site information, but do include CHROM, since that might be useful to see summarized
                    for (int i = 4; i < line.length; i++)
                    {
                        tracker.add(header.get(i), line[i]);
                    }
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            ctx.getLogger().info("writing summary tables");
            try (CSVWriter writer = new CSVWriter(IOUtil.openFileForBufferedWriting(output), '\t', CSVWriter.NO_QUOTE_CHARACTER); CSVWriter valWriter = new CSVWriter(IOUtil.openFileForBufferedWriting(outputPerValue), '\t', CSVWriter.NO_QUOTE_CHARACTER))
            {
                writer.writeNext(new String[]{"Field", "Category", "NonNull", "TotalDistinct", "Levels"});
                for (String fn : new TreeSet<>(tracker.perField.keySet()))
                {
                    FieldData data = tracker.perField.get(fn);

                    String vals = "";
                    if (data.countsPerLevel.size() < 10)
                    {
                        vals = StringUtils.join(data.countsPerLevel.keySet(), ",");
                    }

                    writer.writeNext(new String[]{fn, "None", String.valueOf(data.nonNull), (data.countsPerLevel.isEmpty() ? "" : String.valueOf(data.countsPerLevel.size())), vals});
                }

                valWriter.writeNext(new String[]{"Field", "Level", "Total"});
                valWriter.writeNext(new String[]{"Counts", "TotalVariants", String.valueOf(totalVariants)});
                valWriter.writeNext(new String[]{"Counts", "TotalPrivateVariants", String.valueOf(totalPrivateVariants)});
                valWriter.writeNext(new String[]{"Counts", "TotalSamples", String.valueOf(totalSubjects)});

                for (VariantContext.Type type : typeCounts.keySet())
                {
                    valWriter.writeNext(new String[]{"VariantType", type.name(), String.valueOf(typeCounts.get(type))});
                }

                for (String fn : new TreeSet<>(tracker.perField.keySet()))
                {
                    FieldData data = tracker.perField.get(fn);
                    for (String val : data.countsPerLevel.keySet())
                    {
                        valWriter.writeNext(new String[]{fn, val, String.valueOf(data.countsPerLevel.get(val))});
                    }
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }

        private void maybeWriteVariantLine(Set<List<String>> queuedLines, VariantContext vc, @Nullable String allele, String source, String reason, String description, Collection<String> overlappingGenes, Collection<String> omims, Collection<String> omimds, Logger log, String identifier)
        {
            if (allele == null)
            {
                List<String> alts = new ArrayList<>();
                vc.getAlternateAlleles().forEach(a -> alts.add(a.getDisplayString()));
                allele = StringUtils.join(alts, ",");
            }

            Object af = null;
            if (allele != null && !allele.contains(",") && vc.hasAttribute("AF"))
            {
                List<Object> afs = vc.getAttributeAsList("AF");
                int i = 0;
                for (Allele a : vc.getAlternateAlleles())
                {
                    if (allele.equals(a.getBaseString()))
                    {
                        if (i < afs.size())
                        {
                            af = afs.get(i);
                            break;
                        }
                        else
                        {
                            log.error("alleles and AF values not same length for " + vc.getContig() + " " + vc.getStart() + ". " + vc.getAttributeAsString("AF", ""));
                        }
                    }

                    i++;
                }
            }

            Object cadd = vc.getAttribute("CADD_PH");

            queuedLines.add(Arrays.asList(vc.getContig(), String.valueOf(vc.getStart()), vc.getReference().getDisplayString(), allele, source, reason, (description == null ? "" : description), StringUtils.join(overlappingGenes, ";"), StringUtils.join(omims, ";"), StringUtils.join(omimds, ";"), af == null ? "" : af.toString(), identifier == null ? "" : identifier, cadd == null ? "" : cadd.toString()));
        }

        private boolean indexExists(File vcf)
        {
            File idx = new File(vcf.getPath() + ".tbi");
            return idx.exists();
        }

        public Set<String> parseOmim(String input, Logger log)
        {
            if (input == null || input.equals("."))
            {
                return Collections.emptySet();
            }

            Set<String> ret = new LinkedHashSet<>();
            //TODO: consider: tokens = input.split("\\)(,){0,1}(?!_[0-9]+)");
            String[] tokens = input.split("\\)(,){0,1}");
            for (String token : tokens)
            {
                if (StringUtils.isEmpty(token))
                {
                    log.warn("OMIMD was empty: " + input + ", " + StringUtils.join(tokens, ";"));
                    continue;
                }

                String[] split = token.split("(_){0,1}\\([0-9]+$");

                //can occur if the string completely matches the delimiter
                if (split.length == 0)
                {
                    continue;
                }

                String id = "";
                String name = split[0];
                int lastDigits = lastIndexOfRegex(name, "\\d+$");
                if (lastDigits > 0)
                {
                    id = name.substring(lastDigits);
                    name = name.substring(0, lastDigits);
                }

                name = name.replaceAll("\\{", "");
                name = name.replaceAll("\\}", "");
                name = name.replaceAll("_", " ");
                name = StringUtils.trimToEmpty(name);
                name = name.replaceAll("^,", "");
                name = name.replaceAll(",$", "");
                name = name.replaceAll(" +", " ");
                name = name.replaceAll("\\[", "");
                name = name.replaceAll("\\]", "");
                name = name.replaceAll("^\\?", "");
                name = name.replaceAll("\\?$", "");
                name = StringUtils.trimToEmpty(name);

                if (!StringUtils.isEmpty(name) && !".".equals(name) && !StringUtils.isEmpty(id))
                {
                    if (id.length() < 4)
                    {
                        if (!_omimWarnings .contains(input))
                        {
                            log.warn("suspect OMIM parsing: " + input + " / " + name + "<>" + id);
                            _omimWarnings .add(input);
                        }
                    }
                    ret.add(name + "<>" + id);
                }
            }

            return ret;
        }

        private int lastIndexOfRegex(String str, String toFind)
        {
            Pattern pattern = Pattern.compile(toFind);
            Matcher matcher = pattern.matcher(str);

            int lastIndex = -1;

            // Search for the given pattern
            while (matcher.find())
            {
                lastIndex = matcher.start();
            }

            return lastIndex;
        }

        public int updatePhenotypes(String releaseId, Logger log, Container c, User u) throws QueryException
        {
            List<Map<String, Object>> phenotypeRows = new ArrayList<>();
            Set<String> keys = new HashSet<>();

            new TableSelector(mGAPSchema.getInstance().getSchema().getTable(mGAPSchema.TABLE_VARIANT_TABLE), new SimpleFilter(FieldKey.fromString("releaseId"), releaseId), null).forEachResults(rs ->{
                if (rs.getObject("omim_phenotype") == null)
                {
                    return;
                }

                String[] tokens = rs.getString("omim_phenotype").split(";");
                for (String phenotype : tokens)
                {
                    String key = phenotype + "|" + rs.getString(FieldKey.fromString("omim"));
                    if (keys.contains(key))
                    {
                        return;
                    }
                    keys.add(key);

                    String[] parts = phenotype.split("<>");
                    if (parts.length != 2)
                    {
                        log.warn("Malformed phenotype: " + phenotype);
                        continue;
                    }

                    Map<String, Object> map = new CaseInsensitiveHashMap<>();
                    map.put("releaseId", releaseId);
                    map.put("omim_phenotype", parts[0]);
                    map.put("omim_entry", parts[1]);
                    map.put("omim", rs.getString(FieldKey.fromString("omim")));
                    map.put("objectId", new GUID().toString());
                    phenotypeRows.add(map);
                }
            });

            TableInfo phenotype = QueryService.get().getUserSchema(u, c, mGAPSchema.NAME).getTable(mGAPSchema.TABLE_PHENOTYPES);
            BatchValidationException errors = new BatchValidationException();

            try
            {
                //delete existing:
                List<Map<String, Object>> toDelete = new ArrayList<>();
                new TableSelector(phenotype, PageFlowUtil.set("rowId", "container"), new SimpleFilter(FieldKey.fromString("releaseId"), releaseId), null).forEachResults(rs -> {
                    Map<String, Object> map = new CaseInsensitiveHashMap<>();
                    map.put("rowId", rs.getInt("rowId"));
                    map.put("container", rs.getString("container"));

                    toDelete.add(map);
                });

                if (!toDelete.isEmpty())
                {
                    phenotype.getUpdateService().deleteRows(u, c, toDelete, null, new HashMap<>());
                }

                //then add:
                QueryUpdateService qus = phenotype.getUpdateService();
                qus.setBulkLoad(true);
                qus.insertRows(u, c, phenotypeRows, errors, null, new HashMap<>());
                if (errors.hasErrors())
                {
                    throw errors;
                }

                return phenotypeRows.size();
            }
            catch (BatchValidationException | QueryUpdateServiceException | DuplicateKeyException | SQLException | InvalidKeyException e)
            {
                throw new QueryException(e.getMessage(), e);
            }
        }
    }

    public static class TestCase extends Assert
    {
        private final Logger _log = LogManager.getLogger(TestCase.class);

        @Test
        public void testOMIMParse() throws Exception
        {
            //chr01	9610198
            //chr01	65358095
            //chr01	76965588
            //chr04	32436641
            //chr01	5611371
            Set<Pair<String, Set<String>>> toTest = PageFlowUtil.set(
                    Pair.of("Spinal_muscular_atrophy,_distal,_autosomal_recessive,_4,_611067_(3),Charcot-Marie-Tooth_disease,_recessive_intermediate_C,_615376_(3)",PageFlowUtil.set("Spinal muscular atrophy, distal, autosomal recessive, 4<>611067", "Charcot-Marie-Tooth disease, recessive intermediate C<>615376")),
                    Pair.of("Charcot-Marie-Tooth_disease,_type_2A1,_118210_(3),_Pheochromocytoma,171300_(3),_{Neuroblastoma,_susceptibility_to,_1},_256700_(3)", PageFlowUtil.set("Charcot-Marie-Tooth disease, type 2A1<>118210","Pheochromocytoma<>171300","Neuroblastoma, susceptibility to, 1<>256700")),
                    Pair.of("Obesity,_morbid,_due_to_leptin_receptor_deficiency,_614963_(3)", PageFlowUtil.set("Obesity, morbid, due to leptin receptor deficiency<>614963")),
                    Pair.of("?Severe_combined_immunodeficiency_due_to_ADA_deficiency,_102700_(3),Adenosine_deaminase_deficiency,_partial,_102700_(3)", PageFlowUtil.set("Severe combined immunodeficiency due to ADA deficiency<>102700","Adenosine deaminase deficiency, partial<>102700")),
                    Pair.of("{Malaria,_cerebral,_susceptibility_to},_611162_(3),_{Septic_shock,susceptibility_to}_(3),_{Asthma,_susceptibility_to},_600807_(3),_{Dementia,_vascular,_susceptibility_to}_(3),_{Migraine_without_aura,_susceptibility_to},157300_(3)_157300_(3)", PageFlowUtil.set("Malaria, cerebral, susceptibility to<>611162","Asthma, susceptibility to<>600807","Migraine without aura, susceptibility to<>157300"))
                    //TODO: this is a badly formed entry; however, it will not get parsed correctly.  they key thing is that we get the entityId right though
                    //Pair.of("Peroxisome_biogenesis_disorder_6A_(Zellweger),_614870_(3),Peroxisome_biogenesis_disorder_6B,_614871_(3)", PageFlowUtil.set("Peroxisome_biogenesis_disorder_6A_(Zellweger)<>614870", "Peroxisome_biogenesis_disorder_6B<>614871"))
                    //Night_blindness,_congenital_stationary_(complete),_1F,_autosomalrecessive,_615058_(3)
            );

            mGapReleaseGenerator.Processor pr = new mGapReleaseGenerator.Processor();

            for (Pair<String, Set<String>> pair : toTest)
            {
                Set<String> ret = pr.parseOmim(pair.getLeft(), _log);
                Assert.assertEquals(pair.getRight(), ret);

                //NOTE: since this requires an API key and queries OMIM not suitable to general testing, but this can be uncommented for local dev
                //for (String term : ret)
                //{
                //    String updated = pr.updateOmimD(term, ContainerManager.getRoot(), _log);
                //    Assert.assertNotNull(updated);
                //}
            }
        }
    }
}
