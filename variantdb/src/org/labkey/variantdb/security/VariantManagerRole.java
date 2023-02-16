package org.labkey.variantdb.security;

import org.labkey.api.data.Container;
import org.labkey.api.laboratory.security.LaboratoryAdminPermission;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.security.SecurableResource;
import org.labkey.api.security.SecurityPolicy;
import org.labkey.api.security.permissions.DeletePermission;
import org.labkey.api.security.permissions.InsertPermission;
import org.labkey.api.security.permissions.ReadPermission;
import org.labkey.api.security.permissions.UpdatePermission;
import org.labkey.api.security.roles.AbstractRole;
import org.labkey.variantdb.VariantDBModule;

/**
 * Created by bimber on 1/4/2015.
 */
public class VariantManagerRole extends AbstractRole
{
    public VariantManagerRole()
    {
        super("Variant Reference Data Manager", "Grants users the ability to manage the site-wide reference data used by the VariantDB module, such as the list of reference variants.",
                ReadPermission.class,
                InsertPermission.class,
                UpdatePermission.class,
                DeletePermission.class,
                LaboratoryAdminPermission.class
        );
    }

    @Override
    public boolean isApplicable(SecurityPolicy policy, SecurableResource resource)
    {
        return resource instanceof Container && ((Container) resource).getActiveModules().contains(ModuleLoader.getInstance().getModule(VariantDBModule.class));
    }
}