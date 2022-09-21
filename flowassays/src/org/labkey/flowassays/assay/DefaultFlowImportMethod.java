package org.labkey.flowassays.assay;

import org.json.old.JSONObject;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.Container;
import org.labkey.api.exp.api.ExpProtocol;
import org.labkey.api.laboratory.assay.AssayImportMethod;
import org.labkey.api.laboratory.assay.AssayParser;
import org.labkey.api.laboratory.assay.DefaultAssayImportMethod;
import org.labkey.api.laboratory.assay.DefaultAssayParser;
import org.labkey.api.laboratory.assay.ImportContext;
import org.labkey.api.laboratory.assay.ParserErrors;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.security.User;
import org.labkey.api.view.ViewContext;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: bimber
 * Date: 12/11/12
 * Time: 7:54 AM
 */
public class DefaultFlowImportMethod extends DefaultAssayImportMethod
{
    public DefaultFlowImportMethod(String providerName)
    {
        super(providerName);
    }

    @Override
    public JSONObject getMetadata(ViewContext ctx, ExpProtocol protocol)
    {
        JSONObject meta = super.getMetadata(ctx, protocol);
        FlowImportHelper.applyDefaultMetadata(meta);
        return meta;
    }

    @Override
    public AssayParser getFileParser(Container c, User u, int assayId)
    {
        return new Parser(this, c, u, assayId);
    }

    private class Parser extends DefaultAssayParser
    {
        public Parser(AssayImportMethod method, Container c, User u, int assayId)
        {
            super(method, c, u, assayId);
        }

        @Override
        protected List<Map<String, Object>> processRowsFromFile(List<Map<String, Object>> rows, ImportContext context) throws BatchValidationException
        {
            ListIterator<Map<String, Object>> rowsIter = rows.listIterator();
            ParserErrors errors = context.getErrors();

            FlowImportHelper helper = new FlowImportHelper();
            List<Map<String, Object>> newRows = new ArrayList<Map<String, Object>>();

            while (rowsIter.hasNext())
            {
                Map<String, Object> row = new CaseInsensitiveHashMap(rowsIter.next());
                appendPromotedResultFields(row, context);

                helper.normalizePopulationField(row, "population", context);
                helper.normalizePopulationField(row, "parentPopulation", context);
                newRows.add(row);
            }

            errors.confirmNoErrors();

            return newRows;
        }
    }
}
