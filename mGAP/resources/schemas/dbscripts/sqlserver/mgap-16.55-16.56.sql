CREATE TABLE mGAP.demographics (
    rowid int identity(1,1),
    subjectname varchar(100),
    species varchar(100),
    gender varchar(100),
    geographic_origin varchar(100),
    center varchar(1000),
    status varchar(1000),

    container entityid,
    created datetime,
    createdby userid,
    modified datetime,
    modifiedby userid,

    CONSTRAINT PK_demographics PRIMARY KEY (rowid)
);