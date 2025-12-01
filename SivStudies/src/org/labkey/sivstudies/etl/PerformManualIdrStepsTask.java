package org.labkey.sivstudies.etl;

import org.apache.xmlbeans.XmlException;
import org.jetbrains.annotations.NotNull;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.di.TaskRefTask;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.RecordedActionSet;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.query.DuplicateKeyException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.InvalidKeyException;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.QueryUpdateServiceException;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.writer.ContainerUser;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class PerformManualIdrStepsTask implements TaskRefTask
{
    protected ContainerUser _containerUser;

    @Override
    public RecordedActionSet run(@NotNull PipelineJob pipelineJob) throws PipelineJobException
    {
        pruneSivChallenges(pipelineJob);
        updateVaccineInformation(pipelineJob);
        updateChallengeAnchorDates(pipelineJob);
        updateArtInitiationAnchorDates(pipelineJob);

        return new RecordedActionSet();
    }

    private void pruneSivChallenges(PipelineJob pipelineJob) throws PipelineJobException
    {
        TableInfo ti = QueryService.get().getUserSchema(_containerUser.getUser(), _containerUser.getContainer(), "study").getTable("treatments");

        Map<String, Set<Date>> existingRecords = new HashMap<>();
        new TableSelector(ti, PageFlowUtil.set("Id", "date", "lsid"), new SimpleFilter(FieldKey.fromString("treatment"), "SIV - Unknown", CompareType.NEQ_OR_NULL).addCondition(FieldKey.fromString("category"), "SIV Infection"), null).forEachResults(rs -> {
            String id = rs.getString(FieldKey.fromString("Id"));
            if (!existingRecords.containsKey(id))
            {
                existingRecords.put(id, new HashSet<>());
            }

            existingRecords.get(id).add(rs.getDate(FieldKey.fromString("date")));
        });

        final List<Map<String, Object>> toDelete = new ArrayList<>();
        new TableSelector(ti, PageFlowUtil.set("Id", "date", "lsid"), new SimpleFilter(FieldKey.fromString("treatment"), "SIV - Unknown"), null).forEachResults(rs -> {
            String id = rs.getString(FieldKey.fromString("Id"));
            if (!existingRecords.containsKey(id))
            {
                return;
            }

            if (existingRecords.get(id).contains(rs.getDate(FieldKey.fromString("date"))))
            {
                toDelete.add(new CaseInsensitiveHashMap<>(Map.of("lsid", rs.getString(FieldKey.fromString("lsid")))));
            }
        });

        if (!toDelete.isEmpty())
        {
            pipelineJob.getLogger().info("Deleting " + toDelete.size() + " SIV challenge records");

            try
            {
                ti.getUpdateService().deleteRows(_containerUser.getUser(), _containerUser.getContainer(), toDelete, null, null);
            }
            catch (SQLException | BatchValidationException | QueryUpdateServiceException | InvalidKeyException e)
            {
                throw new PipelineJobException(e);
            }
        }
    }

    private static final Pattern mir126_RE = Pattern.compile("miR[- ]{0,1}126",  Pattern.CASE_INSENSITIVE);
    private static final Pattern mir142_RE = Pattern.compile("miR[- ]{0,1}142",  Pattern.CASE_INSENSITIVE);
    private static final Pattern mir126_142_RE = Pattern.compile("miR[- ]{0,1}142[ ,-]{1,2}126",  Pattern.CASE_INSENSITIVE);

    private void updateVaccineInformation(PipelineJob pipelineJob) throws PipelineJobException
    {
        TableInfo ti = QueryService.get().getUserSchema(_containerUser.getUser(), _containerUser.getContainer(), "study").getTable("immunizations");

        final List<Map<String, Object>> toUpdate = new ArrayList<>();
        new TableSelector(ti, PageFlowUtil.set("lsid", "treatment", "backbone", "antigens")).forEachResults(rs -> {
            String treatment = rs.getString(FieldKey.fromString("treatment"));
            if (treatment == null)
            {
                return;
            }

            String backbone = rs.getString(FieldKey.fromString("backbone"));
            Map<String, Object> updatedRow = new HashMap<>();

            if (backbone != null && backbone.contains("68-1"))
            {
                if (mir126_142_RE.matcher(treatment).find())
                {
                    updatedRow.put("backbone", "68-1 MHC-1A-only");
                }
                else if (mir126_RE.matcher(treatment).find() && !treatment.contains("142"))
                {
                    updatedRow.put("backbone", "68-1 MHC-E-only");
                }
                else if (mir142_RE.matcher(treatment).find() && !treatment.contains("126"))
                {
                    updatedRow.put("backbone", "68-1 MHC-II-only");
                }
                else if (treatment.contains("RhCMV FL"))
                {
                    updatedRow.put("backbone", "68-1 FL");
                }
                else if (treatment.contains("RhCMV") && treatment.contains("d186-189"))
                {
                    updatedRow.put("backbone", "68-1 d186-189");
                }
            }

            if (treatment.toUpperCase().contains("MOCK"))
            {
                updatedRow.put("isMock", true);
            }

            if (!updatedRow.isEmpty())
            {
                updatedRow.put("lsid", rs.getString(FieldKey.fromString("lsid")));
                toUpdate.add(updatedRow);
            }
        });

        if (!toUpdate.isEmpty())
        {
            pipelineJob.getLogger().info("Updating " + toUpdate.size() + " immunization records");

            try
            {
                BatchValidationException bve = new BatchValidationException();

                List<Map<String, Object>> oldKeys = toUpdate.stream().map(x -> (Map<String, Object>)new CaseInsensitiveHashMap<>(Map.of("lsid", x.get("lsid")))).toList();
                ti.getUpdateService().updateRows(_containerUser.getUser(), _containerUser.getContainer(), toUpdate, oldKeys, bve, null, null);

                if (bve.hasErrors())
                {
                    throw bve;
                }
            }
            catch (SQLException | BatchValidationException | QueryUpdateServiceException | InvalidKeyException e)
            {
                throw new PipelineJobException(e);
            }
        }
    }

    private void updateChallengeAnchorDates(PipelineJob pipelineJob) throws PipelineJobException
    {
        updateAnchorDates(pipelineJob, "SIV Infection", "SIV Infection", "date");
    }

    private void updateArtInitiationAnchorDates(PipelineJob pipelineJob) throws PipelineJobException
    {
        updateAnchorDates(pipelineJob, "ART Initiation", "ART", "date");
        updateAnchorDates(pipelineJob, "ART End", "ART", "enddate");
    }

    private void updateAnchorDates(PipelineJob pipelineJob, String eventType, String treatmentCategory, String sourceDateField) throws PipelineJobException
    {
        TableInfo treatments = QueryService.get().getUserSchema(_containerUser.getUser(), _containerUser.getContainer(), "study").getTable("treatments");
        TableInfo ad = QueryService.get().getUserSchema(_containerUser.getUser(), _containerUser.getContainer(), "studies").getTable("subjectAnchorDates");

        Map<String, Set<Date>> existingRecords = new HashMap<>();
        new TableSelector(ad, PageFlowUtil.set("subjectId", "date", "rowid"), new SimpleFilter(FieldKey.fromString("eventLabel"), eventType), null).forEachResults(rs -> {
            String id = rs.getString(FieldKey.fromString("subjectId"));
            if (!existingRecords.containsKey(id))
            {
                existingRecords.put(id, new HashSet<>());
            }

            existingRecords.get(id).add(rs.getDate(FieldKey.fromString("date")));
        });

        final Map<String, Set<Date>> sourceRecords = new HashMap<>();
        final List<Map<String, Object>> toInsert = new ArrayList<>();
        new TableSelector(treatments, PageFlowUtil.set("Id", sourceDateField, "objectId"), new SimpleFilter(FieldKey.fromString("category"), treatmentCategory), null).forEachResults(rs -> {
            String id = rs.getString(FieldKey.fromString("Id"));
            Date date = rs.getDate(FieldKey.fromString(sourceDateField));
            if (date == null)
            {
                return;
            }

            if (!sourceRecords.containsKey(id))
            {
                sourceRecords.put(id, new HashSet<>());
            }
            sourceRecords.get(id).add(date);

            if (!sourceRecords.containsKey(id) || !sourceRecords.get(id).contains(date))
            {
                toInsert.add(new CaseInsensitiveHashMap<>(Map.of(
                        "subjectId", id,
                        "date", date,
                        "category", eventType,
                        "sourceRecord", rs.getString(FieldKey.fromString("objectId"))
                )));
            }
        });

        if (!toInsert.isEmpty())
        {
            pipelineJob.getLogger().info("Inserting " + toInsert.size() + " " + eventType + " anchor date records");

            try
            {
                BatchValidationException bve = new BatchValidationException();
                ad.getUpdateService().insertRows(_containerUser.getUser(), _containerUser.getContainer(), toInsert, bve, null, null);

                if (bve.hasErrors())
                {
                    throw bve;
                }
            }
            catch (SQLException | BatchValidationException | QueryUpdateServiceException | DuplicateKeyException e)
            {
                throw new PipelineJobException(e);
            }
        }

        final List<Map<String, Object>> toDelete = new ArrayList<>();
        new TableSelector(ad, PageFlowUtil.set("subjectId", "date", "rowid"), new SimpleFilter(FieldKey.fromString("eventLabel"), eventType), null).forEachResults(rs -> {
            String id = rs.getString(FieldKey.fromString("subjectId"));
            Date date = rs.getDate(FieldKey.fromString("date"));
            if (!sourceRecords.containsKey(id) | !sourceRecords.get(id).contains(date))
            {
                toDelete.add(new CaseInsensitiveHashMap<>(Map.of("rowid", rs.getInt(FieldKey.fromString("rowId")))));
            }
        });

        if (!toDelete.isEmpty())
        {
            pipelineJob.getLogger().info("Deleting " + toDelete.size() + " " + eventType + " anchor date records");

            try
            {
                ad.getUpdateService().deleteRows(_containerUser.getUser(), _containerUser.getContainer(), toDelete, null, null);
            }
            catch (SQLException | BatchValidationException | QueryUpdateServiceException | InvalidKeyException e)
            {
                throw new PipelineJobException(e);
            }
        }
    }

    @Override
    public List<String> getRequiredSettings()
    {
        return List.of();
    }

    @Override
    public void setSettings(Map<String, String> map) throws XmlException
    {

    }

    @Override
    public void setContainerUser(ContainerUser containerUser)
    {
        _containerUser = containerUser;
    }
}
