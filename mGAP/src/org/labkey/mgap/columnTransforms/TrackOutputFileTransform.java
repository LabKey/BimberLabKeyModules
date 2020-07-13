package org.labkey.mgap.columnTransforms;

import java.util.Map;

/**
 * Created by bimber on 5/1/2017.
 */
public class TrackOutputFileTransform extends AbstractVariantTransform
{
    @Override
    protected Object doTransform(Object inputValue)
    {
        if (null == inputValue)
        {
            getStatusLogger().warn("value was null for track outputfile");
            return null;
        }

        return getOrCreateOutputFile(inputValue, getInputValue("releaseId"), (String)getInputValue("label"));
    }

    @Override
    protected Integer getLibraryId()
    {
        String name = (String)getInputValue("vcfId/library_id/name");
        Map<String, Integer> genomeMap = getGenomeIdMap();
        if (name == null || genomeMap == null)
        {
            return null;
        }

        return genomeMap.get(name);
    }

}
