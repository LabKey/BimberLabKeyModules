package org.labkey.tcrdb.query;

import org.labkey.api.pipeline.PipelineJobService;

import java.io.File;
import java.util.Date;

public class MixcrLibrary
{
    private int _rowId;
    private String _label;
    private String _libraryName;
    private String _species;
    private String _additionalParams;
    private Integer _libraryId;
    private Date _dateDisabled;
    private String _container;
    private Date _created;
    private int _createdBy;
    private Date _modified;
    private int _modifiedBy;

    public MixcrLibrary()
    {

    }

    public int getRowId()
    {
        return _rowId;
    }

    public void setRowId(int rowId)
    {
        _rowId = rowId;
    }

    public String getLabel()
    {
        return _label;
    }

    public void setLabel(String label)
    {
        _label = label;
    }

    public String getLibraryName()
    {
        return _libraryName;
    }

    public void setLibraryName(String libraryName)
    {
        _libraryName = libraryName;
    }

    public String getSpecies()
    {
        return _species;
    }

    public void setSpecies(String species)
    {
        _species = species;
    }

    public String getAdditionalParams()
    {
        return _additionalParams;
    }

    public void setAdditionalParams(String additionalParams)
    {
        _additionalParams = additionalParams;
    }

    public Date getDateDisabled()
    {
        return _dateDisabled;
    }

    public void setDateDisabled(Date dateDisabled)
    {
        _dateDisabled = dateDisabled;
    }

    public String getContainer()
    {
        return _container;
    }

    public void setContainer(String container)
    {
        _container = container;
    }

    public Date getCreated()
    {
        return _created;
    }

    public void setCreated(Date created)
    {
        _created = created;
    }

    public int getCreatedBy()
    {
        return _createdBy;
    }

    public void setCreatedBy(int createdBy)
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

    public int getModifiedBy()
    {
        return _modifiedBy;
    }

    public void setModifiedBy(int modifiedBy)
    {
        _modifiedBy = modifiedBy;
    }

    public File getJsonFile()
    {
        String toolDir = PipelineJobService.get().getAppProperties().getToolsDirectory();
        if (toolDir == null)
        {
            return null;
        }

        File libraryDir = new File(toolDir, "libraries");
        if (!libraryDir.exists())
        {
            return null;
        }

        File json = new File(libraryDir, getLibraryName() + ".json");
        if (json.exists())
        {
            return json;
        }

        json = new File(json.getPath() + ".gz");
        if (json.exists())
        {
            return json;
        }

        return null;
    }

    public Integer getLibraryId()
    {
        return _libraryId;
    }

    public void setLibraryId(Integer libraryId)
    {
        _libraryId = libraryId;
    }
}
