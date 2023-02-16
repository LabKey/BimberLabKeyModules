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

import com.milaboratory.core.sequence.NucleotideSequence;
import io.repseq.core.GeneFeature;
import io.repseq.core.VDJCGene;
import io.repseq.core.VDJCLibrary;
import io.repseq.core.VDJCLibraryRegistry;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.InvalidKeyException;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.QueryUpdateServiceException;
import org.labkey.api.query.UserSchema;
import org.labkey.api.security.User;
import org.labkey.api.sequenceanalysis.GenomeTrigger;
import org.labkey.api.sequenceanalysis.RefNtSequenceModel;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.tcrdb.query.MixcrLibrary;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TCRdbManager
{
    private static final TCRdbManager _instance = new TCRdbManager();
    private static final Logger _log = LogManager.getLogger(TCRdbManager.class);

    private TCRdbManager()
    {

    }

    public static TCRdbManager get()
    {
        return _instance;
    }

    public void createGenomeFromMixcrDb(int mixcrRowId, User u, Container c) throws Exception
    {
        MixcrLibrary lib = new TableSelector(TCRdbSchema.getInstance().getSchema().getTable(TCRdbSchema.TABLE_MIXCR_LIBRARIES)).getObject(mixcrRowId, MixcrLibrary.class);
        if (lib == null)
        {
            throw new IllegalArgumentException("Unable to find MiXCR library: " + mixcrRowId);
        }

        File jsonFile = lib.getJsonFile();
        if (jsonFile == null)
        {
            throw new IllegalArgumentException("Unable to find JSON for MiXCR library: " + mixcrRowId);
        }

        if (lib.getLibraryId() != null)
        {
            throw new IllegalArgumentException("MiXCR library already has a genome associated with it: " + mixcrRowId);
        }

        Container target = c.isWorkbookOrTab() ? c.getParent() : c;
        UserSchema us = QueryService.get().getUserSchema(u, target, TCRdbSchema.SEQUENCE_ANALYSIS);
        TableInfo refNt = us.getTable("ref_nt_sequences");

        try
        {
            List<Integer> sequences = new ArrayList<>();

            VDJCLibraryRegistry reg = VDJCLibraryRegistry.getDefault();
            reg.addPathResolver(jsonFile.getParentFile().getPath());

            VDJCLibrary library = reg.getLibrary(lib.getLibraryName(), lib.getSpecies());
            String species = StringUtils.capitalize(lib.getSpecies());

            OUTER: for (VDJCGene gene : library.getGenes())
            {
                String name = gene.getName();
                Map<String, GeneFeature> regions = new LinkedHashMap<>();
                switch (gene.getGeneType())
                {
                    case Variable:
                        regions.put("L1+VExon2", new GeneFeature(GeneFeature.L1, GeneFeature.VExon2));
                        regions.put("VRegion", GeneFeature.VRegion);
                        break;
                    case Joining:
                        regions.put("JRegion", GeneFeature.JRegion);
                        break;
                    case Diversity:
                        regions.put("DRegion", GeneFeature.DRegion);
                        break;
                    case Constant:
                        regions.put("CExon1", GeneFeature.CExon1);
                        break;
                }

                String seq = null;
                String regionUsed = null;
                for (String regionName : regions.keySet())
                {
                    GeneFeature region = regions.get(regionName);
                    try
                    {
                        NucleotideSequence nt = gene.getFeature(region);
                        if (nt != null)
                        {
                            seq = nt.toString();
                            regionUsed = regionName;
                            break;
                        }
                    }
                    catch (Exception e)
                    {
                        _log.error(e.getMessage(), e);
                    }
                }

                if (seq == null)
                {
                    _log.error("Unable to find segment for sequence: " + gene.getName());
                    continue;
                }

                List<String> comments = new ArrayList<>();
                comments.add("Region: " + regionUsed);
                if (!gene.isFunctional())
                {
                    comments.add("Functional: " + gene.isFunctional());
                }

                if (gene.getData().getMeta() != null && gene.getData().getMeta().get("In_IMGT") != null)
                {
                    comments.add("In IMGT: " + StringUtils.join(gene.getData().getMeta().get("In_IMGT"), ","));

                    if (gene.getData().getMeta().get("ExtendedFromIMGT") != null)
                    {
                        comments.add("ExtendedFromIMGT: " + StringUtils.join(gene.getData().getMeta().get("ExtendedFromIMGT"), ","));
                    }
                }

                SimpleFilter filter = new SimpleFilter(FieldKey.fromString("name"), name);
                filter.addCondition(FieldKey.fromString("species"), species);
                TableSelector ts = new TableSelector(refNt, filter, null);
                if (ts.exists())
                {
                    List<RefNtSequenceModel> refs = ts.getArrayList(RefNtSequenceModel.class);
                    for (RefNtSequenceModel ref : refs)
                    {
                        if (seq.equals(ref.getSequence()))
                        {
                            _log.info("Using existing: " + ref.getName());
                            sequences.add(ref.getRowid());

                            //update fields, as needed:
                            Map<String, Object> toUpdate = new CaseInsensitiveHashMap<>();
                            if (!"TCR".equals(ref.getCategory()))
                            {
                                toUpdate.put("category", "TCR");

                            }

                            if (ref.getDatedisabled() != null)
                            {
                                toUpdate.put("datedisabled", null);
                            }

                            if (ref.getDisabledby() != null)
                            {
                                toUpdate.put("disabledby", null);
                            }

                            if (!gene.getGeneName().equals(ref.getSubset()))
                            {
                                toUpdate.put("subset", gene.getGeneName());
                            }

                            String locus = StringUtils.join(gene.getChains(), ",");
                            if (!locus.equals(ref.getLocus()))
                            {
                                toUpdate.put("locus", locus);
                            }

                            if (!gene.getFamilyName().equals(ref.getLineage()))
                            {
                                toUpdate.put("lineage", gene.getFamilyName());
                            }

                            if (!comments.equals(ref.getComments()))
                            {
                                toUpdate.put("comments", comments);
                            }

                            if (!toUpdate.isEmpty())
                            {
                                _log.info("Updating existing record: " + ref.getName());
                                toUpdate.put("rowid", ref.getRowid());
                                Map<String, Object> oldKeys = new CaseInsensitiveHashMap<>();
                                oldKeys.put("rowid", ref.getRowid());
                                refNt.getUpdateService().updateRows(u, ContainerManager.getForId(ref.getContainer()), List.of(toUpdate), List.of(oldKeys), null, null);
                            }

                            continue OUTER;
                        }
                    }
                }

                //otherwise create new:
                Map<String, Object> row = new CaseInsensitiveHashMap<>();
                row.put("name", name);
                row.put("category", "TCR");
                row.put("species", species);
                row.put("subset", gene.getGeneName());
                row.put("locus", StringUtils.join(gene.getChains(), ","));
                row.put("lineage", gene.getFamilyName());
                row.put("container", lib.getContainer());
                row.put("sequence", seq);
                row.put("comments", StringUtils.join(comments, "\n"));

                BatchValidationException errors = new BatchValidationException();
                List<Map<String, Object>> inserted = refNt.getUpdateService().insertRows(u, target, List.of(row), errors, null, null);
                if (errors.hasErrors())
                {
                    throw errors;
                }

                RefNtSequenceModel ref = new TableSelector(refNt).getObject(inserted.get(0).get("rowid"), RefNtSequenceModel.class);
                sequences.add(ref.getRowid());
            }

            if (!sequences.isEmpty())
            {
                _log.info("Creating mixcr genome with " + sequences.size() + " sequences");
                SequenceAnalysisService.get().createReferenceLibrary(sequences, ContainerManager.getForId(lib.getContainer()), u, lib.getLabel(), null, "Created from MiXCR library: " + lib.getLibraryName(), true, true, PageFlowUtil.set(new MiXCRGenomeTrigger(mixcrRowId)));
            }
            else
            {
                _log.error("No sequences found: " + lib.getLibraryName());
            }
        }
        catch (Exception e)
        {
            //TODO: improve
            _log.error(e.getMessage(), e);
            throw e;

        }
    }

    public static class MiXCRGenomeTrigger implements GenomeTrigger
    {
        private Integer _mixcrId = null;

        public MiXCRGenomeTrigger()
        {

        }

        public MiXCRGenomeTrigger(int mixcrId)
        {
            _mixcrId = mixcrId;
        }

        public Integer getMixcrId()
        {
            return _mixcrId;
        }

        public void setMixcrId(Integer mixcrId)
        {
            _mixcrId = mixcrId;
        }

        @Override
        public String getName()
        {
            return "MiXCR Library Update";
        }

        @Override
        public void onCreate(Container c, User u, Logger log, int genomeId)
        {
            if (_mixcrId != null)
            {
                TableInfo ti = QueryService.get().getUserSchema(u, c, TCRdbSchema.NAME).getTable(TCRdbSchema.TABLE_MIXCR_LIBRARIES);
                List<Map<String, Object>> rows = new ArrayList<>();
                List<Map<String, Object>> oldKeys = new ArrayList<>();
                Map<String, Object> row = new CaseInsensitiveHashMap<>();
                row.put("rowid", _mixcrId);
                row.put("libraryId", genomeId);
                rows.add(row);

                Map<String, Object> keyRow = new CaseInsensitiveHashMap<>();
                keyRow.put("rowid", _mixcrId);
                oldKeys.add(keyRow);

                try
                {
                    ti.getUpdateService().updateRows(u, c, rows, oldKeys, null, null);
                }
                catch (QueryUpdateServiceException | InvalidKeyException | BatchValidationException | SQLException e)
                {
                    _log.error(e.getMessage(), e);
                }
            }
        }

        @Override
        public void onRecreate(Container c, User u, Logger log, int genomeId)
        {

        }

        @Override
        public void onDelete(Container c, User u, Logger log, int genomeId)
        {

        }

        @Override
        public boolean isAvailable(Container c)
        {
            return c.getActiveModules().contains(ModuleLoader.getInstance().getModule(TCRdbModule.class));
        }
    }
}