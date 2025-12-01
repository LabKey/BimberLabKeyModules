SELECT
    t.Id,
    group_concat(DISTINCT CASE
      WHEN t.category = 'Intervention' THEN (t.treatment || ' (' || t.timePostSivChallenge.timePostInfection || ')')
      ELSE NULL
  END, char(10)) as allInterventions,
    min(CASE
            WHEN t.category = 'Intervention' THEN t.date
            ELSE NULL
        END) as firstInterventionDate,
    min(CASE
            WHEN t.category = 'Intervention' THEN t.timePostSivChallenge.daysPostInfection
            ELSE NULL
        END) as firstInterventionDPI,
    min(CASE
            WHEN t.category = 'Intervention' THEN t.timePostSivChallenge.weeksPostInfection
            ELSE NULL
        END) as firstInterventionWPI
FROM study.treatments t
GROUP BY t.Id