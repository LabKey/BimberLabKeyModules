package org.labkey.variantdb.pipeline;

import htsjdk.samtools.util.CloseableIterator;
import htsjdk.tribble.AbstractFeatureReader;
import htsjdk.tribble.FeatureReader;
import htsjdk.tribble.TribbleException;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFCodec;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.BaseColumnInfo;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.DbScope;
import org.labkey.api.data.JdbcType;
import org.labkey.api.data.Results;
import org.labkey.api.data.ResultsImpl;
import org.labkey.api.data.SQLFragment;
import org.labkey.api.data.Selector;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.SqlExecutor;
import org.labkey.api.data.Table;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.data.TempTableInfo;
import org.labkey.api.pipeline.AbstractTaskFactory;
import org.labkey.api.pipeline.AbstractTaskFactorySettings;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedAction;
import org.labkey.api.pipeline.RecordedActionSet;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.UserSchema;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.util.FileType;
import org.labkey.api.util.GUID;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.variantdb.VariantDBManager;
import org.labkey.variantdb.VariantDBSchema;

import java.io.IOException;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by bimber on 1/5/2015.
 */
public class VariantImportTask extends PipelineJob.Task<VariantImportTask.Factory>
{
    protected VariantImportTask(Factory factory, PipelineJob job)
    {
        super(factory, job);
    }

    public static class Factory extends AbstractTaskFactory<AbstractTaskFactorySettings, Factory>
    {
        public Factory()
        {
            super(VariantImportTask.class);
            //setLocation("webserver-high-priority");
        }

        public List<FileType> getInputTypes()
        {
            return Collections.emptyList();
        }

        public String getStatusName()
        {
            return PipelineJob.TaskStatus.running.toString();
        }

        public List<String> getProtocolActionNames()
        {
            return Arrays.asList("Variant Import");
        }

        public PipelineJob.Task createTask(PipelineJob job)
        {
            return new VariantImportTask(this, job);
        }

        public boolean isJobComplete(PipelineJob job)
        {
            return false;
        }
    }

    public RecordedActionSet run() throws PipelineJobException
    {
        String batchId = createBatch();

        try
        {
            List<RecordedAction> actions = new ArrayList<>();

            actions.add(processVCFs(batchId));

            return new RecordedActionSet(actions);
        }
        catch (PipelineJobException e)
        {
            //roll back changes in case of failure
            deleteAllFromTable(batchId, VariantDBSchema.TABLE_REFERENCE_VARIANTS);
            deleteAllFromTable(batchId, VariantDBSchema.TABLE_REFERENCE_VARIANT_ALLELES);
            deleteAllFromTable(batchId, VariantDBSchema.TABLE_VARIANT_ATTRIBUTES);
            deleteAllFromTable(batchId, VariantDBSchema.TABLE_VARIANT_SAMPLE_MAPPING);
            deleteAllFromTable(batchId, VariantDBSchema.TABLE_VARIANTS);
            deleteAllFromTable(batchId, VariantDBSchema.TABLE_UPLOAD_BATCHES);

            throw e;
        }
    }

    private void deleteAllFromTable(String batchId, String tableName)
    {
        String sql = "DELETE FROM " + VariantDBSchema.NAME + "." + tableName + " WHERE batchId = ?";
        try (Connection connection = DbScope.getLabKeyScope().getConnection();PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setString(1, batchId);
            ps.execute();
        }
        catch (SQLException e)
        {
            getJob().getLogger().error("Unable to delete record from table: " + tableName, e);
        }
    }

    private String createBatch()
    {
        String batchId = new GUID().toString();

        CaseInsensitiveHashMap<String> batchRow = new CaseInsensitiveHashMap<>();
        batchRow.put("batchId", batchId);
        batchRow.put("description", "Import of data from local VCF files");
        batchRow.put("source", "Local VCF");
        batchRow.put("jobId", getJob().getJobGUID());
        Table.insert(getJob().getUser(), VariantDBSchema.getInstance().getSchema().getTable(VariantDBSchema.TABLE_UPLOAD_BATCHES), batchRow);

        return batchId;
    }

    private RecordedAction processVCFs(String batchId) throws PipelineJobException
    {
        RecordedAction action = new RecordedAction("Loading variants");
        action.setStartTime(new Date());

        for (SequenceOutputFile f : getPipelineJob().getOutputFiles())
        {
            processVCF(batchId, f);

            //liftoverVariants(batchId, f);
        }

        action.setEndTime(new Date());

        return action;
    }

    private void processVCF(String batchId, SequenceOutputFile outputFile) throws PipelineJobException
    {
        getJob().getLogger().info("reading file: " + outputFile.getFile().getName());

        //create temp table so we can assign IDs, then insert
        TempTableInfo variantTemp = createTempTable(VariantDBSchema.getInstance().getSchema(), "variants", Arrays.asList(
                new BaseColumnInfo("objectid", JdbcType.VARCHAR),
                new BaseColumnInfo("sequenceid", JdbcType.INTEGER),
                new BaseColumnInfo("startPosition", JdbcType.INTEGER),
                new BaseColumnInfo("endPosition", JdbcType.INTEGER),
                new BaseColumnInfo("reference", JdbcType.VARCHAR),
                new BaseColumnInfo("allele", JdbcType.VARCHAR),
                new BaseColumnInfo("status", JdbcType.VARCHAR),
                new BaseColumnInfo("dbSnpAccession", JdbcType.VARCHAR),
                new BaseColumnInfo("variantId", JdbcType.VARCHAR),
                new BaseColumnInfo("referenceVariantId", JdbcType.VARCHAR),
                new BaseColumnInfo("referenceAlleleId", JdbcType.VARCHAR),
                new BaseColumnInfo("provisionalId", JdbcType.VARCHAR)
        ));

        try (FeatureReader reader = AbstractFeatureReader.getFeatureReader(outputFile.getFile().getAbsolutePath(), new VCFCodec(), false))
        {
            //VCFHeader header = (VCFHeader)reader.getHeader();
            String variantSql = "INSERT INTO " + variantTemp +
                    " (objectid, sequenceid, startPosition, endPosition, reference, allele, status, dbSnpAccession, provisionalId) " +
                    " values (?, ?, ?, ?, ?, ?, ?, ?, ?);";

            try (Connection connection = DbScope.getLabKeyScope().getConnection();PreparedStatement variantPs = connection.prepareStatement(variantSql))
            {
                final int batchSize = 5000;
                int count = 0;

                try (CloseableIterator<VariantContext> i = reader.iterator())
                {
                    while (i.hasNext())
                    {
                        try
                        {
                            VariantContext f = i.next();
                            //f.fullyDecode(header, true);

                            String dbSnpAccession = f.hasAttribute("RS") ? "rs" + f.getAttributeAsString("RS", null) : null;
                            for (Allele a : f.getAlleles())
                            {
                                variantPs.setString(1, new GUID().toString()); //objectid
                                variantPs.setInt(2, resolveSequenceId(f.getChr(), outputFile.getLibrary_id())); //sequenceid
                                variantPs.setInt(3, f.getStart());  //start pos
                                variantPs.setInt(4, f.getEnd());  //end pos
                                variantPs.setString(5, f.getReference().getBaseString()); //ref
                                variantPs.setString(6, a.getBaseString()); //allele
                                variantPs.setString(7, "Provisional");
                                variantPs.setString(8, dbSnpAccession);  //dbSnpAccession
                                variantPs.setString(9, new GUID().toString());  //provisionalId
                                variantPs.execute();

                                if (++count % batchSize == 0)
                                {
                                    getJob().getLogger().info("processed " + NumberFormat.getInstance().format(count) + " variants");
                                    insertVariantsFromTempTable(variantTemp, batchId, outputFile);
                                }
                            }
                        }
                        catch (TribbleException e)
                        {
                            //ignore for now...
                            getJob().getLogger().error(e);
                        }
                    }

                    insertVariantsFromTempTable(variantTemp, batchId, outputFile);
                }
            }
        }
        catch (BatchUpdateException e)
        {
            SQLException se = e.getNextException();
            while (se != null)
            {
                getJob().getLogger().error(se.getMessage(), se);
                se = se.getNextException();
            }
        }
        catch (IOException | SQLException e)
        {
            throw new PipelineJobException(e);
        }
        finally
        {
            variantTemp.delete();
        }
    }

    private void liftoverVariants(String batchId, SequenceOutputFile outputFile) throws PipelineJobException
    {
        getJob().getLogger().info("calculating liftover for file: " + outputFile.getFile().getName());
        if (outputFile.getLibrary_id() == null)
        {
            getJob().getLogger().info("no genome associated with this file, skipping");
            return;
        }

        try
        {
            VariantDBManager.get().liftOverVariants(outputFile.getLibrary_id(), new SimpleFilter(FieldKey.fromString("batchId"), batchId), getJob().getLogger(), getJob().getUser());
        }
        catch (SQLException e)
        {
            throw new PipelineJobException(e);
        }
    }

    private TempTableInfo createTempTable(DbSchema schema, String name, List<ColumnInfo> columnInfos)
    {
        TempTableInfo tt = new TempTableInfo(name, columnInfos, Arrays.asList("objectid"));

        String sql = "CREATE TABLE " + tt.toString() + " ( ";
        String delim = "\n";
        for (ColumnInfo col : columnInfos)
        {
            sql += delim + col.getName() + " " + tt.getSqlDialect().getSqlTypeName(col.getJdbcType()) + (JdbcType.VARCHAR == col.getJdbcType() ? "(1000)" : "");
            delim = ",\n";
        }
        sql += ");";

        new SqlExecutor(schema).execute(sql);

        tt.track();

        return tt;
    }

    private Map<Integer, Map<String, Integer>> _cachedReferencesByGenome = new HashMap<>();

    private int resolveSequenceId(String refName, int genomeId) throws PipelineJobException
    {
        if (!_cachedReferencesByGenome.containsKey(genomeId))
        {
            final Map<String, Integer> cachedReferences = new CaseInsensitiveHashMap<>();
            UserSchema us = QueryService.get().getUserSchema(getPipelineJob().getUser(), getPipelineJob().getContainer(), "sequenceanalysis");
            TableInfo ti = us.getTable("reference_library_members");
            final Map<FieldKey, ColumnInfo> cols = QueryService.get().getColumns(ti, PageFlowUtil.set(FieldKey.fromString("ref_nt_id"), FieldKey.fromString("ref_nt_id/name")));
            TableSelector ts = new TableSelector(ti, cols.values(), new SimpleFilter(FieldKey.fromString("library_id"), genomeId), null);
            ts.forEach(new Selector.ForEachBlock<ResultSet>()
            {
                @Override
                public void exec(ResultSet object) throws SQLException
                {
                    Results rs = new ResultsImpl(object, cols);
                    cachedReferences.put(rs.getString(FieldKey.fromString("ref_nt_id/name")), rs.getInt(FieldKey.fromString("ref_nt_id")));
                }
            });

            _cachedReferencesByGenome.put(genomeId, cachedReferences);
        }

        Map<String, Integer> cachedReferences = _cachedReferencesByGenome.get(genomeId);
        if (!cachedReferences.containsKey(refName))
        {
            throw new PipelineJobException("Unable to find reference matching: " + refName + " within genomeId: " + genomeId);
        }

        return cachedReferences.get(refName);
    }

    private void insertVariantsFromTempTable(TempTableInfo ti, String batchId, SequenceOutputFile outputFile)
    {
        new SqlExecutor(ti.getSchema()).execute(new SQLFragment("UPDATE " + ti.getSelectName() + "\n" +
                " SET referenceVariantId = (SELECT rv.objectid FROM " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_REFERENCE_VARIANTS + " rv WHERE rv.dbSnpAccession = " + ti.getSelectName() + ".dbSnpAccession)"
        ));

        new SqlExecutor(ti.getSchema()).execute(new SQLFragment("UPDATE " + ti.getSelectName() + "\n" +
                " SET referenceAlleleId = (SELECT rv.objectid FROM " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_REFERENCE_VARIANT_ALLELES + " rv WHERE rv.referenceVariantId = " + ti.getSelectName() + ".referenceVariantId AND rv.allele = " + ti.getSelectName() + ".allele)"
        ));

        int updated1 = new SqlExecutor(ti.getSchema()).execute(new SQLFragment("UPDATE " + ti.getSelectName() + "\n" +
                " SET variantId = (SELECT rv.objectid FROM " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANTS+ " rv WHERE " +
                "rv.sequenceid = " + ti.getSelectName() + ".sequenceid AND " +
                "rv.startposition = " + ti.getSelectName() + ".startposition AND " +
                "rv.endposition = " + ti.getSelectName() + ".endposition AND " +
                "rv.allele = " + ti.getSelectName() + ".allele)"
        ));
        //getJob().getLogger().info("\tupdated " + updated1 + " incoming variants with Ids of existing variants (indicating we have encountered this position before)");

        SQLFragment sql = new SQLFragment("INSERT INTO " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANTS + "\n" +
                " (objectid, sequenceid, startPosition, endPosition, reference, allele, referenceVariantId, referenceAlleleId, batchId, created, createdBy, modified, modifiedBy)\n" +
                " SELECT t.objectid, t.sequenceid, t.startPosition, t.endPosition, t.reference, t.allele, t.referenceVariantId, t.referenceAlleleId, ?, {fn now()}, ?, {fn now()}, ?\n" +
                " FROM " + ti.getSelectName() + " t\n" +
                //only import if we dont already have an allele at this position
                " WHERE t.variantid IS NULL;", batchId, getJob().getUser().getUserId(), getJob().getUser().getUserId()
        );

        int changed = new SqlExecutor(ti.getSchema()).execute(sql);
        getJob().getLogger().info("\tinserted " + changed + " new variants");

        //repeat, this time for newly inserted records
        int updated2 = new SqlExecutor(ti.getSchema()).execute(new SQLFragment("UPDATE " + ti.getSelectName() + "\n" +
                " SET variantId = (SELECT rv.objectid FROM " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANTS + " rv WHERE " +
                "rv.sequenceid = " + ti.getSelectName() + ".sequenceid AND " +
                "rv.startposition = " + ti.getSelectName() + ".startposition AND " +
                "rv.endposition = " + ti.getSelectName() + ".endposition AND " +
                "rv.allele = " + ti.getSelectName() + ".allele AND " +
                "rv.batchId = ?) WHERE " + ti.getSelectName() + ".variantId IS NULL;", batchId
        ));
        //getJob().getLogger().info("\tupdated " + updated2 + " incoming variants with variantIds (should match the # inserted)");

        //next import into variant/sample mapping table
        if (outputFile != null)
        {
            SQLFragment sql2 = new SQLFragment("INSERT INTO " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANT_SAMPLE_MAPPING + "\n" +
                    " (variantid, readset, dataid, batchid, container, created, createdBy, modified, modifiedBy)\n" +
                    " SELECT t.variantid, " + (outputFile.getReadset() == null ? "null" : outputFile.getReadset()) + ", ?, ?, ?, {fn now()}, ?, {fn now()}, ?\n" +
                    " FROM " + ti.getSelectName() + " t\n" +
                    //only import if not already present
                    " LEFT JOIN " +  VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANT_SAMPLE_MAPPING + " sm ON (" +
                    "sm.variantId = t.variantid AND " +
                    "sm.dataId = ? AND " +
                    (outputFile.getReadset() == null ? "(sm.readset IS NULL)" : "(sm.readset = " + outputFile.getReadset() + ")") +
                    ") " +
                    " WHERE t.variantid IS NOT NULL AND sm.rowid IS NULL;", outputFile.getDataId(), batchId, getJob().getContainer().getId(), getJob().getUser().getUserId(), getJob().getUser().getUserId(), outputFile.getDataId()
            );
            int changed2 = new SqlExecutor(ti.getSchema()).execute(sql2);
            getJob().getLogger().info("\tinserted " + changed2 + " sample/variant mapping records");
        }

        //finally truncate temp table
        new SqlExecutor(ti.getSchema()).execute(ti.getSqlDialect().getTruncateSql(ti.getSelectName()));
    }

    private VariantImportPipelineJob getPipelineJob()
    {
        return (VariantImportPipelineJob)getJob();
    }
}
