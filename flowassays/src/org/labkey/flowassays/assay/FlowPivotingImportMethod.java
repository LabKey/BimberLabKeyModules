package org.labkey.flowassays.assay;

import org.json.JSONObject;
import org.labkey.api.data.Container;
import org.labkey.api.exp.api.ExpProtocol;
import org.labkey.api.laboratory.assay.AssayImportMethod;
import org.labkey.api.laboratory.assay.AssayParser;
import org.labkey.api.laboratory.assay.ImportContext;
import org.labkey.api.laboratory.assay.PivotingAssayParser;
import org.labkey.api.laboratory.assay.PivotingImportMethod;
import org.labkey.api.security.User;
import org.labkey.api.view.ViewContext;
import org.labkey.flowassays.FlowAssaysSchema;

import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: bimber
 * Date: 12/11/12
 * Time: 7:21 AM
 */
public class FlowPivotingImportMethod extends PivotingImportMethod
{
    public FlowPivotingImportMethod(AssayImportMethod method)
    {
        super(method, "population", "result", FlowAssaysSchema.getInstance().getTable(FlowAssaysSchema.TABLE_POPULATIONS), "name");
    }

    @Override
    public String getName()
    {
        return "pivotedByPopulation";
    }

    @Override
    public String getLabel()
    {
        return "Pivoted By Population";
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
        return new FlowPivotingAssayParser(this, c, u, assayId);
    }

    private class FlowPivotingAssayParser extends PivotingAssayParser
    {
        private FlowImportHelper _helper = new FlowImportHelper();
        private Map<String, String> _guessed = null;

        public FlowPivotingAssayParser(PivotingImportMethod method, Container c, User u, int assayId)
        {
            super(method, c, u, assayId);
        }

        @Override
        protected String handleUnknownColumn(String col, Map<String, String> allowable, ImportContext context)
        {
            String guessed = _helper.guessPopulation(col);
            if (guessed != null)
            {
                return guessed;
            }
            else
            {
                //TODO: allow a flag that lets us assume known columns hold results
                context.getErrors().addError("Unknown column: " + col);
            }

            return null;
        }
    }
}
