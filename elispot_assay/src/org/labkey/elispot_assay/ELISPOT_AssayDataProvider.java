/*
 * Copyright (c) 2012 LabKey Corporation
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
package org.labkey.elispot_assay;

import org.json.JSONObject;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.laboratory.NavItem;
import org.labkey.api.laboratory.SimpleSettingsItem;
import org.labkey.api.laboratory.assay.AbstractAssayDataProvider;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.security.User;
import org.labkey.api.view.ViewContext;
import org.labkey.elispot_assay.assay.AIDImportMethod;
import org.labkey.elispot_assay.assay.DefaultImportMethod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: bimber
 * Date: 10/7/12
 * Time: 3:38 PM
 */
public class ELISPOT_AssayDataProvider extends AbstractAssayDataProvider
{
    public ELISPOT_AssayDataProvider(Module m){
        _providerName = ELISPOT_AssayManager.ASSAY_PROVIDER_NAME;
        _module = m;

        _importMethods.add(new DefaultImportMethod(_providerName));
        _importMethods.add(new AIDImportMethod(_providerName));
    }

    @Override
    public List<NavItem> getSettingsItems(Container c, User u)
    {
        if (!c.isRoot() && !ContainerManager.getSharedContainer().equals(c) && !c.getActiveModules().contains(ModuleLoader.getInstance().getModule(ELISPOT_AssayModule.class)))
        {
            return Collections.emptyList();
        }

        List<NavItem> items = new ArrayList<NavItem>();
        String categoryName = "ELISPOT Assay";
        if (ContainerManager.getSharedContainer().equals(c))
        {
            items.add(new SimpleSettingsItem(this, ELISPOT_AssaySchema.SCHEMA_NAME, "Assay_Types", categoryName, "Assay Types"));
            items.add(new SimpleSettingsItem(this, ELISPOT_AssaySchema.SCHEMA_NAME, "Instruments", categoryName, "Instruments"));
        }

        items.add(new SimpleSettingsItem(this, ELISPOT_AssaySchema.SCHEMA_NAME, "Peptide_Pools", categoryName, "Peptide Pools"));
        items.add(new SimpleSettingsItem(this, ELISPOT_AssaySchema.SCHEMA_NAME, "Peptide_Pool_Members", categoryName, "Peptide Pool Members"));

        return items;
    }

    @Override
    public JSONObject getTemplateMetadata(ViewContext ctx)
    {
        JSONObject meta = super.getTemplateMetadata(ctx);
        JSONObject domainMeta = meta.getJSONObject("domains");

        JSONObject resultMeta = getJsonObject(domainMeta, "Results");
        String[] hiddenResultFields = new String[]{"plate", "qual_result", "spots", "spotsAboveBackground", "pvalue", "qcflag"};
        for (String field : hiddenResultFields)
        {
            JSONObject json = getJsonObject(resultMeta, field);
            json.put("hidden", true);
            resultMeta.put(field, json);
        }

        String[] requiredFields = new String[]{"well", "subjectId", "category", "cell_number"};
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
        meta.put("hideDownloadBtn", true);
        meta.put("colOrder", Arrays.asList("plate", "well", "category", "subjectId"));
        meta.put("showPlateLayout", true);

        return meta;
    }
}