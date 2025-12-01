SELECT

    d1.Id,
    d1.litterId,

    (SELECT GROUP_CONCAT(distinct d2.Id, ',') as litterMates FROM study.Demographics d2 WHERE d2.qcstate.publicdata = true AND d1.litterId = d2.litterId AND d1.id != d2.id) as litterMates

FROM study.Demographics d1

WHERE d1.qcstate.publicdata = true
