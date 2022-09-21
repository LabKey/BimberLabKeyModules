package org.labkey.flowassays.assay;

import org.json.old.JSONObject;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.gwt.client.util.StringUtils;
import org.labkey.api.laboratory.assay.ImportContext;
import org.labkey.flowassays.FlowAssaysSchema;

import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: bimber
 * Date: 12/11/12
 * Time: 7:55 AM
 */
public class FlowImportHelper
{
    private Map<String, String> _allowableResults = null;
    private final String NAME_FIELD = "name";

    public FlowImportHelper()
    {

    }

    public void normalizePopulationField(Map<String, Object> row, String populationField, ImportContext context)
    {
        if (!row.containsKey(populationField))
            return;

        String population = StringUtils.trimToNull((String) row.get(populationField));
        if (population == null)
            return;

        if (population.startsWith("#"))
            population = population.replaceAll("^#", "");

        if (population.contains("/"))
        {
            String[] tokens = population.split("/");
            population = tokens[0];

            if (StringUtils.trimToNull((String) row.get("units")) == null)
            {
                row.put("units", tokens[1]);
            }
        }

        String normalizedValue = guessPopulation(population);
        if (normalizedValue != null)
            row.put(populationField, normalizedValue);
        else
            context.getErrors().addError("Unknown value for " + populationField + ": " + row.get(populationField));
    }

    public String guessPopulation(String population)
    {
        Map<String, String> allowableResults = getResultValues();
        if (allowableResults.containsKey(population))
            return allowableResults.get(population);
        else
        {
            //if we didnt find a perfect match, try to guess
            Map<String, String> guesses = getGuessedValues(allowableResults);

            String guess = population.replaceAll(" |-", "");
            if (allowableResults.containsKey(guess))
                return allowableResults.get(guess);

            if (guesses.containsKey(guess))
                return guesses.get(guess);

            return null;
        }
    }

    public Map<String, String> getGuessedValues(Map<String, String> allowable)
    {
        Map<String, String> guesses = new CaseInsensitiveHashMap();
        for (String key : allowable.keySet())
        {
            String guess = key.replaceAll(" ", "");
            guesses.put(guess, allowable.get(key));

            guess = key.replaceAll("-", "");
            guesses.put(guess, allowable.get(key));

            guess = key.replaceAll(" |-", "");
            guesses.put(guess, allowable.get(key));
        }

        return guesses;
    }

    private Map<String, String> getResultValues()
    {
        if (_allowableResults != null)
            return _allowableResults;

        TableInfo ti = FlowAssaysSchema.getInstance().getTable(FlowAssaysSchema.TABLE_POPULATIONS);
        TableSelector ts = new TableSelector(ti);
        Map<String, Object>[] rows = ts.getMapArray();
        Map<String, String> ret = new CaseInsensitiveHashMap();
        for (Map<String, Object> row : rows)
        {
            ret.put((String)row.get(NAME_FIELD), (String)row.get(NAME_FIELD));

            if (row.get("marker") != null)
                ret.put((String)row.get("marker"), (String)row.get(NAME_FIELD));

            if (row.get("importAliases") != null)
            {
                String[] tokens = ((String)row.get("importAliases")).split(",");
                for (String token : tokens)
                {
                    token = StringUtils.trimToNull(token);
                    if (token != null)
                        ret.put(token, (String)row.get(NAME_FIELD));
                }
            }
        }
        return _allowableResults = ret;
    }

    public static JSONObject applyDefaultMetadata(JSONObject meta)
    {
        JSONObject runMeta = getJsonObject(meta, "Run");
        JSONObject instrument = getJsonObject(runMeta, "instrument");
        instrument.put("defaultValue", "BD LSR II");
        runMeta.put("instrument", instrument);
        meta.put("Run", runMeta);

        JSONObject resultsMeta = getJsonObject(meta, "Results");

        JSONObject sampleType = getJsonObject(resultsMeta, "sampleType");
        sampleType.put("setGlobally", true);
        sampleType.put("defaultValue", "PBMC");
        resultsMeta.put("sampleType", sampleType);

        JSONObject parentPopulation = getJsonObject(resultsMeta, "parentPopulation");
        parentPopulation.put("setGlobally", true);
        resultsMeta.put("parentPopulation", parentPopulation);

        JSONObject qualResult = getJsonObject(resultsMeta, "qualResult");
        qualResult.put("hidden", true);
        resultsMeta.put("qualResult", qualResult);

        JSONObject plate = getJsonObject(resultsMeta, "plate");
        plate.put("hidden", true);
        resultsMeta.put("plate", plate);

        meta.put("Results", resultsMeta);

        return meta;
    }

    private  static JSONObject getJsonObject(JSONObject parent, String key)
    {
        return parent.containsKey(key) ? parent.getJSONObject(key): new JSONObject();
    }
}
