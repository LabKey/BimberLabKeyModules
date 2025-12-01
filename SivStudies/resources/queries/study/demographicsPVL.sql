SELECT
    t.Id,
    count(*) AS numPVL,
    min(sivChallenge) as sivChallenge,
    min(t.date) as dateOfFirstPvl,
    max(t.date) as dateOfLastPvl,

    min(CONVERT(CASE WHEN t.sivChallenge IS NULL THEN NULL WHEN t.date <= t.sivChallenge THEN NULL ELSE TIMESTAMPDIFF('SQL_TSI_DAY', t.sivChallenge, t.date) END, INTEGER)) as firstPvlDPI,
    max(CONVERT(CASE WHEN t.sivChallenge IS NULL THEN NULL WHEN t.date <= t.sivChallenge THEN NULL ELSE TIMESTAMPDIFF('SQL_TSI_DAY', t.sivChallenge, t.date) END, INTEGER)) as lastPvlDPI,

    min(CONVERT(CASE WHEN t.sivChallenge IS NULL THEN NULL WHEN t.date <= t.sivChallenge THEN NULL ELSE TIMESTAMPDIFF('SQL_TSI_WEEK', t.sivChallenge, t.date) END, INTEGER)) as firstPvlWPI,
    max(CONVERT(CASE WHEN t.sivChallenge IS NULL THEN NULL WHEN t.date <= t.sivChallenge THEN NULL ELSE TIMESTAMPDIFF('SQL_TSI_WEEK', t.sivChallenge, t.date) END, INTEGER)) as lastPvlWPI,

    min(artRelease) as artRelease,
    sum(CASE WHEN (t.artRelease IS NOT NULL AND t.date > t.artRelease) THEN 1 ELSE 0 END) as numPVLPostArtRelease,

    min(CONVERT(CASE WHEN t.artRelease IS NULL THEN NULL WHEN t.date <= t.artRelease THEN NULL ELSE age_in_months(t.artRelease, t.date) END, FLOAT)) as firstPvlPostArtReleaseMonths,
    max(CONVERT(CASE WHEN t.artRelease IS NULL THEN NULL WHEN t.date <= t.artRelease THEN NULL ELSE age_in_months(t.artRelease, t.date) END, FLOAT)) as lastPvlPostArtReleaseMonths,

    min(CONVERT(CASE WHEN t.artRelease IS NULL THEN NULL WHEN t.date <= t.artRelease THEN NULL ELSE TIMESTAMPDIFF('SQL_TSI_WEEK', t.artRelease, t.date) END, INTEGER)) as firstPvlPostArtReleaseWeeks,
    max(CONVERT(CASE WHEN t.artRelease IS NULL THEN NULL WHEN t.date <= t.artRelease THEN NULL ELSE TIMESTAMPDIFF('SQL_TSI_WEEK', t.artRelease, t.date) END, INTEGER)) as lastPvlPostArtReleaseWeeks

FROM (SELECT
    vl.Id,
    vl.date,
    (SELECT min(tr.date) as sivChallenge FROM study.treatments tr WHERE tr.category = 'SIV Infection' AND tr.Id = vl.Id) as sivChallenge,
    (SELECT max(tr.enddate) as artRelease FROM study.treatments tr WHERE tr.category = 'ART' AND tr.Id = vl.Id) as artRelease

FROM study.viralLoads vl
WHERE vl.target = 'SIV' AND vl.sampleType = 'Plasma'
) t
GROUP BY t.Id