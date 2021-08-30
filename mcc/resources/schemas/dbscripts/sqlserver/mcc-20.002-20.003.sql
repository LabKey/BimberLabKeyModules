CREATE TABLE mcc.rowsToDelete (
   objectid varchar(200),
   source_modified datetime,

   container entityid,
   created datetime,
   createdby userid,
   modified datetime,
   modifiedby userid,

   CONSTRAINT PK_rowsToDelete PRIMARY KEY (objectid)
);