SELECT
i_rh as Id,
d_PVLDate as date,
c_PVL as result,

'Copies/mL' as units,
'Plasma' as sampleType,
'SIVmac239' as assayType,
'SIV' as target,
'Hansen/IDR' as dataSource

FROM bimber_data.pvl
WHERE c_PVL != 'missing from box' AND c_PVL != 'pending'