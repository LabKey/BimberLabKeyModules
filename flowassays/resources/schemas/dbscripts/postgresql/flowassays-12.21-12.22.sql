-- @SkipOnEmptySchemasBegin
DELETE FROM flowassays.populations WHERE name = 'Lymphocytes';
INSERT INTO flowassays.populations (name) VALUES ('Lymphocytes');

DELETE FROM flowassays.assay_types WHERE name = 'Tetramer';
DELETE FROM flowassays.assay_types WHERE name = 'Phenotyping';
INSERT INTO flowassays.assay_types(name) VALUES ('Tetramer');
INSERT INTO flowassays.assay_types(name) VALUES ('Phenotyping');
-- @SkipOnEmptySchemasEnd
