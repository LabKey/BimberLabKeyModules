/*
 * Copyright (c) 2021 LabKey Corporation
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

-- Create schema, tables, indexes, and constraints used for Covidseq module here
-- All SQL VIEW definitions should be created in covidseq-create.sql and dropped in covidseq-drop.sql
CREATE SCHEMA covidseq;

CREATE TABLE covidseq.samples (
  rowid SERIAL,
  sampleName varchar(2000),
  patientId varchar(2000),
  sampleDate timestamp,
  sampleSource varchar(2000),
  sampleType varchar(2000),
  comment varchar(4000),
  status varchar(2000),
  gisaidId varchar(2000),
  county varchar(2000),
  country varchar(2000),

  container entityid,
  created timestamp,
  createdby int,
  modified timestamp,
  modifiedby int,

  constraint PK_samples PRIMARY KEY (rowid)
);