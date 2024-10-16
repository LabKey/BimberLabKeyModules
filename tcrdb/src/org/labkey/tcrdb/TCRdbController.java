/*
 * Copyright (c) 2015 LabKey Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.labkey.tcrdb;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.labkey.api.action.ConfirmAction;
import org.labkey.api.action.ExportAction;
import org.labkey.api.action.SimpleViewAction;
import org.labkey.api.action.SpringActionController;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Container;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.exp.api.ExperimentService;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryAction;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.UserSchema;
import org.labkey.api.reader.Readers;
import org.labkey.api.security.IgnoresTermsOfUse;
import org.labkey.api.security.RequiresPermission;
import org.labkey.api.security.permissions.AdminPermission;
import org.labkey.api.security.permissions.ReadPermission;
import org.labkey.api.sequenceanalysis.RefNtSequenceModel;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.util.FileType;
import org.labkey.api.util.FileUtil;
import org.labkey.api.util.HtmlString;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.StringUtilsLabKey;
import org.labkey.api.util.URLHelper;
import org.labkey.api.view.HtmlView;
import org.labkey.api.view.NavTree;
import org.labkey.api.view.SpringErrorView;
import org.labkey.tcrdb.pipeline.MiXCRWrapper;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TCRdbController extends SpringActionController
{
    private static final DefaultActionResolver _actionResolver = new DefaultActionResolver(TCRdbController.class);
    public static final String NAME = "tcrdb";

    private static final FileType FASTA = new FileType("fasta");

    private static final Logger _log = LogManager.getLogger(TCRdbController.class);

    public TCRdbController()
    {
        setActionResolver(_actionResolver);
    }

    //TODO: based on set of IDs, run exportAlignmentsPretty

    @RequiresPermission(ReadPermission.class)
    public class ExportAlignmentsAction extends SimpleViewAction<ExportAlignmentsForm>
    {
        @Override
        public ModelAndView getView(ExportAlignmentsForm form, BindException errors) throws Exception
        {
            if (form.getAssayRowIds() == null || form.getAssayRowIds().length == 0)
            {
                errors.reject(ERROR_MSG, "Must provide IDs to display");
                return new SpringErrorView(errors);
            }

            if (StringUtils.isEmpty(form.getSchemaName()))
            {
                errors.reject(ERROR_MSG, "Must provide the assay schema name");
                return new SpringErrorView(errors);
            }

            //find rows
            UserSchema us = QueryService.get().getUserSchema(getUser(), getContainer(), form.getSchemaName());
            if (us == null)
            {
                errors.reject(ERROR_MSG, "Unknown schema: " + form.getSchemaName());
                return new SpringErrorView(errors);
            }

            TableInfo ti = us.getTable("data");
            final Map<File, List<AssayRecord>> VDJMap = new HashMap<>();
            List<Integer> rowIds = new ArrayList<>(Arrays.asList(form.getAssayRowIds()));

            TableSelector ts = new TableSelector(ti, new SimpleFilter(FieldKey.fromString("rowid"), rowIds, CompareType.IN), null);
            final StringWriter writer = new StringWriter();

            ts.forEach(AssayRecord.class, r -> {
                if (r.getVdjFile() == null)
                {
                    writer.write("ERROR: Row lacks VDJCA file: " + r.getRowId() + "\n");
                    return;
                }

                ExpData d = ExperimentService.get().getExpData(r.getVdjFile());
                if (d == null)
                {
                    writer.write("ERROR: Unable to find VDJCA file for row: " + r.getRowId() + ", ExpData: " + PageFlowUtil.filter(r.getVdjFile()) + "\n");
                    return;
                }

                if (!d.getFile().exists())
                {
                    writer.write("ERROR: Unable to find VDJCA file for row: " + r.getRowId() + ", file does not exist: " + PageFlowUtil.filter(d.getFile().getPath()) + "\n");
                    return;
                }

                if (!VDJMap.containsKey(d.getFile()))
                {
                    VDJMap.put(d.getFile(), new ArrayList<>());
                }

                VDJMap.get(d.getFile()).add(r);
            });

            if (VDJMap.isEmpty())
            {
                errors.reject(ERROR_MSG, "No matching rows found for IDs: " + StringUtils.join(rowIds, ","));
                return new SpringErrorView(errors);
            }

            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd");
            for (File f : VDJMap.keySet())
            {
                File tmp = FileUtil.createTempFile("mixcr", ".txt");
                MiXCRWrapper wrapper = new MiXCRWrapper(_log);
                //TODO:
                //wrapper.setLibraryPath();
                List<String> args = new ArrayList<>();
                if (StringUtils.trimToNull(form.getCdr3Equals()) != null)
                {
                    args.add("--cdr3-equals");
                    args.add(form.getCdr3Equals());
                }

                if (StringUtils.trimToNull(form.getReadContains()) != null)
                {
                    args.add("--read-contains");
                    args.add(form.getReadContains());
                }

                try
                {
                    wrapper.doExportAlignmentsPretty(f, tmp, args);

                    writer.write("File: " + PageFlowUtil.filter(f.getName()) + '\n');
                    writer.write("Result Rows From This File: " + '\n');
                    for (AssayRecord r : VDJMap.get(f))
                    {
                        writer.write("Sample: " + PageFlowUtil.filter(r.getSampleName()) + '\n');
                        writer.write("Sample Date: " + PageFlowUtil.filter(r.getDate() == null ? "" : fmt.format(r.getDate())) + '\n');
                        writer.write("CDR3: " + PageFlowUtil.filter(coalesce(r.getCDR3())) + '\n');
                        writer.write("vHit: " + PageFlowUtil.filter(coalesce(r.getvHit())) + '\n');
                        writer.write("dHit: " + PageFlowUtil.filter(coalesce(r.getdHit())) + '\n');
                        writer.write("jHit: " + PageFlowUtil.filter(coalesce(r.getjHit())) + '\n');
                        writer.write("cHit: " + PageFlowUtil.filter(coalesce(r.getcHit())) + '\n');
                        writer.write("Read Count: " + PageFlowUtil.filter(coalesce(r.getCount())) + '\n');
                        writer.write("Fraction: " + PageFlowUtil.filter(coalesce(r.getFraction())) + '\n');
                        writer.write("Comments: " + PageFlowUtil.filter(coalesce(r.getComment())) + '\n');
                        writer.write('\n');
                    }
                    writer.write('\n');
                    try (BufferedReader reader = Readers.getReader(new FileInputStream(tmp)))
                    {
                        String line;
                        boolean inAlignmentBlock = false;
                        while ((line = reader.readLine()) != null)
                        {
                            line = PageFlowUtil.filter(line);

                            String trimmed = StringUtils.trimToEmpty(line);
                            if (StringUtils.isEmpty(trimmed))
                            {
                                inAlignmentBlock = false;
                            }

                            //Highlight mismatches
                            if (inAlignmentBlock)
                            {
                                String[] tokens = trimmed.split("( )+");
                                String alignmentRaw = tokens[2];
                                StringBuilder sb = new StringBuilder();
                                for (int i=0; i<alignmentRaw.length();i++)
                                {
                                    char c = alignmentRaw.charAt(i);
                                    if (Character.isUpperCase(c))
                                    {
                                        sb.append("<span style=\"background: yellow;\">");
                                        sb.append(c);
                                        sb.append("</span>");
                                    }
                                    else
                                    {
                                        sb.append(PageFlowUtil.filter(c));
                                    }
                                }

                                line = line.replaceAll(alignmentRaw, sb.toString());
                            }

                            writer.write(line + '\n');

                            if (trimmed.startsWith("Target"))
                            {
                                inAlignmentBlock = true;
                            }
                        }
                    }

                    writer.write('\n');
                    writer.write("<hr>");
                    writer.write('\n');
                    writer.write('\n');

                    tmp.delete();
                }
                catch (PipelineJobException e){
                    writer.write("Unable to run export alignments\n");

                    _log.error("Unable to run exportAlignments:\n" + StringUtils.join(wrapper.getCommandsExecuted(), "\n"), e);
                }
            }

            //mixcr exportReadsForClones index_file alignments.vdjca.gz 0 1 2 33 54 reads.fastq.gz
            //mixcr exportAlignmentsPretty input.vdjca test.txt

            return new HtmlView("MiXCR Alignments", HtmlString.unsafe("<div style=\"font-family:courier,Courier New,monospace;white-space:nowrap;padding:5px;\"><pre>" + writer + "</pre></div>"));
        }

        private String coalesce(Object s)
        {
            return s == null ? "None" : s.toString();
        }

        @Override
        public void addNavTrail(NavTree root)
        {
            root.addChild("TCR Data Export"); //necessary to set page title, it seems
        }
    }

    public static class AssayRecord
    {
        private Integer _rowId;
        private Date _date;
        private String _CDR3;
        private String _vHit;
        private String _dHit;
        private String _jHit;
        private String _cHit;
        private Integer _clonesFile;
        private String _sampleName;
        private String _cloneId;
        private Integer _alignmentId;
        private Integer _vdjFile;
        private String _comment;
        private Integer _count;
        private Double _fraction;

        public Integer getRowId()
        {
            return _rowId;
        }

        public void setRowId(Integer rowId)
        {
            _rowId = rowId;
        }

        public Integer getClonesFile()
        {
            return _clonesFile;
        }

        public void setClonesFile(Integer clonesFile)
        {
            _clonesFile = clonesFile;
        }

        public String getSampleName()
        {
            return _sampleName;
        }

        public void setSampleName(String sampleName)
        {
            _sampleName = sampleName;
        }

        public String getCloneId()
        {
            return _cloneId;
        }

        public void setCloneId(String cloneId)
        {
            _cloneId = cloneId;
        }

        public Integer getAlignmentId()
        {
            return _alignmentId;
        }

        public void setAlignmentId(Integer alignmentId)
        {
            _alignmentId = alignmentId;
        }

        public Integer getVdjFile()
        {
            return _vdjFile;
        }

        public void setVdjFile(Integer vdjFile)
        {
            _vdjFile = vdjFile;
        }

        public Date getDate()
        {
            return _date;
        }

        public void setDate(Date date)
        {
            _date = date;
        }

        public String getCDR3()
        {
            return _CDR3;
        }

        public void setCDR3(String CDR3)
        {
            _CDR3 = CDR3;
        }

        public String getvHit()
        {
            return _vHit;
        }

        public void setvHit(String vHit)
        {
            _vHit = vHit;
        }

        public String getdHit()
        {
            return _dHit;
        }

        public void setdHit(String dHit)
        {
            _dHit = dHit;
        }

        public String getjHit()
        {
            return _jHit;
        }

        public void setjHit(String jHit)
        {
            _jHit = jHit;
        }

        public String getcHit()
        {
            return _cHit;
        }

        public void setcHit(String cHit)
        {
            _cHit = cHit;
        }

        public String getComment()
        {
            return _comment;
        }

        public void setComment(String comment)
        {
            _comment = comment;
        }

        public Integer getCount()
        {
            return _count;
        }

        public void setCount(Integer count)
        {
            _count = count;
        }

        public Double getFraction()
        {
            return _fraction;
        }

        public void setFraction(Double fraction)
        {
            _fraction = fraction;
        }
    }

    public static class ExportAlignmentsForm
    {
        private Integer[] _assayRowIds;
        private String schemaName;
        private String _cdr3Equals;
        private String _readContains;

        public Integer[] getAssayRowIds()
        {
            return _assayRowIds;
        }

        public void setAssayRowIds(Integer[] assayRowIds)
        {
            _assayRowIds = assayRowIds;
        }

        public String getSchemaName()
        {
            return schemaName;
        }

        public void setSchemaName(String schemaName)
        {
            this.schemaName = schemaName;
        }

        public String getCdr3Equals()
        {
            return _cdr3Equals;
        }

        public void setCdr3Equals(String cdr3Equals)
        {
            _cdr3Equals = cdr3Equals;
        }

        public String getReadContains()
        {
            return _readContains;
        }

        public void setReadContains(String readContains)
        {
            _readContains = readContains;
        }
    }

    @RequiresPermission(ReadPermission.class)
    @IgnoresTermsOfUse
    public static class DownloadSequenceAction extends ExportAction<DownloadCloneMaterialsForm>
    {
        @Override
        public void export(DownloadCloneMaterialsForm form, HttpServletResponse response, BindException errors) throws Exception
        {
            Container target = getContainer().isWorkbook() ? getContainer().getParent() : getContainer();
            UserSchema us = QueryService.get().getUserSchema(getUser(), target, form.getSchemaName());
            if (us == null)
            {
                errors.reject(ERROR_MSG, "Unable to find schema: " + form.getSchemaName());
                return;
            }

            TableInfo assayData = us.getTable(form.getQueryName());
            if (assayData == null)
            {
                errors.reject(ERROR_MSG, "Unable to find table: " + form.getQueryName());
                return;
            }

            List<String> rowIds = Arrays.asList(form.getRowId());
            if (rowIds.isEmpty())
            {
                errors.reject(ERROR_MSG, "No rows provided");
                return;
            }

            StringBuilder fasta = new StringBuilder();
            Map<Integer, Set<String>> segmentsByLibrary = new HashMap<>();

            //find assay records
            SimpleFilter assayFilter = new SimpleFilter(FieldKey.fromString("rowId"), rowIds, CompareType.IN);
            Map<FieldKey, ColumnInfo> cols = QueryService.get().getColumns(assayData, PageFlowUtil.set(
                    FieldKey.fromString("samplename"),
                    FieldKey.fromString("sequence"),
                    FieldKey.fromString("cdr3"),
                    FieldKey.fromString("vHit"),
                    FieldKey.fromString("jHit"),
                    FieldKey.fromString("dHit"),
                    FieldKey.fromString("cHit"),
                    FieldKey.fromString("libraryId/libraryId"),
                    FieldKey.fromString("analysisId/library_id")));
            TableSelector ts = new TableSelector(assayData, cols.values(), assayFilter, null);
            Set<String> segmentsByName = new HashSet<>();
            final String[] segmentFields = new String[]{"vHit", "jHit", "cHit"};
            ts.forEachResults(rs -> {
                // Allows this to work with both MiXCR and 10x data
                Integer libraryId = null;
                if (rs.getObject(FieldKey.fromString("libraryId/libraryId")) != null)
                {
                    libraryId = rs.getInt(FieldKey.fromString("libraryId/libraryId"));
                }

                if (libraryId == null && rs.getObject(FieldKey.fromString("analysisId/library_id")) != null)
                {
                    libraryId = rs.getInt(FieldKey.fromString("analysisId/library_id"));
                }

                for (String fn : segmentFields)
                {
                    if (rs.getString(FieldKey.fromString(fn)) != null)
                    {
                        if (libraryId != null)
                        {
                            Set<String> map = segmentsByLibrary.getOrDefault(libraryId, new HashSet<>());
                            map.add(StringUtils.trimToNull(rs.getString(FieldKey.fromString(fn))));
                            segmentsByLibrary.put(libraryId, map);
                        }
                        else
                        {
                            segmentsByName.add(StringUtils.trimToNull(rs.getString(FieldKey.fromString(fn))));
                        }
                    }
                }

                fasta.append(">").append(rs.getString("samplename")).append("_").append(rs.getString("cdr3")).append("\n");
                if (rs.getObject(FieldKey.fromString("sequence")) != null)
                {
                    fasta.append(rs.getString("sequence")).append("\n");
                }
                else
                {
                    fasta.append("No Data").append("\n");
                }
            });

            // look up segments in NT table
            Set<String> missingSegments = new HashSet<>(segmentsByName);
            for (int libraryId : segmentsByLibrary.keySet())
            {
                missingSegments.addAll(segmentsByLibrary.get(libraryId));
            }

            if (!segmentsByLibrary.isEmpty())
            {
                for (int libraryId : segmentsByLibrary.keySet())
                {
                    SimpleFilter ntFilter = new SimpleFilter();
                    ntFilter.addCondition(FieldKey.fromString("ref_nt_id/datedisabled"), null, CompareType.ISBLANK);
                    ntFilter.addCondition(FieldKey.fromString("library_id"), libraryId, CompareType.EQUAL);
                    ntFilter.addClause(new SimpleFilter.OrClause(
                            new SimpleFilter.InClause(FieldKey.fromString("ref_nt_id/name"), segmentsByLibrary.get(libraryId)),
                            new SimpleFilter.InClause(FieldKey.fromString("ref_nt_id/lineage"), segmentsByLibrary.get(libraryId))
                    ));
                    new TableSelector(QueryService.get().getUserSchema(getUser(), target, "sequenceanalysis").getTable("reference_library_members"), PageFlowUtil.set("ref_nt_id"), ntFilter, null).forEachResults(rs -> {
                        RefNtSequenceModel nt = RefNtSequenceModel.getForRowId(rs.getInt(FieldKey.fromString("ref_nt_id")));
                        fasta.append(">").append(nt.getName() + (nt.getSpecies() != null ? "-" + nt.getSpecies() : "")).append('\n').append(nt.getSequence()).append('\n');
                        missingSegments.remove(nt.getName());
                        missingSegments.remove(nt.getLineage());
                    });
                }
            }

            if (!segmentsByName.isEmpty())
            {
                SimpleFilter ntFilter = new SimpleFilter(FieldKey.fromString("name"), segmentsByName, CompareType.IN);
                ntFilter.addCondition(FieldKey.fromString("datedisabled"), null, CompareType.ISBLANK);

                new TableSelector(QueryService.get().getUserSchema(getUser(), target, "sequenceanalysis").getTable("ref_nt_sequences"), PageFlowUtil.set("rowid"), ntFilter, null).forEachResults(rs -> {
                    RefNtSequenceModel nt = RefNtSequenceModel.getForRowId(rs.getInt(FieldKey.fromString("rowid")));
                    fasta.append(">").append(nt.getName() + (nt.getSpecies() != null ? "-" + nt.getSpecies() : "")).append('\n').append(nt.getSequence()).append('\n');
                    missingSegments.remove(nt.getName());
                });
            }

            if (!missingSegments.isEmpty())
            {
                logger.error("Unable to find the following NT sequences: [" + StringUtils.join(missingSegments, "],[") + "]");
            }

            PageFlowUtil.prepareResponseForFile(response, Collections.emptyMap(), "TCR_Data.fasta", true);
            if (fasta.length() == 0)
            {
                response.getOutputStream().write("No data found".getBytes(StringUtilsLabKey.DEFAULT_CHARSET));
            }
            else
            {
                response.getOutputStream().write(fasta.toString().getBytes(StringUtilsLabKey.DEFAULT_CHARSET));
            }
        }
    }

    @RequiresPermission(ReadPermission.class)
    @IgnoresTermsOfUse
    public static class DownloadCloneMaterials extends ExportAction<DownloadCloneMaterialsForm>
    {
        @Override
        public void export(DownloadCloneMaterialsForm form, HttpServletResponse response, BindException errors) throws Exception
        {
            Container target = getContainer().isWorkbook() ? getContainer().getParent() : getContainer();
            UserSchema us = QueryService.get().getUserSchema(getUser(), target, form.getSchemaName());
            if (us == null)
            {
                errors.reject(ERROR_MSG, "Unable to find schema: " + form.getSchemaName());
                return;
            }

            TableInfo assayData = us.getTable(form.getQueryName());
            if (assayData == null)
            {
                errors.reject(ERROR_MSG, "Unable to find table: " + form.getQueryName());
                return;
            }

            List<String> rowIds = Arrays.asList(form.getRowId());
            if (rowIds.isEmpty())
            {
                errors.reject(ERROR_MSG, "No rows provided");
                return;
            }

            // find distinct analyses for assay rows and primary segments
            SimpleFilter assayFilter = new SimpleFilter(FieldKey.fromString("rowId"), rowIds, CompareType.IN);
            Map<FieldKey, ColumnInfo> cols = QueryService.get().getColumns(assayData, PageFlowUtil.set(
                    FieldKey.fromString("analysisId"),
                    FieldKey.fromString("samplename"),
                    FieldKey.fromString("sequence"),
                    FieldKey.fromString("cdr3"),
                    FieldKey.fromString("vHit"),
                    FieldKey.fromString("jHit"),
                    FieldKey.fromString("dHit"),
                    FieldKey.fromString("cHit"),
                    FieldKey.fromString("cloneId"),
                    FieldKey.fromString("sequence"),
                    FieldKey.fromString("clonesFile"),
                    FieldKey.fromString("libraryId/libraryId")));

            TableSelector ts = new TableSelector(assayData, cols.values(), assayFilter, null);
            Set<String> segmentsByName = new HashSet<>();
            Map<Integer, Set<String>> segmentsByLibrary = new HashMap<>();

            Map<Integer, Set<String>> clnaToCloneMap = new HashMap<>();
            Map<String, String> clnaToCDR3Map = new HashMap<>();
            StringBuilder imputedSequences = new StringBuilder();
            Set<Integer> analyses = new HashSet<>();
            final String[] segmentFields = new String[]{"vHit", "jHit", "cHit"};
            ts.forEachResults(rs -> {
                Integer libraryId = rs.getObject(FieldKey.fromString("libraryId/libraryId")) == null ? null : rs.getInt(FieldKey.fromString("libraryId/libraryId"));
                for (String fn : segmentFields)
                {
                    if (rs.getString(FieldKey.fromString(fn)) != null)
                    {
                        if (libraryId != null)
                        {
                            Set<String> map = segmentsByLibrary.getOrDefault(libraryId, new HashSet<>());
                            map.add(StringUtils.trimToNull(rs.getString(FieldKey.fromString(fn))));
                            segmentsByLibrary.put(libraryId, map);
                        }
                        else
                        {
                            segmentsByName.add(StringUtils.trimToNull(rs.getString(FieldKey.fromString(fn))));
                        }
                    }
                }

                if (rs.getObject(FieldKey.fromString("analysisId")) != null)
                {
                    analyses.add(rs.getInt(FieldKey.fromString("analysisId")));
                }

                if (rs.getObject(FieldKey.fromString("sequence")) != null)
                {
                    imputedSequences.append(">").append(rs.getString(FieldKey.fromString("sampleName"))).append("_").append(rs.getString(FieldKey.fromString("cdr3"))).append("\n");
                    imputedSequences.append(rs.getString(FieldKey.fromString("sequence"))).append("\n");
                }

                // This applies to MiXCR
                if (rs.getObject(FieldKey.fromString("cloneId")) != null && rs.getObject(FieldKey.fromString("clonesFile")) != null)
                {
                    Integer key = rs.getInt(FieldKey.fromString("clonesFile"));
                    Set<String> set = clnaToCloneMap.containsKey(key) ? clnaToCloneMap.get(key) : new HashSet<>();
                    set.add(rs.getString(FieldKey.fromString("cloneId")));

                    clnaToCloneMap.put(key, set);

                    clnaToCDR3Map.put(key + "_" + rs.getString(FieldKey.fromString("cloneId")), rs.getString(FieldKey.fromString("cdr3")));
                }
            });

            if (analyses.isEmpty())
            {
                errors.reject(ERROR_MSG, "Unable to find analyses for rows");
                return;
            }

            // then find all segments from these analyses
            SimpleFilter assayFilter2 = new SimpleFilter(FieldKey.fromString("analysisId"), analyses, CompareType.IN);
            new TableSelector(assayData, cols.values(), assayFilter2, null).forEachResults(rs -> {
                Integer libraryId = rs.getObject(FieldKey.fromString("libraryId/libraryId")) == null ? null : rs.getInt(FieldKey.fromString("libraryId/libraryId"));
                for (String fn : segmentFields)
                {
                    if (rs.getString(FieldKey.fromString(fn)) != null)
                    {
                        if (libraryId != null)
                        {
                            Set<String> map = segmentsByLibrary.getOrDefault(libraryId, new HashSet<>());
                            map.add(StringUtils.trimToNull(rs.getString(FieldKey.fromString(fn))));
                            segmentsByLibrary.put(libraryId, map);
                        }
                        else
                        {
                            segmentsByName.add(StringUtils.trimToNull(rs.getString(FieldKey.fromString(fn))));
                        }
                    }
                }
            });

            // look up segments in NT table
            Set<String> missingSegments = new HashSet<>(segmentsByName);
            for (int libraryId : segmentsByLibrary.keySet())
            {
                missingSegments.addAll(segmentsByLibrary.get(libraryId));
            }

            StringBuilder fasta = new StringBuilder();
            if (!segmentsByLibrary.isEmpty())
            {
                for (int libraryId : segmentsByLibrary.keySet())
                {
                    SimpleFilter ntFilter = new SimpleFilter(FieldKey.fromString("ref_nt_id/name"), segmentsByLibrary.get(libraryId), CompareType.IN);
                    ntFilter.addCondition(FieldKey.fromString("ref_nt_id/datedisabled"), null, CompareType.ISBLANK);
                    ntFilter.addCondition(FieldKey.fromString("library_id"), libraryId, CompareType.EQUAL);
                    new TableSelector(QueryService.get().getUserSchema(getUser(), target, "sequenceanalysis").getTable("reference_library_members"), PageFlowUtil.set("ref_nt_id"), ntFilter, null).forEachResults(rs -> {
                        RefNtSequenceModel nt = RefNtSequenceModel.getForRowId(rs.getInt(FieldKey.fromString("ref_nt_id")));
                        fasta.append(">").append(nt.getName() + (nt.getSpecies() != null ? "-" + nt.getSpecies() : "")).append('\n').append(nt.getSequence()).append('\n');
                        missingSegments.remove(nt.getName());
                    });
                }
            }

            if (!segmentsByName.isEmpty())
            {
                SimpleFilter ntFilter = new SimpleFilter(FieldKey.fromString("name"), segmentsByName, CompareType.IN);
                ntFilter.addCondition(FieldKey.fromString("datedisabled"), null, CompareType.ISBLANK);
                new TableSelector(QueryService.get().getUserSchema(getUser(), target, "sequenceanalysis").getTable("ref_nt_sequences"), PageFlowUtil.set("rowid"), ntFilter, null).forEachResults(rs -> {
                    RefNtSequenceModel nt = RefNtSequenceModel.getForRowId(rs.getInt(FieldKey.fromString("rowid")));
                    fasta.append(">").append(nt.getName() + (nt.getSpecies() != null ? "-" + nt.getSpecies() : "")).append('\n').append(nt.getSequence()).append('\n');
                    missingSegments.remove(nt.getName());
                });
            }

            if (!missingSegments.isEmpty())
            {
                logger.error("Unable to find the following NT sequences: [" + StringUtils.join(missingSegments, "],[") + "]");
            }

            // then grab actual Overlapping Contigs record(s).  only bother grabbing if FASTA
            SimpleFilter outputFilter = new SimpleFilter(FieldKey.fromString("analysis_id"), analyses, CompareType.IN);
            outputFilter.addCondition(FieldKey.fromString("category"), "Overlapping Contigs");

            Set<File> files = new HashSet<>();
            new TableSelector(QueryService.get().getUserSchema(getUser(), target, "sequenceanalysis").getTable("outputfiles"), PageFlowUtil.set("dataid"), outputFilter, null).forEachResults(rs -> {
                ExpData d = ExperimentService.get().getExpData(rs.getInt(FieldKey.fromString("dataid")));
                if (d != null && d.getFile().exists())
                {
                    if (FASTA.isType(d.getFile()))
                    {
                        files.add(d.getFile());
                    }
                }
            });

            //then exportReadsForClones:
            for (Integer expData : clnaToCloneMap.keySet())
            {
                MiXCRWrapper wrapper = new MiXCRWrapper(_log);
                ExpData d = ExperimentService.get().getExpData(expData);
                if (d == null)
                {
                    _log.error("Unable to find exp data with ID: " + expData);
                    continue;
                }

                if (d.getFile() == null || !d.getFile().exists())
                {
                    _log.error("File not found for ExpData: " + (d.getFile() == null ? expData : d.getFile().getPath()));
                    continue;
                }

                List<String> args = new ArrayList<>();
                args.add("-s");
                args.add("--id");
                args.addAll(clnaToCloneMap.get(expData));

                String basename = SequenceAnalysisService.get().getUnzippedBaseName(d.getFile().getName()) + ".readsForClones";
                File fqBase = new File(FileUtils.getTempDirectory(), basename + ".fastq.gz");

                wrapper.doExportReadsForClones(d.getFile(), fqBase, args);

                for (String cloneId : clnaToCloneMap.get(expData))
                {
                    String cdr3 = clnaToCDR3Map.get(expData + "_" + cloneId);

                    File fq1 = new File(fqBase.getParentFile(), basename + "_cln" + cloneId + "_R1.fastq.gz");
                    if (!fq1.exists())
                    {
                        _log.error("unable to find file: " + fq1.getPath());
                    }
                    else
                    {
                        File fq1m = new File(fqBase.getParentFile(), basename + "_cln" + cloneId + "." + cdr3 + ".R1.fastq.gz");
                        FileUtils.moveFile(fq1, fq1m);

                        files.add(fq1m);
                    }

                    File fq2 = new File(fqBase.getParentFile(), basename + "_cln" + cloneId + "_R2.fastq.gz");
                    if (!fq2.exists())
                    {
                        _log.error("unable to find file: " + fq2.getPath());
                    }
                    else
                    {
                        File fq2m = new File(fqBase.getParentFile(), basename + "_cln" + cloneId + "." + cdr3 + ".R2.fastq.gz");
                        FileUtils.moveFile(fq2, fq2m);

                        files.add(fq2m);
                    }
                }
            }

            PageFlowUtil.prepareResponseForFile(response, Collections.emptyMap(), "Clones.zip", true);
            Set<String> distinctNames = new HashSet<>();
            try (ZipOutputStream zOut = new ZipOutputStream(response.getOutputStream()))
            {
                //the segments:
                ZipEntry fileEntryFasta = new ZipEntry("segments.fasta");
                distinctNames.add("segments.fasta");

                zOut.putNextEntry(fileEntryFasta);
                zOut.write(fasta.toString().getBytes(StringUtilsLabKey.DEFAULT_CHARSET));
                zOut.closeEntry();

                //the FL imputed clones:
                ZipEntry fileEntryFasta2 = new ZipEntry("imputedClones.fasta");
                distinctNames.add("imputedClones.fasta");

                zOut.putNextEntry(fileEntryFasta2);
                zOut.write(imputedSequences.toString().getBytes(StringUtilsLabKey.DEFAULT_CHARSET));
                zOut.closeEntry();

                for (File f : files)
                {
                    String name = getUnique(f.getName(), distinctNames);
                    ZipEntry fileEntry = new ZipEntry(name);
                    zOut.putNextEntry(fileEntry);

                    try (FileInputStream in = new FileInputStream(f))
                    {
                        IOUtils.copy(in, zOut);
                        zOut.closeEntry();
                    }
                    catch (Exception e)
                    {
                        _log.error(e.getMessage(), e);
                    }
                }
            }
        }

        private String getUnique(String name, Set<String> distinctNames)
        {
            int i = 1;
            String newName = name;
            while (distinctNames.contains(newName))
            {
                newName = FileUtil.getBaseName(name) + "." + i + "." + FileUtil.getExtension(name);
                i++;
            }

            distinctNames.add(newName);

            return newName;
        }
    }

    public static class DownloadCloneMaterialsForm
    {
        private String[] _rowId;
        private String _schemaName;
        private String _queryName;

        public String[] getRowId()
        {
            return _rowId;
        }

        public void setRowId(String[] rowId)
        {
            _rowId = rowId;
        }

        public String getSchemaName()
        {
            return _schemaName;
        }

        public void setSchemaName(String schemaName)
        {
            _schemaName = schemaName;
        }

        public String getQueryName()
        {
            return _queryName;
        }

        public void setQueryName(String queryName)
        {
            _queryName = queryName;
        }
    }

    @RequiresPermission(AdminPermission.class)
    public class CreateGenomeFromMixcrAction extends ConfirmAction<CreateGenomeFromMixcrForm>
    {
        @Override
        public ModelAndView getConfirmView(CreateGenomeFromMixcrForm form, BindException errors) throws Exception
        {
            setTitle("Create Genome from MiXCR Library");

            HtmlView view = new HtmlView("This will create a reference genome from the selected MiXCR library JSON.  Note: if there is an existing sequence in this folder with the same name and sequence, it will be re-used rather than creating a new sequence.  If there is an existing record of the same name, but with a different shorter sequence, that sequence will be marked as disabled.  Do you want to continue?");
            return view;
        }

        @Override
        public boolean handlePost(CreateGenomeFromMixcrForm form, BindException errors) throws Exception
        {
            try
            {
                TCRdbManager.get().createGenomeFromMixcrDb(form.getRowId(), getUser(), getContainer());
            }
            catch (Exception e)
            {
                _log.error(e.getMessage(), e);
                throw e;
            }

            return true;
        }

        @Override
        public void validateCommand(CreateGenomeFromMixcrForm form, Errors errors)
        {
            if (form.getRowId() == null || form.getRowId() == 0)
            {
                errors.reject(ERROR_MSG, "Must provide the library row Id");
            }
        }

        @NotNull
        @Override
        public URLHelper getSuccessURL(CreateGenomeFromMixcrForm form)
        {
            return QueryService.get().urlFor(getUser(), getContainer(), QueryAction.executeQuery, TCRdbSchema.NAME, TCRdbSchema.TABLE_MIXCR_LIBRARIES);
        }
    }

    public static class CreateGenomeFromMixcrForm
    {
        private Integer _rowId;

        public Integer getRowId()
        {
            return _rowId;
        }

        public void setRowId(Integer rowId)
        {
            _rowId = rowId;
        }
    }
}
