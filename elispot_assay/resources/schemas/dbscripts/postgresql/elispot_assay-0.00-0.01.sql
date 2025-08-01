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
CREATE SCHEMA elispot_assay;

CREATE TABLE elispot_assay.peptide_pools (
  rowid serial,
  pool_name varchar(200),
  category varchar(200),
  comments varchar(4000),

  CreatedBy USERID,
  Created TIMESTAMP,
  ModifiedBy USERID,
  Modified TIMESTAMP,

  constraint PK_peptide_pools PRIMARY KEY (rowid)
);

CREATE TABLE elispot_assay.peptide_pool_members (
  rowid serial,
  poolId int,
  peptideId int,

  CreatedBy USERID,
  Created TIMESTAMP,
  ModifiedBy USERID,
  Modified TIMESTAMP,

  constraint PK_peptide_pool_members PRIMARY KEY (rowid)
);

CREATE TABLE elispot_assay.assay_types (
  rowid serial,
  name varchar(200),
  description varchar(500),
  category varchar(200),

  CreatedBy USERID,
  Created TIMESTAMP,
  ModifiedBy USERID,
  Modified TIMESTAMP,

  constraint PK_assay_types PRIMARY KEY (rowid)
);

-- @SkipOnEmptySchemasBegin
INSERT INTO elispot_assay.assay_types (name, description) VALUES ('IFN-Gamma', 'Interferon Gamma');
-- @SkipOnEmptySchemasEnd

CREATE TABLE elispot_assay.instruments
(
    instrument VARCHAR(200) NOT NULL,

    CONSTRAINT PK_instruments PRIMARY KEY (instrument)
);