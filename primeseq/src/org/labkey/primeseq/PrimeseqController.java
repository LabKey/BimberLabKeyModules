/*
 * Copyright (c) 2019 LabKey Corporation
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

package org.labkey.primeseq;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.labkey.api.action.ApiResponse;
import org.labkey.api.action.ApiSimpleResponse;
import org.labkey.api.action.ConfirmAction;
import org.labkey.api.action.ReadOnlyApiAction;
import org.labkey.api.action.SpringActionController;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.ContainerType;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineUrls;
import org.labkey.api.security.RequiresPermission;
import org.labkey.api.security.RequiresSiteAdmin;
import org.labkey.api.security.permissions.ReadPermission;
import org.labkey.api.util.HtmlString;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.URLHelper;
import org.labkey.api.view.ActionURL;
import org.labkey.api.view.HtmlView;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.web.servlet.ModelAndView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PrimeseqController extends SpringActionController
{
    private static final DefaultActionResolver _actionResolver = new DefaultActionResolver(PrimeseqController.class);
    public static final String NAME = "primeseq";
    private static final Logger _log = LogManager.getLogger(PrimeseqController.class);

    public PrimeseqController()
    {
        setActionResolver(_actionResolver);
    }

    @RequiresPermission(ReadPermission.class)
    public class GetNavItemsAction extends ReadOnlyApiAction<Object>
    {
        public ApiResponse execute(Object form, BindException errors)
        {
            Map<String, Object> resultProperties = new HashMap<>();

            resultProperties.put("collaborations", getSection("/Public/Collaborations"));
            resultProperties.put("internal", getSection("/Internal"));
            resultProperties.put("labs", getSection("/Labs"));

            //for now, public is hard coded
            List<JSONObject> publicJson = new ArrayList<>();
            Container publicContainer = ContainerManager.getForPath("/Public");
            if (publicContainer != null)
            {
                JSONObject json = new JSONObject();
                json.put("name", "Front Page");
                json.put("path", ContainerManager.getHomeContainer().getPath());
                json.put("url", ContainerManager.getHomeContainer().getStartURL(getUser()).toString());
                json.put("canRead", ContainerManager.getHomeContainer().hasPermission(getUser(), ReadPermission.class));
                publicJson.add(json);

                json = new JSONObject();
                json.put("name", "Overview / Tutorials");
                json.put("path", publicContainer.getPath());
                json.put("url", publicContainer.getStartURL(getUser()).toString());
                json.put("canRead", publicContainer.hasPermission(getUser(), ReadPermission.class));
                publicJson.add(json);

                json = new JSONObject();
                Container publicBlast = ContainerManager.getForPath("/Public/PublicBLAST");
                if (publicBlast != null)
                {
                    json.put("name", "BLAST");
                    json.put("path", publicBlast.getPath());
                    json.put("url", new ActionURL("blast", "blast", publicBlast).toString());
                    json.put("canRead", publicBlast.hasPermission(getUser(), ReadPermission.class));
                    publicJson.add(json);
                }

                json = new JSONObject();
                json.put("name", "Genome Browser");
                json.put("path", publicContainer.getPath());
                json.put("url", new ActionURL("wiki", "page", publicContainer).toString() + "name=Genome Browser Instructions");
                json.put("canRead", publicContainer.hasPermission(getUser(), ReadPermission.class));
                publicJson.add(json);

            }

            resultProperties.put("public", publicJson);
            resultProperties.put("success", true);

            return new ApiSimpleResponse(resultProperties);
        }

        private List<JSONObject> getSection(String path)
        {
            List<JSONObject> ret = new ArrayList<>();
            Container mainContainer = ContainerManager.getForPath(path);
            if (mainContainer != null)
            {
                for (Container c : mainContainer.getChildren())
                {
                    //NOTE: unlike EHR, omit children if the current user cannot read them
                    if (!c.hasPermission(getUser(), ReadPermission.class))
                    {
                        continue;
                    }

                    JSONObject json = new JSONObject();
                    json.put("name", c.getName());
                    json.put("title", c.getTitle());
                    json.put("path", c.getPath());
                    json.put("url", c.getStartURL(getUser()));
                    json.put("canRead", c.hasPermission(getUser(), ReadPermission.class));
                    ret.add(json);
                }
            }

            return ret;
        }
    }

    @RequiresSiteAdmin
    public class EnsureModuleActiveAction extends ConfirmAction<Object>
    {
        @Override
        public ModelAndView getConfirmView(Object o, BindException errors) throws Exception
        {
            setTitle("Enable Module Across Site");

            return new HtmlView(HtmlString.of("This will enable the PRIMe-seq module in any folder that has already enabled SequenceAnalysis.  Do you want to continue?"));
        }

        @Override
        public boolean handlePost(Object o, BindException errors) throws Exception
        {
            Container root = ContainerManager.getRoot();
            processContainer(root);

            return true;
        }

        private void processContainer(Container c)
        {
            if (!c.isContainerFor(ContainerType.DataType.folderManagement))
            {
                return;
            }

            Set<Module> am = c.getActiveModules();
            Module primeSeq = ModuleLoader.getInstance().getModule(PrimeseqModule.NAME);
            if (am.contains(ModuleLoader.getInstance().getModule("SequenceAnalysis")) && !am.contains(primeSeq))
            {
                am = new HashSet<>(am);
                am.add(primeSeq);

                _log.info("Enabling Prime-seq module in container: " + c.getPath());
                c.setActiveModules(am, getUser());
            }

            for (Container child : c.getChildren())
            {
                processContainer(child);
            }
        }

        @Override
        public void validateCommand(Object o, Errors errors)
        {

        }

        @NotNull
        @Override
        public URLHelper getSuccessURL(Object o)
        {
            return PageFlowUtil.urlProvider(PipelineUrls.class).urlBegin(getContainer());
        }
    }

}