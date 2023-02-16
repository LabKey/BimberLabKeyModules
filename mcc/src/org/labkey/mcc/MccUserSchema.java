package org.labkey.mcc;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerFilter;
import org.labkey.api.data.DataColumn;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.JdbcType;
import org.labkey.api.data.SQLFragment;
import org.labkey.api.data.TableInfo;
import org.labkey.api.ldk.table.ContainerScopedTable;
import org.labkey.api.ldk.table.CustomPermissionsTable;
import org.labkey.api.query.ExprColumn;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.SimpleUserSchema;
import org.labkey.api.security.User;
import org.labkey.api.security.permissions.DeletePermission;
import org.labkey.api.security.permissions.InsertPermission;
import org.labkey.api.security.permissions.ReadPermission;
import org.labkey.api.security.permissions.UpdatePermission;
import org.labkey.mcc.security.MccRabReviewPermission;
import org.labkey.mcc.security.MccRequestAdminPermission;
import org.labkey.mcc.security.MccRequestorPermission;

import java.util.Arrays;
import java.util.Collections;

public class MccUserSchema extends SimpleUserSchema
{
    public MccUserSchema(User user, Container container, DbSchema dbschema)
    {
        super(MccSchema.NAME, "MCC-specific tables, such as requests", user, container, dbschema);
    }

    @Override
    @Nullable
    protected TableInfo createWrappedTable(String name, @NotNull TableInfo schemaTable, ContainerFilter cf)
    {
        if (MccSchema.TABLE_ANIMAL_MAPPING.equalsIgnoreCase(name))
        {
            return new ContainerScopedTable<>(this, schemaTable, cf, "subjectname").init();
        }
        else if (MccSchema.TABLE_ANIMAL_REQUESTS.equalsIgnoreCase(name))
        {
            CustomPermissionsTable<?> ret = new CustomPermissionsTable<>(this, schemaTable, cf);
            ret.addPermissionMapping(InsertPermission.class, MccRequestorPermission.class);
            ret.addPermissionMapping(UpdatePermission.class, MccRequestorPermission.class);
            ret.addPermissionMapping(DeletePermission.class, MccRequestAdminPermission.class);

            return ret.init();
        }
        else if (MccSchema.TABLE_REQUEST_REVIEWS.equalsIgnoreCase(name))
        {
            CustomPermissionsTable<?> ret = new CustomPermissionsTable<>(this, schemaTable, cf);
            ret.addPermissionMapping(ReadPermission.class, MccRabReviewPermission.class);
            ret.addPermissionMapping(InsertPermission.class, MccRabReviewPermission.class);
            ret.addPermissionMapping(UpdatePermission.class, MccRabReviewPermission.class);
            ret.addPermissionMapping(DeletePermission.class, MccRabReviewPermission.class);

            ret = ret.init();

            return ret;
        }
        else if (MccSchema.TABLE_REQUEST_SCORE.equalsIgnoreCase(name))
        {
            CustomPermissionsTable<?> ret = new CustomPermissionsTable<>(this, schemaTable, cf);
            ret.addPermissionMapping(ReadPermission.class, MccRequestAdminPermission.class);
            ret.addPermissionMapping(InsertPermission.class, MccRequestAdminPermission.class);
            ret.addPermissionMapping(UpdatePermission.class, MccRequestAdminPermission.class);
            ret.addPermissionMapping(DeletePermission.class, MccRequestAdminPermission.class);

            ret = ret.init();

            return addScoreColumns(ret);
        }

        return super.createWrappedTable(name, schemaTable, cf);
    }

    // TODO: make automatic aggregation queries for ETLs

    private CustomPermissionsTable<?> addScoreColumns(CustomPermissionsTable<?> ti)
    {
        if (ti.getColumn("rabReviewStatus") == null)
        {
            SQLFragment sql = new SQLFragment("(SELECT CONCAT(COALESCE(CAST(sum(CASE WHEN r.review IS NULL THEN 0 ELSE 1 END) as varchar), '0'), ' of ', cast(count(*) as varchar), ' completed') as expr FROM mcc.requestReviews r WHERE r.requestId = " + ExprColumn.STR_TABLE_ALIAS + ".requestId)");
            ExprColumn newCol = new ExprColumn(ti, "rabReviewStatus", sql, JdbcType.VARCHAR, ti.getColumn("requestId"));
            newCol.setSortFieldKeys(Collections.singletonList(FieldKey.fromString("pendingRabReviews")));

            newCol.setLabel("RAB Review Status");
            newCol.setDisplayColumnFactory(colInfo -> {
                return new DataColumn(colInfo)
                {
                    @Override
                    public boolean isFilterable()
                    {
                        return false;
                    }

                    @Override
                    public boolean isSortable()
                    {
                        return false;
                    }
                };
            });

            ti.addColumn(newCol);

            SQLFragment sql2 = new SQLFragment("(SELECT COALESCE(sum(CASE WHEN r.review IS NULL THEN 1 ELSE 0 END), -1) as expr FROM mcc.requestReviews r WHERE r.requestId = " + ExprColumn.STR_TABLE_ALIAS + ".requestId)");
            ExprColumn newCol2 = new ExprColumn(ti, "pendingRabReviews", sql2, JdbcType.INTEGER, ti.getColumn("requestId"));
            newCol2.setLabel("Pending RAB Reviews");
            ti.addColumn(newCol2);
        }

        return ti;
    }
}
