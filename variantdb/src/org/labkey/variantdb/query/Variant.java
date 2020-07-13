package org.labkey.variantdb.query;

import org.labkey.api.sequenceanalysis.RefNtSequenceModel;

import java.util.Date;

/**
 * Created by bimber on 1/8/2015.
 */
public class Variant
{
    private Integer _rowId;
    private String _objectid;
    private Integer _sequenceId;
    private Integer _startPosition;
    private Integer _endPosition;
    private String _reference;
    private String _allele;
    private String _status;
    private String _referenceVariantId;
    private String _referenceAlleleId;
    private String _batchId;
    private Date _created;
    private Integer _createdBy;
    private Date _modified;
    private Integer _modifiedBy;

    private String _sequenceName;

    public Integer getRowId()
    {
        return _rowId;
    }

    public void setRowId(Integer rowId)
    {
        _rowId = rowId;
    }

    public String getObjectid()
    {
        return _objectid;
    }

    public void setObjectid(String objectid)
    {
        _objectid = objectid;
    }

    public Integer getSequenceId()
    {
        return _sequenceId;
    }

    public void setSequenceId(Integer sequenceId)
    {
        _sequenceId = sequenceId;
    }

    public Integer getStartPosition()
    {
        return _startPosition;
    }

    public void setStartPosition(Integer startPosition)
    {
        _startPosition = startPosition;
    }

    public Integer getEndPosition()
    {
        return _endPosition;
    }

    public void setEndPosition(Integer endPosition)
    {
        _endPosition = endPosition;
    }

    public String getReference()
    {
        return _reference;
    }

    public void setReference(String reference)
    {
        _reference = reference;
    }

    public String getAllele()
    {
        return _allele;
    }

    public void setAllele(String allele)
    {
        _allele = allele;
    }

    public String getStatus()
    {
        return _status;
    }

    public void setStatus(String status)
    {
        _status = status;
    }

    public String getReferenceVariantId()
    {
        return _referenceVariantId;
    }

    public void setReferenceVariantId(String referenceVariantId)
    {
        _referenceVariantId = referenceVariantId;
    }

    public String getReferenceAlleleId()
    {
        return _referenceAlleleId;
    }

    public void setReferenceAlleleId(String referenceAlleleId)
    {
        _referenceAlleleId = referenceAlleleId;
    }

    public String getBatchId()
    {
        return _batchId;
    }

    public void setBatchId(String batchId)
    {
        _batchId = batchId;
    }

    public Date getCreated()
    {
        return _created;
    }

    public void setCreated(Date created)
    {
        _created = created;
    }

    public Integer getCreatedBy()
    {
        return _createdBy;
    }

    public void setCreatedBy(Integer createdBy)
    {
        _createdBy = createdBy;
    }

    public Date getModified()
    {
        return _modified;
    }

    public void setModified(Date modified)
    {
        _modified = modified;
    }

    public Integer getModifiedBy()
    {
        return _modifiedBy;
    }

    public void setModifiedBy(Integer modifiedBy)
    {
        _modifiedBy = modifiedBy;
    }

    public String getSequenceName()
    {
        if (_sequenceName != null)
        {
            return _sequenceName;
        }

        if (_sequenceId == null)
        {
            return null;
        }

        RefNtSequenceModel m = RefNtSequenceModel.getForRowId(getSequenceId());
        if (m != null)
        {
            _sequenceName = m.getName();
        }

        return _sequenceName;
    }

    public void setSequenceName(String sequenceName)
    {
        _sequenceName = sequenceName;
    }
}
