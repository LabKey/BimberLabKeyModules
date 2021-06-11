package org.labkey.mcc.etl;

import org.labkey.api.di.columnTransform.ColumnTransform;

public class ParentageObjectIdTransform extends ColumnTransform
{
    @Override
    protected Object doTransform(Object inputValue)
    {
        if (inputValue == null)
        {
            return null;
        }

        return String.valueOf(inputValue) + getConstant("relationship");
    }
}
