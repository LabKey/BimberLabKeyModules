package org.labkey.sivstudies.query;

import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.collections.CaseInsensitiveHashSet;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Container;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.data.triggers.Trigger;
import org.labkey.api.data.triggers.TriggerFactory;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.query.DuplicateKeyException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.QueryUpdateServiceException;
import org.labkey.api.query.ValidationException;
import org.labkey.api.security.User;
import org.labkey.api.study.Study;
import org.labkey.api.study.StudyService;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.logging.LogHelper;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AutoCreateDemographicsTrigger extends DefaultDatasetTrigger
{
    protected static final Logger _log = LogHelper.getLogger(DefaultDatasetTrigger.class, "Messages related to CreateDemographicsTrigger");

    public static class Factory implements TriggerFactory
    {
        public Factory()
        {

        }

        @Override
        public @NotNull Collection<Trigger> createTrigger(@Nullable Container c, TableInfo table, Map<String, Object> extraContext)
        {
            return List.of(new AutoCreateDemographicsTrigger());
        }
    }

    private final Set<String> _idsToCheck = new CaseInsensitiveHashSet();

    @Override
    protected void afterUpsert(TableInfo table, Container c, User user, @Nullable Map<String, Object> newRow, @Nullable Map<String, Object> oldRow, ValidationException errors, Map<String, Object> extraContext) throws ValidationException
    {
        String idField = getIdField(c);
        String id = newRow.get(idField) != null  ? newRow.get(idField).toString() : null;
        if (id != null)
        {
            _idsToCheck.add(id);
        }
    }

    @Override
    public void complete(TableInfo table, Container c, User user, TableInfo.TriggerType event, BatchValidationException errors, Map<String, Object> extraContext)
    {
        if (!_idsToCheck.isEmpty())
        {
            String idField = getIdField(c);
            TableInfo ti = QueryService.get().getUserSchema(user, getTargetContainer(c), "study").getTable("demographics");
            List<String> existingIds = new TableSelector(ti, PageFlowUtil.set(idField), new SimpleFilter(FieldKey.fromString(idField), _idsToCheck, CompareType.IN), null).getArrayList(String.class);

            _idsToCheck.removeAll(existingIds);

            if (!_idField.isEmpty())
            {
                List<Map<String, Object>> toInsert = _idsToCheck.stream().map(id -> Map.of(idField, (Object)id)).toList();
                try
                {
                    ti.getUpdateService().insertRows(user, c, toInsert, null, null, null);
                }
                catch (SQLException | BatchValidationException | QueryUpdateServiceException | DuplicateKeyException e)
                {
                    _log.error("Error creating demographics records", e);
                }
            }
        }
    }

    private String _idField = null;

    private String getIdField(Container c)
    {
        if (_idField == null)
        {
            Study s = StudyService.get().getStudy(getTargetContainer(c));
            if (s == null)
            {
                return null;
            }

            _idField = s.getSubjectColumnName();
        }

        return _idField;
    }
}
