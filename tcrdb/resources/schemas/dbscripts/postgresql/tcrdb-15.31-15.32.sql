CREATE TABLE tcrdb.loci (
  locus varchar(100),

  constraint PK_loci PRIMARY KEY (locus)
);

-- @SkipOnEmptySchemasBegin
INSERT INTO tcrdb.loci (locus) VALUES ('IGH');
INSERT INTO tcrdb.loci (locus) VALUES ('IGL');
INSERT INTO tcrdb.loci (locus) VALUES ('IGK');
INSERT INTO tcrdb.loci (locus) VALUES ('TRA');
INSERT INTO tcrdb.loci (locus) VALUES ('TRB');
INSERT INTO tcrdb.loci (locus) VALUES ('TRG');
INSERT INTO tcrdb.loci (locus) VALUES ('TRD');
INSERT INTO tcrdb.loci (locus) VALUES ('IG');
INSERT INTO tcrdb.loci (locus) VALUES ('TCR');
INSERT INTO tcrdb.loci (locus) VALUES ('ALL');
-- @SkipOnEmptySchemasEnd
