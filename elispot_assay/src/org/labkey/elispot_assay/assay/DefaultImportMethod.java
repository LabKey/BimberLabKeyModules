package org.labkey.elispot_assay.assay;

import org.apache.commons.beanutils.ConversionException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.commons.math3.stat.inference.TestUtils;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.Container;
import org.labkey.api.data.ConvertHelper;
import org.labkey.api.exp.api.ExpProtocol;
import org.labkey.api.laboratory.assay.AssayImportMethod;
import org.labkey.api.laboratory.assay.AssayParser;
import org.labkey.api.laboratory.assay.DefaultAssayImportMethod;
import org.labkey.api.laboratory.assay.DefaultAssayParser;
import org.labkey.api.laboratory.assay.ImportContext;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.query.ValidationException;
import org.labkey.api.security.User;
import org.labkey.api.util.JsonUtil;
import org.labkey.api.view.ViewContext;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: bimber
 * Date: 11/1/12
 * Time: 4:51 PM
 */
public class DefaultImportMethod extends DefaultAssayImportMethod
{
    protected static final String SUBJECT_FIELD = "subjectId";
    protected static final String DATE_FIELD = "date";
    protected static final String PEPTIDE_FIELD = "peptide";
    protected static final String THRESHOLD_FIELD = "positivity_threshold";
    protected static final String MIN_SPOTS_FIELD = "minspots";
    protected static final String SPOTS_FIELD = "spots";
    protected static final String SPOTS_ABOVE_FIELD = "spotsAboveBackground";
    protected static final String QUAL_RESULT_FIELD = "qual_result";
    protected static final String QCFLAG_FIELD = "qcflag";
    protected static final String CATEGORY_FIELD = "category";
    //protected static final String STDDEV_FIELD = "std_deviations";
    protected static final String PVAL_FIELD = "pvalue";
    protected static final String REPLICATES_FIELD = "num_replicates";

    public DefaultImportMethod(String providerName)
    {
        super(providerName);
    }

    @Override
    public AssayParser getFileParser(Container c, User u, int assayId)
    {
        return new Parser(this, c, u, assayId);
    }

    @Override
    public String getTemplateInstructions()
    {
        return "This assay will perform several calculations on import.  Samples are grouped into replicates based on subjectId, date and peptide.  For each subjectId/date, negative controls are required.  Positivity is calculated using a homoscedastic t-test, comparing the spot counts from each sample replicate against the corresponding negative control wells.  A pvalue is reported for each well, and wells with be flagged as either positive for negative based on the threshold set above (which defaults to 0.05).  The imported results will report both the raw spot count and the spots above background, which is the raw count count minus the average of the negative controls.";
    }

    @Override
    public JSONObject getMetadata(ViewContext ctx, ExpProtocol protocol)
    {
        JSONObject meta = super.getMetadata(ctx, protocol);

        JSONObject runMeta = getJsonObject(meta, "Run");

        JSONObject assayJson = getJsonObject(runMeta, "assayName");
        assayJson.put("defaultValue", 1);
        runMeta.put("assayName", assayJson);

        JSONObject spotsJson = getJsonObject(runMeta, "minspots");
        spotsJson.put("defaultValue", 2);
        runMeta.put("minspots", spotsJson);

        meta.put("Run", runMeta);


        JSONObject resultMeta = getJsonObject(meta, "Results");
        String[] globalResultFields = new String[]{"sampleType"};
        for (String field : globalResultFields)
        {
            JSONObject json = getJsonObject(resultMeta, field);
            json.put("setGlobally", true);
            resultMeta.put(field, json);
        }

        meta.put("Results", resultMeta);

        return meta;
    }

    protected List<Map<String, Object>> calculatePositivity(List<Map<String, Object>> rows, ImportContext context) throws BatchValidationException
    {
        String delim = "/";

        Map<String, Object> runProperties = context.getRunProperties();
        List<Map<String, Object>> newRows = new ArrayList<>();
        Map<String, List<Double>> negWellMap = new CaseInsensitiveHashMap<>();
        Map<String, String> sampleKeyToNegCtlKey = new HashMap<>();

        Map<String, List<Map<String, Object>>> map = new CaseInsensitiveHashMap<>();
        for (Map<String, Object> row : rows)
        {
            //build a map of rows, grouped by id/date/peptide
            StringBuilder sb = new StringBuilder();
            sb.append(row.get(SUBJECT_FIELD)).append(delim);
            sb.append(row.get(DATE_FIELD)).append(delim);
            sb.append(row.get(PEPTIDE_FIELD)).append(delim);
            String key = sb.toString();

            List<Map<String, Object>> foundRows = map.get(key);
            if (foundRows == null)
                foundRows = new ArrayList<>();

            foundRows.add(row);
            map.put(key, foundRows);

            //also find the negative controls for this subject/date
            StringBuilder negSb = new StringBuilder();
            negSb.append(row.get(SUBJECT_FIELD)).append(delim);
            negSb.append(row.get(DATE_FIELD));
            String negCtlKey = negSb.toString();
            sampleKeyToNegCtlKey.put(key, negCtlKey);

            if (TYPE.NEG.getText().equals(row.get(CATEGORY_FIELD)))
            {
                Integer spots = (Integer)row.get(SPOTS_FIELD);
                List<Double> negCtls = negWellMap.get(negCtlKey);
                if (negCtls == null)
                    negCtls = new ArrayList<Double>();

                negCtls.add(spots.doubleValue());

                negWellMap.put(negCtlKey, negCtls);
            }

            //append placeholder used later in validation
            if (!negWellMap.containsKey(negCtlKey))
                negWellMap.put(negCtlKey, null);
        }

        //now validate neg ctls
        for (String key : negWellMap.keySet())
        {
            List<Double> negCtls = negWellMap.get(key);
            if (negCtls == null || negCtls.size() == 0)
            {
                context.getErrors().addError("No negative control wells were found for sample: " + key);
                throw context.getErrors().getErrors();
            }

            if (negCtls.size() < 2)
            {
                context.getErrors().addError("At least 2 negative controls are required to calculate positivity.  Missing for sample: " + key);
                throw context.getErrors().getErrors();
            }
        }

        Integer minspots = runProperties.get(MIN_SPOTS_FIELD) != null ? Integer.valueOf(runProperties.get(MIN_SPOTS_FIELD).toString()) : null;
        if (minspots == null)
        {
            minspots = 0;
            runProperties.put(MIN_SPOTS_FIELD, minspots);
        }

        Double threshold = runProperties.get(THRESHOLD_FIELD) != null ? Double.parseDouble(runProperties.get(THRESHOLD_FIELD).toString()) : null;
        if (threshold == null)
        {
            threshold = 0.05;
            runProperties.put(THRESHOLD_FIELD, threshold);
        }

        for (String key : map.keySet())
        {
            List<Map<String, Object>> rowSet = map.get(key);
            double totalSpots = 0.0;
            double[] allSpots = new double[rowSet.size()];
            int rowIdx = 0;
            for (Map<String, Object> row : rowSet)
            {
                totalSpots += (Integer)row.get(SPOTS_FIELD);
                allSpots[rowIdx] = (Integer)row.get(SPOTS_FIELD);
                rowIdx++;
            }
            Double avgSpots = totalSpots / rowSet.size();
            double rowStdDev = new StandardDeviation().evaluate(allSpots);
            double cv = rowStdDev / avgSpots;
            List<String> qcflags = new ArrayList<String>();
            if (cv > 0.2 && avgSpots > 10)
            {
                qcflags.add("High CV: " + new DecimalFormat("0.###").format(cv));
            }

            String negCtlKey = sampleKeyToNegCtlKey.get(key);
            List<Double> negCtls = negWellMap.get(negCtlKey);
            assert negCtls != null;  //should be confirmed above

            double[] negCtlArray = new double[negCtls.size()];
            SummaryStatistics negCtlSummary = new SummaryStatistics();
            int i = 0;
            for (Double d : negCtls)
            {
                negCtlArray[i] = d;
                negCtlSummary.addValue(d);
                i++;
            }

            if (negCtlArray.length < 2)
            {
                context.getErrors().addError("Must provide at least 2 negative controls for each sample/date.  Missing for: " + key);
                throw context.getErrors().getErrors();
            }

            int numReplicates = allSpots.length;
            if (allSpots.length < 2)
            {
                //TODO: remove after legacy data added
                if (allSpots.length == 1)
                {
                    double[] spots = new double[2];
                    spots[0] = allSpots[0];
                    spots[1] = allSpots[0];
                    allSpots = spots;

                    qcflags.add("Only one replicate provided");
                }
                else
                {
                    context.getErrors().addError("Replicate not provided for sample: " + key, Level.WARN);
                    throw context.getErrors().getErrors();
                }
            }

            //NOTE: see below for detail on calculation
            //http://commons.apache.org/proper/commons-math//apidocs/org/apache/commons/math3/stat/inference/TTest.html
            Double alpha = TestUtils.homoscedasticTTest(allSpots, negCtlArray);
            if (Double.isNaN(alpha))
                alpha = null;

            Integer qualResult;
            if (alpha != null && alpha >= 0 && alpha <= threshold && avgSpots > negCtlSummary.getMean() && avgSpots >= minspots)
                qualResult = QUAL_RESULT.POS.getRowId();
            else
                qualResult = QUAL_RESULT.NEG.getRowId();

            for (Map<String, Object> row : rowSet)
            {
                row.put(QUAL_RESULT_FIELD, qualResult);
                row.put(REPLICATES_FIELD, numReplicates);
                row.put(PVAL_FIELD, alpha);
                if (qcflags.size() > 0)
                    row.put(QCFLAG_FIELD, StringUtils.join(qcflags, "\n"));

                //subtract background
                Integer spots = (Integer)row.get(SPOTS_FIELD);
                Double adj = spots - negCtlSummary.getMean();
                List<String> comments = new ArrayList<String>();
                if (row.get("comment") != null)
                    comments.add((String)row.get("comment"));

                comments.add("Avg of Replicates: " + new DecimalFormat("0.##").format(avgSpots));
                comments.add("# Replicates: " + numReplicates);
                comments.add("Avg Neg Ctls: " + new DecimalFormat("0.##").format(negCtlSummary.getMean()));
                comments.add("# Neg Ctls: " + negCtlSummary.getN());
                row.put("comment", StringUtils.join(comments, "\n"));
                row.put(SPOTS_ABOVE_FIELD, adj);

                newRows.add(row);
            }
        }

        return newRows;
    }

    @Override
    public void validateTemplate(User u, Container c, ExpProtocol protocol, @Nullable Integer templateId, String title, JSONObject json, BatchValidationException errors) throws BatchValidationException
    {
        //ensure each subject/date has at least 2 neg controls
        JSONObject resultDefaults = json.optJSONObject("Results");
        JSONArray rawResults = json.getJSONArray("ResultRows");
        List<JSONObject> results = new ArrayList<JSONObject>();

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        Map<String, Double> negCtlCounts = new HashMap<String, Double>();
        for (JSONObject row : JsonUtil.toJSONObjectList(rawResults))
        {
            for (String prop : resultDefaults.keySet())
            {
                row.put(prop, resultDefaults.get(prop));
            }
            results.add(row);

            try
            {
                Date date = null;
                if (row.get("date") != null)
                {
                    if (row.get("date") instanceof String)
                    {
                        date = ConvertHelper.convert(row.get("date"), Date.class);
                    }
                    else if (row.get("date") instanceof Date)
                    {
                        date = (Date)row.get("date");
                    }
                }

                String key = row.getString("subjectId") + (date == null ? "" : " / " + dateFormat.format(date));
                Double count = negCtlCounts.get(key);
                if (count == null)
                    count = 0.0;

                if (TYPE.NEG.getText().equals(row.get(CATEGORY_FIELD)))
                    count++;

                negCtlCounts.put(key, count);
            }
            catch (ConversionException e)
            {
                errors.addRowError(new ValidationException("Invalid date: " + row.get("date")));
            }
        }

        for (String key : negCtlCounts.keySet())
        {
            if (negCtlCounts.get(key) < 2)
            {
                errors.addRowError(new ValidationException("Must provide at least 2 negative controls for each subjectId/date.  Missing for: " + key));
            }
        }

        super.validateTemplate(u, c, protocol, templateId, title, json, errors);

        if (errors.hasErrors())
            throw errors;
    }

    private enum TYPE
    {
        POS("Pos Control"),
        NEG("Neg Control"),
        Unknown("Unknown");

        private String text;

        TYPE (String text)
        {
            this.text = text;
        }

        public String getText()
        {
            return text;
        }
    }

    protected class Parser extends DefaultAssayParser
    {
        public Parser(AssayImportMethod method, Container c, User u, int assayId)
        {
            super(method, c, u, assayId);
        }

        @Override
        protected List<Map<String, Object>> processRows(List<Map<String, Object>> rows, ImportContext context) throws BatchValidationException
        {
            List<Map<String, Object>> newRows = super.processRows(rows, context);
            newRows = calculatePositivity(newRows, context);

            return newRows;
        }
    }
}