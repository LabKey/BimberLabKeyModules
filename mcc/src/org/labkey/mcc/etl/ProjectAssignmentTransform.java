package org.labkey.mcc.etl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.di.columnTransform.ColumnTransform;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.query.DuplicateKeyException;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryException;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.QueryUpdateServiceException;
import org.labkey.api.util.GUID;
import org.labkey.api.util.PageFlowUtil;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;

public class ProjectAssignmentTransform extends ColumnTransform
{
    private static final Logger _log = LogManager.getLogger(ProjectAssignmentTransform.class);

    private static final String U24_TITLE = "U24 marmoset breeding";
    private static final String U24_BREEDER = "U24 breeder";
    private static final String U24_OFFSPRING = "U24 offspring";
    private static final String U24_OTHER = "Other";

    @Override
    protected Object doTransform(Object inputValue)
    {
        if (inputValue == null)
        {
            return null;
        }

        // This is the WNPRC value:
        if (U24_TITLE.equalsIgnoreCase(String.valueOf(inputValue)))
        {
            return getOrCreateFlag(U24_TITLE);
        }
        // SNPRC:
        else if (U24_BREEDER.equalsIgnoreCase(String.valueOf(inputValue)))
        {
            return getOrCreateFlag(U24_BREEDER);
        }
        else if (U24_OFFSPRING.equalsIgnoreCase(String.valueOf(inputValue)))
        {
            return getOrCreateFlag(U24_OFFSPRING);
        }

        return inputValue;
    }

    private String getOrCreateFlag(String type)
    {
        TableInfo ti = QueryService.get().getUserSchema(getContainerUser().getUser(), getContainerUser().getContainer(), "ehr_lookups").getTable("flag_values");
        String objectId = new TableSelector(ti, PageFlowUtil.set("objectid"), new SimpleFilter(FieldKey.fromString("value"), type), null).getObject(String.class);
        if (objectId == null)
        {
            Map<String, Object> map = new CaseInsensitiveHashMap<>();
            objectId = new GUID().toString();
            map.put("objectid", objectId);
            map.put("value", U24_TITLE);
            map.put("category", "U24 Assignment Type");

            try
            {
                BatchValidationException bve = new BatchValidationException();
                ti.getUpdateService().insertRows(getContainerUser().getUser(), getContainerUser().getContainer(), Collections.singletonList(map), bve, null, null);
                if (bve.hasErrors())
                {
                    throw bve;
                }
            }
            catch (SQLException | BatchValidationException | QueryException | QueryUpdateServiceException | DuplicateKeyException e)
            {
                _log.error("Error creating flag row", e);
                return null;
            }
        }

        return objectId;
    }
}
