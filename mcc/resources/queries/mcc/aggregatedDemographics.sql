SELECT
  d.Id,
  d.date,
  d.species,
  d.gender,
  d.birth,
  d.death,
  d.colony,
  d.dam,
  d.sire,
  d.Id.mostRecentWeight.mostRecentWeight as mostRecentWeight,
  d.objectid,
  d.calculated_status,
  CASE
    WHEN (SELECT COUNT(f.flag.value) as total FROM study.flags f WHERE f.Id = d.Id AND f.isActive = true) > 0 THEN true
    ELSE false
  END as u24_status

FROM "/data/Colonies/SNPRC/".study.demographics d

UNION ALL

SELECT
  d.Id,
  d.date,
  d.species,
  d.gender,
  d.birth,
  d.death,
  d.colony,
  d.dam,
  d.sire,
  d.Id.mostRecentWeight.mostRecentWeight as mostRecentWeight,
  d.objectid,
  d.calculated_status,
  CASE
    WHEN (SELECT COUNT(f.flag.value) as total FROM study.flags f WHERE f.Id = d.Id AND f.isActive = true) > 0 THEN true
    ELSE false
  END as u24_status

FROM "/data/Colonies/WNPRC/".study.demographics d

UNION ALL

SELECT
    d.Id,
    d.date,
    d.species,
    d.gender,
    d.birth,
    d.death,
    'U NEB' as colony,
    d.dam,
    d.sire,
    d.Id.mostRecentWeight.mostRecentWeight as mostRecentWeight,
    d.objectid,
    d.calculated_status,
    false as u24_status

FROM "/data/Colonies/UNO/".study.demographics d