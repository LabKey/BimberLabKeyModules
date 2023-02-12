package org.labkey.variantdb.pipeline;

import htsjdk.samtools.util.CloseableIterator;
import htsjdk.tribble.AbstractFeatureReader;
import htsjdk.tribble.FeatureReader;
import htsjdk.tribble.TribbleException;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFCodec;
import htsjdk.variant.vcf.VCFHeader;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
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
import org.labkey.api.util.FileType;
import org.labkey.api.util.GUID;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.variantdb.VariantDBSchema;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
public class DbSnpImportTask extends PipelineJob.Task<DbSnpImportTask.Factory>
{
    protected DbSnpImportTask(Factory factory, PipelineJob job)
    {
        super(factory, job);
    }

    public static class Factory extends AbstractTaskFactory<AbstractTaskFactorySettings, Factory>
    {
        public Factory()
        {
            super(DbSnpImportTask.class);
            //setLocation("webserver");
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
            return Arrays.asList("dbSNP Import");
        }

        public PipelineJob.Task createTask(PipelineJob job)
        {
            return new DbSnpImportTask(this, job);
        }

        public boolean isJobComplete(PipelineJob job)
        {
            return false;
        }
    }

    public RecordedActionSet run() throws PipelineJobException
    {
        String batchId = new GUID().toString();

        try
        {
            List<RecordedAction> actions = new ArrayList<>();

            //TODO: restore this
            //actions.add(processVariants(batchId));
            actions.add(processClinVar(batchId));

            return new RecordedActionSet(actions);
        }
        catch (PipelineJobException e)
        {
            //roll back changes in case of failure
            getJob().getLogger().info("cleaning up partial records");
            deleteAllFromTable(batchId, VariantDBSchema.TABLE_REFERENCE_VARIANTS);
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

    private RecordedAction processVariants(String batchId) throws PipelineJobException
    {
        RecordedAction action = new RecordedAction("Loading variants");
        action.setStartTime(new Date());

        String remoteURL = "/snp/organisms/" + getPipelineJob().getRemoteDirName() + "/VCF/00-common_all.vcf.gz";
        File localFile = copyFileLocally(remoteURL);

        processVariantsFromFile(batchId, localFile);

        localFile.delete();

        action.setEndTime(new Date());

        return action;
    }

    private void processVariantsFromFile(String batchId, File localFile) throws PipelineJobException
    {        
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
                new BaseColumnInfo("referenceVariantId", JdbcType.VARCHAR),
                new BaseColumnInfo("referenceAlleleId", JdbcType.VARCHAR)
        ));

        TempTableInfo refVariantsTemp = createTempTable(VariantDBSchema.getInstance().getSchema(), "refVariants", Arrays.asList(
                new BaseColumnInfo("objectid", JdbcType.VARCHAR),
                new BaseColumnInfo("dbSnpAccession", JdbcType.VARCHAR)
        ));

        TempTableInfo refVariantAllelesTemp = createTempTable(VariantDBSchema.getInstance().getSchema(), "refVariantAlleles", Arrays.asList(
                new BaseColumnInfo("objectid", JdbcType.VARCHAR),
                new BaseColumnInfo("dbSnpAccession", JdbcType.VARCHAR),
                new BaseColumnInfo("referencePosition", JdbcType.INTEGER),
                new BaseColumnInfo("reference", JdbcType.VARCHAR),
                new BaseColumnInfo("allele", JdbcType.VARCHAR),
                new BaseColumnInfo("status", JdbcType.VARCHAR)
        ));

        try (FeatureReader<VariantContext> reader = AbstractFeatureReader.getFeatureReader(localFile.getAbsolutePath(), new VCFCodec(), false))
        {
            VCFHeader header = (VCFHeader)reader.getHeader();

            CaseInsensitiveHashMap<String> batchRow = new CaseInsensitiveHashMap<>();
            batchRow.put("batchId", batchId);
            batchRow.put("description", "Automatic import of data from the NCBI/dbSNP FTP site");
            batchRow.put("source", "dbSNP");
            batchRow.put("build", header.getMetaDataLine("dbSNP_BUILD_ID").getValue());
            batchRow.put("jobId", getJob().getJobGUID());
            Table.insert(getJob().getUser(), VariantDBSchema.getInstance().getSchema().getTable(VariantDBSchema.TABLE_UPLOAD_BATCHES), batchRow);

            String variantSql = "INSERT INTO " + variantTemp +
                    " (objectid, sequenceid, startPosition, endPosition, reference, allele, status, dbSnpAccession) " +
                    " values (?, ?, ?, ?, ?, ?, ?, ?);";

            String refVariantSql = "INSERT INTO " + refVariantsTemp +
                    " (objectid, dbSnpAccession) " +
                    " values (?, ?);";

            String refVariantAlleleSql = "INSERT INTO " + refVariantAllelesTemp +
                    " (objectid, dbSnpAccession, referencePosition, reference, allele, status) " +
                    " values (?, ?, ?, ?, ?, ?);";

            try (Connection connection = DbScope.getLabKeyScope().getConnection();
                 PreparedStatement variantPs = connection.prepareStatement(variantSql);
                 PreparedStatement refVariantPs = connection.prepareStatement(refVariantSql);
                 PreparedStatement refVariantAllelePs = connection.prepareStatement(refVariantAlleleSql)
            )
            {

                final int batchSize = 10000;
                int count = 0;

                try (CloseableIterator<VariantContext> i = reader.iterator())
                {
                    while (i.hasNext())
                    {
                        try
                        {
                            VariantContext f = i.next();
                            f.fullyDecode(header, true);

                            if (!f.hasAttribute("RS") || f.getAttribute("RS") == null)
                            {
                                continue;
                            }

                            String dbSnpAccession = "rs" + f.getAttributeAsString("RS", null);

                            refVariantPs.setString(1, new GUID().toString());
                            refVariantPs.setString(2, dbSnpAccession);
                            refVariantPs.execute();

                            for (Allele a : f.getAlleles())
                            {
                                variantPs.setString(1, new GUID().toString()); //objectid
                                variantPs.setInt(2, resolveSequenceId(f.getChr())); //sequenceid
                                variantPs.setInt(3, f.getStart());  //start pos
                                variantPs.setInt(4, f.getEnd());  //end pos
                                variantPs.setString(5, f.getReference().getBaseString()); //ref
                                variantPs.setString(6, a.getBaseString()); //allele
                                variantPs.setString(7, "Reference");
                                variantPs.setString(8, dbSnpAccession);  //dbSnpAccession
                                variantPs.execute();

                                refVariantAllelePs.setString(1, new GUID().toString()); //objectid
                                refVariantAllelePs.setString(2, dbSnpAccession); //dbSnpAccession
                                refVariantAllelePs.setInt(3, f.getStart());  //referencePosition
                                refVariantAllelePs.setString(4, f.getReference().getBaseString()); //ref
                                refVariantAllelePs.setString(5, a.getBaseString()); //allele
                                refVariantAllelePs.setString(6, "Reference");  //status
                                refVariantAllelePs.execute();

                                if (++count % batchSize == 0)
                                {
                                    getJob().getLogger().info("processed " + NumberFormat.getInstance().format(count) + " variants");
                                    insertRefVariantsFromTempTable(refVariantsTemp, batchId);
                                    insertRefVariantAllelesFromTempTable(refVariantAllelesTemp, batchId);
                                    insertVariantsFromTempTable(variantTemp, batchId);
                                }
                            }
                        }
                        catch (TribbleException e)
                        {
                            //ignore for now...
                            getJob().getLogger().error(e);
                        }
                    }

                    getJob().getLogger().info("processed " + NumberFormat.getInstance().format(count) + " variants");
                    insertRefVariantsFromTempTable(refVariantsTemp, batchId);
                    insertRefVariantAllelesFromTempTable(refVariantAllelesTemp, batchId);
                    insertVariantsFromTempTable(variantTemp, batchId);
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
            refVariantsTemp.delete();
            refVariantAllelesTemp.delete();
        }
    }

    private File copyFileLocally(String remoteURL) throws PipelineJobException
    {
        String baseUrl = "ftp.ncbi.nlm.nih.gov";
        getJob().getLogger().info("reading file: " + baseUrl + remoteURL);

        File localFile = new File(getJob().getLogFile().getParentFile(), FilenameUtils.getName(remoteURL));
        if (localFile.exists())
        {
            getJob().getLogger().info("file has already been downloaded");
            localFile.deleteOnExit();
        }
        else
        {
            getJob().getLogger().info("copying file locally: " + localFile.getName());
            localFile.deleteOnExit();
            try
            {
                FTPClient ftpClient = new FTPClient();
                try
                {
                    ftpClient.connect(baseUrl);
                    if (!FTPReply.isPositiveCompletion(ftpClient.getReplyCode()))
                    {
                        ftpClient.disconnect();
                        throw new PipelineJobException("FTP server refused connection.");
                    }

                    ftpClient.login("anonymous", "");
                    ftpClient.enterLocalPassiveMode();
                    ftpClient.setFileType(FTPClient.BINARY_FILE_TYPE);

                    try (OutputStream outputStream = new FileOutputStream(localFile))
                    {
                        FTPFile remoteFile = ftpClient.mlistFile(remoteURL);
                        if (remoteFile == null)
                        {
                            throw new PipelineJobException("Unable to locate remote file: " + remoteURL);
                        }

                        if (remoteFile.isSymbolicLink())
                        {
                            remoteURL = remoteFile.getLink();
                        }
                        ftpClient.changeWorkingDirectory(FilenameUtils.getPath(remoteURL));

                        try (InputStream is = ftpClient.retrieveFileStream(FilenameUtils.getName(remoteURL)))
                        {
                            if (is == null)
                            {
                                throw new PipelineJobException("unable to open input stream");
                            }

                            IOUtils.copy(is, outputStream);
                            if (remoteFile.getSize() != localFile.length())
                            {
                                getJob().getLogger().warn("file size did not match original file from FTP site");
                            }
                            //ftpClient.retrieveFile(remoteURL, outputStream);
                        }
                        getJob().getLogger().info("copy complete");
                    }

                    ftpClient.completePendingCommand();
                    ftpClient.logout();
                }
                finally
                {
                    if (ftpClient.isConnected())
                    {
                        ftpClient.disconnect();
                    }
                }
            }
            catch (IOException e)
            {
                throw new PipelineJobException(e);
            }
        }

        return localFile;
    }

    private void insertRefVariantsFromTempTable(TempTableInfo ti, String batchId)
    {
        SQLFragment sql = new SQLFragment("INSERT INTO " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_REFERENCE_VARIANTS + "\n" +
                " (dbSnpAccession, objectid, batchId, created, createdBy, modified, modifiedBy)\n" +
                //it doesnt matter if we lose the previously assigned GUIDs, since nothing references them yet
                " SELECT DISTINCT t.dbSnpAccession, max(t.objectId), ?, {fn now()}, ?, {fn now()}, ?\n" +
                " FROM " + ti.getSelectName() + " t\n" +
                " LEFT JOIN " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_REFERENCE_VARIANTS + " r ON (r.dbSnpAccession = t.dbSnpAccession)\n" +
                " WHERE r.objectid IS NULL\n" +
                " GROUP BY t.dbSnpAccession;", batchId, getJob().getUser().getUserId(), getJob().getUser().getUserId()
        );

        int changed = new SqlExecutor(ti.getSchema()).execute(sql);
        getJob().getLogger().info("\tinserted " + changed + " new reference variants");
        new SqlExecutor(ti.getSchema()).execute(ti.getSqlDialect().getTruncateSql(ti.getSelectName()));
    }

    private void insertRefVariantAllelesFromTempTable(TempTableInfo ti, String batchId)
    {
        SQLFragment sql = new SQLFragment("INSERT INTO " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_REFERENCE_VARIANT_ALLELES + "\n" +
                " (objectid, referenceVariantId, referencePosition, reference, allele, status, batchId, created, createdBy, modified, modifiedBy)\n" +
                " SELECT t.objectid, rv.objectid as referenceVariantId, t.referencePosition, t.reference, t.allele, t.status, ?, {fn now()}, ?, {fn now()}, ?\n" +
                " FROM " + ti.getSelectName() + " t\n" +
                " LEFT JOIN " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_REFERENCE_VARIANTS + " rv ON (rv.dbSnpAccession = t.dbSnpAccession)\n" +
                " LEFT JOIN " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_REFERENCE_VARIANT_ALLELES + " r ON (rv.objectid = r.referenceVariantId AND r.allele = t.allele)\n" +
                " WHERE r.objectid IS NULL;", batchId, getJob().getUser().getUserId(), getJob().getUser().getUserId()
        );

        int changed = new SqlExecutor(ti.getSchema()).execute(sql);
        getJob().getLogger().info("\tinserted " + changed + " new reference alleles");
        new SqlExecutor(ti.getSchema()).execute(ti.getSqlDialect().getTruncateSql(ti.getSelectName()));
    }

    private void insertVariantsFromTempTable(TempTableInfo ti, String batchId)
    {
        SQLFragment sql = new SQLFragment("INSERT INTO " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANTS + "\n" +
                " (objectid, sequenceid, startPosition, endPosition, reference, allele, referenceVariantId, referenceAlleleId, batchId, created, createdBy, modified, modifiedBy)\n" +
                " SELECT t.objectid, t.sequenceid, t.startPosition, t.endPosition, t.reference, t.allele, rv.objectId as referenceVariantId, r.objectid as referenceAlleleId, ?, {fn now()}, ?, {fn now()}, ?\n" +
                " FROM " + ti.getSelectName() + " t\n" +
                " LEFT JOIN " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_REFERENCE_VARIANTS + " rv ON (rv.dbSnpAccession = t.dbSnpAccession)\n" +
                " LEFT JOIN " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_REFERENCE_VARIANT_ALLELES + " r ON (rv.objectid = r.referenceVariantId AND r.allele = t.allele)\n" +
                " LEFT JOIN variantdb.Variants v ON (t.sequenceid = v.sequenceid AND v.startposition = t.startposition AND v.endposition = t.endposition AND v.allele = t.allele)\n" +
                " WHERE v.objectid IS NULL;", batchId, getJob().getUser().getUserId(), getJob().getUser().getUserId()
        );

        int changed = new SqlExecutor(ti.getSchema()).execute(sql);
        getJob().getLogger().info("\tinserted " + changed + " new variants");
        new SqlExecutor(ti.getSchema()).execute(ti.getSqlDialect().getTruncateSql(ti.getSelectName()));
    }

    private RecordedAction processClinVar(String batchId) throws PipelineJobException
    {
        RecordedAction action = new RecordedAction("Loading list of clinvar variants");
        action.setStartTime(new Date());

        String remoteURL = "/snp/organisms/" + getPipelineJob().getRemoteDirName() + "/VCF/clinical_vcf_set/clinvar.vcf.gz";
        File localFile = copyFileLocally(remoteURL);
        processVariantsFromFile(batchId, localFile);

        //create temp table so we can assign IDs, then insert
        List<ColumnInfo> cols = new ArrayList<>();
        cols.add(new BaseColumnInfo("objectid", JdbcType.VARCHAR));
        cols.add(new BaseColumnInfo("sequenceid", JdbcType.INTEGER));
        cols.add(new BaseColumnInfo("startPosition", JdbcType.INTEGER));
        cols.add(new BaseColumnInfo("endPosition", JdbcType.INTEGER));
        cols.add(new BaseColumnInfo("reference", JdbcType.VARCHAR));
        cols.add(new BaseColumnInfo("allele", JdbcType.VARCHAR));
        cols.add(new BaseColumnInfo("dbSnpAccession", JdbcType.VARCHAR));
        cols.add(new BaseColumnInfo("attributeName", JdbcType.VARCHAR));
        cols.add(new BaseColumnInfo("attributeValue", JdbcType.VARCHAR));
        cols.add(new BaseColumnInfo("referenceVariantId", JdbcType.VARCHAR));
        cols.add(new BaseColumnInfo("referenceAlleleId", JdbcType.VARCHAR));


        TempTableInfo variantTemp = createTempTable(VariantDBSchema.getInstance().getSchema(), "clinvar", cols);

        try (FeatureReader reader = AbstractFeatureReader.getFeatureReader(localFile.getAbsolutePath(), new VCFCodec(), false))
        {
            VCFHeader header = (VCFHeader)reader.getHeader();
            String sql = "INSERT INTO " + variantTemp +
                    " (objectid, dbSnpAccession, allele, attributeName, attributeValue) " +
                    " values (?, ?, ?, ?, ?);";

            try (Connection connection = DbScope.getLabKeyScope().getConnection();PreparedStatement ps = connection.prepareStatement(sql))
            {
                final int batchSize = 10000;
                int count = 0;

                try (CloseableIterator<VariantContext> i = reader.iterator())
                {
                    while (i.hasNext())
                    {
                        try
                        {
                            VariantContext f = i.next();
                            f.fullyDecode(header, true);

                            if (!f.hasAttribute("RS") || f.getAttribute("RS") == null)
                            {
                                continue;
                            }

                            String dbSnpAccession = "rs" + f.getAttributeAsString("RS", null);

                            List<Allele> alleles = f.getAlleles();
                            if (f.getAttribute("CLNALLE") == null)
                            {
                                continue;
                            }
                            else if (f.getAttribute("CLNALLE") instanceof List)
                            {
                                int index = 0;
                                for (Object idx : (List)f.getAttribute("CLNALLE"))
                                {
                                    Integer alleleIdx = Integer.parseInt(String.valueOf(idx));
                                    if (alleleIdx >= 0)
                                    {
                                        processClinvarAllele(index, f, alleles.get(alleleIdx), ps, dbSnpAccession);
                                    }
                                    index++;
                                }
                            }
                            else
                            {
                                processClinvarAllele(0, f, alleles.get(1), ps, dbSnpAccession);
                            }

                            if (++count % batchSize == 0)
                            {
                                getJob().getLogger().info("processed " + NumberFormat.getInstance().format(count) + " clinvar variants");
                                insertClinvarDataFromTempTable(variantTemp, batchId);
                            }
                        }
                        catch (TribbleException e)
                        {
                            //ignore for now...
                            getJob().getLogger().error(e);
                        }
                    }

                    getJob().getLogger().info("processed " + NumberFormat.getInstance().format(count) + " clinvar variants");
                    insertClinvarDataFromTempTable(variantTemp, batchId);
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

            if (localFile.exists())
            {
                localFile.delete();
            }
        }

        action.setEndTime(new Date());

        return action;
    }

    private Map<String, String> _clinsigMap = new HashMap<String, String>()
    {
        {
            put("0", "Uncertain significance");
            put("1", "Not provided");
            put("2", "Benign");
            put("3", "Likely benign");
            put("4", "Likely pathogenic");
            put("5", "Pathogenic");
            put("6", "drug-response");
            put("7", "histocompatibility");
            put("255", "other");
        }
    };

    private void processClinvarAllele(int idx, VariantContext ctx, Allele a, PreparedStatement ps, String dbSnpAccession) throws SQLException
    {
        // see:
        // http://www.ncbi.nlm.nih.gov/variation/docs/faq/
        for (String attribute : Arrays.asList("CLNSIG", "CLNDBN", "CLNDSDB"))
        {
            if (ctx.hasAttribute(attribute))
            {
                ps.setString(1, new GUID().toString()); //objectid
                ps.setString(2, dbSnpAccession); //dbSnpAccession
                ps.setString(3, a.getBaseString()); //allele
                ps.setString(4, attribute); //attribute

                Object value;
                if (ctx.getAttribute(attribute) instanceof List)
                {
                    List<Object> attList = (List)ctx.getAttribute(attribute);
                    value = attList.get(idx); //value
                }
                else
                {
                    value = ctx.getAttribute(attribute);
                }

                //transform value:
                if ("CLNDSDB".equals(attribute) && "ClinVar".equalsIgnoreCase(String.valueOf(value)))
                {
                    if (ctx.hasAttribute("CLNDSDBID"))
                    {
                        String id = ctx.getAttributeAsString("CLNDSDBID", null);
                        if (id != null)
                        {
                            ps.setString(4, "ClinVarId");
                            value = id;
                        }
                        else
                        {
                            continue;
                        }
                    }
                }
                else if ("CLNSIG".equals(attribute))
                {
                    value = _clinsigMap.get(value);
                }

                if (value != null)
                {
                    ps.setObject(5, value); //value
                    ps.execute();
                }
            }
            else
            {
                getJob().getLogger().info("attribute not found: " + attribute);
            }
        }
    }

    private void insertClinvarDataFromTempTable(TempTableInfo ti, String batchId)
    {
        //first create any missing attribute types
        SQLFragment attributeSql = new SQLFragment("INSERT INTO " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANT_ATTRIBUTE_TYPES + "\n" +
                " (name)\n" +
                " SELECT DISTINCT t.attributeName\n" +
                " FROM " + ti.getSelectName() + " t\n" +
                " LEFT JOIN " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANT_ATTRIBUTE_TYPES + " r ON (r.name = t.attributeName)\n" +
                " WHERE r.rowid IS NULL;"
        );
        int changed1 = new SqlExecutor(ti.getSchema()).execute(attributeSql);
        getJob().getLogger().info("\tinserted " + changed1 + " new variant attribute types");


        new SqlExecutor(ti.getSchema()).execute(new SQLFragment("UPDATE " + ti.getSelectName() + "\n" +
                " SET referenceVariantId = (SELECT rv.objectid FROM " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_REFERENCE_VARIANTS + " rv WHERE rv.dbSnpAccession = " + ti.getSelectName() + ".dbSnpAccession)"
        ));

        new SqlExecutor(ti.getSchema()).execute(new SQLFragment("UPDATE " + ti.getSelectName() + "\n" +
                " SET referenceAlleleId = (SELECT rv.objectid FROM " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_REFERENCE_VARIANT_ALLELES + " rv WHERE rv.referenceVariantId = " + ti.getSelectName() + ".referenceVariantId AND rv.allele = " + ti.getSelectName() + ".allele)"
        ));

        SQLFragment sql = new SQLFragment("INSERT INTO " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANT_ATTRIBUTES + "\n" +
                " (referenceVariantId, referenceAlleleId, attributeId, value, batchId, created, createdBy, modified, modifiedBy)\n" +
                " SELECT t.referenceVariantId, t.referenceAlleleId, at.rowid, t.attributeValue, ?, {fn now()}, ?, {fn now()}, ?\n" +
                " FROM " + ti.getSelectName() + " t\n" +
                " LEFT JOIN " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANT_ATTRIBUTE_TYPES + " at ON (at.name = t.attributeName)\n" +
                " LEFT JOIN " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_VARIANT_ATTRIBUTES + " va ON (\n" +
                    "va.referenceVariantId = t.referenceVariantId AND " +
                    "va.referenceAlleleId = t.referenceAlleleId AND " +
                    "va.attributeid = at.rowid AND " +
                    "va.value = t.attributeValue" +
                ")\n" +
                " WHERE va.rowid IS NULL AND t.referenceVariantId is not null AND  t.referenceAlleleId is not null;", batchId, getJob().getUser().getUserId(), getJob().getUser().getUserId()
        );

        int changed = new SqlExecutor(ti.getSchema()).execute(sql);
        getJob().getLogger().info("\tinserted " + changed + " new clinvar variant attributes");
        new SqlExecutor(ti.getSchema()).execute(ti.getSqlDialect().getTruncateSql(ti.getSelectName()));
    }

    private Map<String, Integer> _cachedReferences;

    private int resolveSequenceId(String refName) throws PipelineJobException
    {
        if (_cachedReferences == null)
        {
            _cachedReferences = new CaseInsensitiveHashMap<>();
            UserSchema us = QueryService.get().getUserSchema(getPipelineJob().getUser(), getPipelineJob().getContainer(), "sequenceanalysis");
            TableInfo ti = us.getTable("reference_library_members");
            final Map<FieldKey, ColumnInfo> cols = QueryService.get().getColumns(ti, PageFlowUtil.set(FieldKey.fromString("ref_nt_id"), FieldKey.fromString("ref_nt_id/name")));
            TableSelector ts = new TableSelector(ti, cols.values(), new SimpleFilter(FieldKey.fromString("library_id"), getPipelineJob().getGenomeId()), null);
            ts.forEach(new Selector.ForEachBlock<ResultSet>()
            {
                @Override
                public void exec(ResultSet object) throws SQLException
                {
                    Results rs = new ResultsImpl(object, cols);
                    _cachedReferences.put(rs.getString(FieldKey.fromString("ref_nt_id/name")), rs.getInt(FieldKey.fromString("ref_nt_id")));
                }
            });
        }

        if (_cachedReferences.containsKey(refName))
        {
            return _cachedReferences.get(refName);
        }
        else if (_cachedReferences.containsKey("chr" + refName))
        {
            return _cachedReferences.get("chr" + refName);
        }

        throw new PipelineJobException("Unable to find reference matching: " + refName + " within genomeId: " + getPipelineJob().getGenomeId());
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

    private DbSnpImportPipelineJob getPipelineJob()
    {
        return (DbSnpImportPipelineJob)getJob();
    }
}
