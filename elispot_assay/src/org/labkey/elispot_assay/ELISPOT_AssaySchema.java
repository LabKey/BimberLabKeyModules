/*
 * Copyright (c) 2012 LabKey Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.labkey.elispot_assay;

import org.labkey.api.data.DbSchema;
import org.labkey.api.data.DbSchemaType;

public class ELISPOT_AssaySchema
{
    private static final ELISPOT_AssaySchema _INSTANCE = new ELISPOT_AssaySchema();
    public static final String SCHEMA_NAME = "elispot_assay";

    public static ELISPOT_AssaySchema getInstance()
    {
        return _INSTANCE;
    }

    private ELISPOT_AssaySchema()
    {
        // private constructor to prevent instantiation from
        // outside this class: this singleton should only be
        // accessed via org.labkey.elispot_assay.ELISPOT_AssaySchema.getInstance()
    }

    public DbSchema getSchema()
    {
        return DbSchema.get(SCHEMA_NAME, DbSchemaType.Module);
    }
}
