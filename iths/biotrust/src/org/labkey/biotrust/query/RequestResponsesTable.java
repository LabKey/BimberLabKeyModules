/*
 * Copyright (c) 2013 LabKey Corporation
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
package org.labkey.biotrust.query;

import org.labkey.api.exp.property.Domain;
import org.labkey.api.query.UserSchema;

/**
 * User: klum
 * Date: 1/7/13
 */
public class RequestResponsesTable extends BioTrustTable
{
    public RequestResponsesTable(Domain domain, BioTrustQuerySchema schema)
    {
        super(domain, schema);
    }

    public RequestResponsesTable(String name, BioTrustQuerySchema schema)
    {
        super(name, schema, RequestResponsesDomain.NAME);
    }
}


