package org.labkey.mgap.columnTransforms;

import org.json.old.JSONObject;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.DbSchemaType;
import org.labkey.api.data.DbScope;
import org.labkey.api.data.Results;
import org.labkey.api.data.SQLFragment;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.SqlSelector;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.jbrowse.JBrowseService;
import org.labkey.api.pipeline.PipelineValidationException;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.UserSchema;
import org.labkey.api.util.GUID;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.mgap.mGAPSchema;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by bimber on 5/15/2017.
 */
public class JBrowseSessionTransform extends AbstractVariantTransform
{
    private transient TableInfo _jsonFiles;
    private transient TableInfo _databaseMembers;
    private transient TableInfo _databases;
    private transient UserSchema _jbus;

    protected String getDataFileUrlField()
    {
        return "vcfId/dataid/DataFileUrl";
    }

    @Override
    protected Object doTransform(Object inputValue)
    {
        String releaseId = (String)getInputValue("objectId");
        if (releaseId == null)
        {
            getStatusLogger().error("no release ID for variantRelease row");
        }

        Object input = getInputValue(getDataFileUrlField());
        if (input == null)
        {
            throw new IllegalArgumentException("DataFileUrl was null for key: " + getDataFileUrlField());
        }

        Integer outputFileId = getOrCreateOutputFile(input, getInputValue("objectId"), null);
        if (outputFileId != null)
        {
            //find database ID, if exists:
            //determine if there is already a JSONfile for this outputfile
            UserSchema us = getJbrowseUserSchema();
            SQLFragment sql = new SQLFragment("SELECT m." + us.getDbSchema().getScope().getSqlDialect().makeLegalIdentifier("database") + " FROM jbrowse.jsonfiles j JOIN jbrowse.database_members m ON (j.objectId = m.jsonfile) WHERE j.outputfile = ?", outputFileId);
            String databaseId = new SqlSelector(us.getDbSchema().getScope(), sql).getObject(String.class);
            if (databaseId != null)
            {
                getStatusLogger().info("jbrowse database exists using the output file: " + outputFileId);
                return databaseId;
            }
            else
            {
                try
                {
                    databaseId = new GUID().toString();
                    getStatusLogger().info("creating jbrowse database: " + databaseId);

                    //create database
                    TableInfo databases = getJbrowseUserSchema().getTable("databases");
                    CaseInsensitiveHashMap<Object> dbRow = new CaseInsensitiveHashMap<>();
                    dbRow.put("objectid", databaseId);
                    dbRow.put("name", getDatabaseName());
                    dbRow.put("description", null);
                    dbRow.put("libraryId", getLibraryId());
                    dbRow.put("temporary", false);
                    dbRow.put("container", getContainerUser().getContainer().getId());
                    dbRow.put("created", new Date());
                    dbRow.put("createdby", getContainerUser().getUser().getUserId());
                    dbRow.put("modified", new Date());
                    dbRow.put("modifiedby", getContainerUser().getUser().getUserId());
                    dbRow.put("jsonConfig", getSessionJson());

                    databases.getUpdateService().insertRows(getContainerUser().getUser(), getContainerUser().getContainer(), Arrays.asList(dbRow), new BatchValidationException(), null, new HashMap<>());
                }
                catch (Exception e)
                {
                    getStatusLogger().error("Error creating database: " + String.valueOf(inputValue), e);
                }
            }

            addTracks(databaseId, releaseId);
            recreateSession(databaseId);

            return databaseId;
        }
        else
        {
            getStatusLogger().info("output file was null for incoming release: " + releaseId);
        }

        return null;
    }

    protected String getSessionJson()
    {
        return "{\"trackSelector\": {\"sortHierarchical\": false},\"defaultLocation\":\"4:137934540..137937146\"}";
    }

    private void recreateSession(final String databaseId)
    {
        // Note: because this transction hasnt committed yet, the DB record will not exist yet, unless it was created in the previous ETL iteration
        getStatusLogger().info("recreating jbrowse session: " + databaseId);
        DbScope.getLabKeyScope().addCommitTask(() -> {
            try
            {
                JBrowseService.get().reprocessDatabase(getContainerUser().getUser(), databaseId);
            }
            catch (PipelineValidationException e)
            {
                getStatusLogger().error(e.getMessage(), e);
            }
        }, DbScope.CommitTaskOption.POSTCOMMIT);
    }

    protected void addTracks(final String databaseId, String releaseId)
    {
        //then JSONfiles/database members
        List<FieldKey> fks = Arrays.asList(
                FieldKey.fromString("trackName"),
                FieldKey.fromString("label"),
                FieldKey.fromString("category"),
                FieldKey.fromString("url"),
                FieldKey.fromString("description"),
                FieldKey.fromString("isprimarytrack"),
                FieldKey.fromString("vcfId/dataid/DataFileUrl")
        );

        TableInfo tracksPerRelease = QueryService.get().getUserSchema(getContainerUser().getUser(), getContainerUser().getContainer(), mGAPSchema.NAME).getTable(mGAPSchema.TABLE_TRACKS_PER_RELEASE);
        Map<FieldKey, ColumnInfo> colMap = QueryService.get().getColumns(tracksPerRelease, fks);

        TableSelector ts = new TableSelector(tracksPerRelease, colMap.values(), new SimpleFilter(FieldKey.fromString("releaseId"), releaseId), null);
        if (!ts.exists())
        {
            getStatusLogger().error("no track records found for release: " + releaseId);
        }

        ts.forEachResults(rs -> {
            try
            {
                getStatusLogger().info("possibly creating track for: " + rs.getString(FieldKey.fromString("trackName")));
                String jsonFile = getOrCreateJsonFile(rs, "vcfId/dataid/DataFileUrl");
                getOrCreateDatabaseMember(databaseId, jsonFile);
            }
            catch (Exception e)
            {
                getStatusLogger().error(e.getMessage(), e);
            }
        });
    }

    protected void getOrCreateDatabaseMember(String databaseId, String jsonFileId) throws Exception
    {
        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("database"), databaseId);
        filter.addCondition(FieldKey.fromString("jsonfile"), jsonFileId);

        if (new TableSelector(getDatabaseMembers(), filter, null).exists())
        {
            getStatusLogger().info("database member exists for: " + jsonFileId);
            return;
        }

        TableInfo databaseMembers = getJbrowseUserSchema().getTable("database_members");
        CaseInsensitiveHashMap<Object> row = new CaseInsensitiveHashMap<>();
        row.put("database", databaseId);
        row.put("jsonfile", jsonFileId);
        row.put("category", "Variants");
        row.put("container", getContainerUser().getContainer().getId());
        row.put("created", new Date());
        row.put("createdby", getContainerUser().getUser().getUserId());
        row.put("modified", new Date());
        row.put("modifiedby", getContainerUser().getUser().getUserId());

        getStatusLogger().info("creating database member for: " + jsonFileId);
        databaseMembers.getUpdateService().insertRows(getContainerUser().getUser(), getContainerUser().getContainer(), Arrays.asList(row), new BatchValidationException(), null, new HashMap<>());
    }

    protected TableInfo getJsonFiles()
    {
        if (_jsonFiles == null)
        {
            _jsonFiles = DbSchema.get("jbrowse", DbSchemaType.Module).getTable("jsonfiles");
        }

        return _jsonFiles;
    }

    private TableInfo getDatabaseMembers()
    {
        if (_databaseMembers == null)
        {
            _databaseMembers = DbSchema.get("jbrowse", DbSchemaType.Module).getTable("database_members");
        }

        return _databaseMembers;
    }

    protected UserSchema getJbrowseUserSchema()
    {
        if (_jbus == null)
        {
            _jbus = QueryService.get().getUserSchema(getContainerUser().getUser(), getContainerUser().getContainer(), "jbrowse");
        }

        return _jbus;
    }

    private TableInfo getDatabases()
    {
        if (_databases == null)
        {
            _databases = DbSchema.get("jbrowse", DbSchemaType.Module).getTable("databases");
        }

        return _databases;

    }

    private String getOrCreateJsonFile(Results rs, String fieldKey) throws SQLException
    {
        int outputFileId = getOrCreateOutputFile(rs.getString(FieldKey.fromString(fieldKey)), getInputValue("objectId"), rs.getString("label"));

        //determine if there is already a JSONfile for this outputfile
        TableSelector ts1 = new TableSelector(getJsonFiles(), PageFlowUtil.set("objectid"), new SimpleFilter(FieldKey.fromString("outputfile"), outputFileId), null);
        if (ts1.exists())
        {
            getStatusLogger().info("jsonfile already exists for output: " + outputFileId);
            return ts1.getArrayList(String.class).get(0);
        }

        try
        {
            boolean isDefaultTrack = rs.getObject(FieldKey.fromString("isprimarytrack")) != null && rs.getBoolean(FieldKey.fromString("isprimarytrack"));

            TableInfo jsonFiles = getJbrowseUserSchema().getTable("jsonfiles");
            CaseInsensitiveHashMap<Object> row = new CaseInsensitiveHashMap<>();
            row.put("objectid", new GUID().toString());
            row.put("outputFile", outputFileId);
            row.put("relPath", "tracks/data-" + outputFileId);
            row.put("container", getContainerUser().getContainer().getId());
            row.put("created", new Date());
            row.put("createdby", getContainerUser().getUser().getUserId());
            row.put("modified", new Date());
            row.put("modifiedby", getContainerUser().getUser().getUserId());

            if (isDefaultTrack)
            {
                row.put("trackJson", getTrackJson());
            }
            else
            {
                JSONObject meta = new JSONObject();
                if (rs.getObject(FieldKey.fromString("description")) != null)
                {
                    meta.put("Description", rs.getString(FieldKey.fromString("description")));
                }

                if (rs.getObject(FieldKey.fromString("url")) != null)
                {
                    meta.put("Website", rs.getString(FieldKey.fromString("url")));
                }

                String metaStr = meta.isEmpty() ? "" : ", metadata: " + meta.toString();
                row.put("trackJson", "{\"category\":\"" + rs.getString(FieldKey.fromString("category")) + "\",\"visibleByDefault\": false" + metaStr + "}");
            }

            getStatusLogger().info("creating jsonfile for output: " + outputFileId);
            List<Map<String, Object>> rows = jsonFiles.getUpdateService().insertRows(getContainerUser().getUser(), getContainerUser().getContainer(), Arrays.asList(row), new BatchValidationException(), null, new HashMap<>());

            return (String) rows.get(0).get("objectid");
        }
        catch (Exception e)
        {
            getStatusLogger().error("Error creating jsonfile for ID: " + outputFileId, e);
        }

        return null;
    }

    protected String getDatabaseName()
    {
        return "mGAP Release: " + getInputValue("version");
    }

    protected String getTrackJson()
    {
        return "{\"category\":\"mGAP Variant Catalog\",\"visibleByDefault\": true,\"ensemblId\":\"Macaca_mulatta\",\"additionalFeatureMsg\":\"<h2>**The annotations below are primarily derived from human data sources (not macaque), and must be viewed in that context.</h2>\"}";
    }
}
