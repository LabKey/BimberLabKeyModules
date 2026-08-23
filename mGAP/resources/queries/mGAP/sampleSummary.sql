SELECT
    t.subjectId,
    am.externalAlias,
    t.readset,
    ss.gender,
    ss.species,
    ss.geographic_origin,
    ss.center,
    t.tracks,
    t.total,
    CASE WHEN ss.originalId IS NULL OR ss.gender IS NULL or ss.species IS NULL or ss.center IS NULL THEN true ELSE false END as missingDemographics,
    am.subjectname as aliasSubjectName

FROM (SELECT
        COALESCE(o.readset.subjectId, rt.subjectId) as subjectId,
         o.readset,

         group_concat(rt.trackName, chr(10)) as tracks,
         count(distinct o.rowid) as total

      FROM sequenceanalysis.outputfiles o
      FULL JOIN mgap.releaseTrackSubsets rt ON (o.readset.subjectId = rt.subjectId)
      WHERE o.fileSets like '%mGAP%'

      GROUP BY o.readset, rt.subjectId, o.readset.subjectId

) t

LEFT JOIN mgap.subjectsSource ss on (t.subjectId = ss.originalId)
-- Matched case-insensitively so an id whose alias-table spelling differs only in case still resolves; SampleSummaryCustomizer surfaces those as subjectCaseMismatch
LEFT JOIN mgap.animalMapping am on (LOWER(t.subjectId) = LOWER(am.subjectname))