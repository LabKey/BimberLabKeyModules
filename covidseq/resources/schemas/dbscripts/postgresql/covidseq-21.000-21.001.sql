ALTER TABLE covidseq.samples ADD COLUMN assayType varchar(200);
ALTER TABLE covidseq.samples ADD COLUMN N1_or_S double precision;
ALTER TABLE covidseq.samples ADD COLUMN N2_orN double precision;
ALTER TABLE covidseq.samples ADD COLUMN RP_or_ORF1ab double precision;
ALTER TABLE covidseq.samples ADD COLUMN MS2 double precision;
ALTER TABLE covidseq.samples ADD COLUMN cDNA_Plate_ID varchar(200);
ALTER TABLE covidseq.samples ADD COLUMN cDNA_Plate_Location varchar(200);

ALTER TABLE covidseq.samples DROP COLUMN country;
ALTER TABLE covidseq.samples DROP COLUMN county;

ALTER TABLE covidseq.samples DROP COLUMN patientId;
ALTER TABLE covidseq.samples ADD COLUMN patientId ENTITYID;

CREATE TABLE covidseq.patients (
    rowid serial,
    identifier varchar(1000),
    patientId ENTITYID,

    "state" varchar(1000),
    county varchar(2000),
    country varchar(2000),
    age double precision,

    container entityid,
    created timestamp,
    createdby int,
    modified timestamp,
    modifiedby int,

    CONSTRAINT PK_patients PRIMARY KEY (patientId)
);
