package org.labkey.flowassays.assay;

import org.json.JSONObject;
import org.labkey.api.laboratory.assay.AbstractAssayDataProvider;
import org.labkey.api.laboratory.assay.AssayImportMethod;
import org.labkey.api.module.Module;
import org.labkey.api.view.ViewContext;
import org.labkey.flowassays.FlowAssaysManager;

import java.util.Arrays;

/**
 * Created with IntelliJ IDEA.
 * User: bimber
 * Date: 11/8/12
 * Time: 7:03 PM
 */
public class ImmunophenotypingDataProvider extends AbstractAssayDataProvider
{
    public ImmunophenotypingDataProvider(Module m){
        _providerName = FlowAssaysManager.IMMUNPHENOTYPING_ASSAYNAME;
        _module = m;

        AssayImportMethod method = new DefaultFlowImportMethod(_providerName);
        _importMethods.add(method);
        _importMethods.add(new FlowPivotingImportMethod(method));
    }

    @Override
    public JSONObject getTemplateMetadata(ViewContext ctx)
    {
        JSONObject meta = super.getTemplateMetadata(ctx);
        JSONObject domainMeta = meta.getJSONObject("domains");

        JSONObject resultMeta = getJsonObject(domainMeta, "Results");
        String[] hiddenResultFields = new String[]{"plate"};
        for (String field : hiddenResultFields)
        {
            JSONObject json = getJsonObject(resultMeta, field);
            json.put("hidden", true);
            resultMeta.put(field, json);
        }

        String[] requiredFields = new String[]{"well", "subjectId", "category"};
        for (String field : requiredFields)
        {
            JSONObject json = getJsonObject(resultMeta, field);
            json.put("nullable", false);
            json.put("allowBlank", false);
            resultMeta.put(field, json);
        }

        String[] globalResultFields = new String[]{"sampleType"};
        for (String field : globalResultFields)
        {
            JSONObject json = getJsonObject(resultMeta, field);
            json.put("setGlobally", true);
            resultMeta.put(field, json);
        }

        domainMeta.put("Results", resultMeta);

        meta.put("domains", domainMeta);
        meta.put("colOrder", Arrays.asList("plate", "well", "category", "subjectId"));
        //meta.put("showPlateLayout", true);

        return meta;
    }
}
