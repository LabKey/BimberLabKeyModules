CREATE TABLE mGAP.demographics (
    rowid serial,
    subjectname varchar(100),
    species varchar(100),
    gender varchar(100),
    geographic_origin varchar(100),
    center varchar(1000),
    status varchar(1000),

    container entityid,
    created timestamp,
    createdby userid,
    modified timestamp,
    modifiedby userid,

    CONSTRAINT PK_demographics PRIMARY KEY (rowid)
);