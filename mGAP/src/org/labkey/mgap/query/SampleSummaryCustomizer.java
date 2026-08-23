package org.labkey.mgap.query;

import org.labkey.api.data.AbstractTableInfo;
import org.labkey.api.data.JdbcType;
import org.labkey.api.data.SQLFragment;
import org.labkey.api.data.TableInfo;
import org.labkey.api.gwt.client.FacetingBehaviorType;
import org.labkey.api.ldk.table.AbstractTableCustomizer;
import org.labkey.api.query.ExprColumn;

public class SampleSummaryCustomizer extends AbstractTableCustomizer
{
    @Override
    public void customize(TableInfo ti)
    {
        if (ti instanceof AbstractTableInfo ati)
        {
            customizeTable(ati);
        }
    }

    private void customizeTable(AbstractTableInfo ti)
    {
        String fieldName = "subjectCaseMismatch";
        if (ti.getColumn(fieldName) != null)
        {
            return;
        }

        // Pairs up with the case-insensitive join in sampleSummary.sql: that supplies aliasSubjectName for ids differing only in case, and this case-sensitive comparison is what spots them
        SQLFragment sql = new SQLFragment("CASE WHEN " + ExprColumn.STR_TABLE_ALIAS + ".subjectId = " + ExprColumn.STR_TABLE_ALIAS + ".aliasSubjectName THEN NULL ELSE " + ExprColumn.STR_TABLE_ALIAS + ".aliasSubjectName END");
        ExprColumn col = new ExprColumn(ti, fieldName, sql, JdbcType.VARCHAR, ti.getColumn("subjectId"), ti.getColumn("aliasSubjectName"));
        col.setLabel("Id Case Mismatch?");
        col.setFacetingBehaviorType(FacetingBehaviorType.ALWAYS_OFF);
        col.setDescription("If the case of the subjectId differs from the alias table, the updated case is shown");
        ti.addColumn(col);
    }
}
