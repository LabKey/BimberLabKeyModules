package org.labkey.tcrdb;

import org.labkey.api.assay.AssayProtocolSchema;
import org.labkey.api.assay.AssayProvider;
import org.labkey.api.assay.AssayResultTable;
import org.labkey.api.assay.AssayService;
import org.labkey.api.data.AbstractTableInfo;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Container;
import org.labkey.api.data.JdbcType;
import org.labkey.api.data.SQLFragment;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.Table;
import org.labkey.api.data.TableInfo;
import org.labkey.api.exp.api.ExpProtocol;
import org.labkey.api.laboratory.LaboratoryService;
import org.labkey.api.ldk.LDKService;
import org.labkey.api.ldk.table.AbstractTableCustomizer;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.query.ExprColumn;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryForeignKey;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.UserSchema;

import java.util.Arrays;
import java.util.List;

public class TCRdbTableCustomizer extends AbstractTableCustomizer
{
    @Override
    public void customize(TableInfo table)
    {
        if (table instanceof AbstractTableInfo)
        {
            AbstractTableInfo ti = (AbstractTableInfo) table;
            if (matches(ti, "sequenceanalysis", "sequence_analyses"))
            {
                addAssayFieldsToAnalyses(ti);
            }
            else if (matches(ti, "sequenceanalysis", "sequence_readsets"))
            {
                customizeReadsets(ti);
            }
            else if (matches(ti, "tcrdb", "stims"))
            {
                customizeStims(ti);
            }
            else if (matches(ti, "tcrdb", "sorts"))
            {
                customizeSorts(ti);
            }
            else if (matches(ti, "tcrdb", "cdnas"))
            {
                customizeCdnas(ti);
            }
            else if (matches(ti, "tcrdb", "clones"))
            {
                customizeClones(ti);
            }
            else if (ti instanceof AssayResultTable)
            {
                customizeAssayData(ti);
            }
        }
    }

    private void customizeCdnas(AbstractTableInfo ti)
    {
        String name = "hasReadsetWithData";
        if (ti.getColumn(name) == null)
        {
            SQLFragment sql = new SQLFragment("CASE " +
                    " WHEN (select count(*) as expr FROM sequenceanalysis.sequence_readsets r JOIN sequenceanalysis.readdata d ON (r.rowid = d.readset) WHERE r.rowid = " + ExprColumn.STR_TABLE_ALIAS + ".readsetId) > 0 THEN " + ti.getSqlDialect().getBooleanTRUE() +
                    " WHEN (select count(*) as expr FROM sequenceanalysis.sequence_readsets r JOIN sequenceanalysis.readdata d ON (r.rowid = d.readset) WHERE r.rowid = " + ExprColumn.STR_TABLE_ALIAS + ".enrichedReadsetId) > 0 THEN " + ti.getSqlDialect().getBooleanTRUE() +
                    " ELSE " + ti.getSqlDialect().getBooleanFALSE() + " END");

            ExprColumn newCol = new ExprColumn(ti, name, sql, JdbcType.BOOLEAN, ti.getColumn("readsetId"), ti.getColumn("enrichedReadsetId"));
            newCol.setLabel("Has Any Readset With Data?");
            ti.addColumn(newCol);
        }

        String name2 = "allReadsetsHaveData";
        if (ti.getColumn(name2) == null)
        {
            SQLFragment sql = new SQLFragment("CASE " +
                    " WHEN (" + ExprColumn.STR_TABLE_ALIAS + ".readsetId IS NOT NULL AND (select count(*) as expr FROM sequenceanalysis.sequence_readsets r JOIN sequenceanalysis.readdata d ON (r.rowid = d.readset) WHERE r.rowid = " + ExprColumn.STR_TABLE_ALIAS + ".readsetId) = 0) THEN " + ti.getSqlDialect().getBooleanFALSE() +
                    " WHEN (" + ExprColumn.STR_TABLE_ALIAS + ".enrichedReadsetId IS NOT NULL AND (select count(*) as expr FROM sequenceanalysis.sequence_readsets r JOIN sequenceanalysis.readdata d ON (r.rowid = d.readset) WHERE r.rowid = " + ExprColumn.STR_TABLE_ALIAS + ".enrichedReadsetId) = 0) THEN " + ti.getSqlDialect().getBooleanFALSE() +
                    " ELSE " + ti.getSqlDialect().getBooleanTRUE() + " END");

            ExprColumn newCol = new ExprColumn(ti, name2, sql, JdbcType.BOOLEAN, ti.getColumn("readsetId"), ti.getColumn("enrichedReadsetId"));
            newCol.setLabel("All Readsets Have Data?");
            ti.addColumn(newCol);
        }

        addAssayFieldsToCDnas(ti);

        LDKService.get().applyNaturalSort(ti, "plateId");
    }

    private void customizeSorts(AbstractTableInfo ti)
    {
        String name = "numLibraries";
        if (ti.getColumn(name) == null)
        {
            DetailsURL details = DetailsURL.fromString("/query/executeQuery.view?schemaName=tcrdb&query.queryName=cdnas&query.sortId~eq=${rowid}", (ti.getUserSchema().getContainer().isWorkbook() ? ti.getUserSchema().getContainer().getParent() : ti.getUserSchema().getContainer()));

            SQLFragment sql = new SQLFragment("(select count(*) as expr FROM " + TCRdbSchema.NAME + "." + TCRdbSchema.TABLE_CDNAS + " s WHERE s.sortId = " + ExprColumn.STR_TABLE_ALIAS + ".rowid)");
            ExprColumn newCol = new ExprColumn(ti, name, sql, JdbcType.INTEGER, ti.getColumn("rowid"));
            newCol.setLabel("# cDNA Libraries");
            newCol.setURL(details);
            ti.addColumn(newCol);
        }

        name = "maxCellsForPlate";
        if (ti.getColumn(name) == null)
        {
            SQLFragment sql = new SQLFragment("(select count(*) as expr FROM " + TCRdbSchema.NAME + "." + TCRdbSchema.TABLE_SORTS + " s WHERE s.plateId = " + ExprColumn.STR_TABLE_ALIAS + ".plateId AND s.container = " + ExprColumn.STR_TABLE_ALIAS + ".container)");
            ExprColumn newCol = new ExprColumn(ti, name, sql, JdbcType.INTEGER, ti.getColumn("plateId"), ti.getColumn("container"));
            newCol.setLabel("Max Cells/Well In Plate");
            ti.addColumn(newCol);
        }

        name = "processingRequested";
        if (ti.getColumn(name) == null)
        {
            SQLFragment sql = new SQLFragment("(select ").append(ti.getSqlDialect().getGroupConcat(new SQLFragment("p.type"), true, true)).append(new SQLFragment(" as expr FROM " + TCRdbSchema.NAME + "." + TCRdbSchema.TABLE_PROCESSING + " p WHERE p.plateId = " + ExprColumn.STR_TABLE_ALIAS + ".plateId AND p.container = " + ExprColumn.STR_TABLE_ALIAS + ".container)"));
            ExprColumn newCol = new ExprColumn(ti, name, sql, JdbcType.VARCHAR, ti.getColumn("plateId"), ti.getColumn("container"));
            newCol.setLabel("Processing Requested");
            ti.addColumn(newCol);
        }
    }

    private void customizeStims(AbstractTableInfo ti)
    {
        String name = "numSorts";
        if (ti.getColumn(name) == null)
        {
            DetailsURL details = DetailsURL.fromString("/query/executeQuery.view?schemaName=tcrdb&query.queryName=sorts&query.stimId~eq=${rowid}", (ti.getUserSchema().getContainer().isWorkbook() ? ti.getUserSchema().getContainer().getParent() : ti.getUserSchema().getContainer()));

            SQLFragment sql = new SQLFragment("(select count(*) as expr FROM " + TCRdbSchema.NAME + "." + TCRdbSchema.TABLE_SORTS + " s WHERE s.stimId = " + ExprColumn.STR_TABLE_ALIAS + ".rowid)");
            ExprColumn newCol = new ExprColumn(ti, "numSorts", sql, JdbcType.INTEGER, ti.getColumn("rowid"));
            newCol.setLabel("# Sorts");
            newCol.setURL(details);
            ti.addColumn(newCol);
        }

        name = "numLibraries";
        if (ti.getColumn(name) == null)
        {
            DetailsURL details = DetailsURL.fromString("/query/executeQuery.view?schemaName=tcrdb&query.queryName=cdnas&query.sortId/stimId~eq=${rowid}", (ti.getUserSchema().getContainer().isWorkbook() ? ti.getUserSchema().getContainer().getParent() : ti.getUserSchema().getContainer()));

            SQLFragment sql = new SQLFragment("(select count(c.rowid) as expr FROM " + TCRdbSchema.NAME + "." + TCRdbSchema.TABLE_SORTS + " so JOIN " + TCRdbSchema.NAME + "." + TCRdbSchema.TABLE_CDNAS + " c ON (so.rowid = c.sortId) WHERE so.stimId = " + ExprColumn.STR_TABLE_ALIAS + ".rowid)");
            ExprColumn newCol = new ExprColumn(ti, "numLibraries", sql, JdbcType.INTEGER, ti.getColumn("rowid"));
            newCol.setLabel("# cDNA Libraries");
            newCol.setURL(details);
            ti.addColumn(newCol);
        }
    }

    private void customizeReadsets(AbstractTableInfo ti)
    {
        addAssayFieldsToTable(ti, "analysisId/readset", "LEFT JOIN sequenceanalysis.sequence_analyses a2 ON (a.analysisId = a2.rowId) WHERE a2.readset = " + ExprColumn.STR_TABLE_ALIAS + ".rowid", "rowid");

        String name = "numTCRLibraries";
        if (ti.getColumn(name) == null)
        {
            SQLFragment sql = new SQLFragment("(select count(*) as expr FROM " + TCRdbSchema.NAME + "." + TCRdbSchema.TABLE_CDNAS + " c WHERE c.readsetId = " + ExprColumn.STR_TABLE_ALIAS + ".rowid OR c.enrichedReadsetId = " + ExprColumn.STR_TABLE_ALIAS + ".rowid)");
            ExprColumn newCol = new ExprColumn(ti, name, sql, JdbcType.INTEGER, ti.getColumn("rowid"));
            newCol.setLabel("# TCR Libraries");
            ti.addColumn(newCol);
        }

        String cDNA = "cDNA";
        if (ti.getColumn(cDNA) == null)
        {
            SQLFragment sql = new SQLFragment("(CASE" +
                    " WHEN ((select count(*) as expr FROM " + TCRdbSchema.NAME + "." + TCRdbSchema.TABLE_CDNAS + " c WHERE c.readsetId = " + ExprColumn.STR_TABLE_ALIAS + ".rowid OR c.enrichedReadsetId = " + ExprColumn.STR_TABLE_ALIAS + ".rowid) > 0) " +
                    " THEN (select max(c.rowid) FROM " + TCRdbSchema.NAME + "." + TCRdbSchema.TABLE_CDNAS + " c WHERE c.readsetId = " + ExprColumn.STR_TABLE_ALIAS + ".rowid OR c.enrichedReadsetId = " + ExprColumn.STR_TABLE_ALIAS + ".rowid) " +
                    " ELSE null " +
                    "END)");
            ExprColumn newCol = new ExprColumn(ti, cDNA, sql, JdbcType.INTEGER, ti.getColumn("rowid"));
            newCol.setLabel("cDNA Library");
            UserSchema us = QueryService.get().getUserSchema(ti.getUserSchema().getUser(), (ti.getUserSchema().getContainer().isWorkbook() ? ti.getUserSchema().getContainer().getParent() : ti.getUserSchema().getContainer()), TCRdbSchema.NAME);
            newCol.setFk(QueryForeignKey.from(us, ti.getContainerFilter())
                    .table(TCRdbSchema.TABLE_CDNAS)
                    .key("rowid")
                    .display("rowid"));
            ti.addColumn(newCol);
        }
    }

    private void addAssayFieldsToAnalyses(AbstractTableInfo ti)
    {
        addAssayFieldsToTable(ti, "analysisId", "WHERE a.analysisId = " + ExprColumn.STR_TABLE_ALIAS + ".rowid", "rowid");
    }

    private void addAssayFieldsToCDnas(AbstractTableInfo ti)
    {
        addAssayFieldsToTable(ti, "analysisId/readset", "LEFT JOIN sequenceanalysis.sequence_analyses a2 ON (a.analysisId = a2.rowId) WHERE a2.readset = " + ExprColumn.STR_TABLE_ALIAS + ".readsetId", "readsetId", "FullTranscript", " (Full Transcriptome)");

        addAssayFieldsToTable(ti, "cdna", " WHERE a.cDNA = " + ExprColumn.STR_TABLE_ALIAS + ".rowid", "rowid", "TCREnriched", " (TCR Enriched)", false);
    }

    private void addAssayFieldsToTable(AbstractTableInfo ti, String urlField, String whereClause, String urlSourceCol)
    {
        addAssayFieldsToTable(ti, urlField, whereClause, urlSourceCol, "", "");
    }

    private void addAssayFieldsToTable(AbstractTableInfo ti, String urlField, String whereClause, String urlSourceCol, String colNameSuffix, String colLabelSuffix)
    {
        addAssayFieldsToTable(ti, urlField, whereClause, urlSourceCol, colNameSuffix, colLabelSuffix, true);
    }

    private void addAssayFieldsToTable(AbstractTableInfo ti, String urlField, String whereClause, String urlSourceCol, String colNameSuffix, String colLabelSuffix, boolean addRunColumns)
    {
        if (ti.getColumn("numTcrResults" + colNameSuffix) == null)
        {
            AssayProvider ap = AssayService.get().getProvider("TCRdb");
            if (ap == null)
            {
                return;
            }

            List<ExpProtocol> protocols = AssayService.get().getAssayProtocols(ti.getUserSchema().getContainer(), ap);
            if (protocols.size() != 1)
            {
                return;
            }

            AssayProtocolSchema schema = ap.createProtocolSchema(ti.getUserSchema().getUser(), ti.getUserSchema().getContainer(), protocols.get(0), null);
            TableInfo data = schema.getTable("data");

            SimpleFilter filter = new SimpleFilter(FieldKey.fromString("locus"), "None", CompareType.NEQ_OR_NULL);
            filter.addCondition(FieldKey.fromString("disabled"), true, CompareType.NEQ_OR_NULL);

            SQLFragment selectSql = QueryService.get().getSelectSQL(data, Arrays.asList(data.getColumn("analysisId"), data.getColumn("CDR3"), data.getColumn("locus"), data.getColumn("fraction"), data.getColumn("count"), data.getColumn("cDNA")), filter, null, Table.ALL_ROWS, Table.NO_OFFSET, false);
            DetailsURL details = DetailsURL.fromString("/query/executeQuery.view?schemaName=assay." + ap.getName().replaceAll(" ", "") + "." + protocols.get(0).getName() + "&query.queryName=data&query." + urlField + "~eq=${" + urlSourceCol + "}", (ti.getUserSchema().getContainer().isWorkbook() ? ti.getUserSchema().getContainer().getParent() : ti.getUserSchema().getContainer()));

            SQLFragment sql = new SQLFragment("(select count(*) as expr FROM (").append(selectSql).append(") a " + whereClause + ")");
            ExprColumn newCol = new ExprColumn(ti, "numTcrResults" + colNameSuffix, sql, JdbcType.INTEGER, ti.getColumn("rowid"));
            newCol.setLabel("# TCR Results" + colLabelSuffix);
            newCol.setURL(details);
            ti.addColumn(newCol);

            SQLFragment sql2 = new SQLFragment("(select count(distinct CDR3) as expr FROM (").append(selectSql).append(") a " + whereClause + ")");
            ExprColumn newCol2 = new ExprColumn(ti, "numCDR3s" + colNameSuffix, sql2, JdbcType.INTEGER, ti.getColumn("rowid"));
            newCol2.setLabel("# Distinct CDR3s" + colLabelSuffix);
            newCol2.setURL(details);
            ti.addColumn(newCol2);

            SQLFragment sql3 = new SQLFragment("(select ").append(ti.getSqlDialect().getGroupConcat(new SQLFragment("locus"), true, true)).append(" FROM (").append(selectSql).append(") a " + whereClause + ")");
            ExprColumn newCol3 = new ExprColumn(ti, "distinctLoci" + colNameSuffix, sql3, JdbcType.VARCHAR, ti.getColumn("rowid"));
            newCol3.setLabel("Distinct Loci" + colLabelSuffix);
            newCol3.setURL(details);
            ti.addColumn(newCol3);

            SQLFragment sql4 = new SQLFragment("(select ").append(ti.getSqlDialect().getGroupConcat(new SQLFragment("CDR3"), true, true, getChr(ti) + "(10)")).append(" FROM (").append(selectSql).append(") a " + whereClause + " )");
            ExprColumn newCol4 = new ExprColumn(ti, "distinctCDR3s" + colNameSuffix, sql4, JdbcType.VARCHAR, ti.getColumn("rowid"));
            newCol4.setLabel("Distinct CDR3s" + colLabelSuffix);
            newCol4.setURL(details);
            ti.addColumn(newCol4);

            SQLFragment sqlTotal = new SQLFragment("(select sum(count) as expr FROM (").append(selectSql).append(") a " + whereClause + ")");
            ExprColumn newColTotal = new ExprColumn(ti, "numCells" + colNameSuffix, sqlTotal, JdbcType.INTEGER, ti.getColumn("rowid"));
            newColTotal.setLabel("# TCR Cells/Reads" + colLabelSuffix);
            newColTotal.setURL(details);
            ti.addColumn(newColTotal);

            SQLFragment sql6 = new SQLFragment("(select sum(a.count) as expr FROM (").append(selectSql).append(") a " + whereClause + " )");
            ExprColumn newCol6 = new ExprColumn(ti, "totalCDR3Reads" + colNameSuffix, sql6, JdbcType.INTEGER, ti.getColumn("rowid"));
            newCol6.setLabel("Total CDR3 Reads" + colLabelSuffix);
            newCol6.setURL(details);
            ti.addColumn(newCol6);

            if (addRunColumns)
            {
                TableInfo runs = schema.getTable("runs");
                SQLFragment runSelectSql = QueryService.get().getSelectSQL(runs, Arrays.asList(runs.getColumn("analysisId")), null, null, Table.ALL_ROWS, Table.NO_OFFSET, false);
                DetailsURL runDetails = DetailsURL.fromString("/query/executeQuery.view?schemaName=assay." + ap.getName().replaceAll(" ", "") + "." + protocols.get(0).getName() + "&query.queryName=runs&query." + urlField + "~eq=${" + urlSourceCol + "}", (ti.getUserSchema().getContainer().isWorkbook() ? ti.getUserSchema().getContainer().getParent() : ti.getUserSchema().getContainer()));

                SQLFragment sql5 = new SQLFragment("(select count(*) as expr FROM (").append(runSelectSql).append(") a " + whereClause + ")");
                ExprColumn newCol5 = new ExprColumn(ti, "numTcrRuns" + colNameSuffix, sql5, JdbcType.INTEGER, ti.getColumn("rowid"));
                newCol5.setLabel("# TCR Runs" + colLabelSuffix);
                newCol5.setURL(runDetails);
                ti.addColumn(newCol5);
            }

            addClonotypeForLocusCol(ti, selectSql, whereClause, details, "TRA", colNameSuffix, colLabelSuffix);
            addClonotypeForLocusCol(ti, selectSql, whereClause, details, "TRB", colNameSuffix, colLabelSuffix);
            addClonotypeForLocusCol(ti, selectSql, whereClause, details, "TRD", colNameSuffix, colLabelSuffix);
            addClonotypeForLocusCol(ti, selectSql, whereClause, details, "TRG", colNameSuffix, colLabelSuffix);
        }
    }

    private void addAssayClonotypeColumn(AbstractTableInfo ti)
    {
        if (ti.getColumn("clonotypes") != null)
        {
            return;
        }

        if (!(ti instanceof AssayResultTable))
        {
            _log.error("Table not an AssayResultTable: " + ti.getName());
            return;
        }

        SimpleFilter filter = new SimpleFilter(FieldKey.fromString("locus"), "None", CompareType.NEQ_OR_NULL);
        filter.addCondition(FieldKey.fromString("disabled"), true, CompareType.NEQ_OR_NULL);

        SQLFragment selectSql = QueryService.get().getSelectSQL(ti, Arrays.asList(ti.getColumn("analysisId"), ti.getColumn("cloneId"), ti.getColumn("Run"), ti.getColumn("cDNA"), ti.getColumn("cdr3"), ti.getColumn("locus")), filter, null, Table.ALL_ROWS, Table.NO_OFFSET, false);

        String whereClause = " WHERE (a.cloneId = " + ExprColumn.STR_TABLE_ALIAS + ".cloneId AND a.analysisId = " + ExprColumn.STR_TABLE_ALIAS + ".analysisId AND a.Run = " + ExprColumn.STR_TABLE_ALIAS + ".Run AND a.cDNA = " + ExprColumn.STR_TABLE_ALIAS + ".cDNA) ";
        SQLFragment sql = new SQLFragment("(select ").append(ti.getSqlDialect().getGroupConcat(new SQLFragment(ti.getSqlDialect().concatenate("a.locus", "':'","a.CDR3")), true, true, getChr(ti) + "(10)")).append(" FROM (").append(selectSql).append(") a " + whereClause + " )");
        ExprColumn newCol = new ExprColumn(ti, "clonotypes", sql, JdbcType.VARCHAR, ti.getColumn("analysisId"), ti.getColumn("cloneId"));
        newCol.setLabel("Clonotype for Clone");
        newCol.setDescription("CDR3 clonotypes for this cloneId");

        DetailsURL details = DetailsURL.fromString("/query/executeQuery.view?schemaName=assay." + ti.getUserSchema().getSchemaName().replaceAll(" ", "") + "&query.queryName=data&query.analysisId~eq=${analysisId}&query.cDNA~eq=${cDNA}&query.cloneId~eq=${cloneId}", (ti.getUserSchema().getContainer().isWorkbook() ? ti.getUserSchema().getContainer().getParent() : ti.getUserSchema().getContainer()));
        newCol.setURL(details);
        ti.addColumn(newCol);
    }

    private void addClonotypeForLocusCol(AbstractTableInfo ti, SQLFragment selectSql, String whereClause, DetailsURL details, String locus, String colNameSuffix, String colLabelSuffix)
    {
        whereClause += " AND a.locus = '" + locus + "' AND a.fraction >= 0.05";
        DetailsURL detailsURL = details.clone();
        detailsURL.addParameter("data.locus~eq", locus);

        SQLFragment sql = new SQLFragment("(select ").append(ti.getSqlDialect().getGroupConcat(new SQLFragment("CDR3"), true, true, getChr(ti) + "(10)")).append(" FROM (").append(selectSql).append(") a " + whereClause + " )");
        ExprColumn newCol = new ExprColumn(ti, "clonotype" + locus + colNameSuffix, sql, JdbcType.VARCHAR, ti.getColumn("rowid"));
        newCol.setLabel("Clonotype: " + locus + colLabelSuffix);
        newCol.setDescription("Showing CDR3 clonotypes for " + locus + " with fraction >=0.05");
        newCol.setURL(detailsURL);
        ti.addColumn(newCol);
    }

    private void customizeClones(AbstractTableInfo ti)
    {
        LDKService.get().applyNaturalSort(ti, "cloneName");

        AssayProvider ap = AssayService.get().getProvider("TCRdb");
        if (ap == null)
        {
            return;
        }

        Container target = ti.getUserSchema().getContainer().isWorkbook() ? ti.getUserSchema().getContainer().getParent() : ti.getUserSchema().getContainer();
        List<ExpProtocol> protocols = AssayService.get().getAssayProtocols(target, ap);
        if (protocols.size() != 1)
        {
            return;
        }

        AssayProtocolSchema schema = ap.createProtocolSchema(ti.getUserSchema().getUser(), target, protocols.get(0), null);
        DetailsURL details = DetailsURL.fromString("/query/executeQuery.view?schemaName=" + schema.getSchemaName() + "&query.queryName=data&query.viewName=Clonotype Export&query.CDR3~eq=${cdr3}", target);
        ti.getMutableColumn("cdr3").setURL(details);

        String colName = "distinctAnimals";
        if (ti.getColumn(colName) == null)
        {
            TableInfo data = schema.createDataTable(null,false);
            SQLFragment dataSelectSql = QueryService.get().getSelectSQL(data, Arrays.asList(data.getColumn("subjectId"), data.getColumn("cdr3"), data.getColumn("fraction")), null, null, Table.ALL_ROWS, Table.NO_OFFSET, false);

            SQLFragment sql = new SQLFragment("(select ").append(ti.getSqlDialect().getGroupConcat(new SQLFragment("a.subjectId"), true, true, getChr(ti) + "(10)")).append(" as expr FROM (").append(dataSelectSql).append(") a WHERE a.cdr3 = " + ExprColumn.STR_TABLE_ALIAS + ".cdr3 AND a.fraction >= 0.005)");
            ExprColumn col = new ExprColumn(ti, colName, sql, JdbcType.VARCHAR, ti.getColumn("cdr3"));
            col.setLabel("Animals with CDR3");
            col.setURL(details);
            ti.addColumn(col);
        }
    }

    private void customizeAssayData(AbstractTableInfo ti)
    {
        LaboratoryService.get().getAssayTableCustomizer().customize(ti);

        String colName = "cloneNames";
        if (ti.getColumn(colName) == null)
        {
            SQLFragment sql = new SQLFragment("(select ").append(ti.getSqlDialect().getGroupConcat(new SQLFragment("c.cloneName"), true, true, getChr(ti) + "(10)")).append(" as expr FROM " + TCRdbSchema.NAME + "." + TCRdbSchema.TABLE_CLONES + " c WHERE c.cdr3 = " + ExprColumn.STR_TABLE_ALIAS + ".cdr3)");
            ExprColumn col = new ExprColumn(ti, colName, sql, JdbcType.VARCHAR, ti.getColumn("cdr3"));
            col.setLabel("Clone Name(s)");
            ti.addColumn(col);
        }

        addAssayClonotypeColumn(ti);
    }
}