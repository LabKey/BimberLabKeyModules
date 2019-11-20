package org.labkey.tcrdb.pipeline;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;
import htsjdk.samtools.fastq.FastqReader;
import htsjdk.samtools.fastq.FastqWriter;
import htsjdk.samtools.fastq.FastqWriterFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.labkey.api.assay.AssayProvider;
import org.labkey.api.assay.AssayService;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.exp.api.ExpProtocol;
import org.labkey.api.exp.api.ExpRun;
import org.labkey.api.exp.api.ExperimentService;
import org.labkey.api.laboratory.LaboratoryService;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.ValidationException;
import org.labkey.api.reader.Readers;
import org.labkey.api.resource.FileResource;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.model.AnalysisModel;
import org.labkey.api.sequenceanalysis.model.ReadData;
import org.labkey.api.sequenceanalysis.model.Readset;
import org.labkey.api.sequenceanalysis.pipeline.AbstractAnalysisStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.AbstractPipelineStep;
import org.labkey.api.sequenceanalysis.pipeline.AnalysisStep;
import org.labkey.api.sequenceanalysis.pipeline.DefaultPipelineStepOutput;
import org.labkey.api.sequenceanalysis.pipeline.PipelineContext;
import org.labkey.api.sequenceanalysis.pipeline.PipelineStepProvider;
import org.labkey.api.sequenceanalysis.pipeline.PreprocessingStep;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.sequenceanalysis.pipeline.SequencePipelineService;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.sequenceanalysis.run.PicardWrapper;
import org.labkey.api.sequenceanalysis.run.SimpleScriptWrapper;
import org.labkey.api.util.FileUtil;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.Pair;
import org.labkey.api.util.Path;
import org.labkey.api.util.StringUtilsLabKey;
import org.labkey.api.view.ViewBackgroundInfo;
import org.labkey.api.view.ViewContext;
import org.labkey.api.writer.PrintWriters;
import org.labkey.tcrdb.TCRdbModule;
import org.labkey.tcrdb.TCRdbSchema;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

import static org.labkey.tcrdb.pipeline.CellRangerVDJWrapper.DELETE_EXISTING_ASSAY_DATA;


/**
 * Created by bimber on 5/10/2016.
 */
public class MiXCRAnalysis extends AbstractPipelineStep implements AnalysisStep
{
    public MiXCRAnalysis(PipelineStepProvider provider, PipelineContext ctx)
    {
        super(provider, ctx);
    }

    private static final String TCR_DBs = "tcrDB";
    private static final String FLAG_MISSENSE = "flagMissense";
    private static final String MIN_CLONE_FRACTION = "minCloneFraction";
    private static final String MIN_CLONE_READS = "minCloneReads";
    private static final String CLONES_FILE = "MiXCR Clones File";
    private static final String FINAL_VDJ_FILE = "MiXCR VDJ Alignment";
    private static final String DIFF_LOCI = "diffLoci";
    private static final String TARGET_ASSAY = "targetAssay";
    private static final String LOCI = "loci";

    public static class Provider extends AbstractAnalysisStepProvider<MiXCRAnalysis>
    {
        public Provider()
        {
            super("MiXCR", "MiXCR", null, "Any reads in the BAM file that are mapped will be fed to MiXCR for TCR sequence analysis.  Results will be imported into the selected assay.  The analysis expects the reads are aligned to some type of TCR sequence DB, which serves as a filter step to enrich for TCR-specific reads.", Arrays.asList(
                    ToolParameterDescriptor.create(TCR_DBs, "TCR DB(s)", "The sequence DB(s), usually species, to be used for alignment.", "tcr-libraryfield", new JSONObject()
                    {{
                        put("allowBlank", false);
                    }}, null),
                    ToolParameterDescriptor.create(MIN_CLONE_FRACTION, "Min Clone Fraction", "Any CDR3 sequences will be reported if the they represent at least this fraction of total reads for that sample.", "ldk-numberfield", new JSONObject()
                    {{
                        put("minValue", 0);
                        put("maxValue", 1);
                    }}, 0.2),
                    ToolParameterDescriptor.create(MIN_CLONE_READS, "Min Reads Per Clone", "Any CDR3 sequences will be reported if the they represent at least this many reads.", "ldk-integerfield", new JSONObject()
                    {{
                        put("minValue", 0);
                    }}, 2),
                    ToolParameterDescriptor.create(DIFF_LOCI, "Allow Different V/J Loci", "If checked, MiXCR will accept alignments with different loci of V and J genes.  Otheriwse these are discarded.", "checkbox", new JSONObject()
                    {{
                        put("checked", true);
                    }}, true),
                    ToolParameterDescriptor.create(TARGET_ASSAY, "Target Assay", "Results will be loaded into this assay.  If no assay is selected, a table will be created with nothing in the DB.", "tcr-assayselectorfield", null, null),
                    ToolParameterDescriptor.create(LOCI, "Loci", "Clones matching the selected loci will be exported.", "tcrdb-locusfield", new JSONObject()
                    {{
                        put("value", "ALL");
                    }}, true),
                    ToolParameterDescriptor.create(DELETE_EXISTING_ASSAY_DATA, "Delete Any Existing Assay Data", "If selected, prior to importing assay data, and existing assay runs in the target container from this readset will be deleted.", "checkbox", new JSONObject(){{
                        put("checked", true);
                    }}, true),
                    ToolParameterDescriptor.create(FLAG_MISSENSE, "Flag Missense CDR3", "If checked, if a sample has duplicate CDR3 clones from the same locus, and and one of these is missense, that clone will be flagged and excluded from many reports.", "checkbox", new JSONObject()
                    {{
                        put("checked", false);
                    }}, true)
                    //TODO
                    //--do-not-extend-alignments
                    //--impute-germline-on-export
            ), Arrays.asList("ux/CheckCombo/CheckCombo.css", "ux/CheckCombo/CheckCombo.js", "tcrdb/field/LibraryField.js", "tcrdb/field/AssaySelectorField.js", "tcrdb/field/LocusField.js"), null);
        }

        @Override
        public MiXCRAnalysis create(PipelineContext ctx)
        {
            return new MiXCRAnalysis(this, ctx);
        }
    }

    @Override
    public Output performAnalysisPerSampleRemote(Readset rs, File inputBam, ReferenceGenome referenceGenome, File outputDir) throws PipelineJobException
    {
        MiXCROutput output = new MiXCROutput();
        output.addInput(inputBam, "Input BAM");

        getPipelineCtx().getLogger().debug("creating FASTQs from BAM: " + inputBam.getName());
        File forwardFq;
        File reverseFq;
        if (rs.getReadData() != null)
        {
            getPipelineCtx().getLogger().debug("using raw readset data instead of BAM");
            if (rs.getReadData().size() == 1)
            {
                ReadData rd = rs.getReadData().get(0);
                forwardFq = rd.getFile1();
                reverseFq = rd.getFile2();
            }
            else
            {
                getPipelineCtx().getLogger().debug("concatenating multiple ReadData together into single FASTQ");

                forwardFq = new File(outputDir, FileUtil.getBaseName(inputBam) + "-R1.fastq.gz");
                output.addIntermediateFile(forwardFq, "FASTQ Data");

                reverseFq = new File(outputDir, FileUtil.getBaseName(inputBam) + "-R2.fastq.gz");
                output.addIntermediateFile(reverseFq, "FASTQ Data");

                FastqWriterFactory fact = new FastqWriterFactory();
                fact.setUseAsyncIo(true);
                try (FastqWriter w1 = fact.newWriter(forwardFq);FastqWriter w2 = fact.newWriter(reverseFq))
                {
                    for (ReadData rd : rs.getReadData())
                    {
                        try (FastqReader reader = new FastqReader(rd.getFile1()))
                        {
                            while (reader.hasNext())
                            {
                                w1.write(reader.next());
                            }
                        }

                        if (rd.getFile2() != null)
                        {
                            try (FastqReader reader = new FastqReader(rd.getFile2()))
                            {
                                while (reader.hasNext())
                                {
                                    w2.write(reader.next());
                                }
                            }
                        }
                    }
                }

                if (!hasLines(reverseFq))
                {
                    getPipelineCtx().getLogger().debug("deleting empty file: " + reverseFq.getPath());
                    reverseFq.delete();
                    reverseFq = null;
                }
            }

            //now trim:
            List<String> trimParams = Arrays.asList("MAXINFO:50:0.9", "MINLEN:50");
            PreprocessingStep.Output trimOutput = SequencePipelineService.get().simpleTrimFastqPair(forwardFq, reverseFq, trimParams, getPipelineCtx().getWorkingDirectory(), getPipelineCtx().getLogger());
            for (File f : trimOutput.getIntermediateFiles())
            {
                output.addIntermediateFile(f);
            }

            forwardFq = trimOutput.getProcessedFastqFiles().first;
            reverseFq = trimOutput.getProcessedFastqFiles().second;
        }
        else
        {
            SimpleScriptWrapper wrapper = new SimpleScriptWrapper(getPipelineCtx().getLogger());

            File bamScript = getScript("external/exportMappedReads.sh");
            forwardFq = new File(outputDir, FileUtil.getBaseName(inputBam) + "-R1.fastq.gz");
            output.addIntermediateFile(forwardFq, "FASTQ Data");
            reverseFq = new File(outputDir, FileUtil.getBaseName(inputBam) + "-R2.fastq.gz");

            wrapper.addToEnvironment("JAVA", SequencePipelineService.get().getJavaFilepath());
            wrapper.addToEnvironment("SAMTOOLS", SequencePipelineService.get().getExeForPackage("SAMTOOLSPATH", "samtools").getPath());
            wrapper.addToEnvironment("PICARD", PicardWrapper.getPicardJar().getPath());
            wrapper.setWorkingDir(outputDir);
            wrapper.execute(Arrays.asList("bash", bamScript.getPath(), inputBam.getPath(), forwardFq.getPath(), reverseFq.getPath()));

            //abort if no reads present
            if (!forwardFq.exists() || !hasLines(forwardFq))
            {
                getPipelineCtx().getLogger().info("no mapped reads found, aborting: " + inputBam.getName());
                if (forwardFq.exists())
                {
                    forwardFq.delete();
                }
                return output;
            }
            else
            {
                getPipelineCtx().getLogger().info("calculating FASTQ metrics:");
                Map<String, Object> metricsMap = SequencePipelineService.get().getQualityMetrics(forwardFq, getPipelineCtx().getJob().getLogger());
                for (String metricName : metricsMap.keySet())
                {
                    getPipelineCtx().getLogger().debug(metricName + ": " + metricsMap.get(metricName));
                }
            }

            //only add if has reads
            if (reverseFq.exists() && hasLines(reverseFq))
            {
                output.addIntermediateFile(reverseFq, "FASTQ Data");
                SequencePipelineService.get().getQualityMetrics(reverseFq, getPipelineCtx().getJob().getLogger());
            }
            else
            {
                if (reverseFq.exists())
                {
                    getPipelineCtx().getLogger().info("deleting empty file: " + reverseFq.getName());
                    reverseFq.delete();
                }
                else
                {
                    getPipelineCtx().getLogger().info("no reverse reads found");
                }

                reverseFq = null;
            }
        }

        //now run mixcr
        String locusString = getProvider().getParameterByName(LOCI).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), String.class);
        if (locusString == null)
        {
            locusString = "ALL";
        }

        String[] loci = locusString.split(";");

        String version = new MiXCRWrapper(getPipelineCtx().getLogger()).getVersionString();

        //iterate selected species/loci:
        Map<Integer, Map<String, Map<String, List<File>>>> tables = new HashMap<>();
        JSONArray libraries = getTcrDbs();

        Map<Integer, Map<String, Map<String, Integer>>> totalReadsInExportedAlignments = new HashMap<>();
        Map<Integer, Map<String, Map<String, Integer>>> totalReadsInExportedClones = new HashMap<>();

        for (JSONObject library : libraries.toJSONObjectArray())
        {
            String species = library.getString("species");
            Integer rowid = library.optInt("rowid");

            totalReadsInExportedAlignments.put(rowid, new HashMap<>());
            totalReadsInExportedAlignments.get(rowid).put(species, new HashMap<>());
            totalReadsInExportedAlignments.get(rowid).get(species).put("All", 0);

            totalReadsInExportedClones.put(rowid, new HashMap<>());
            totalReadsInExportedClones.get(rowid).put(species, new HashMap<>());
            totalReadsInExportedClones.get(rowid).get(species).put("All", 0);

            MiXCRWrapper mixcr = new MiXCRWrapper(getPipelineCtx().getLogger());
            mixcr.setOutputDir(outputDir);
            String javaDir = StringUtils.trimToNull(System.getenv("JAVA_HOME"));
            if (javaDir != null)
            {
                getPipelineCtx().getLogger().debug("setting JAVA_HOME: " + javaDir);
                mixcr.addToEnvironment("JAVA_HOME", javaDir);
            }
            else
            {
                getPipelineCtx().getLogger().debug("JAVA_HOME not set");
            }

            List<String> alignParams = new ArrayList<>();
            List<String> assembleParams = new ArrayList<>();

            String libraryName = StringUtils.trimToNull(library.optString("libraryName"));
            if (libraryName != null)
            {
                alignParams.add("--library " + libraryName);
            }

            if (library.optString("additionalParams") != null)
            {
                // -OvParameters.geneFeatureToAlign=VRegion
                String additional = StringUtils.trimToNull(library.getString("additionalParams"));
                if (additional != null)
                {
                    for (String s : library.getString("additionalParams").split(";"))
                    {
                        alignParams.add(s);
                    }
                }
            }

            if (getProvider().getParameterByName(DIFF_LOCI).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Boolean.class))
            {
                alignParams.add("-OallowChimeras=true");
            }

            Integer threads = SequencePipelineService.get().getMaxThreads(getPipelineCtx().getJob().getLogger());
            if (threads != null)
            {
                alignParams.add("-t " + threads.toString());
                assembleParams.add("-t " + threads.toString());
            }

            List<String> generalParams = new ArrayList<>();

            String prefix = getOutputPrefix(FileUtil.getBaseName(inputBam), String.valueOf(rowid), species);
            File assembledClones = mixcr.doAnalyze(forwardFq, reverseFq, prefix, species, generalParams, alignParams, assembleParams, Collections.emptyList());
            if (!assembledClones.exists())
            {
                throw new PipelineJobException("Unable to find file: " + assembledClones.getPath());
            }

            output.addSequenceOutput(assembledClones, assembledClones.getName(), "MiXCR Clones", rs.getReadsetId(), null, referenceGenome.getGenomeId(), null);

            File alignOutput = mixcr.getAlignFile(prefix, outputDir);
            output.addIntermediateFile(alignOutput, "MiXCR VDJ Alignment");

            File alignPartialOutput1 = mixcr.getAssemblePartialFile(prefix, outputDir, "0");
            output.addIntermediateFile(alignPartialOutput1, "MiXCR VDJ Alignment, Recovery Step 1");

            File alignPartialOutput2 = mixcr.getAssemblePartialFile(prefix, outputDir, "1");
            output.addIntermediateFile(alignPartialOutput2, "MiXCR VDJ Alignment, Recovery Step 2");

            File extendAlignmentsOut = mixcr.getExtendedFile(prefix, outputDir);
            //output.addIntermediateFile(extendAlignmentsOut, "MiXCR VDJ Alignment, Extended");

            File assembleOut = mixcr.getAssembleOut(prefix, outputDir);
            output.addOutput(assembleOut, CLONES_FILE);
            //output.addIntermediateFile(assembleOut, "MiXCR VDJ Assembled Data");

            //File assembleContigsOut = mixcr.getAssembleContigsOut(prefix, outputDir);
            //output.addIntermediateFile(assembleContigsOut, "MiXCR VDJ Assembled");

            //this is our final output in the chain:
            output.addOutput(extendAlignmentsOut, FINAL_VDJ_FILE);

            //write summary
            writeSummary(outputDir, prefix, mixcr, output, rs, totalReadsInExportedAlignments.get(rowid).get(species));

            for (String locus : loci)
            {
                File table = new File(outputDir, prefix + "." + StringUtils.replace(locus , ",", "_") + ".mixcr.txt");

                List<String> exportParams = new ArrayList<>();
                Double minCloneFraction = getProvider().getParameterByName(MIN_CLONE_FRACTION).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Double.class, null);
                Integer minCloneReads = getProvider().getParameterByName(MIN_CLONE_READS).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class, null);

                if (minCloneReads != null)
                {
                    exportParams.add("--minimal-clone-count");
                    exportParams.add(minCloneReads.toString());
                }

                if (minCloneFraction != null)
                {
                    exportParams.add("--minimal-clone-fraction");
                    exportParams.add(minCloneFraction.toString());
                }

                mixcr.doExportClones(assembledClones, table, locus, exportParams);
                if (!tables.containsKey(rowid))
                {
                    tables.put(rowid, new HashMap<>());
                }

                if (!tables.get(rowid).containsKey(species))
                {
                    tables.get(rowid).put(species, new HashMap<>());
                }

                if (!tables.get(rowid).get(species).containsKey(locus))
                {
                    tables.get(rowid).get(species).put(locus, new ArrayList<>());
                }

                tables.get(rowid).get(species).get(locus).add(table);
            }
        }

        File combinedTable = getCombinedTable(outputDir);
        try (PrintWriter writer = PrintWriters.getPrintWriter(combinedTable))
        {
            boolean hasHeader = false;
            for (Integer libraryId : tables.keySet())
            {
                Map<String, Map<String, List<File>>> tablesForSpecies = tables.get(libraryId);
                for (String species : tablesForSpecies.keySet())
                {
                    Map<String, List<File>> tablesForLocus = tablesForSpecies.get(species);
                    for (String locus : tablesForLocus.keySet())
                    {
                        for (File f : tablesForLocus.get(locus))
                        {
                            try (BufferedReader reader = Readers.getReader(f))
                            {
                                String line;
                                int idx = 0;
                                while ((line = reader.readLine()) != null)
                                {
                                    idx++;
                                    if (idx == 1)
                                    {
                                        if (!hasHeader)
                                        {
                                            writer.write("LibraryId\tSpecies\tLocus\tMiXCR_Version\t" + line);
                                            writer.write('\n');
                                            hasHeader = true;
                                        }
                                    }
                                    else
                                    {
                                        //attempt to infer locus based on the gene hits
                                        //this is primarily used to differentiate TRA,TRD hits
                                        String[] fields = line.split("\t");
                                        Set<String> chainsWithoutV = new HashSet<>();
                                        Set<String> allChains = new HashSet<>();

                                        for (String fn : Arrays.asList("vHit", "dHit", "jHit", "cHit"))
                                        {
                                            String val = StringUtils.trimToNull(fields[FIELDS.indexOf(fn) - TOTAL_ADDED_FIELDS]);
                                            if (val == null)
                                            {
                                                continue;
                                            }

                                            for (String chain : Arrays.asList("TRA", "TRB", "TRD", "TRG"))
                                            {
                                                if (val.startsWith(chain))
                                                {
                                                    allChains.add(chain);
                                                    if (!fn.equals("vHit"))
                                                    {
                                                        chainsWithoutV.add(chain);
                                                    }
                                                }
                                            }
                                        }

                                        String inferredLocusWithoutV = StringUtils.join(chainsWithoutV, ",");
                                        String inferredLocusWithV = StringUtils.join(allChains, ",");
                                        if (inferredLocusWithoutV.isEmpty())
                                        {
                                            getPipelineCtx().getLogger().warn("unable to infer locus for row: " + line);
                                        }

                                        String rowLocus = inferredLocusWithoutV.isEmpty() ? inferredLocusWithV : inferredLocusWithoutV;
                                        if (rowLocus.contains(","))
                                        {
                                            getPipelineCtx().getLogger().warn("chimeric locus found: " + line);
                                        }

                                        List<String> chains = Arrays.asList(locus.split(","));
                                        if (!chains.contains(inferredLocusWithoutV) && !"ALL".equals(locus))
                                        {
                                            getPipelineCtx().getLogger().warn("Initial locus does not match the locus inferred by the gene hits.  chains: " + locus + ", inferred without V: " + inferredLocusWithoutV + ", with V: " + inferredLocusWithV);
                                        }

                                        int readCount = ((Double)Double.parseDouble(fields[FIELDS.indexOf("count") - TOTAL_ADDED_FIELDS])).intValue();
                                        if (!totalReadsInExportedClones.get(libraryId).get(species).containsKey(rowLocus))
                                        {
                                            totalReadsInExportedClones.get(libraryId).get(species).put(rowLocus, 0);
                                        }

                                        totalReadsInExportedClones.get(libraryId).get(species).put(rowLocus, totalReadsInExportedClones.get(libraryId).get(species).get(rowLocus) + readCount);
                                        totalReadsInExportedClones.get(libraryId).get(species).put("All", totalReadsInExportedClones.get(libraryId).get(species).get("All") + readCount);

                                        writer.write(String.valueOf(libraryId) + '\t' + species + '\t' + rowLocus + '\t' + version + '\t' + line);
                                        writer.write('\n');
                                    }
                                }
                            }

                            f.delete();
                        }
                    }
                }
            }
            output.addOutput(combinedTable, "MiXCR CDR3 Data");
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        for (Integer libraryId : totalReadsInExportedAlignments.keySet())
        {
            for (String species : totalReadsInExportedAlignments.get(libraryId).keySet())
            {
                File summary = getSummaryFile(getOutputPrefix(FileUtil.getBaseName(inputBam), String.valueOf(libraryId), species), outputDir);
                try (CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(summary), '\t', CSVWriter.NO_QUOTE_CHARACTER))
                {
                    writer.writeNext(new String[]{"LibraryId", "Species", "Locus", "Alignments", "ReadsInExportedClones", "Percent", "TotalAlignments", "TotalReadsInExportedClones"});

                    Set<String> allLoci = new TreeSet<>(totalReadsInExportedAlignments.get(libraryId).get(species).keySet());
                    allLoci.addAll(totalReadsInExportedClones.get(libraryId).get(species).keySet());
                    for (String locus : allLoci)
                    {
                        String fractionString = "";
                        if (totalReadsInExportedAlignments.get(libraryId).get(species).containsKey(locus) && totalReadsInExportedClones.get(libraryId).get(species).containsKey(locus))
                        {
                            fractionString = String.valueOf(100.0 * totalReadsInExportedClones.get(libraryId).get(species).get(locus) / (double)totalReadsInExportedAlignments.get(libraryId).get(species).get(locus));
                        }

                        writer.writeNext(new String[]{
                            String.valueOf(libraryId),
                            species,
                            locus,
                            (totalReadsInExportedAlignments.get(libraryId).get(species).get(locus) == null ? "0" : String.valueOf(totalReadsInExportedAlignments.get(libraryId).get(species).get(locus))),
                            (totalReadsInExportedClones.get(libraryId).get(species).get(locus) == null ? "0" : String.valueOf(totalReadsInExportedClones.get(libraryId).get(species).get(locus))),
                            fractionString,
                            (totalReadsInExportedAlignments.get(libraryId).get(species).get("All") == null ? "0" : String.valueOf(totalReadsInExportedAlignments.get(libraryId).get(species).get("All"))),
                            (totalReadsInExportedClones.get(libraryId).get(species).get("All") == null ? "0" : String.valueOf(totalReadsInExportedClones.get(libraryId).get(species).get("All")))
                        });
                    }
                }
                catch (IOException e)
                {
                    throw new PipelineJobException(e);
                }
            }
        }

        return output;
    }

    private File getSummaryFile(String prefix, File outputDir)
    {
        return new File(outputDir, prefix + ".summary.txt");
    }

    private void writeSummary(File outputDir, String prefix, MiXCRWrapper mixcr, MiXCROutput output, Readset rs, Map<String, Integer> totalReadsInExportedAlignmentsByLocus) throws PipelineJobException
    {
        File alignExport = new File(outputDir, prefix + ".alignments.txt");
        File alignOutput = mixcr.getAlignOutputFile(outputDir, prefix);
        mixcr.doExportAlignments(alignOutput, alignExport, null);
        output.addOutput(alignExport, "MiXCR Alignments");

        try (CSVReader reader = new CSVReader(Readers.getReader(alignExport), '\t'))
        {
            Map<SEGMENTS, Integer> totalBySegment = new HashMap<>();
            Map<SEGMENTS, Set<String>> genesBySegment = new HashMap<>();
            for (SEGMENTS s : SEGMENTS.values())
            {
                genesBySegment.put(s, new HashSet<>());
                totalBySegment.put(s, 0);
            }

            Map<String, Integer> hitMap = new HashMap<>();
            int totalAlignmentsInspected = 0;

            Map<String, List<String[]>> orphans = new HashMap<>();

            String[] line;
            while ((line = reader.readNext()) != null)
            {
                if (line[0].startsWith("bestVGene"))
                {
                    continue;
                }

                totalAlignmentsInspected++;

                Set<SEGMENTS> found = new HashSet<>();
                Set<String> loci = new TreeSet<>();
                for (SEGMENTS s : SEGMENTS.values())
                {
                    String geneHit = StringUtils.trimToNull(line[s.getGeneIdx()]);
                    if (geneHit != null)
                    {
                        found.add(s);
                        genesBySegment.get(s).add(geneHit);
                        totalBySegment.put(s, totalBySegment.get(s) + 1);
                        if (!hitMap.containsKey(geneHit))
                        {
                            hitMap.put(geneHit, 0);
                        }

                        hitMap.put(geneHit, hitMap.get(geneHit) + 1);

                        String locus = geneHit.substring(0, 3);
                        loci.add(locus);
                    }

                    String hit = StringUtils.trimToNull(line[s.getHitIdx()]);
                    if (hit != null)
                    {
                        found.add(s);
                        //genesBySegment.get(s).add(geneHit);
                        if (!hitMap.containsKey(hit))
                        {
                            hitMap.put(hit, 0);
                        }

                        hitMap.put(hit, hitMap.get(hit) + 1);
                    }
                }

                String locus = StringUtils.join(loci, ",");
                if (!totalReadsInExportedAlignmentsByLocus.containsKey(locus))
                {
                    getPipelineCtx().getLogger().debug("adding locus: " + locus);
                    totalReadsInExportedAlignmentsByLocus.put(locus, 0);
                }
                totalReadsInExportedAlignmentsByLocus.put(locus, totalReadsInExportedAlignmentsByLocus.get(locus) + 1);

                inspectForOrphanAlignment(rs, line, found, orphans);
            }

            getPipelineCtx().getLogger().info("Total alignments inspected: " + totalAlignmentsInspected);
            totalReadsInExportedAlignmentsByLocus.put("All", totalAlignmentsInspected);
            getPipelineCtx().getLogger().info("Alignments by segment:");
            for (SEGMENTS s : totalBySegment.keySet())
            {
                getPipelineCtx().getLogger().info(s.name() + ": " + totalBySegment.get(s) + ", " + genesBySegment.get(s).size() + " genes (" + (100.0 * totalBySegment.get(s) / (double)totalAlignmentsInspected) + "%)");
            }

            getPipelineCtx().getLogger().info("Total genes by segment:");
            for (SEGMENTS s : genesBySegment.keySet())
            {
                Set<String> genes = genesBySegment.get(s);
                getPipelineCtx().getLogger().info(s.name() + ": " + genes.size());

                List<Map.Entry<String, Integer>> entries = new ArrayList<>();
                genes.forEach(x -> entries.add(new AbstractMap.SimpleEntry<>(x, hitMap.get(x))));
                Collections.sort(entries, (Map.Entry<String, Integer> p1, Map.Entry<String, Integer> p2) -> p2.getValue().compareTo(p1.getValue()));
                for (Map.Entry<String, Integer> g : entries)
                {
                    getPipelineCtx().getLogger().info(g.getKey() + ": " + g.getValue() + " (" + (100.0 * g.getValue() / (double)totalAlignmentsInspected) + "%)");
                }
            }

            //write separate list of C-Gene counts, primarily to make it easier to append to RNA-Seq gene count tables
            File constantCounts = new File(outputDir, prefix + ".cAlignments.txt");
            try (CSVWriter writer = new CSVWriter(PrintWriters.getPrintWriter(constantCounts), '\t', CSVWriter.NO_QUOTE_CHARACTER))
            {
                writer.writeNext(new String[]{"Readset", "ReadsetId", "Gene", "Alignments", "TotalAlignments"});
                Set<String> cGenes = genesBySegment.get(SEGMENTS.C);
                for (String gene : cGenes)
                {
                    writer.writeNext(new String[]{rs.getName(), String.valueOf(rs.getReadsetId()), gene, String.valueOf(hitMap.get(gene)), String.valueOf(totalAlignmentsInspected)});
                }
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }

        output.addIntermediateFile(alignExport);
    }

    private File getCombinedTable(File outputDir)
    {
        return new File(outputDir, "mixcr.txt");
    }

    private final int TOTAL_ADDED_FIELDS = 4;
    private final int TOTAL_EXPORTED_FIELDS_NOT_IN_DB = 0;
    private List<String> FIELDS = Arrays.asList(
            "libraryId",
            "species",
            "locus",
            "mixcrVersion",
            "cloneId",
            "vHit",
            "dHit",
            "jHit",
            "cHit",
            "CDR3",
            "length",
            "count",
            "fraction",
            "vHits",
            "dHits",
            "jHits",
            "cHits",
            "vGene",
            "vGenes",
            "dGene",
            "dGenes",
            "jGene",
            "jGenes",
            "cGene",
            "cGenes",
            "cdr3_nt",
            "cdr3_qual",
            "sequence"
    );

    private class RunData
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> mixcrVersions = new HashSet<>();
        List<String> runComments = new ArrayList<>();
        String prefix;
        int libraryId;
        String species;

        Integer totalAlignments = null;
        Integer totalReadsInClones = null;
        Integer TRAAlignments = null;
        Integer TRBAlignments = null;
        Integer TRDAlignments = null;
        Integer TRGAlignments = null;

        Double TRAClonePercent = null;
        Double TRBClonePercent = null;
        Double TRDClonePercent = null;
        Double TRGClonePercent = null;

        public RunData(String prefix, int libraryId, String species)
        {
            this.prefix = prefix;
            this.libraryId = libraryId;
            this.species = species;
        }
    }

    @Override
    public Output performAnalysisPerSampleLocal(AnalysisModel model, File inputBam, File referenceFasta, File outDir) throws PipelineJobException
    {
        getPipelineCtx().getLogger().debug("output directory: " + outDir.getPath());

        Map<String, RunData> runMap = new HashMap<>();

        File table = getCombinedTable(outDir);
        if (!table.exists() || !SequencePipelineService.get().hasMinLineCount(table, 2))
        {
            getPipelineCtx().getLogger().warn("no clones exported: " + table.getPath());
            JSONArray libraries = getTcrDbs();
            for (JSONObject library : libraries.toJSONObjectArray())
            {
                String species = library.getString("species");
                Integer rowid = library.optInt("rowid");
                String prefix = getOutputPrefix(FileUtil.getBaseName(inputBam), rowid.toString(), species);
                RunData rd = new RunData(prefix, rowid, species);
                rd.runComments.add("No clones");
                runMap.put(prefix, rd);
            }
        }
        else
        {
            getPipelineCtx().getLogger().info("importing results from: " + table.getPath());
            parseCloneOutput(runMap, table, model, inputBam);
        }

        for (String key : runMap.keySet())
        {
            RunData rd = runMap.get(key);

            inspectAndRemoveDuplicateClones(rd);
            reportConflictingLocusConstantRegion(rd);
            possiblyFlagMissense(rd);
            addSummaryStats(rd, outDir);
            possiblyAddOrphans(rd, outDir);
        }

        Integer assayId = getProvider().getParameterByName(TARGET_ASSAY).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Integer.class);
        if (assayId == null)
        {
            getPipelineCtx().getLogger().info("No assay selected, will not import");
            return null;
        }

        ExpProtocol protocol = ExperimentService.get().getExpProtocol(assayId);
        if (protocol == null)
        {
            throw new PipelineJobException("Unable to find protocol: " + assayId);
        }

        ViewBackgroundInfo info = getPipelineCtx().getJob().getInfo();
        ViewContext vc = ViewContext.getMockViewContext(info.getUser(), info.getContainer(), info.getURL(), false);

        try
        {
            for (String key : runMap.keySet())
            {
                getPipelineCtx().getLogger().info("importing run: "+ key);
                RunData rd = runMap.get(key);
                importRun(rd, outDir, model, protocol, vc);
            }
        }
        catch (ValidationException e)
        {
            throw new PipelineJobException(e);
        }

        return null;
    }

    private void reportConflictingLocusConstantRegion(RunData rd) throws PipelineJobException
    {
        for (Map<String, Object> row : rd.rows)
        {
            String locus = row.get("locus") == null ? null : StringUtils.trimToNull(row.get("locus").toString());
            String cHit = row.get("cHit") == null ? null : StringUtils.trimToNull(row.get("cHit").toString());
            if (locus != null && cHit != null && !locus.contains(","))
            {
                if (!cHit.startsWith(locus))
                {
                    getPipelineCtx().getLogger().error("Locus does not match CHit: " + cHit + "/" + locus);
                    rd.runComments.add("Locus does not match CHit: " + cHit);
                }
            }
        }
    }

    private void inspectAndRemoveDuplicateClones(RunData rd) throws PipelineJobException
    {
        //this can occur if we export separately for each locus, and that clone uses a TRA/D gene
        getPipelineCtx().getLogger().info("inspecting for redundant clones");
        Map<String, List<Map<String, Object>>> resultsByCloneId = new HashMap<>();
        Map<String, Set<String>> lociByCloneId = new HashMap<>();
        Map<String, Set<String>> cHitByCloneId = new HashMap<>();
        Map<String, Set<String>> jHitByCloneId = new HashMap<>();

        for (Map<String, Object> row : rd.rows)
        {
            String cloneId = String.valueOf(row.get("cloneId"));
            if (StringUtils.trimToNull(cloneId) == null)
            {
                throw new PipelineJobException("No clone Id");
            }

            if (!resultsByCloneId.containsKey(cloneId))
            {
                resultsByCloneId.put(cloneId, new ArrayList<>());
            }

            resultsByCloneId.get(cloneId).add(row);

            if (!lociByCloneId.containsKey(cloneId))
            {
                lociByCloneId.put(cloneId, new HashSet<>());
            }
            String locus = row.get("locus") == null ? null : StringUtils.trimToNull(row.get("locus").toString());
            if (locus != null)
            {
                lociByCloneId.get(cloneId).add(locus);
            }

            if (!cHitByCloneId.containsKey(cloneId))
            {
                cHitByCloneId.put(cloneId, new HashSet<>());
            }
            String cHit = row.get("cHit") == null ? null : StringUtils.trimToNull(row.get("cHit").toString());
            if (cHit != null)
            {
                cHitByCloneId.get(cloneId).add(cHit);
            }

            if (!jHitByCloneId.containsKey(cloneId))
            {
                jHitByCloneId.put(cloneId, new HashSet<>());
            }
            String jHit = row.get("jHit") == null ? null : StringUtils.trimToNull(row.get("jHit").toString());
            if (jHit != null)
            {
                jHitByCloneId.get(cloneId).add(jHit);
            }
        }

        List<Map<String, Object>> newRows = new ArrayList<>();
        int rowsSkipped = 0;
        for (String cloneId : resultsByCloneId.keySet())
        {
            List<Map<String, Object>> cloneRows = resultsByCloneId.get(cloneId);
            if (cloneRows.size() == 1)
            {
                newRows.addAll(cloneRows);
            }
            else
            {
                String loci = StringUtils.join(lociByCloneId.get(cloneId), ",");
                Set<String> cHitGenes = cHitByCloneId.get(cloneId);
                Set<String> jHitGenes = jHitByCloneId.get(cloneId);
                getPipelineCtx().getLogger().info("inspecting multi-exported (" + cloneRows.size() + ") clone: " + cloneId + ".  ");

                if (cHitGenes.size() == 1)
                {
                    String cLocus = cHitGenes.iterator().next().substring(0, 3);
                    getPipelineCtx().getLogger().info("single CHit found, will use this locus: " + cLocus);
                    rowsSkipped += addRowsOfLocus(cloneRows, cLocus, newRows, cloneId);
                }
                else if (jHitGenes.size() == 1)
                {
                    getPipelineCtx().getLogger().info("no CHits found, using JRegion");
                    String jLocus = jHitGenes.iterator().next().substring(0, 3);
                    rowsSkipped += addRowsOfLocus(cloneRows, jLocus, newRows, cloneId);
                }
                else
                {
                    String msg = "Unable to infer clone locus, importing the first instance with loci: " + loci;
                    getPipelineCtx().getLogger().error(msg);
                    rd.runComments.add("Warning: " + msg);

                    Map<String, Object> row = cloneRows.get(0);
                    row.put("locus", loci);
                    newRows.add(row);
                }
            }
        }

        //if (rowsSkipped > 0)
        //{
        //    rd.runComments.add("Exported clones skipped due to duplication: " + rowsSkipped);
        //}

        rd.rows = newRows;
    }

    private int addRowsOfLocus(List<Map<String, Object>> cloneRows, String locus, List<Map<String, Object>> newRows, String cloneId)
    {
        boolean found = false;
        for (Map<String, Object> row : cloneRows)
        {
            String rowLocus = row.get("locus") == null ? null : StringUtils.trimToNull(row.get("locus").toString());
            Set<String> rowLoci = new HashSet<>();
            if (rowLocus != null)
            {
                String[] tokens = rowLocus.split(",");
                for (String t : tokens)
                {
                    if (t.length() != 3)
                    {
                        getPipelineCtx().getLogger().warn("long locus name found: " + t + ", " + rowLocus);
                    }
                    rowLoci.add(t.substring(0, 3));
                }
            }

            if (rowLoci.contains(locus))
            {
                newRows.add(row);
                found = true;
                break;
            }
        }

        if (!found)
        {
            getPipelineCtx().getLogger().error("row not found for locus: " + locus + " for clone: " + cloneId);
        }

        return cloneRows.size()-1;
    }

    private File getScript(String path) throws PipelineJobException
    {
        Module module = ModuleLoader.getInstance().getModule(TCRdbModule.class);
        FileResource resource = (FileResource)module.getModuleResolver().lookup(Path.parse(path));
        if (resource == null)
            throw new PipelineJobException("Not found: " + path);

        File file = resource.getFile();
        if (!file.exists())
            throw new PipelineJobException("Not found: " + file.getPath());

        return file;
    }

    private boolean hasLines(File f) throws PipelineJobException
    {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(f.getName().endsWith(".gz") ? new GZIPInputStream(new FileInputStream(f)) : new FileInputStream(f), StringUtilsLabKey.DEFAULT_CHARSET));)
        {
            while (reader.readLine() != null)
            {
                return true;
            }

            return false;
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }

    private enum SEGMENTS
    {
        V(0),
        D(1),
        J(2),
        C(3);

        private int _idx;

        SEGMENTS(int idx)
        {
            _idx = idx;
        }

        public int getGeneIdx()
        {
            return _idx;
        }

        public int getHitIdx()
        {
            return _idx + 4;
        }

        public int getHitScoreIdx()
        {
            return _idx + 8;
        }
    }

    private static class MiXCROutput extends DefaultPipelineStepOutput implements AnalysisStep.Output
    {

    }

    private String getOutputPrefix(String basename, String libraryId, String species)
    {
        return basename + "." + libraryId + "." + species;
    }

    private final int SEQUENCE_IDX = 13;
    private final int VEND_IDX = 14;
    private final int JBEGIN_IDX = 15;
    private final int CSTART_IDX = 16;
    private final int READ1_IDX = 17;
    private final int READ2_IDX = 18;
    private final int J_UPSTREAM_IDX = 19;
    private final int V_DOWNSTREAM_IDX = 20;

    private void inspectForOrphanAlignment(Readset rs, String[] line, Set<SEGMENTS> found, Map<String, List<String[]>> orphans)
    {
        //aligned to V, lacking J
        if (found.contains(SEGMENTS.V) && !found.contains(SEGMENTS.J))
        {
            String[] sequences = line[SEQUENCE_IDX].split(",");
            String[] cdr3Begins = line[VEND_IDX].split(",");
            String[] constantBegins = line[CSTART_IDX].split(",");
            String vHit = line[SEGMENTS.V.getHitIdx()];

            if (sequences.length == 1)
            {
                int cdr3Begin = Integer.parseInt(cdr3Begins[0]);
                if (cdr3Begin != -1)
                {
                    int remaining = sequences[0].length() - cdr3Begin;
                    if (remaining > 70)
                    {
                        String seq = sequences[0].substring(0, cdr3Begin).toLowerCase() + "-" + sequences[0].substring(cdr3Begin);
                        int constantBegin = Integer.parseInt(constantBegins[0]);
                        if (constantBegin > -1)
                        {
                            seq = seq.substring(0, constantBegin) + "-" + seq.substring(constantBegin).toLowerCase();
                        }

                        //store by vHit
                        if (!orphans.containsKey(vHit))
                        {
                            orphans.put(vHit, new ArrayList<>());
                        }
                        orphans.get(vHit).add(new String[]{rs.getName(), String.valueOf(rs.getRowId()), "J", vHit, line[READ1_IDX], "R0", seq});
                    }
                }
            }
            else
            {
                boolean doAdd = false;
                List<String> seqs = new ArrayList<>();

                int i = 0;
                for (String c : cdr3Begins)
                {
                    String seq = sequences[i];
                    int constantBegin = Integer.parseInt(constantBegins[i]);
                    if (constantBegin > -1)
                    {
                        seq = seq.substring(0, constantBegin) + "-" + seq.substring(constantBegin).toLowerCase();
                    }

                    int cdr3Begin = Integer.parseInt(c);
                    if (cdr3Begin != -1)
                    {
                        int remaining = sequences[i].length() - cdr3Begin;
                        if (remaining > 70)
                        {
                            seq = sequences[i].substring(0, cdr3Begin).toLowerCase() + "-" + sequences[i].substring(cdr3Begin);
                            doAdd = true;
                        }
                    }

                    seqs.add(seq);

                    i++;
                }

                if (doAdd)
                {
                    if (!orphans.containsKey(vHit))
                    {
                        orphans.put(vHit, new ArrayList<>());
                    }
                    orphans.get(vHit).add(new String[]{rs.getName(), String.valueOf(rs.getRowId()), "J", vHit, line[READ1_IDX], "R1", seqs.get(0), line[READ2_IDX], "R2", seqs.get(1)});
                }
            }
        }

        //aligned to J, lacking V
        if (!found.contains(SEGMENTS.V) && found.contains(SEGMENTS.J))
        {
            String jHit = line[SEGMENTS.J.getHitIdx()];

            String[] sequences = line[SEQUENCE_IDX].split(",");
            String[] cdr3Ends = line[JBEGIN_IDX].split(",");
            String[] constantBegins = line[CSTART_IDX].split(",");
            if (sequences.length == 1)
            {
                int cdr3End = Integer.parseInt(cdr3Ends[0]);
                if (cdr3End > 100)
                {
                    String seq = sequences[0].substring(0, cdr3End) + "-" + sequences[0].substring(cdr3End).toLowerCase();
                    if (!orphans.containsKey(jHit))
                    {
                        orphans.put(jHit, new ArrayList<>());
                    }
                    orphans.get(jHit).add(new String[]{rs.getName(), String.valueOf(rs.getRowId()), "V", jHit, line[READ1_IDX], "R0", seq});
                }
            }
            else
            {
                boolean doAdd = false;
                List<String> seqs = new ArrayList<>();

                int i = 0;
                for (String c : cdr3Ends)
                {
                    String seq = sequences[i];
                    int constantBegin = Integer.parseInt(constantBegins[i]);
                    if (constantBegin > -1)
                    {
                        seq = seq.substring(0, constantBegin) + "-" + seq.substring(constantBegin).toLowerCase();
                    }

                    int cdr3End = Integer.parseInt(c);
                    if (cdr3End > 70)
                    {
                        seq = sequences[i].substring(0, cdr3End) + "-" + sequences[i].substring(cdr3End).toLowerCase();
                        doAdd = true;
                    }

                    seqs.add(seq);

                    i++;
                }

                if (doAdd)
                {
                    if (!orphans.containsKey(jHit))
                    {
                        orphans.put(jHit, new ArrayList<>());
                    }
                    orphans.get(jHit).add(new String[]{rs.getName(), String.valueOf(rs.getRowId()), "V", jHit, line[READ1_IDX], "R1", seqs.get(0), line[READ2_IDX], "R2", seqs.get(1)});
                }
            }
        }
    }

    private void parseCloneOutput(Map<String, RunData> runMap, File table, AnalysisModel model, File inputBam) throws PipelineJobException
    {
        Integer runId = SequencePipelineService.get().getExpRunIdForJob(getPipelineCtx().getJob());
        ExpRun run = ExperimentService.get().getExpRun(runId);

        List<? extends ExpData> cloneDatas = run.getInputDatas(CLONES_FILE, ExpProtocol.ApplicationType.ExperimentRunOutput);
        List<? extends ExpData> vdjDatas = run.getInputDatas(FINAL_VDJ_FILE, ExpProtocol.ApplicationType.ExperimentRunOutput);

        try (CSVReader reader = new CSVReader(Readers.getReader(table), '\t'))
        {
            int lineNo = 0;
            String[] line;
            while ((line = reader.readNext()) != null)
            {
                lineNo++;
                if (lineNo == 1)
                {
                    continue;
                }

                Map<String, Object> row = getBaseRow(model, runId);

                if (line.length != (FIELDS.size() + TOTAL_EXPORTED_FIELDS_NOT_IN_DB))  //this includes one additional field appended to the end
                {
                    getPipelineCtx().getLogger().warn(lineNo + ": line length not " + (FIELDS.size() + TOTAL_EXPORTED_FIELDS_NOT_IN_DB) + ".  was: " + line.length);
                    getPipelineCtx().getLogger().warn(StringUtils.join(line, ","));
                }

                for (int i = 0; i < FIELDS.size(); i++)
                {
                    if (FIELDS.get(i).contains("Mismatches") && "-".equals(line[i]))
                    {
                        row.put(FIELDS.get(i), null);
                    }
                    else
                    {
                        row.put(FIELDS.get(i), StringUtils.trimToNull(line[i]));
                    }
                }

                String key = line[FIELDS.indexOf("libraryId")] + "-" + line[FIELDS.indexOf("species")];
                if (!runMap.containsKey(key))
                {
                    runMap.put(key, new RunData(getOutputPrefix(FileUtil.getBaseName(inputBam), line[FIELDS.indexOf("libraryId")], line[FIELDS.indexOf("species")]), Integer.parseInt(line[FIELDS.indexOf("libraryId")]), line[FIELDS.indexOf("species")]));
                }
                RunData rd = runMap.get(key);

                if (row.get("mixcrVersion") != null)
                {
                    rd.mixcrVersions.add(row.get("mixcrVersion").toString());
                }

                String cloneFileName = MiXCRWrapper.getAssembledFileName(rd.prefix);
                ExpData clonesFile = null;
                for (ExpData d : cloneDatas)
                {
                    if (cloneFileName.equals(d.getFile().getName()))
                    {
                        clonesFile = d;
                        break;
                    }
                }

                if (clonesFile == null)
                {
                    getPipelineCtx().getLogger().error("unable to find clones file, expected: " + cloneFileName);
                }
                else
                {
                    row.put("clonesFile", clonesFile.getRowId());
                }

                String vdjFileName = MiXCRWrapper.getFinalVDJFileName(rd.prefix);
                ExpData vdjFile = null;
                for (ExpData d : vdjDatas)
                {
                    if (vdjFileName.equals(d.getFile().getName()))
                    {
                        vdjFile = d;
                        break;
                    }
                }

                if (vdjFile == null)
                {
                    getPipelineCtx().getLogger().warn("unable to find VDJ file, expected: " + vdjFileName);
                }
                else
                {
                    row.put("vdjFile", vdjFile.getRowId());
                }

                if (row.get("CDR3") != null && row.get("CDR3").toString().contains("_") || row.get("CDR3").toString().contains("*"))
                {
                    String msg = "Frameshift or stop codon";
                    getPipelineCtx().getLogger().warn(msg);
                    row.put("comment", msg);
                }

                rd.rows.add(row);
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }

    private Map<String, Object> getBaseRow(AnalysisModel model, Integer runId) throws PipelineJobException
    {
        Map<String, Object> row = new CaseInsensitiveHashMap<>();
        if (model.getReadset() != null)
        {
            Readset rs = SequenceAnalysisService.get().getReadset(model.getReadset(), getPipelineCtx().getJob().getUser());
            if (rs != null)
            {
                row.put("sampleName", rs.getName());
                row.put("subjectid", rs.getSubjectId());
                row.put("date", rs.getSampleDate());
            }
            else
            {
                throw new PipelineJobException("Unable to find readset: " + model.getReadset());
            }
        }
        else
        {
            row.put("sampleName", "Analysis Id: " + model.getRowId());
        }

        row.putIfAbsent("date", new Date());
        //round to day
        row.put("date", DateUtils.truncate(row.get("date"), Calendar.DATE));

        row.put("sampleType", null);
        row.put("category", null);

        row.put("alignmentId", model.getAlignmentFile());
        row.put("analysisId", model.getRowId());

        //attempt to locate cDNA:
        TableInfo cDNATable = TCRdbSchema.getInstance().getSchema().getTable(TCRdbSchema.TABLE_CDNAS);
        if (model.getReadset() != null)
        {
            SimpleFilter filter = new SimpleFilter();
            filter.addClause(new SimpleFilter.OrClause(
                new CompareType.CompareClause(FieldKey.fromString("readsetId"), CompareType.EQUAL, model.getReadset()),
                new CompareType.CompareClause(FieldKey.fromString("enrichedReadsetId"), CompareType.EQUAL, model.getReadset())
            ));

            TableSelector ts1 = new TableSelector(cDNATable, PageFlowUtil.set("rowId"), filter, null);
            if (ts1.exists())
            {
                List<Integer> rowIds = ts1.getArrayList(Integer.class);
                if (rowIds.size() == 1)
                {
                    row.put("cDNA", rowIds.get(0));
                }
                else
                {
                    getPipelineCtx().getLogger().warn("Expected single cDNA for: " + model.getReadset() + ", found: " + rowIds.size());
                }
            }
        }

        if (!row.containsKey("cDNA") || row.get("cDNA") == null)
        {
            getPipelineCtx().getLogger().warn("Unable to find cDNA for: " + model.getReadset());
        }

        row.put("pipelineRunId", runId);

        return row;
    }

    private void possiblyFlagMissense(RunData rd)
    {
        if (getProvider().getParameterByName(FLAG_MISSENSE).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Boolean.class, false))
        {
            getPipelineCtx().getLogger().debug("will flag the frameshifted CDR3s from any library/locus that also has a non-frameshift CDR3");
            Map<String, Pair<List<Map<String, Object>>, List<Map<String, Object>>>> resultsByLocus = new HashMap<>();
            for (Map<String, Object> row : rd.rows)
            {
                String key = row.get("libraryId") + "-" + row.get("locus");
                if (!resultsByLocus.containsKey(key))
                {
                    resultsByLocus.put(key, Pair.of(new ArrayList<>(), new ArrayList<>()));
                }

                boolean isFrameshift = String.valueOf(row.get("CDR3")).contains("_") || String.valueOf(row.get("CDR3")).contains("*");
                if (isFrameshift)
                {
                    resultsByLocus.get(key).first.add(row);
                }
                else
                {
                    resultsByLocus.get(key).second.add(row);
                }
            }

            List<Map<String, Object>> newRows = new ArrayList<>();
            for (String key : resultsByLocus.keySet())
            {
                //if we have any non-frameshift from this library/locus, flag all frameshifted CDR3s
                if (!resultsByLocus.get(key).second.isEmpty() && !resultsByLocus.get(key).first.isEmpty())
                {
                    getPipelineCtx().getLogger().debug("disabling " + resultsByLocus.get(key).first.size() + " frameshifted CDR3s: " + key);
                    for (Map<String, Object> row : resultsByLocus.get(key).first)
                    {
                        row.put("disabled", true);
                        String comment = "Sample has non-frameshift CDR3 for locus";
                        row.put("comment", row.get("comment") != null ? row.get("comment") + "; " + comment : comment);
                    }
                }

                newRows.addAll(resultsByLocus.get(key).first);
                newRows.addAll(resultsByLocus.get(key).second);
            }

            rd.rows = newRows;
        }
    }

    private void possiblyAddOrphans(RunData rd, File outDir) throws PipelineJobException
    {
        //look for orphan alignment file:
        File possibleNovels = new File(outDir, rd.prefix + ".orphanAlignments.txt");
        if (possibleNovels.exists())
        {
            int potentialNovel = 0;
            int potentialNovelWithGenomic = 0;

            try (CSVReader reader = new CSVReader(Readers.getReader(possibleNovels), '\t'))
            {
                String[] line;
                while ((line = reader.readNext()) != null)
                {
                    if (line[0].startsWith("Readset"))
                    {
                        continue;
                    }
                    else if (line[2].equals("V") && line[9].equals("true"))
                    {
                        potentialNovelWithGenomic++;
                        continue;
                    }
                    else if (line[2].equals("J") && line[8].equals("true"))
                    {
                        potentialNovelWithGenomic++;
                        continue;
                    }

                    potentialNovel++;
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }

            getPipelineCtx().getLogger().info("Potential Novels: " + potentialNovel);
            getPipelineCtx().getLogger().info("Potential Novels Discarded For Flanking Genomic Sequence: " + potentialNovelWithGenomic);

            if (potentialNovel > 1)
            {
                rd.runComments.add("Potential Novels: " + potentialNovel + (rd.totalAlignments > 0 ? (" (" + (100.0*(potentialNovel)/rd.totalAlignments) + "%)") : ""));
            }
        }
    }

    private void addSummaryStats(RunData rd, File outDir) throws PipelineJobException
    {
        //also bring in run stats:
        File summary = getSummaryFile(rd.prefix, outDir);
        if (!summary.exists())
        {
            return;
        }

        getPipelineCtx().getLogger().info("processing file: " + summary.getPath());
        try (CSVReader reader = new CSVReader(Readers.getReader(summary), '\t'))
        {
            String[] line;
            int lineIdx = 0;
            while ((line = reader.readNext()) != null)
            {
                lineIdx++;
                if (lineIdx == 1)
                {
                    continue;
                }

                String locus = line[2];
                Integer alignments = Integer.parseInt(line[3]);
                Integer readsInExportedClones = StringUtils.isEmpty(line[4]) ? 0 : Integer.parseInt(line[4]);

                Integer totalAlignments = StringUtils.isEmpty(line[6]) ? 0 : Integer.parseInt(line[6]);
                Integer totalReadsInClones = StringUtils.isEmpty(line[7]) ? 0 : Integer.parseInt(line[7]);
                Double fractionOfTotalClones = totalReadsInClones == 0 ? 0.0 : readsInExportedClones / (double)totalReadsInClones;

                rd.totalAlignments = totalAlignments;
                rd.totalReadsInClones = totalReadsInClones;

                switch (locus)
                {
                    case "TRA":
                        rd.TRAAlignments = alignments;
                        rd.TRAClonePercent = fractionOfTotalClones;
                        break;
                    case "TRB":
                        rd.TRBAlignments = alignments;
                        rd.TRBClonePercent = fractionOfTotalClones;
                        break;
                    case "TRD":
                        rd.TRDAlignments = alignments;
                        rd.TRDClonePercent = fractionOfTotalClones;
                        break;
                    case "TRG":
                        rd.TRGAlignments = alignments;
                        rd.TRGClonePercent = fractionOfTotalClones;
                        break;
                    case "All":
                        break;
                    //default:
                    //    rd.runComments.add("Chimeric Locus: " + locus);
                }
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }

    private void importRun(RunData rd, File outDir, AnalysisModel model, ExpProtocol protocol, ViewContext vc) throws ValidationException, PipelineJobException
    {
        JSONObject runProps = new JSONObject();
        runProps.put("performedby", getPipelineCtx().getJob().getUser().getDisplayName(getPipelineCtx().getJob().getUser()));
        runProps.put("assayName", (rd.mixcrVersions.isEmpty() ? "MiXCR" : rd.mixcrVersions.iterator().next()));
        runProps.put("Name", "Analysis: " + model.getAnalysisId());
        runProps.put("analysisId", model.getAnalysisId());

        Integer runId = SequencePipelineService.get().getExpRunIdForJob(getPipelineCtx().getJob());
        runProps.put("pipelineRunId", runId);

        runProps.put("totalAlignments", rd.totalAlignments);
        runProps.put("totalReadsInClones", rd.totalReadsInClones);
        runProps.put("TRAAlignments", rd.TRAAlignments);
        runProps.put("TRBAlignments", rd.TRBAlignments);
        runProps.put("TRDAlignments", rd.TRDAlignments);
        runProps.put("TRGAlignments", rd.TRGAlignments);

        runProps.put("TRAClonePercent", rd.TRAClonePercent);
        runProps.put("TRBClonePercent", rd.TRBClonePercent);
        runProps.put("TRDClonePercent", rd.TRDClonePercent);
        runProps.put("TRGClonePercent", rd.TRGClonePercent);

        if (!rd.runComments.isEmpty())
        {
            runProps.put("runComments", StringUtils.join(new LinkedHashSet<>(rd.runComments), ", "));
        }

        JSONObject json = new JSONObject();
        json.put("Run", runProps);

        File assayTmp = new File(outDir, FileUtil.makeLegalName("mixcr-assay-upload_" + FileUtil.getTimestamp() + ".txt"));
        if (assayTmp.exists())
        {
            assayTmp.delete();
        }

        getPipelineCtx().getLogger().info("total rows imported: " + rd.rows.size());
        if (rd.rows.isEmpty())
        {
            //NOTE: we need to add a placeholder row since assay import will die w/ a run-only import:
            Map<String, Object> row = getBaseRow(model, runId);
            row.put("species", rd.species);
            row.put("libraryId", rd.libraryId);
            row.put("locus", "None");
            rd.rows.add(row);
        }

        getPipelineCtx().getLogger().debug("saving assay file to: " + assayTmp.getPath());

        AssayProvider ap = AssayService.get().getProvider(protocol);
        boolean deleteExistingAssayData = getProvider().getParameterByName(DELETE_EXISTING_ASSAY_DATA).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), Boolean.class, false);
        if (deleteExistingAssayData)
        {
            if (model.getReadset() == null)
            {
                getPipelineCtx().getLogger().info("No readset found for this sample, cannot delete existing runs");
            }
            else
            {
                CellRangerVDJUtils.deleteExistingData(ap, protocol, getPipelineCtx().getJob().getContainer(), getPipelineCtx().getJob().getUser(), getPipelineCtx().getLogger(), model.getReadset());
            }
        }
        LaboratoryService.get().saveAssayBatch(rd.rows, json, assayTmp, vc, ap, protocol);
    }

    private JSONArray getTcrDbs() throws PipelineJobException
    {
        String tcrDBJSON = getProvider().getParameterByName(TCR_DBs).extractValue(getPipelineCtx().getJob(), getProvider(), getStepIdx(), String.class);
        if (tcrDBJSON == null)
        {
            throw new PipelineJobException("No TCR DBs selected");
        }

        return new JSONArray(tcrDBJSON);
    }
}
