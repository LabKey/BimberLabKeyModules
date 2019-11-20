SELECT
  c.rowid,
  c.cloneName,
  c.chain,
  c.cdr3,

  v.hits as vHits,
  v.hitCount as vHitCount,

  j.hits as jHits,
  j.hitCount as jHitCount,

  ct.hits as cHits,
  ct.hitCount as cHitCount


FROM tcrdb.clones c

LEFT JOIN (
  SELECT t.cdr3, group_concat(distinct t.hit, char(10)) as hits, group_concat(distinct (t.hit || ': ' || cast(t.total as varchar(100))), char(10)) as hitCount
  FROM (
   SELECT d.cdr3,
      d.vHit as hit,
      sum("count") as total
    FROM Data d
    GROUP BY d.cdr3, d.vHit
   HAVING count(*) > 1 AND sum("count") > 100
  ) t GROUP BY t.cdr3
) v ON (v.cdr3 = c.cdr3)

LEFT JOIN (
  SELECT t.cdr3, group_concat(distinct t.hit, char(10)) as hits, group_concat(distinct (t.hit || ': ' || cast(t.total as varchar(100))), char(10)) as hitCount
  FROM (
         SELECT d.cdr3,
                d.jHit as hit,
                sum("count") as total
         FROM Data d
         GROUP BY d.cdr3, d.jHit
         HAVING count(*) > 1 AND sum("count") > 100
       ) t GROUP BY t.cdr3
) j ON (j.cdr3 = c.cdr3)

LEFT JOIN (
  SELECT t.cdr3, group_concat(distinct t.hit, char(10)) as hits, group_concat(distinct (t.hit || ': ' || cast(t.total as varchar(100))), char(10)) as hitCount
  FROM (
         SELECT d.cdr3,
                d.cHit as hit,
                sum("count") as total
         FROM Data d
         GROUP BY d.cdr3, d.cHit
         HAVING count(*) > 1 AND sum("count") > 100
       ) t GROUP BY t.cdr3
) ct ON (ct.cdr3 = c.cdr3)





