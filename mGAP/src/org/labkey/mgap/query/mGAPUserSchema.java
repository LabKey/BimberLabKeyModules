package org.labkey.mgap.query;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerFilter;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.DbSchemaType;
import org.labkey.api.data.TableInfo;
import org.labkey.api.ldk.table.ContainerScopedTable;
import org.labkey.api.module.Module;
import org.labkey.api.query.DefaultSchema;
import org.labkey.api.query.QuerySchema;
import org.labkey.api.query.SimpleUserSchema;
import org.labkey.api.security.User;
import org.labkey.mgap.mGAPSchema;

/**
 * Created by bimber on 3/28/2017.
 */
public class mGAPUserSchema extends SimpleUserSchema
{
    private mGAPUserSchema(User user, Container container, DbSchema schema)
    {
        super(mGAPSchema.NAME, null, user, container, schema);
    }

    public static void register(final Module m)
    {
        final DbSchema dbSchema = DbSchema.get(mGAPSchema.NAME, DbSchemaType.Module);

        DefaultSchema.registerProvider(mGAPSchema.NAME, new DefaultSchema.SchemaProvider(m)
        {
            public QuerySchema createSchema(final DefaultSchema schema, Module module)
            {
                return new mGAPUserSchema(schema.getUser(), schema.getContainer(), dbSchema);
            }
        });
    }

    @Override
    @Nullable
    protected TableInfo createWrappedTable(String name, @NotNull TableInfo sourceTable, ContainerFilter cf)
    {
        if (mGAPSchema.TABLE_ANIMAL_MAPPING.equalsIgnoreCase(name))
        {
            // TODO: assert cf is null or not default?
            return new ContainerScopedTable<>(this, sourceTable, cf, "subjectname").init();
        }
        else if (mGAPSchema.TABLE_DEMOGRAPHICS.equalsIgnoreCase(name))
        {
            return new ContainerScopedTable<>(this, sourceTable, cf, "subjectname").init();
        }
        else if (mGAPSchema.TABLE_VARIANT_CATALOG_RELEASES.equalsIgnoreCase(name))
        {
            return createWrappedVariantTable(name, sourceTable, cf);
        }

        return super.createWrappedTable(name, sourceTable, cf);
    }

    private TableInfo createWrappedVariantTable(String name, TableInfo sourceTable, ContainerFilter cf)
    {
        return super.createWrappedTable(name, sourceTable, cf);
    }
}
