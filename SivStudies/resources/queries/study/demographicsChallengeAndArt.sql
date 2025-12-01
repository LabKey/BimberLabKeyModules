SELECT
    t.*,
    TIMESTAMPDIFF('SQL_TSI_WEEK', t.infectionDate, t.artReleaseDate) as artReleaseWPI
FROM (
    SELECT
      t.Id,
      group_concat(DISTINCT CASE
          WHEN t.category = 'SIV Infection' THEN (cast(month(t.date) as varchar) || '/' || cast(dayofmonth(t.date) as varchar) || '/' || cast(year(t.date) as varchar) || ' (' || t.treatment || ')')
          ELSE NULL
      END, char(10)) as allInfections,
      min(floor(age(t.DataSets.Demographics.birth, CASE WHEN t.category = 'SIV Infection' THEN t.date ELSE NULL END))) AS ageAtInfection,

      group_concat(DISTINCT CASE
          WHEN t.category = 'ART' THEN (cast(month(t.date) as varchar) || '/' || cast(dayofmonth(t.date) as varchar) || '/' || cast(year(t.date) as varchar) || ' (' || t.treatment || ')')
          ELSE NULL
      END, char(10)) as allART,
      min(CASE
          WHEN t.category = 'SIV Infection' THEN t.date
          ELSE NULL
      END) as infectionDate,
      min(CASE
              WHEN t.category = 'ART' THEN t.date
              ELSE NULL
          END) as artInitiationDate,
        min(CASE
              WHEN t.category = 'ART' THEN t.timePostSivChallenge.daysPostInfection
              ELSE NULL
          END) as artInitiationDPI,
      min(CASE
              WHEN t.category = 'ART' THEN t.artInformation.artRelease
              ELSE NULL
          END) as artReleaseDate
    FROM study.treatments t
    GROUP BY t.Id
) t