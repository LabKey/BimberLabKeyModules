CREATE TABLE mcc.rowsToDelete (
   objectid varchar(200),
   source_modified timestamp,

   container entityid,
   created timestamp,
   createdby userid,
   modified timestamp,
   modifiedby userid,

   CONSTRAINT PK_rowsToDelete PRIMARY KEY (objectid)
);