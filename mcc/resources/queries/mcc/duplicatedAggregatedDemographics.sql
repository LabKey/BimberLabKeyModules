SELECT

    m.Id,
    GROUP_CONCAT(distinct m.originalId, ',') as originalIds,
    GROUP_CONCAT(distinct m.container.name, ',') as folders

FROM mcc.aggregatedDemographics m

GROUP BY m.Id
HAVING COUNT(*) > 1