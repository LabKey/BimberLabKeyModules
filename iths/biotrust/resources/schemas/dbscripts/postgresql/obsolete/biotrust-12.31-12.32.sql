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

-- drop the RequestOwner table and replace it with RequestCategory
DROP TABLE biotrust.RequestOwner;

CREATE TABLE biotrust.RequestCategory
(
    RowId SERIAL,
    Category VARCHAR(100) NOT NULL,
    SortOrder REAL NOT NULL,

    Container ENTITYID,
    CreatedBy USERID,
    Created TIMESTAMP,
    ModifiedBy USERID,
    Modified TIMESTAMP,

    CONSTRAINT PK_RequestCategory PRIMARY KEY (RowId),
    CONSTRAINT UQ_ContainerCategory UNIQUE (Container, Category),
    CONSTRAINT UQ_ContainerSortOrder UNIQUE (Container, SortOrder)
);
