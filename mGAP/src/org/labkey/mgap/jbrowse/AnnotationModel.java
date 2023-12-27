package org.labkey.mgap.jbrowse;

public class AnnotationModel
{
    private Integer _rowid;
    private String _category;
    private String _label;
    private String _dataSource;
    private String _infoKey;
    private String _sourceField;
    private String _dataType;
    private String _dataNumber;
    private String _url;
    private String _dataUrl;
    private String _description;
    private String _toolName;
    private String _formatString;
    private Boolean _hidden = false;
    private Boolean _isIndexed = false;
    private Boolean _inDefaultColumns = false;
    private String _allowableValues;

    public AnnotationModel()
    {

    }

    public int getRowid()
    {
        return _rowid;
    }

    public void setRowid(int rowid)
    {
        _rowid = rowid;
    }

    public String getCategory()
    {
        return _category;
    }

    public void setCategory(String category)
    {
        _category = category;
    }

    public String getLabel()
    {
        return _label;
    }

    public void setLabel(String label)
    {
        _label = label;
    }

    public String getDataSource()
    {
        return _dataSource;
    }

    public void setDataSource(String dataSource)
    {
        _dataSource = dataSource;
    }

    public String getInfoKey()
    {
        return _infoKey;
    }

    public void setInfoKey(String infoKey)
    {
        _infoKey = infoKey;
    }

    public String getSourceField()
    {
        return _sourceField;
    }

    public void setSourceField(String sourceField)
    {
        _sourceField = sourceField;
    }

    public String getDataType()
    {
        return _dataType;
    }

    public void setDataType(String dataType)
    {
        _dataType = dataType;
    }

    public String getDataNumber()
    {
        return _dataNumber;
    }

    public void setDataNumber(String dataNumber)
    {
        _dataNumber = dataNumber;
    }

    public String getUrl()
    {
        return _url;
    }

    public void setUrl(String url)
    {
        _url = url;
    }

    public String getDataUrl()
    {
        return _dataUrl;
    }

    public void setDataUrl(String dataUrl)
    {
        _dataUrl = dataUrl;
    }

    public String getDescription()
    {
        return _description;
    }

    public void setDescription(String description)
    {
        _description = description;
    }

    public String getToolName()
    {
        return _toolName;
    }

    public void setToolName(String toolName)
    {
        _toolName = toolName;
    }

    public String getFormatString()
    {
        return _formatString;
    }

    public void setFormatString(String formatString)
    {
        _formatString = formatString;
    }

    public boolean isHidden()
    {
        return _hidden != null && _hidden;
    }

    public void setHidden(Boolean hidden)
    {
        _hidden = hidden;
    }

    public boolean isIndexed()
    {
        // Defaults to false when null
        return _isIndexed != null && _isIndexed;
    }

    public void setIndexed(Boolean indexed)
    {
        _isIndexed = indexed;
    }

    public String getAllowableValues()
    {
        return _allowableValues;
    }

    public void setAllowableValues(String allowableValues)
    {
        _allowableValues = allowableValues;
    }

    public Boolean isInDefaultColumns()
    {
        // Defaults to false when null
        return _inDefaultColumns != null && _inDefaultColumns;
    }

    public void setInDefaultColumns(Boolean inDefaultColumns)
    {
        _inDefaultColumns = inDefaultColumns;
    }
}
