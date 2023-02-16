package org.labkey.mgap.etl;

import org.apache.xmlbeans.XmlException;
import org.jetbrains.annotations.NotNull;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.di.DataIntegrationService;
import org.labkey.api.di.TaskRefTask;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedActionSet;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.UserSchema;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.writer.ContainerUser;
import org.labkey.mgap.mGAPSchema;
import org.labkey.remoteapi.CommandException;
import org.labkey.remoteapi.query.DeleteRowsCommand;
import org.labkey.remoteapi.query.Filter;
import org.labkey.remoteapi.query.InsertRowsCommand;
import org.labkey.remoteapi.query.SelectRowsCommand;
import org.labkey.remoteapi.query.SelectRowsResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PopulateIdsStep implements TaskRefTask
{
    protected final Map<String, String> _settings = new CaseInsensitiveHashMap<>();
    protected ContainerUser _containerUser;

    private enum Settings
    {
        remoteSource(),
        targetSchema(),
        targetQuery(),
        targetColumn(),
        targetAliasColumn()
    }

    @Override
    public RecordedActionSet run(@NotNull PipelineJob job) throws PipelineJobException
    {
        UserSchema sourceSchema = QueryService.get().getUserSchema(_containerUser.getUser(), _containerUser.getContainer(), mGAPSchema.NAME);
        if (sourceSchema == null)
        {
            throw new PipelineJobException("Unable to find source schema: " + mGAPSchema.NAME);
        }

        TableInfo sourceTi = sourceSchema.getTable(mGAPSchema.TABLE_ANIMAL_MAPPING);
        if (sourceTi == null)
        {
            throw new PipelineJobException("Unable to find table: " + mGAPSchema.TABLE_ANIMAL_MAPPING);
        }

        TableSelector ts = new TableSelector(sourceTi, PageFlowUtil.set("subjectname", "externalAlias"));
        Map<String, String> localIdToAlias = new HashMap<>();
        ts.forEachResults(rs -> {
            localIdToAlias.put(rs.getString(FieldKey.fromString("subjectname")), rs.getString(FieldKey.fromString("externalAlias")));
        });

        DataIntegrationService.RemoteConnection rc = DataIntegrationService.get().getRemoteConnection(_settings.get(Settings.remoteSource.name()), _containerUser.getContainer(), job.getLogger());
        if (rc == null)
        {
            throw new PipelineJobException("Unable to find remote connection: " + _settings.get(Settings.remoteSource.name()));
        }

        try
        {
            //first select all rows from remote table
            SelectRowsCommand sr = new SelectRowsCommand(_settings.get(Settings.targetSchema.name()), _settings.get(Settings.targetQuery.name()));
            sr.setColumns(Arrays.asList(_settings.get(Settings.targetColumn.name()), _settings.get(Settings.targetAliasColumn.name())));
            sr.addFilter(new Filter(_settings.get(Settings.targetColumn.name()), null, Filter.Operator.NONBLANK));

            //and select all rows from our source table
            SelectRowsResponse srr = sr.execute(rc.connection, rc.remoteContainer);
            Map<String, String> existingIds = new HashMap<>();
            srr.getRows().forEach(x -> existingIds.put(String.valueOf(x.get(_settings.get(Settings.targetColumn.name()))), String.valueOf(x.get(_settings.get(Settings.targetAliasColumn.name())))));

            //identify remote deletes
            Set<String> toDelete = new HashSet<>();
            existingIds.forEach((id, alias) -> {
                if (!localIdToAlias.containsKey(id) || !localIdToAlias.get(id).equals(alias))
                {
                    toDelete.add(id);
                }
            });

            if (!toDelete.isEmpty())
            {
                DeleteRowsCommand dr = new DeleteRowsCommand(_settings.get(Settings.targetSchema.name()), _settings.get(Settings.targetQuery.name()));
                toDelete.forEach(x -> {
                    dr.addRow(toRow(x, null));
                });

                dr.execute(rc.connection, rc.remoteContainer);

                job.getLogger().info("deleted " + dr.getRows().size() + " remote rows");
                toDelete.forEach(existingIds::remove);
            }
            else
            {
                job.getLogger().info("no rows to delete");
            }

            //then remote inserts
            Set<String> toInsert = new HashSet<>();
            localIdToAlias.forEach((id, alias) -> {
                if (!existingIds.containsKey(id))
                {
                    toInsert.add(id);
                }
            });

            if (!toInsert.isEmpty())
            {
                InsertRowsCommand dr = new InsertRowsCommand(_settings.get(Settings.targetSchema.name()), _settings.get(Settings.targetQuery.name()));
                toInsert.forEach(x -> {
                    dr.addRow(toRow(x, localIdToAlias));
                });

                dr.execute(rc.connection, rc.remoteContainer);

                job.getLogger().info("inserted " + dr.getRows().size() + " remote rows");
            }
            else
            {
                job.getLogger().info("no rows to insert");
            }
        }
        catch (CommandException | IOException e)
        {
            throw new PipelineJobException(e);
        }

        return new RecordedActionSet();
    }

    private Map<String, Object> toRow(String sampleName, Map<String, String> localIdToAlias)
    {
        Map<String, Object> ret = new CaseInsensitiveHashMap<>();
        ret.put(_settings.get(Settings.targetColumn.name()), sampleName);
        if (localIdToAlias != null)
            ret.put(_settings.get(Settings.targetAliasColumn.name()), localIdToAlias.get(sampleName));

        return ret;
    }

    @Override
    public List<String> getRequiredSettings()
    {
        return Collections.unmodifiableList(Arrays.asList(Settings.remoteSource.name(), Settings.targetSchema.name(), Settings.targetQuery.name()));
    }

    @Override
    public void setSettings(Map<String, String> settings) throws XmlException
    {
        _settings.putAll(settings);
    }

    @Override
    public void setContainerUser(ContainerUser containerUser)
    {
        _containerUser = containerUser;
    }
}
