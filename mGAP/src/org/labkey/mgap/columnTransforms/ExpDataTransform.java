package org.labkey.mgap.columnTransforms;

import org.labkey.api.di.columnTransform.ColumnTransform;
import org.labkey.api.exp.api.DataType;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.exp.api.ExperimentService;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Created by bimber on 5/1/2017.
 */
public class ExpDataTransform extends ColumnTransform
{
    @Override
    protected Object doTransform(Object inputValue)
    {
        if (null == inputValue)
            return null;

        try
        {
            URI uri = new URI(String.valueOf(inputValue));
            File f = new File(uri);
            if (!f.exists())
            {
                getStatusLogger().error("File not found: " + uri.toString());
            }

            ExpData d = ExperimentService.get().getExpDataByURL(String.valueOf(inputValue), getContainerUser().getContainer());
            if (d == null)
            {
                d = ExperimentService.get().createData(getContainerUser().getContainer(), new DataType("Variant Catalog"));
                d.setDataFileURI(uri);
                d.setName(f.getName());
                d.save(getContainerUser().getUser());
            }

            return d.getRowId();
        }
        catch (URISyntaxException e)
        {
            getStatusLogger().error("Error syncing file: " + String.valueOf(inputValue), e);
        }

        return null;
    }
}
