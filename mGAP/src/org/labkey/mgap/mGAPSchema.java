/*
 * Copyright (c) 2015 LabKey Corporation
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

package org.labkey.mgap;

import org.labkey.api.data.DbSchema;
import org.labkey.api.data.DbSchemaType;
import org.labkey.api.data.dialect.SqlDialect;

public class mGAPSchema
{
    private static final mGAPSchema _instance = new mGAPSchema();
    public static final String NAME = "mgap";
    public static final String TABLE_ANIMAL_MAPPING = "animalMapping";
    public static final String TABLE_USER_REQUESTS = "userRequests";
    public static final String TABLE_VARIANT_CATALOG_RELEASES = "variantCatalogReleases";
    public static final String TABLE_RELEASE_STATS = "releaseStats";
    public static final String TABLE_VARIANT_TABLE = "variantList";
    public static final String TABLE_RELEASE_TRACKS = "releaseTracks";
    public static final String TABLE_RELEASE_TRACK_SUBSETS = "releaseTrackSubsets";
    public static final String TABLE_TRACKS_PER_RELEASE = "tracksPerRelease";
    public static final String TABLE_PHENOTYPES = "phenotypes";
    public static final String TABLE_PEDIGREE_OVERRIDES = "pedigreeOverrides";
    public static final String TABLE_DEMOGRAPHICS = "demographics";
    public static final String TABLE_SUBJECT_SOURCE = "subjectsSource";


    public static mGAPSchema getInstance()
    {
        return _instance;
    }

    private mGAPSchema()
    {
        // private constructor to prevent instantiation from
        // outside this class: this singleton should only be
        // accessed via org.labkey.mgap.mGAPSchema.getInstance()
    }

    public DbSchema getSchema()
    {
        return DbSchema.get(NAME, DbSchemaType.Module);
    }

    public SqlDialect getSqlDialect()
    {
        return getSchema().getSqlDialect();
    }
}
