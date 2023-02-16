package org.labkey.mgap.columnTransforms;

import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.util.GUID;
import org.labkey.api.util.PageFlowUtil;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JBrowseHumanSessionTransform extends JBrowseSessionTransform
{
    @Override
    protected String getDataFileUrlField()
    {
        return "liftedVcfId/dataid/DataFileUrl";
    }

    @Override
    protected String getDatabaseName()
    {
        return "mGAP Release: " + getInputValue("version") + " Lifted To GRCh37";
    }

    @Override
    protected String getTrackJson()
    {
        return "{\"category\":\"mGAP Variant Catalog\",\"visibleByDefault\": true,\"additionalFeatureMsg\":\"<h2>**These annotations are created by lifting the macaque variants to human coordinates, and must be viewed in that context.</h2>\"}";
    }

    @Override
    protected Object doTransform(Object inputValue)
    {
        Object input = getInputValue(getDataFileUrlField());
        if (input == null)
        {
            getStatusLogger().info("Input value not found for human jbrowse session, skipping");
            return null;
        }

        return super.doTransform(inputValue);
    }

    @Override
    protected String getGenomeIdField()
    {
        return "liftedVcfId/library_id/name";
    }

    @Override
    protected void addTracks(final String databaseId, String releaseId)
    {
        try
        {
            getStatusLogger().info("possibly creating track for: " + getDatabaseName());
            String jsonFile = getOrCreateJsonFile();
            getOrCreateDatabaseMember(databaseId, jsonFile);
        }
        catch(Exception e)
        {
            getStatusLogger().error(e.getMessage(), e);
        }
    }

    private String getOrCreateJsonFile()
    {
        int outputFileId = getOrCreateOutputFile(getInputValue(getDataFileUrlField()), getInputValue("objectId"), getDatabaseName());

        //determine if there is already a JSONfile for this outputfile
        TableSelector ts1 = new TableSelector(getJsonFiles(), PageFlowUtil.set("objectid"), new SimpleFilter(FieldKey.fromString("outputfile"), outputFileId), null);
        if (ts1.exists())
        {
            getStatusLogger().info("jsonfile already exists for output: " + outputFileId);
            return ts1.getArrayList(String.class).get(0);
        }

        try
        {
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
            row.put("trackJson", getTrackJson());

            getStatusLogger().info("creating jsonfile for output: " + outputFileId);
            List<Map<String, Object>> rows = jsonFiles.getUpdateService().insertRows(getContainerUser().getUser(), getContainerUser().getContainer(), List.of(row), new BatchValidationException(), null, new HashMap<>());

            return (String) rows.get(0).get("objectid");
        }
        catch (Exception e)
        {
            getStatusLogger().error("Error creating jsonfile for ID: " + outputFileId, e);
        }

        return null;
    }

    @Override
    protected String getSessionJson()
    {
        return "{\"trackSelector\": {\"sortHierarchical\": false},\"defaultLocation\":\"7:117105838..117144362\"}";
    }
}
