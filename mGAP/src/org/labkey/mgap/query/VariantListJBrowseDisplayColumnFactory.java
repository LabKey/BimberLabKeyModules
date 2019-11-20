package org.labkey.mgap.query;

import org.apache.commons.lang3.StringUtils;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.DataColumn;
import org.labkey.api.data.DisplayColumn;
import org.labkey.api.data.DisplayColumnFactory;
import org.labkey.api.data.RenderContext;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.query.FieldKey;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Set;

public class VariantListJBrowseDisplayColumnFactory implements DisplayColumnFactory
{
    @Override
    public DisplayColumn createRenderer(ColumnInfo colInfo)
    {
        return new DataColumn(colInfo)
        {
            @Override
            public void addQueryFieldKeys(Set<FieldKey> keys)
            {
                super.addQueryFieldKeys(keys);
                keys.add(getBoundKey("releaseId/jbrowseId"));
                keys.add(getBoundKey("releaseId/vcfId"));
                keys.add(getBoundKey("container"));
                keys.add(getBoundKey("contig"));
                keys.add(getBoundKey("reference"));
                keys.add(getBoundKey("position"));
                keys.add(getBoundKey("identifier"));
            }

            private FieldKey getBoundKey(String colName)
            {
                FieldKey ret = getBoundColumn().getFieldKey().getParent();
                List<String> parts = FieldKey.fromString(colName).getParts();
                for (String part : parts)
                {
                    ret = new FieldKey(ret, part);
                }

                return ret;
            }

            @Override
            public void renderGridCellContents(RenderContext ctx, Writer out) throws IOException
            {
                String jbrowseId = StringUtils.trimToNull(ctx.get(getBoundKey("releaseId/jbrowseId"), String.class));
                Integer jbrowseTrackId = ctx.get(getBoundKey("releaseId/vcfId"), Integer.class);
                String containerId = ctx.get(getBoundKey("container"), String.class);
                String contig = StringUtils.trimToNull(ctx.get(getBoundKey("contig"), String.class));
                String ref = ctx.get(getBoundKey("reference"), String.class);
                Integer position = ctx.get(getBoundKey("position"), Integer.class);
                String delim = "";
                int start = position - 200;
                int stop = position + 200;
                int length = ref.length();
                if (jbrowseId != null)
                {
                    DetailsURL url = DetailsURL.fromString("/jbrowse/browser.view?database=" + jbrowseId + "&loc=" + contig + ":" + start + ".." + stop + "&highlight=" + contig + ":" + position + ".." + (position + length - 1), ContainerManager.getForId(containerId));
                    out.write("<a class=\"labkey-text-link\" href=\"" + url.getActionURL().getURIString() + "\");\">View In Genome Browser</a>");
                    delim = "<br>";
                }

                if (jbrowseTrackId != null)
                {
                    out.write(delim);
                    DetailsURL url = DetailsURL.fromString("/jbrowse/genotypeTable.view?trackId=data-" + jbrowseTrackId + "&chr=" + contig + "&start=" + position + "&stop=" + position, ContainerManager.getForId(containerId));
                    out.write("<a class=\"labkey-text-link\" href=\"" + url.getActionURL().getURIString() + "\");\">View Genotypes At Position</a>");
                    delim = "<br>";
                }

                if (ctx.get(FieldKey.fromString("identifier")) != null)
                {
                    String identifier = StringUtils.trimToNull(ctx.get(getBoundKey("identifier"), String.class));
                    if (identifier != null && identifier.contains(":"))
                    {
                        String[] parts = identifier.split(":");
                        switch (parts[0])
                        {
                            case "ClinVar":
                                if (!StringUtils.isEmpty(parts[1]))
                                {
                                    String url = "https://www.ncbi.nlm.nih.gov/clinvar/variation/" + parts[1] + "/";
                                    out.write(delim);
                                    out.write("<a class=\"labkey-text-link\" href=\"" + url + "\");\">View in ClinVar</a>");
                                    delim = "<br>";
                                }
                                break;
                        }
                    }
                }

                //Ensembl does use chr or padded names.
                String contigE = contig.replaceAll("chr", "");
                contigE = contigE.replaceAll("^0", "");
                String url = "https://jul2019.archive.ensembl.org/Macaca_mulatta/Location/View?db=core;r=" + contigE + ":" + start +"-" + stop;
                out.write(delim);
                out.write("<a class=\"labkey-text-link\" href=\"" + url + "\");\">View Region in Ensembl</a>");
            }
        };
    }
}
