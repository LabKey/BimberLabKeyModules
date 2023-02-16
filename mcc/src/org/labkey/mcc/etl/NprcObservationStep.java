package org.labkey.mcc.etl;

import au.com.bytecode.opencsv.CSVReader;
import org.apache.commons.lang3.StringUtils;
import org.apache.xmlbeans.XmlException;
import org.jetbrains.annotations.NotNull;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.ConvertHelper;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.di.TaskRefTask;
import org.labkey.api.pipeline.PipeRoot;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.PipelineService;
import org.labkey.api.pipeline.RecordedActionSet;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.query.DuplicateKeyException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.InvalidKeyException;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.QueryUpdateServiceException;
import org.labkey.api.query.UserSchema;
import org.labkey.api.reader.Readers;
import org.labkey.api.util.GUID;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.writer.ContainerUser;
import org.labkey.mcc.MccSchema;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class NprcObservationStep implements TaskRefTask
{
    protected ContainerUser _containerUser;

    @Override
    public RecordedActionSet run(@NotNull PipelineJob job) throws PipelineJobException
    {
        processFile(job);

        return new RecordedActionSet();
    }

    private void processFile(PipelineJob job) throws PipelineJobException
    {
        PipeRoot root = PipelineService.get().findPipelineRoot(job.getContainer());
        File f = new File(root.getRootPath(), _settings.get(Settings.fileName.name()));
        if (!f.exists())
        {
            throw new PipelineJobException("Unable to find file: " + f.getPath());
        }

        // Query aggregated demographics:
        UserSchema studySchema = QueryService.get().getUserSchema(_containerUser.getUser(), _containerUser.getContainer(), "study");
        if (studySchema == null)
        {
            throw new PipelineJobException("Unable to find study schema: " + MccSchema.NAME);
        }

        TableInfo clinicalObs = studySchema.getTable("clinical_observations");
        if (clinicalObs == null)
        {
            throw new PipelineJobException("Unable to find table: clinical observations");
        }

        final List<Map<String, Object>> toInsert = new ArrayList<>();
        final List<Map<String, Object>> toUpdate = new ArrayList<>();
        final List<Map<String, Object>> toDelete = new ArrayList<>();
        try (CSVReader reader = new CSVReader(Readers.getReader(f), ','))
        {
            String[] line;
            int idx = 0;
            while ((line = reader.readNext()) != null)
            {
                idx++;

                if (idx == 1)
                {
                    continue;
                }

                String id = line[0];
                Date date = ConvertHelper.convert(line[1], Date.class);

                for (String category : Arrays.asList("Medical History", "Fertility Status", "Infant History", "Current Housing Status", "Availability"))
                {
                    Map<String, Object> row = new CaseInsensitiveHashMap<>();
                    row.put("Id", id);
                    row.put("date", date);
                    row.put("category", category);
                    row.put("QCStateLabel", "Completed");

                    String observation;
                    switch (category)
                    {
                        case "Availability":
                            observation = convertToAvailability(line[3]);
                            break;
                        case "Current Housing Status":
                            observation = convertToHousingStatus(line[4]);
                            break;
                        case "Infant History":
                            observation = convertToInfantHistory(line[5]);
                            break;
                        case "Fertility Status":
                            observation = convertToFertilityStatus(line[6]);
                            break;
                        case "Medical History":
                            observation = convertToMedicalHistory(line[7]);
                            break;
                        default:
                            throw new IllegalStateException("Should never hit this");
                    }

                    observation = StringUtils.trimToNull(observation);

                    row.put("observation", observation);

                    SimpleFilter filter = new SimpleFilter(FieldKey.fromString("Id"), id).addCondition(FieldKey.fromString("date"), date, CompareType.DATE_EQUAL);
                    filter.addCondition(FieldKey.fromString("category"), category);

                    String objectId = new TableSelector(clinicalObs, PageFlowUtil.set("objectid"), filter, null).getObject(String.class);
                    if (observation == null && objectId != null)
                    {
                        toDelete.add(row);
                        continue;
                    }

                    if (objectId != null)
                    {
                        row.put("objectId", objectId);
                        toUpdate.add(row);
                    }
                    else
                    {
                        row.put("objectid", new GUID() + "-" + category);
                        toInsert.add(row);
                    }
                }
            }

            if (!toDelete.isEmpty())
            {
                job.getLogger().info("Deleting " + toDelete.size() + " rows");
                try
                {
                    clinicalObs.getUpdateService().deleteRows(_containerUser.getUser(), _containerUser.getContainer(), toDelete, null, null);
                }
                catch (InvalidKeyException | BatchValidationException | QueryUpdateServiceException | SQLException e)
                {
                    throw new PipelineJobException(e);
                }
            }

            if (!toInsert.isEmpty())
            {
                job.getLogger().info("Inserting " + toInsert.size() + " rows");
                try
                {
                    BatchValidationException bve = new BatchValidationException();
                    clinicalObs.getUpdateService().insertRows(_containerUser.getUser(), _containerUser.getContainer(), toInsert, bve, null, null);
                    if (bve.hasErrors())
                    {
                        throw bve;
                    }
                }
                catch (QueryUpdateServiceException | DuplicateKeyException | SQLException | BatchValidationException e)
                {
                    throw new PipelineJobException(e);
                }
            }
            else
            {
                job.getLogger().info("No clinical observation inserts");
            }

            if (!toUpdate.isEmpty())
            {
                job.getLogger().info("Updating " + toUpdate.size() + " rows");
                try
                {
                    List<Map<String, Object>> oldKeys = toUpdate.stream().map(x -> Map.of("objectid", x.get("objectid"))).collect(Collectors.toList());
                    clinicalObs.getUpdateService().updateRows(_containerUser.getUser(), _containerUser.getContainer(), toUpdate, oldKeys, null, null);
                }
                catch (QueryUpdateServiceException | SQLException | BatchValidationException | InvalidKeyException e)
                {
                    throw new PipelineJobException(e);
                }
            }
            else
            {
                job.getLogger().info("No clinical observation updates");
            }
        }
        catch (IOException e)
        {
            throw new PipelineJobException(e);
        }
    }

    private String convertToHousingStatus(String value)
    {
        return convertRawValue(value, Arrays.asList("singly housed", "natal family group", "active breeding", "social non breeding"));
    }

    private String convertToAvailability(String value)
    {
        return convertRawValue(value, Arrays.asList("not available for transfer", "available for transfer"));
    }

    private String convertToInfantHistory(String value)
    {
        return convertRawValue(value, Arrays.asList("no experience", "sibling experience only", "non successful offspring", "successful rearing of offspring"));
    }

    private String convertToFertilityStatus(String value)
    {
        return convertRawValue(value, Arrays.asList("no mating opportunity", "mated no offspring produced", "successful offspring produced", "hormonal birth control", "sterilized"));
    }

    private String convertToMedicalHistory(String value)
    {
        return convertRawValue(value, Arrays.asList("naive animal", "animal assigned to invasive study"));
    }

    private String convertRawValue(String value, List<String> values)
    {
        value = StringUtils.trimToNull(value);
        if (value == null)
        {
            return null;
        }

        int idx = ConvertHelper.convert(value, Integer.class);
        if (idx >= values.size())
        {
            throw new IllegalStateException("Unexpected value: " + value);
        }

        return values.get(idx);
    }

    protected final Map<String, String> _settings = new CaseInsensitiveHashMap<>();

    @Override
    public List<String> getRequiredSettings()
    {
        return Collections.unmodifiableList(List.of(Settings.fileName.name()));
    }

    private enum Settings
    {
        fileName()
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
