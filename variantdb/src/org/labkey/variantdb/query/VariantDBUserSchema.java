package org.labkey.variantdb.query;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.data.BaseColumnInfo;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerFilter;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.JdbcType;
import org.labkey.api.data.SQLFragment;
import org.labkey.api.data.TableInfo;
import org.labkey.api.ldk.table.CustomPermissionsTable;
import org.labkey.api.module.Module;
import org.labkey.api.query.DefaultSchema;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.query.ExprColumn;
import org.labkey.api.query.QuerySchema;
import org.labkey.api.query.SimpleUserSchema;
import org.labkey.api.security.User;
import org.labkey.api.security.permissions.DeletePermission;
import org.labkey.api.security.permissions.InsertPermission;
import org.labkey.api.security.permissions.Permission;
import org.labkey.api.security.permissions.UpdatePermission;
import org.labkey.variantdb.VariantDBSchema;
import org.labkey.variantdb.security.VariantManagerPermission;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by bimber on 1/4/2015.
 */
public class VariantDBUserSchema extends SimpleUserSchema
{
    private VariantDBUserSchema(User user, Container container, DbSchema schema)
    {
        super(VariantDBSchema.NAME, null, user, container, schema);
    }

    public static void register(final Module m)
    {
        final DbSchema dbSchema = VariantDBSchema.getInstance().getSchema();

        DefaultSchema.registerProvider(VariantDBSchema.NAME, new DefaultSchema.SchemaProvider(m)
        {
            @Override
            public QuerySchema createSchema(final DefaultSchema schema, Module module)
            {
                return new VariantDBUserSchema(schema.getUser(), schema.getContainer(), dbSchema);
            }
        });
    }

    @Override
    @Nullable
    protected TableInfo createWrappedTable(String name, @NotNull TableInfo sourceTable, ContainerFilter cf)
    {
        if (VariantDBSchema.TABLE_REFERENCE_VARIANTS.equalsIgnoreCase(name))
        {
            CustomPermissionsTable ret = getCustomPermissionTable(sourceTable, cf, VariantManagerPermission.class);
            SQLFragment sql = new SQLFragment("COALESCE(" + ExprColumn.STR_TABLE_ALIAS + ".dbSnpAccession, " + ret.getSqlDialect().concatenate("'lcl'", "CAST(" + ExprColumn.STR_TABLE_ALIAS + ".rowid AS VARCHAR)") + ")");
            ExprColumn newCol = new ExprColumn(ret, "displayName", sql, JdbcType.VARCHAR, ret.getColumn("rowid"), ret.getColumn("dbSnpAccession"));
            newCol.setLabel("Variant Id");

            SQLFragment sql2 = new SQLFragment("(SELECT ").append(ret.getSqlDialect().getGroupConcat(new SQLFragment("allele"), true, true)).append(" AS expr FROM " + VariantDBSchema.NAME + "." + VariantDBSchema.TABLE_REFERENCE_VARIANT_ALLELES + " t WHERE t.referenceVariantId = " + ExprColumn.STR_TABLE_ALIAS + ".objectid)");
            ExprColumn newCol2 = new ExprColumn(ret, "alleles", sql2, JdbcType.VARCHAR, ret.getColumn("objectid"));
            newCol2.setLabel("Alleles");

            List<ColumnInfo> cols = new ArrayList<>(ret.getColumns());
            for (ColumnInfo col : cols)
            {
                ret.removeColumn(col);
            }
            cols.add(1, newCol);
            cols.add(4, newCol2);

            for (ColumnInfo col : cols)
            {
                ret.addColumn( (BaseColumnInfo)col );
            }

            ret.setDetailsURL(DetailsURL.fromString("/variantdb/referenceVariantDetails.view?variantId=${objectid}"));
            return ret;
        }
        else if (VariantDBSchema.TABLE_REFERENCE_VARIANT_ALLELES.equalsIgnoreCase(name))
        {
            CustomPermissionsTable ret = getCustomPermissionTable(sourceTable, cf, VariantManagerPermission.class);
            return ret;
        }
        else if (VariantDBSchema.TABLE_VARIANTS.equalsIgnoreCase(name))
        {
            return getCustomPermissionTable(sourceTable, cf, VariantManagerPermission.class);
        }
        else
            return super.createWrappedTable(name, sourceTable, cf);
    }

    private CustomPermissionsTable getCustomPermissionTable(TableInfo schemaTable, ContainerFilter cf, Class<? extends Permission> perm)
    {
        CustomPermissionsTable ret = new CustomPermissionsTable(this, schemaTable, cf);
        ret.addPermissionMapping(InsertPermission.class, perm);
        ret.addPermissionMapping(UpdatePermission.class, perm);
        ret.addPermissionMapping(DeletePermission.class, perm);

        return ret.init();
    }
}
