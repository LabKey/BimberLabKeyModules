/*
 * Copyright (c) 2013 LabKey Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.labkey.onprc_billing.notification;

import org.apache.commons.lang3.time.DateUtils;
import org.json.JSONObject;
import org.labkey.api.data.Aggregate;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Container;
import org.labkey.api.data.Results;
import org.labkey.api.data.ResultsImpl;
import org.labkey.api.data.SQLFragment;
import org.labkey.api.data.Selector;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.Sort;
import org.labkey.api.data.SqlSelector;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.ehr.EHRService;
import org.labkey.api.ldk.notification.AbstractNotification;
import org.labkey.api.ldk.notification.Notification;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryDefinition;
import org.labkey.api.query.QueryException;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.UserSchema;
import org.labkey.api.security.User;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.Pair;
import org.labkey.onprc_billing.ONPRC_BillingManager;
import org.labkey.onprc_billing.ONPRC_BillingModule;
import org.labkey.onprc_billing.ONPRC_BillingSchema;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Created with IntelliJ IDEA.
 * User: bimber
 * Date: 4/5/13
 * Time: 2:25 PM
 */
public class FinanceNotification extends AbstractNotification
{
    private static final DecimalFormat _dollarFormat = new DecimalFormat("$###,##0.00");

    public FinanceNotification()
    {
        super(ModuleLoader.getInstance().getModule(ONPRC_BillingModule.class));
    }

    @Override
    public String getName()
    {
        return "Finance Notification";
    }

    @Override
    public String getCategory()
    {
        return "Billing";
    }

    @Override
    public String getEmailSubject()
    {
        return "Finance/Billing Alerts: " + _dateTimeFormat.format(new Date());
    }

    @Override
    public String getCronString()
    {
        return "0 0 8 * * ?";
    }

    @Override
    public String getScheduleDescription()
    {
        return "every day at 8:00AM";
    }

    @Override
    public String getDescription()
    {
        return "This report is designed to provide a daily summary of current or projected charges since the last invoice date.  It will summarize the total dollar amount, as well as flag suspicious or incomplete items.";
    }

    @Override
    public String getMessage(Container c, User u)
    {
        StringBuilder msg = new StringBuilder();

        Date now = new Date();
        msg.append(getDescription() + "  It was run on: " + _dateFormat.format(now) + " at " + _timeFormat.format(now) + ".<p>");

        Container financeContainer = ONPRC_BillingManager.get().getBillingContainer(c);
        if (financeContainer == null)
        {
            log.error("Finance container is not defined, so the FinanceNotification cannot run");
            return null;
        }

        Date lastInvoiceDate = getLastInvoiceDate(c, u);
        //if we have no previous value, set to an arbitrary value
        if (lastInvoiceDate == null)
            lastInvoiceDate = DateUtils.truncate(new Date(0), Calendar.DATE);

        Map<String, Map<String, Map<String, Map<String, Integer>>>> projectMap = new TreeMap<>();
        Map<String, String> projectToAccountMap = new HashMap<>();
        Map<String, Map<String, Double>> totalsByCategory = new TreeMap<>();

        Calendar start = Calendar.getInstance();
        start.setTime(lastInvoiceDate);
        start.add(Calendar.DATE, 1);

        Calendar endDate = Calendar.getInstance();
        endDate.setTime(new Date());
        endDate.add(Calendar.DATE, 1);

        Map<String, String> categoryToQuery = new HashMap<>();
        categoryToQuery.put("Per Diems", "perDiemRates");
        categoryToQuery.put("Lease Fees", "leaseFeeRates");
        categoryToQuery.put("Procedure Charges", "procedureFeeRates");
        categoryToQuery.put("Labwork Charges", "labworkFeeRates");
        categoryToQuery.put("Other Charges", "miscChargesFeeRates");
        categoryToQuery.put("SLA Per Diems", "slaPerDiemRates");

        Map<String, Container> containerMap = new HashMap<>();
        Container slaContainer = ONPRC_BillingManager.get().getSLADataFolder(c);
        if (slaContainer != null)
        {
            getProjectSummary(slaContainer, u, start, endDate, "SLA Per Diems", categoryToQuery, projectMap, projectToAccountMap, totalsByCategory);
            containerMap.put("SLA Per Diems", slaContainer);
        }

        Container ehrContainer = EHRService.get().getEHRStudyContainer(c);
        if (ehrContainer != null)
        {
            getProjectSummary(ehrContainer, u, start, endDate, "Per Diems", categoryToQuery, projectMap, projectToAccountMap, totalsByCategory);
            containerMap.put("Per Diems", ehrContainer);

            getProjectSummary(ehrContainer, u, start, endDate, "Lease Fees", categoryToQuery, projectMap, projectToAccountMap, totalsByCategory);
            containerMap.put("Lease Fees", ehrContainer);

            getProjectSummary(ehrContainer, u, start, endDate, "Procedure Charges", categoryToQuery, projectMap, projectToAccountMap, totalsByCategory);
            containerMap.put("Procedure Charges", ehrContainer);

            getProjectSummary(ehrContainer, u, start, endDate, "Labwork Charges", categoryToQuery, projectMap, projectToAccountMap, totalsByCategory);
            containerMap.put("Labwork Charges", ehrContainer);

            getProjectSummary(ehrContainer, u, start, endDate, "Other Charges", categoryToQuery, projectMap, projectToAccountMap, totalsByCategory);
            containerMap.put("Other Charges", ehrContainer);
        }

        writeResultTable(msg, lastInvoiceDate, start, endDate, projectMap, projectToAccountMap, totalsByCategory, categoryToQuery, containerMap);

        getExpiredAliases(c, u , msg);
        getAliasesDisabled(c, u, msg);
        getProjectsWithoutAliases(c, u, msg);
        getProjectsNotActive(c, u, msg);
        chargesMissingRates(c, u, msg);
        getExpiredCreditAliases(c, u, msg);
        getCreditAliasesDisabled(c, u, msg);
        simpleAlert(c, u , msg, "onprc_billing", "invalidChargeRateEntries", " charge rate records with invalid or overlapping intervals.  This indicates a problem with how the records are setup in the system and may cause problems with the billing calculation.");
        simpleAlert(c, u , msg, "onprc_billing", "invalidChargeRateExemptionEntries", " charge rate exemptions with invalid or overlapping intervals.  This indicates a problem with how the records are setup in the system and may cause problems with the billing calculation.");
        simpleAlert(c, u , msg, "onprc_billing", "invalidCreditAccountEntries", " credit account records with invalid or overlapping intervals.  This indicates a problem with how the records are setup in the system and may cause problems with the billing calculation.");
        simpleAlert(c, u , msg, "onprc_billing", "duplicateChargeableItems", " active chargeable item records with duplicate names or item codes.  This indicates a problem with how the records are setup in the system and may cause problems with the billing calculation.");

        return msg.toString();
    }

    private class FieldDescriptor {
        private String _fieldName;
        private boolean _flagIfNonNull;
        private String _label;
        private boolean _shouldHighlight;

        public FieldDescriptor(String fieldName, boolean flagIfNonNull, String label, boolean shouldHighlight)
        {            
            _fieldName = fieldName;
            _flagIfNonNull = flagIfNonNull;
            _label = label;
            _shouldHighlight = shouldHighlight;
        }

        public String getFieldName()
        {
            return _fieldName;
        }

        private boolean isShouldHighlight()
        {
            return _shouldHighlight;
        }

        public FieldKey getFieldKey()
        {
            return FieldKey.fromString(_fieldName);
        }

        public String getLabel()
        {
            return _label;
        }

        public boolean shouldFlag(Object val)
        {
            return _flagIfNonNull ? val != null : val == null;
        }

        public String getFilter()
        {
            return "&query." + getFieldName() + "~" + (_flagIfNonNull ? "isnonblank" : "isblank");
        }
    }

    private FieldDescriptor[] _fields = new FieldDescriptor[]
    {
        new FieldDescriptor("project", false, "Missing Project", true),
        new FieldDescriptor("isMissingAccount", true, "Missing Alias", true),
        new FieldDescriptor("isExpiredAccount", true, "Expired Alias", true),
        new FieldDescriptor("isAcceptingCharges", true, "Alias Not Accepting Charges", true),
        new FieldDescriptor("lacksRate", true, "Lacks Rate", true),
        new FieldDescriptor("creditAccount", false, "Missing Credit Alias", true),
        new FieldDescriptor("isMissingFaid", true, "Missing FAID", true),
        new FieldDescriptor("matchesProject", true, "Project Does Not Match Assignment", false),
        //new FieldDescriptor("isMiscCharge", true, "Manually Entered", false),
        new FieldDescriptor("isAdjustment", true, "Adjustment/Reversal", false),
        new FieldDescriptor("isExemption", true, "Non-standard Rate", false),
        new FieldDescriptor("isOldCharge", true, "Over 45 Days Old", false),
        new FieldDescriptor("investigatorId", false, "Missing Investigator", true),
        new FieldDescriptor("isMultipleProjects", true, "Per Diems Split Between Projects", false)
    };

    private void getProjectSummary(Container c, User u, final Calendar start, Calendar endDate, final String categoryName, Map<String, String> categoryToQuery, final Map<String, Map<String, Map<String, Map<String, Integer>>>> projectMap, final Map<String, String> projectToAccountMap, final Map<String, Map<String, Double>> totalsByCategory)
    {
        UserSchema us = QueryService.get().getUserSchema(u, c, ONPRC_BillingSchema.NAME);
        QueryDefinition qd = us.getQueryDefForTable(categoryToQuery.get(categoryName));
        List<QueryException> errors = new ArrayList<>();
        TableInfo ti = qd.getTable(us, errors, true);

        Map<String, Object> params = new HashMap<>();
        Long numDays = ((DateUtils.truncate(new Date(), Calendar.DATE).getTime() - start.getTimeInMillis()) / DateUtils.MILLIS_PER_DAY) + 1;
        params.put("StartDate", start.getTime());
        params.put("EndDate", endDate.getTime());
        params.put("NumDays", numDays.intValue());

        Set<FieldKey> fieldKeys = new HashSet<>();
        for (ColumnInfo col : ti.getColumns())
        {
            fieldKeys.add(col.getFieldKey());
        }

        for (FieldDescriptor fd : _fields)
        {
            fieldKeys.add(fd.getFieldKey());
        }

        fieldKeys.add(FieldKey.fromString("project/displayName"));
        fieldKeys.add(FieldKey.fromString("project/account"));
        fieldKeys.add(FieldKey.fromString("project/account/fiscalAuthority/lastName"));

        final Map<FieldKey, ColumnInfo> cols = QueryService.get().getColumns(ti, fieldKeys);
        TableSelector ts = new TableSelector(ti, cols.values(), null, null);
        ts.setNamedParameters(params);

        ts.forEach(new Selector.ForEachBlock<ResultSet>()
        {
            @Override
            public void exec(ResultSet object) throws SQLException
            {
                Results rs = new ResultsImpl(object, cols);
                Map<String, Double> totalsMap = totalsByCategory.get(categoryName);
                if (totalsMap == null)
                    totalsMap = new HashMap<>();

                Double totalCost = rs.getDouble(FieldKey.fromString("totalCost"));
                if (totalCost != null)
                {
                    Double t = totalsMap.containsKey("totalCost") ? totalsMap.get("totalCost") : 0.0;
                    t += totalCost;
                    totalsMap.put("totalCost", t);
                }

                Double quantity = rs.getDouble(FieldKey.fromString("quantity"));
                if (quantity != null)
                {
                    Double t = totalsMap.containsKey("total") ? totalsMap.get("total") : 0.0;
                    t += quantity;
                    totalsMap.put("total", t);
                }

                totalsByCategory.put(categoryName, totalsMap);
                
                String projectDisplay = rs.getString(FieldKey.fromString("project/displayName"));
                if (projectDisplay == null)
                {
                    projectDisplay = "None";
                }

                String financialAnalyst = rs.getString(FieldKey.fromString("project/account/fiscalAuthority/lastName"));
                if (financialAnalyst == null)
                {
                    financialAnalyst = "Not Assigned";
                }

                String account = rs.getString(FieldKey.fromString("project/account"));
                projectToAccountMap.put(projectDisplay, account);

                for (FieldDescriptor fd : _fields)
                {
                    if (!rs.hasColumn(fd.getFieldKey()))
                    {
                        continue;
                    }

                    Object val = rs.getObject(fd.getFieldKey());
                    if (fd.shouldFlag(val))
                    {
                        Map<String, Map<String, Map<String, Integer>>> valuesForFA = projectMap.get(financialAnalyst);
                        if (valuesForFA == null)
                            valuesForFA = new TreeMap<>();

                        Map<String, Map<String, Integer>> valuesForProject = valuesForFA.get(projectDisplay);
                        if (valuesForProject == null)
                            valuesForProject = new TreeMap<>();

                        Map<String, Integer> values = valuesForProject.get(categoryName);
                        if (values == null)
                            values = new HashMap<>();


                        Integer count = values.containsKey(fd.getFieldName()) ? values.get(fd.getFieldName()) : 0;
                        count++;
                        values.put(fd.getFieldName(), count);

                        valuesForProject.put(categoryName, values);
                        valuesForFA.put(projectDisplay, valuesForProject);
                        projectMap.put(financialAnalyst, valuesForFA);
                    }
                }
            }
        });
    }

    private void writeResultTable(final StringBuilder msg, Date lastInvoiceEnd, Calendar start, Calendar endDate, final Map<String, Map<String, Map<String, Map<String, Integer>>>> projectMap, final Map<String, String> projectToAccountMap, final Map<String, Map<String, Double>> totalsByCategory, Map<String, String> categoryToQuery, Map<String, Container> containerMap)
    {
        msg.append("<b>Charge Summary:</b><p>");
        msg.append("The table below summarizes projected charges since the since the last invoice date of " + _dateFormat.format(lastInvoiceEnd));

        msg.append("<table border=1 style='border-collapse: collapse;'><tr style='font-weight: bold;'><td>Category</td><td># Items</td><td>Amount</td>");
        for (String category : totalsByCategory.keySet())
        {
            Map<String, Double> totalsMap = totalsByCategory.get(category);
            Container container = containerMap.get(category);

            String url = getExecuteQueryUrl(container, ONPRC_BillingSchema.NAME, categoryToQuery.get(category), null) + "&query.param.StartDate=" + _dateFormat.format(start.getTime()) + "&query.param.EndDate=" + _dateFormat.format(endDate.getTime());
            msg.append("<tr><td><a href='" + url + "'>" + category + "</a></td><td>" + totalsMap.get("total") + "</td><td>" + _dollarFormat.format(totalsMap.get("totalCost")) + "</td></tr>");
        }
        msg.append("</table><br><br>");

        msg.append("The tables below highlight any suspicious or abnormal items, grouped by project.  These will not necessarily be problems, but may warrant investigation.<br><br>");

        for (String financialAnalyst : projectMap.keySet())
        {
            //first build header row.  we want to keep fields in the same order as _fields for consistency between tables
            Set<FieldDescriptor> foundCols = new LinkedHashSet<>();
            outerloop:
            for (FieldDescriptor fd : _fields)
            {
                for (String projectDisplay : projectMap.get(financialAnalyst).keySet())
                {
                    Map<String, Map<String, Integer>> projectDataByCategory = projectMap.get(financialAnalyst).get(projectDisplay);
                    for (String category : projectDataByCategory.keySet())
                    {
                        if (projectDataByCategory.get(category).containsKey(fd.getFieldName()))
                        {
                            foundCols.add(fd);
                            continue outerloop;
                        }
                    }
                }
            }

            msg.append("<table border=1 style='border-collapse: collapse;'><tr style='font-weight: bold;'><td>Financial Analyst</td><td>Project</td><td>Alias</td><td>Category</td>");
            for (FieldDescriptor fd : foundCols)
            {
                msg.append("<td>" + fd.getLabel() + "</td>");
            }
            msg.append("</tr>");

            //then append the rows
            for (String projectDisplay : projectMap.get(financialAnalyst).keySet())
            {
                Map<String, Map<String, Integer>> projectDataByCategory = projectMap.get(financialAnalyst).get(projectDisplay);
                for (String category : projectDataByCategory.keySet())
                {
                    Map<String, Integer> totals = projectDataByCategory.get(category);

                    String baseUrl = getExecuteQueryUrl(containerMap.get(category), ONPRC_BillingSchema.NAME, categoryToQuery.get(category), null) + "&query.param.StartDate=" + _dateFormat.format(start.getTime()) + "&query.param.EndDate=" + _dateFormat.format(endDate.getTime());
                    String projUrl = baseUrl + ("None".equals(projectDisplay) ? "&query.project/displayName~isblank" : "&query.project/displayName~eq=" + projectDisplay);
                    msg.append("<tr><td>" + financialAnalyst + "</td>");    //the FA
                    msg.append("<td><a href='" + projUrl + "'>" + projectDisplay + "</a></td>");

                    String account = projectToAccountMap.containsKey(projectDisplay) ? projectToAccountMap.get(projectDisplay) : "Unknown";
                    msg.append("<td>" + (account == null ? "None" : account) + "</td>");
                    msg.append("<td>" + category + "</td>");

                    for (FieldDescriptor fd : foundCols)
                    {
                        if (totals.containsKey(fd.getFieldName()))
                        {
                            String url = projUrl + fd.getFilter();
                            msg.append("<td" + (fd.isShouldHighlight() ? " style='background-color: yellow;'" : "") + "><a href='" + url + "'>" + totals.get(fd.getFieldName()) + "</a></td>");
                        }
                        else
                        {
                            msg.append("<td></td>");
                        }
                    }

                    msg.append("</tr>");
                }
            }

            msg.append("</table><br><br>");
        }

        msg.append("<hr><p>");
    }

    private void chargesMissingRates(Container c, User u, StringBuilder msg)
    {
        Map<String, Object> params = Collections.<String, Object>singletonMap("date", new Date());
        TableInfo ti = QueryService.get().getUserSchema(u, c, "onprc_billing").getTable("chargesMissingRate");
        if (params != null)
        {
            SQLFragment sql = ti.getFromSQL("t");
            QueryService.get().bindNamedParameters(sql, params);
            sql = new SQLFragment("SELECT * FROM " + sql);
            QueryService.get().bindNamedParameters(sql, params);

            SqlSelector ss = new SqlSelector(ti.getSchema(), sql);
            long count = ss.getRowCount();

            if (count > 0)
            {
                msg.append("<b>Warning: there are " + count + " active charge items missing either a default rate or a default credit alias.  This may cause problems with the billing calculation.</b><p>");
                String url = getExecuteQueryUrl(c, "onprc_billing", "chargesMissingRate", null);
                url += "&query.param.Date=" + _dateFormat.format(new Date());

                msg.append("<a href='" + url + "'>Click here to view them</a>");
                msg.append("<hr>");
            }
        }
    }

    private void simpleAlert(Container c, User u, StringBuilder msg, String schemaName, String queryName, String message)
    {
        TableInfo ti = QueryService.get().getUserSchema(u, c, schemaName).getTable(queryName);
        TableSelector ts = new TableSelector(ti);
        long count = ts.getRowCount();
        if (count > 0)
        {
            msg.append("<b>Warning: there are " + count + " " + message + "</b><p>");
            msg.append("<a href='" + getExecuteQueryUrl(c, schemaName, queryName, null) + "'>Click here to view them</a>");
            msg.append("<hr>");
        }
    }

    private Date getLastInvoiceDate(Container c, User u)
    {
        Container financeContainer = ONPRC_BillingManager.get().getBillingContainer(c);
        if (financeContainer == null)
        {
            return null;
        }

        TableInfo ti = QueryService.get().getUserSchema(u, financeContainer, ONPRC_BillingSchema.NAME).getTable(ONPRC_BillingSchema.TABLE_INVOICE_RUNS);
        TableSelector ts = new TableSelector(ti);
        Map<String, List<Aggregate.Result>> aggs = ts.getAggregates(Collections.singletonList(new Aggregate(FieldKey.fromString("billingPeriodEnd"), Aggregate.Type.MAX)));
        for (List<Aggregate.Result> ag : aggs.values())
        {
            for (Aggregate.Result r : ag)
            {
                if (r.getValue() instanceof Date)
                {
                    return r.getValue() == null ? null : DateUtils.truncate((Date)r.getValue(), Calendar.DATE);
                }
            }
        }

        return null;
    }

    private void getAliasesDisabled(Container c, User u, StringBuilder msg)
    {
        if (QueryService.get().getUserSchema(u, c, "onprc_billing_public") == null)
        {
            msg.append("<b>Warning: the ONPRC billing schema has not been enabled in this folder, so the expired alias alert cannot run<p><hr>");
            return;
        }

        TableInfo ti = QueryService.get().getUserSchema(u, c, "ehr").getTable("project");
        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("enddateCoalesced"), "-0d", CompareType.DATE_GTE);
        filter.addCondition(FieldKey.fromString("account/aliasEnabled"), "Y", CompareType.NEQ_OR_NULL);
        filter.addCondition(FieldKey.fromString("account"), null, CompareType.NONBLANK);
        TableSelector ts = new TableSelector(ti, filter, null);
        long count = ts.getRowCount();
        if (count > 0)
        {
            msg.append("<b>Warning: there are " + count + " active ONPRC projects with aliases that are not accepting charges.</b><p>");
            msg.append("<a href='" + getExecuteQueryUrl(c, "ehr", "project", "Alias Info") + "&" + filter.toQueryString("query") + "'>Click here to view them</a>");
            msg.append("<hr>");
        }
    }

    private void getExpiredAliases(Container c, User u, StringBuilder msg)
    {
        if (QueryService.get().getUserSchema(u, c, "onprc_billing_public") == null)
        {
            msg.append("<b>Warning: the ONPRC billing schema has not been enabled in this folder, so the expired alias alert cannot run<p><hr>");
            return;
        }

        TableInfo ti = QueryService.get().getUserSchema(u, c, "ehr").getTable("project");
        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("enddateCoalesced"), "-0d", CompareType.DATE_GTE);
        filter.addCondition(FieldKey.fromString("account/budgetEndDateCoalesced"), "-0d", CompareType.DATE_LT);
        filter.addCondition(FieldKey.fromString("account"), null, CompareType.NONBLANK);
        TableSelector ts = new TableSelector(ti, filter, null);
        long count = ts.getRowCount();
        if (count > 0)
        {
            msg.append("<b>Warning: there are " + count + " active ONPRC projects with aliases that have an expired budget period.</b><p>");
            msg.append("<a href='" + getExecuteQueryUrl(c, "ehr", "project", "Alias Info") + "&" + filter.toQueryString("query") + "'>Click here to view them</a>");
            msg.append("<hr>");
        }
    }

    private void getCreditAliasesDisabled(Container c, User u, StringBuilder msg)
    {
        if (QueryService.get().getUserSchema(u, c, "onprc_billing_public") == null)
        {
            msg.append("<b>Warning: the ONPRC billing schema has not been enabled in this folder, so the expired alias alert cannot run<p><hr>");
            return;
        }

        TableInfo ti = QueryService.get().getUserSchema(u, c, "onprc_billing_public").getTable("creditAccount");
        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("chargeId/active"), true, CompareType.EQUAL);
        filter.addCondition(FieldKey.fromString("account/aliasEnabled"), "Y", CompareType.NEQ_OR_NULL);
        filter.addCondition(FieldKey.fromString("account"), null, CompareType.NONBLANK);
        filter.addCondition(FieldKey.fromString("account"), -1, CompareType.NEQ_OR_NULL);
        TableSelector ts = new TableSelector(ti, filter, null);
        long count = ts.getRowCount();
        if (count > 0)
        {
            msg.append("<b>Warning: there are " + count + " active chargeable items with credit aliases that are not accepting charges.</b><p>");
            msg.append("<a href='" + getExecuteQueryUrl(c, "onprc_billing_public", "creditAccount", null) + "&" + filter.toQueryString("query") + "'>Click here to view them</a>");
            msg.append("<hr>");
        }
    }

    private void getExpiredCreditAliases(Container c, User u, StringBuilder msg)
    {
        if (QueryService.get().getUserSchema(u, c, "onprc_billing_public") == null)
        {
            msg.append("<b>Warning: the ONPRC billing schema has not been enabled in this folder, so the expired alias alert cannot run<p><hr>");
            return;
        }

        TableInfo ti = QueryService.get().getUserSchema(u, c, "onprc_billing_public").getTable("creditAccount");
        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("chargeId/active"), true, CompareType.EQUAL);
        filter.addCondition(FieldKey.fromString("account/budgetEndDateCoalesced"), "-0d", CompareType.DATE_LT);
        filter.addCondition(FieldKey.fromString("account"), null, CompareType.NONBLANK);
        filter.addCondition(FieldKey.fromString("account"), -1, CompareType.NEQ_OR_NULL);
        TableSelector ts = new TableSelector(ti, filter, null);
        long count = ts.getRowCount();
        if (count > 0)
        {
            msg.append("<b>Warning: there are " + count + " active chargeable items using a credit alias with an expired budget period.</b><p>");
            msg.append("<a href='" + getExecuteQueryUrl(c, "onprc_billing_public", "creditAccount", null) + "&" + filter.toQueryString("query") + "'>Click here to view them</a>");
            msg.append("<hr>");
        }
    }

    private void getProjectsWithoutAliases(Container c, User u, StringBuilder msg)
    {
        if (QueryService.get().getUserSchema(u, c, "onprc_billing_public") == null)
        {
            msg.append("<b>Warning: the ONPRC billing schema has not been enabled in this folder, so the expired alias alert cannot run<p><hr>");
            return;
        }

        TableInfo ti = QueryService.get().getUserSchema(u, c, "ehr").getTable("project");
        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("enddateCoalesced"), "-0d", CompareType.DATE_GTE);
        filter.addCondition(FieldKey.fromString("account"), null, CompareType.ISBLANK);
        TableSelector ts = new TableSelector(ti, filter, null);
        long count = ts.getRowCount();
        if (count > 0)
        {
            msg.append("<b>Warning: there are " + count + " active ONPRC projects without an alias.</b><p>");
            msg.append("<a href='" + getExecuteQueryUrl(c, "ehr", "project", "Alias Info") + "&" + filter.toQueryString("query") + "'>Click here to view them</a>");
            msg.append("<hr>");
        }

    }

    private void getProjectsNotActive(Container c, User u, StringBuilder msg)
    {
        if (QueryService.get().getUserSchema(u, c, "onprc_billing_public") == null)
        {
            msg.append("<b>Warning: the ONPRC billing schema has not been enabled in this folder, so the expired alias alert cannot run<p><hr>");
            return;
        }

        TableInfo ti = QueryService.get().getUserSchema(u, c, "ehr").getTable("project");
        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("enddateCoalesced"), "-0d", CompareType.DATE_GTE);
        filter.addCondition(FieldKey.fromString("account/projectNumber/projectStatus"), "ACTIVE", CompareType.NEQ_OR_NULL);
        filter.addCondition(FieldKey.fromString("account/projectNumber/projectStatus"), "No Cost Ext", CompareType.NEQ_OR_NULL);
        filter.addCondition(FieldKey.fromString("account/projectNumber/projectStatus"), "Partial Setup", CompareType.NEQ_OR_NULL);
        filter.addCondition(FieldKey.fromString("account"), null, CompareType.NONBLANK);
        TableSelector ts = new TableSelector(ti, filter, null);
        long count = ts.getRowCount();
        if (count > 0)
        {
            msg.append("<b>Warning: there are " + count + " active ONPRC projects using an alias not marked as ACTIVE.</b><p>");
            msg.append("<a href='" + getExecuteQueryUrl(c, "ehr", "project", "Alias Info") + "&" + filter.toQueryString("query") + "'>Click here to view them</a>");
            msg.append("<hr>");
        }
    }
}