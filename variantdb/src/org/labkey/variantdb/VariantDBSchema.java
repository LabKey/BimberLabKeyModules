/*
 * Copyright (c) 2014 LabKey Corporation
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

package org.labkey.variantdb;

import org.labkey.api.data.DbSchema;
import org.labkey.api.data.DbSchemaType;

public class VariantDBSchema
{
    private static final VariantDBSchema _instance = new VariantDBSchema();
    public static final String NAME = "variantdb";

    public static final String TABLE_VARIANTS = "Variants";
    public static final String TABLE_REFERENCE_VARIANTS = "ReferenceVariants";
    public static final String TABLE_REFERENCE_VARIANT_ALLELES = "ReferenceVariantAlleles";
    public static final String TABLE_VARIANT_ATTRIBUTES = "VariantAttributes";
    public static final String TABLE_VARIANT_ATTRIBUTE_TYPES = "VariantAttributeTypes";
    public static final String TABLE_VARIANT_SAMPLE_MAPPING = "VariantSampleMapping";
    public static final String TABLE_UPLOAD_BATCHES = "UploadBatches";
    public static final String TABLE_VARIANT_LIFTOVER = "VariantLiftover";

    public static VariantDBSchema getInstance()
    {
        return _instance;
    }

    private VariantDBSchema()
    {
    }

    public DbSchema getSchema()
    {
        return DbSchema.get(NAME, DbSchemaType.Module);
    }
}
