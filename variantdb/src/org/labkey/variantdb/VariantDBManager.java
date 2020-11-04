/*
 * Copyright (c) 2014 LabKey Corporation
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

package org.labkey.variantdb;

import htsjdk.samtools.liftover.LiftOver;
import htsjdk.samtools.util.Interval;
import org.apache.logging.log4j.Logger;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.DbScope;
import org.labkey.api.data.SQLFragment;
import org.labkey.api.data.Selector;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.SqlSelector;
import org.labkey.api.data.Table;
import org.labkey.api.data.TableSelector;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.exp.api.ExperimentService;
import org.labkey.api.query.FieldKey;
import org.labkey.api.security.User;
import org.labkey.api.util.GUID;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.Pair;
import org.labkey.variantdb.query.LiftedVariant;
import org.labkey.variantdb.query.Variant;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class VariantDBManager
{
    private static final VariantDBManager _instance = new VariantDBManager();

    private VariantDBManager()
    {
        // prevent external construction with a private default constructor
    }

    public static VariantDBManager get()
    {
        return _instance;
    }

    private LiftedVariant liftOverVariant(LiftOver lo, Variant v, ChainFileWrapper chainFile)
    {
        Interval iv = new Interval(v.getSequenceName(), v.getStartPosition(), v.getEndPosition(), false, v.getObjectid());
        Interval lifted = lo.liftOver(iv);

        LiftedVariant lv = new LiftedVariant(v, lifted, chainFile.getChainFile().getRowId());
        if (lifted != null)
        {
            Integer seqId = chainFile.resolveTargetSequenceId(lifted.getSequence());
            if (seqId != null)
            {
                lv.setSequenceId(seqId);
            }
            else
            {

            }
        }
        //else {
        //    List<LiftOver.PartialLiftover> intervals = lo.diagnosticLiftover(iv);
        //}

        return lv;
    }

    public void liftOverVariants(final int genomeId, SimpleFilter variantFilter, final Logger log, final User u) throws SQLException
    {
        final Map<Integer, ChainFileWrapper> chainFileMap = new HashMap<>();

        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("genomeId1"), genomeId);
        filter.addCondition(FieldKey.fromString("dateDisabled"), null, CompareType.ISBLANK);
        TableSelector ts = new TableSelector(DbSchema.get("sequenceanalysis").getTable("chain_files"), PageFlowUtil.set("chainFile", "genomeId2"), filter, null);
        ts.forEach(new Selector.ForEachBlock<ResultSet>()
        {
            @Override
            public void exec(ResultSet rs) throws SQLException
            {
                Integer targetGenomeId = rs.getInt("genomeId2");
                Integer chainFileId = rs.getInt("chainFile");
                ExpData d = ExperimentService.get().getExpData(chainFileId);
                if (d == null || d.getFile() == null || !d.getFile().exists())
                {
                    log.warn("chain file does not exist, cannot liftover to: " + targetGenomeId);
                    return;
                }

                log.warn("will attempt to liftover to genome: " + targetGenomeId);
                chainFileMap.put(targetGenomeId, new ChainFileWrapper(genomeId, targetGenomeId, d));
            }
        });

        if (chainFileMap.isEmpty())
        {
            log.warn("there are no available chain files for this genome");
        }
        else
        {
            log.info("initializing chain files");
            final Map<Integer, LiftOver> liftOverMap = new HashMap<>();
            for (Integer i : chainFileMap.keySet())
            {
                liftOverMap.put(i, new LiftOver(chainFileMap.get(i).getChainFile().getFile()));
            }

            final int batchSize = 5000;
            String deleteSql = "DELETE FROM " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANT_LIFTOVER +
                    " WHERE variantid = ? ;";

            String insertSql = "INSERT INTO " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANT_LIFTOVER +
                    " (variantid, sequenceid, startPosition, endPosition, reference, allele, referenceVariantId, referenceAlleleId, batchId, chainFile, created, createdBy, modified, modifiedBy) " +
                    " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";

            try (Connection connection = DbScope.getLabKeyScope().getConnection();
                 final PreparedStatement deletePs = connection.prepareStatement(deleteSql);
                 final PreparedStatement insertPs = connection.prepareStatement(insertSql);
            )
            {
                log.info("querying/updating variants");
                CaseInsensitiveHashMap batchRow = new CaseInsensitiveHashMap();
                final String batchId = new GUID().toString();
                batchRow.put("batchId", batchId);
                batchRow.put("description", "Liftover of local variants");
                batchRow.put("source", "Local Variants");

                Table.insert(u, VariantDBSchema.getInstance().getSchema().getTable(VariantDBSchema.TABLE_UPLOAD_BATCHES), batchRow);

                final Pair<Integer, Integer> matches = Pair.of(0, 0);
                TableSelector variantTs = new TableSelector(VariantDBSchema.getInstance().getSchema().getTable(VariantDBSchema.TABLE_VARIANTS), variantFilter, null);
                variantTs.forEach(Variant.class, v -> {
                    String name = resolveSequenceName(v.getSequenceId());
                    if (name != null)
                    {
                        matches.second++;

                        //only delete once
                        deletePs.setString(1, v.getObjectid());
                        deletePs.addBatch();

                        v.setSequenceName(name);
                        for (Integer targetId : liftOverMap.keySet())
                        {
                            LiftedVariant lv = VariantDBManager.get().liftOverVariant(liftOverMap.get(targetId), v, chainFileMap.get(targetId));
                            if (lv.successfulLiftover())
                            {
                                matches.first++;
                            }

                            //variantid, sequenceid, startPosition, endPosition, reference, allele, referenceVariantId, referenceAlleleId, batchId, chainFile, created, createdBy, modified, modifiedBy
                            insertPs.setString(1, v.getObjectid());
                            if (lv.successfulLiftover())
                            {
                                insertPs.setInt(2, lv.getSequenceId());
                                insertPs.setInt(3, lv.getStartPosition());
                                insertPs.setInt(4, lv.getEndPosition());
                            }
                            else
                            {
                                insertPs.setInt(2, -1);
                                insertPs.setInt(3, 0);
                                insertPs.setInt(4, 0);
                            }
                            insertPs.setString(5, null);
                            insertPs.setString(6, null);

                            insertPs.setString(7, v.getReferenceVariantId());
                            insertPs.setString(8, v.getReferenceAlleleId());
                            insertPs.setString(9, batchId);
                            insertPs.setInt(10, lv.getChainFile());
                            insertPs.setDate(11, new Date(System.currentTimeMillis()));
                            insertPs.setInt(12, u.getUserId());
                            insertPs.setDate(13, new Date(System.currentTimeMillis()));
                            insertPs.setInt(14, u.getUserId());

                            insertPs.addBatch();
                        }

                        if (matches.second % batchSize == 0)
                        {
                            log.info("processed: " + matches.second + "  variants");
                            deletePs.executeBatch();
                            insertPs.executeBatch();
                        }
                    }
                    else
                    {
                        log.error("unable to resolve sequenceId: " + v.getSequenceId());
                    }
                });

                //execute any remaining commands
                log.info("processed: " + matches.second + "  variants");
                deletePs.addBatch();
                insertPs.addBatch();

                log.info("total: " + matches.second + ", lifted: " + matches.first);
            }
        }
    }

    private Map<Integer, String> _cachedReferences = new HashMap<>();

    private String resolveSequenceName(int sequenceId)
    {
        if (!_cachedReferences.containsKey(sequenceId))
        {
            String name = new TableSelector(DbSchema.get("sequenceanalysis").getTable("ref_nt_sequences"), PageFlowUtil.set("name")).getObject(sequenceId, String.class);
            _cachedReferences.put(sequenceId, name);
        }

        return _cachedReferences.get(sequenceId);
    }

    private class ChainFileWrapper
    {
        private int _sourceGenomeId;
        private int _targetGenomeId;
        private ExpData _chainFile;
        private Map<String, Integer> _cachedReferences = null;

        public ChainFileWrapper(int sourceGenomeId, int targetGenomeId, ExpData chainFile)
        {
            _sourceGenomeId = sourceGenomeId;
            _targetGenomeId = targetGenomeId;
            _chainFile = chainFile;
        }

        public int getSourceGenomeId()
        {
            return _sourceGenomeId;
        }

        public int getTargetGenomeId()
        {
            return _targetGenomeId;
        }

        public ExpData getChainFile()
        {
            return _chainFile;
        }

        public int resolveTargetSequenceId(String sequenceName)
        {
            if (_cachedReferences == null)
            {
                _cachedReferences = new HashMap<>();

                SqlSelector ss = new SqlSelector(DbScope.getLabKeyScope(), new SQLFragment("SELECT r.rowid, r.name FROM sequenceanalysis.ref_nt_sequences r WHERE r.rowid IN (SELECT ref_nt_id FROM sequenceanalysis.reference_library_members m WHERE m.library_id = ?) ", _targetGenomeId));
                ss.forEach(new Selector.ForEachBlock<ResultSet>()
                {
                    @Override
                    public void exec(ResultSet rs) throws SQLException
                    {
                        _cachedReferences.put(rs.getString("name"), rs.getInt("rowid"));
                    }
                });
            }

            if (!_cachedReferences.containsKey(sequenceName))
            {
                throw new IllegalArgumentException("unable to find sequence with name: " + sequenceName);
            }

            return _cachedReferences.get(sequenceName);
        }
    }
}