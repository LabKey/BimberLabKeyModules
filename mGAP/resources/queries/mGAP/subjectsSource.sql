SELECT
  m.externalAlias as subjectName,
  coalesce(s.gender, d.gender) as gender,
  coalesce(s.species, d.species) as species,
  coalesce(s.geographic_origin, d.geographic_origin) as geographic_origin,
  CASE
    WHEN d.center IS NOT NULL THEN d.center
    WHEN s.subjectname IS NOT NULL THEN 'ONPRC'
    ELSE NULL END as center,
  d.status as status

FROM mgap.animalMapping m
LEFT JOIN laboratory.subjects s ON (m.subjectname = s.subjectname)
LEFT JOIN mgap.demographics d ON (m.subjectname = d.subjectname)
WHERE (s.subjectname IS NOT NULL OR d.subjectname IS NOT NULL)