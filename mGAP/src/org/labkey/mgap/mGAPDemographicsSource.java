package org.labkey.mgap;

import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Container;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.jbrowse.DemographicsSource;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.security.User;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class mGAPDemographicsSource implements DemographicsSource
{

    @Override
    public Map<String, Map<String, Object>> resolveSubjects(List<String> subjects, Container c, User u)
    {
        Map<String, Map<String, Object>> ret = new HashMap<>();

        TableInfo ti = QueryService.get().getUserSchema(u, c, mGAPSchema.NAME).getTable(mGAPSchema.TABLE_DEMOGRAPHICS);
        Set<String> fields = new LinkedHashSet<>(getFields().keySet());
        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("subjectname"), subjects, CompareType.IN);
        fields.add("subjectname");
        new TableSelector(ti, fields, filter, null).forEachResults(rs -> {
            Map<String, Object> map = new CaseInsensitiveHashMap<>();
            for (String field : getFields().keySet())
            {
                map.put(field, rs.getObject(FieldKey.fromString(field)));
            }

            ret.put(rs.getString(FieldKey.fromString("subjectname")), map);
        });

        return ret;
    }

    @Override
    public LinkedHashMap<String, String> getFields()
    {
        LinkedHashMap ret = new LinkedHashMap();
        ret.put("gender", "Gender");
        ret.put("species", "Species");
        ret.put("center", "Center");
        ret.put("geographic_origin", "Geographic Origin");
        ret.put("status", "Status");

        return ret;
    }

    @Override
    public boolean isAvailable(Container c, User u)
    {
        return c.getActiveModules().contains(ModuleLoader.getInstance().getModule(mGAPModule.class));
    }
}
