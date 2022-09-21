package org.labkey.elispot_assay.assay;

import au.com.bytecode.opencsv.CSVWriter;
import org.apache.commons.lang3.StringUtils;
import org.json.old.JSONObject;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.Container;
import org.labkey.api.exp.api.ExpExperiment;
import org.labkey.api.exp.api.ExpProtocol;
import org.labkey.api.exp.api.ExpRun;
import org.labkey.api.laboratory.assay.AssayImportMethod;
import org.labkey.api.laboratory.assay.AssayParser;
import org.labkey.api.laboratory.assay.ImportContext;
import org.labkey.api.laboratory.assay.ParserErrors;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.security.User;
import org.labkey.api.settings.AppProps;
import org.labkey.api.util.Pair;
import org.labkey.api.view.ViewContext;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: bimber
 * Date: 11/8/12
 * Time: 6:55 PM
 */
public class AIDImportMethod extends DefaultImportMethod
{
    public static final String NAME = "AID Plate Reader";

    public AIDImportMethod(String providerName)
    {
        super(providerName);
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getLabel()
    {
        return NAME;
    }

    @Override
    public String getTooltip()
    {
        return "Choose this option to upload data directly from the output of an AID plate reader.  NOTE: this import method expects that you upload sample information prior to creating the experiment run.";
    }

    @Override
    public boolean hideTemplateDownload()
    {
        return true;
    }

    @Override
    public String getTemplateInstructions()
    {
        return super.getTemplateInstructions() + "<br><br>This import path assumes you prepared this run by creating/saving a template from this site, which defines your plate layout and sample information.  The results you enter below will be merged with that previously imported sample information using well.  When you select a saved plate template using the \'Saved Sample Information\' section above, you should see a list of the samples you uploaded.";
    }

    public AssayParser getFileParser(Container c, User u, int assayId)
    {
        return new Parser(this, c, u, assayId);
    }

    @Override
    public String getExampleDataUrl(ViewContext ctx)
    {
        return AppProps.getInstance().getContextPath() + "/elispot_assay/SampleData/AID.txt";
    }

    @Override
    public JSONObject getMetadata(ViewContext ctx, ExpProtocol protocol)
    {
        JSONObject meta = super.getMetadata(ctx, protocol);

        JSONObject runMeta = getJsonObject(meta, "Run");

        JSONObject instrumentJson = getJsonObject(runMeta, "instrument");
        instrumentJson.put("defaultValue", "AID Plate Reader");
        runMeta.put("instrument", instrumentJson);

        meta.put("Run", runMeta);

        return meta;
    }

    @Override
    public boolean supportsRunTemplates()
    {
        return true;
    }

    private class Parser extends DefaultImportMethod.Parser
    {
        public Parser(AssayImportMethod method, Container c, User u, int assayId)
        {
            super(method, c, u, assayId);
        }

        @Override
        public Pair<ExpExperiment, ExpRun> saveBatch(JSONObject json, File file, String fileName, ViewContext ctx) throws BatchValidationException
        {
            Integer templateId = json.getInt("TemplateId");

            Pair<ExpExperiment, ExpRun> result = super.saveBatch(json, file, fileName, ctx);

            saveTemplate(ctx, templateId, result.second.getRowId());
            return result;
        }

        /**
         * We read the entire raw file, transforming into 1 row per sample/test combination
         * We return a TSV string, which is later fed into a TabLoader.  This is done so we let that code handle type conversion
         */
        @Override
        protected String readRawFile(ImportContext context) throws BatchValidationException
        {
            ParserErrors errors = context.getErrors();

            try (StringWriter sw = new StringWriter(); CSVWriter out = new CSVWriter(sw, '\t'))
            {
                Map<String, Map<String, String>> rowMap = new LinkedHashMap<>();
                int idx = 0;
                boolean withinPlate = false;
                PLATE p = null;
                int rowInPlate = 0;

                for (List<String> row : getFileLines(context.getFile()))
                {
                    String line = StringUtils.trimToNull(StringUtils.join(row, "\n"));
                    idx++;

                    if (StringUtils.isEmpty(line))
                        continue;

                    String[] cells = line.split("( )+");

                    if (!withinPlate)
                    {
                        p = PLATE.getByDescription(line);
                        if (p != null)
                        {
                            withinPlate = true;
                            rowInPlate = 0;
                        }
                    }
                    else
                    {
                        rowInPlate++;
                        if (rowInPlate < 2)
                            continue;

                        processPlateRow(cells, p, rowMap, context);

                        if (rowInPlate == 9)
                        {
                            withinPlate = false;
                            rowInPlate = 0;
                            p = null;
                        }
                    }
                }

                int num = 0;
                for (String well : rowMap.keySet())
                {
                    Map<String, String> row = rowMap.get(well);
                    if (num == 0)
                    {
                        List<String> toAdd = new ArrayList<>();
                        toAdd.add("well");
                        for (String field : row.keySet())
                        {
                            toAdd.add(field);
                        }
                        out.writeNext(toAdd.toArray(new String[toAdd.size()]));
                    }
                    num++;

                    List<String> toAdd = new ArrayList<>();
                    toAdd.add(well);
                    for (String field : row.keySet())
                    {
                        toAdd.add(row.get(field));
                    }
                    out.writeNext(toAdd.toArray(new String[toAdd.size()]));
                }

                errors.confirmNoErrors();

                return sw.toString();
            }
            catch (IOException e)
            {
                errors.addError(e.getMessage());
                throw errors.getErrors();
            }
        }

        private void processPlateRow(String[] cells, PLATE p, Map<String, Map<String, String>> rowMap, ImportContext context)
        {
            String letter = cells[0];

            int pos = 1;
            while (pos < cells.length)
            {
                String well = letter + pos;
                Map<String, String> row = rowMap.get(well);
                if (row == null)
                    row = new LinkedHashMap<String, String>();

                Integer value = null;
                try
                {
                    //indicates too numerous to count.  use an arbitrary value.
                    if ("TNTC".equals(cells[pos]))
                        value = 9999;
                    else
                        value = Integer.parseInt(cells[pos]);
                }
                catch (NumberFormatException e)
                {
                    context.getErrors().addError("Non-numeric value for spots in well: " + well + ".  Value was: " + cells[pos]);
                    value = null;
                }

                row.put(p.field, value == null ? "" : value.toString());

                rowMap.put(well, row);
                pos++;
            }
        }

        @Override
        protected List<Map<String, Object>> processRows(List<Map<String, Object>> rows, ImportContext context) throws BatchValidationException
        {
            List<Map<String, Object>> newRows = new ArrayList<Map<String, Object>>();
            ParserErrors errors = context.getErrors();

            String keyProperty = "well";
            Map<String, Map<String, Object>> templateRows = getTemplateRowMap(context, keyProperty);

            ListIterator<Map<String, Object>> rowsIter = rows.listIterator();
            int rowIdx = 0;
            while (rowsIter.hasNext())
            {
                try
                {
                    rowIdx++;
                    Map<String, Object> row = rowsIter.next();
                    Map<String, Object> map = new CaseInsensitiveHashMap<Object>(row);

                    appendPromotedResultFields(map, context);

                    //associate/merge sample information with the incoming results
                    if (!mergeTemplateRow(keyProperty, templateRows, map, context, true))
                        continue;

                    newRows.add(map);
                }
                catch (IllegalArgumentException e)
                {
                    errors.addError(e.getMessage());
                }
            }

            validateRows(newRows, context);
            context.getErrors().confirmNoErrors();

            calculatePositivity(newRows, context);
            context.getErrors().confirmNoErrors();

            return newRows;
        }
    }

    private enum PLATE
    {
        spot("Spot counts:", "spots", Collections.singletonList("Number of Spots:")),
        saturation("Well's saturation values (%)", "saturation", Collections.singletonList("Well's saturation values (%)")),
        cytokine("Cytokine Activities:", "cytokine", Collections.singletonList("Activity:"));

        private String description;
        private String field;
        private Collection<String> aliases;

        PLATE(String description, String field, Collection<String> aliases)
        {
            this.description = description;
            this.field = field;
            this.aliases = aliases;
        }

        public static PLATE getByDescription(String description)
        {
            for (PLATE t : PLATE.values())
            {
                if (t.description.equalsIgnoreCase(description))
                    return t;
                else if (t.aliases != null)
                {
                    for (String alias : t.aliases)
                    {
                        if (alias.equals(description))
                        {
                            return t;
                        }
                    }
                }
            }
            return null;
        }
    }
}
