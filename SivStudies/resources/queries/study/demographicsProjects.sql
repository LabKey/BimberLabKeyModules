SELECT
  s.Id,
  count(s.Id) as totalProjects,
  group_concat(DISTINCT s.study, char(10)) as allStudies,
  group_concat(DISTINCT s.cohortId.studyId.description, char(10)) as studyDescription,
  group_concat(DISTINCT s.category, char(10)) as categories,
  group_concat(DISTINCT s.subgroup, char(10)) as subgroups

FROM study.assignment s
GROUP BY s.Id