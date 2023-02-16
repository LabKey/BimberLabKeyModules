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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.labkey.api.action.ApiResponse;
import org.labkey.api.action.ApiSimpleResponse;
import org.labkey.api.action.ConfirmAction;
import org.labkey.api.action.MutatingApiAction;
import org.labkey.api.action.ReadOnlyApiAction;
import org.labkey.api.action.SpringActionController;
import org.labkey.api.cluster.ClusterService;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.ContainerType;
import org.labkey.api.data.DbScope;
import org.labkey.api.data.SQLFragment;
import org.labkey.api.data.SqlExecutor;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineJob;
import org.labkey.api.pipeline.PipelineJobException;
import org.labkey.api.pipeline.PipelineService;
import org.labkey.api.pipeline.PipelineStatusFile;
import org.labkey.api.pipeline.PipelineUrls;
import org.labkey.api.security.RequiresPermission;
import org.labkey.api.security.RequiresSiteAdmin;
import org.labkey.api.security.permissions.ReadPermission;
import org.labkey.api.sequenceanalysis.pipeline.JobResourceSettings;
import org.labkey.api.sequenceanalysis.pipeline.ToolParameterDescriptor;
import org.labkey.api.util.HtmlString;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.URLHelper;
import org.labkey.api.view.ActionURL;
import org.labkey.api.view.HtmlView;
import org.labkey.api.writer.PrintWriters;
import org.labkey.sequenceanalysis.SequencePipelineServiceImpl;
import org.labkey.sequenceanalysis.pipeline.SequenceJob;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.web.servlet.ModelAndView;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
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
        @Override
        public ApiResponse execute(Object form, BindException errors)
        {
            Map<String, Object> resultProperties = new HashMap<>();

            resultProperties.put("collaborations", getSection("/Labs/Bimber/Collaborations"));
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
                json.put("url", new ActionURL("wiki", "page", publicContainer) + "name=Genome Browser Instructions");
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

    @RequiresSiteAdmin
    public static class UpdateFilePathsAction extends ConfirmAction<UpdateFilePathsForm>
    {
        @Override
        public ModelAndView getConfirmView(UpdateFilePathsForm form, BindException errors) throws Exception
        {
            StringBuilder html = new StringBuilder();
            if (form.getReplacementPrefix() == null)
            {
                html.append("This action is designed to bulk update filepaths stored in the database, such as when a folder's file root is updated. This circumvents LabKey's normal file update listeners, and should only be performed if you are certain this is what you want. A reason for this is because the default codepath can be slow with extremely large moves.");
                html.append("<br><br>");
                html.append("Enter the following:<br>");
                html.append("<table>");
                html.append("<tr><td><label for='sourcePrefix'>File Prefix to Replace:</label></td>");
                html.append("<td><input name='sourcePrefix' type='text' width='600' value='" + HtmlString.of(form.getSourcePrefix()) + "'></td>");
                html.append("</tr>");
                html.append("<td><label for='replacementPrefix'>Replacement:</label></td>");
                html.append("<td><input name='replacementPrefix' type='text' width='600' value='" + HtmlString.of(form.getReplacementPrefix()) + "'></td>");
                html.append("</tr></table>");
                html.append("<br>");
                html.append("When you hit confirm, you will be given an intermediate page summarizing changes before any changes are actually committed. Note: this could potentially make changes site-wide. Continue?");

                return new HtmlView(HtmlString.unsafe(html.toString()));
            }
            else
            {
                return new HtmlView(HtmlString.unsafe(generateChangeSummary(form)));
            }
        }

        private String generateChangeSummary(UpdateFilePathsForm form)
        {

            String ret = "You entered the following values:" +
                    "<br>" +
                    "<table>" +
                    "<tr><td><label for='sourcePrefix'>File Prefix to Replace:</label></td>" +
                    "<td>" + HtmlString.of(form.getSourcePrefix()) + "</td>" +
                    "</tr>" +
                    "<td><label for='replacementPrefix'>Replacement:</label></td>" +
                    "<td>" + HtmlString.of(form.getReplacementPrefix()) + "</td>" +
                    "</tr></table>" +
                    "<input type='hidden' name='updateDatabase' value='1'" +
                    "The following SQL will be executed. Please check carefully before hitting confirm:" +
                    "<br>" +
                    "<pre>" +
                    getSql(form, true) +
                    "</pre>" +
                    "<br>" +
                    "Note: if the URL of the folder changed you may also want to execute something like the following (manually):<br>" +
                    "<pre>" +
                    HtmlString.of("UPDATE pipeline.StatusFiles SET DataUrl = replace(DataUrl, '<SOURCE_FOLDER_URL>', '<REPLACEMENT_FOLDER_URL>') ") +
                    HtmlString.of("WHERE DataUrl like '%<SOURCE_FOLDER_URL>%';") +
                    "</pre>" +
                    "<br>";

            return ret;
        }

        private String ensureSlashes(String input)
        {
            if (input == null)
            {
                return input;
            }

            if (!input.startsWith("/"))
            {
                input = "/" + input;
            }

            if (!input.endsWith("/"))
            {
                input = input + "/";
            }

            return input;
        }

        private String getSql(UpdateFilePathsForm form, boolean calculateCounts)
        {
            // Ensure start/end with slash:
            String sourcePrefix = ensureSlashes(form.getSourcePrefix());
            String replacementPrefix = ensureSlashes(form.getReplacementPrefix());

            StringBuilder sql = new StringBuilder();

            if (calculateCounts)
            {
                int count = new SqlExecutor(DbScope.getLabKeyScope()).execute(new SQLFragment("SELECT count(*) FROM Exp.Data WHERE DataFileUrl like 'file://" + sourcePrefix + "%'"));
                sql.append("--Matching rows: " + count + "\n");
            }

            sql.append("UPDATE Exp.Data SET DataFileUrl = replace(DataFileUrl, 'file://" + sourcePrefix + "', 'file://" + replacementPrefix + "') ");
            sql.append("WHERE DataFileUrl like 'file://" + sourcePrefix + "%';\n");

            if (calculateCounts)
            {
                int count = new SqlExecutor(DbScope.getLabKeyScope()).execute(new SQLFragment("SELECT count(*) FROM pipeline.StatusFiles WHERE FilePath like '" + sourcePrefix + "%'"));
                sql.append("--Matching rows: " + count + "\n");
            }
            sql.append("UPDATE pipeline.StatusFiles SET FilePath = replace(FilePath, '" + sourcePrefix + "', '" + replacementPrefix + "') ");
            sql.append("WHERE FilePath like '" + sourcePrefix + "%';");

            return sql.toString();
        }
        @Override
        public boolean handlePost(UpdateFilePathsForm form, BindException errors) throws Exception
        {
            if (form.isUpdateDatabase())
            {
                String sql = getSql(form, false);

                SqlExecutor se = new SqlExecutor(DbScope.getLabKeyScope());
                se.execute(new SQLFragment(sql));
            }

            return true;
        }

        @Override
        public void validateCommand(UpdateFilePathsForm form, Errors errors)
        {
            if (form.isUpdateDatabase() && form.getReplacementPrefix() == null)
            {
                errors.reject(ERROR_MSG, "Missing replacementPrefix");
            }

            if (form.isUpdateDatabase() && form.getSourcePrefix() == null)
            {
                errors.reject(ERROR_MSG, "Missing sourcePrefix");
            }
        }

        @Override
        public @NotNull URLHelper getSuccessURL(UpdateFilePathsForm form)
        {
            if (!form.isUpdateDatabase())
            {
                ActionURL url = new ActionURL(UpdateFilePathsAction.class, getContainer());
                url.addParameter("sourcePrefix", form.getSourcePrefix());
                url.addParameter("replacementPrefix", form.getReplacementPrefix());

                return url;
            }
            else
            {
                return getContainer().getStartURL(getUser());
            }
        }
    }

    public static class UpdateFilePathsForm
    {
        private String _sourcePrefix;
        private String _replacementPrefix;
        private boolean _updateDatabase = false;

        public String getSourcePrefix()
        {
            return _sourcePrefix;
        }

        public void setSourcePrefix(String sourcePrefix)
        {
            _sourcePrefix = sourcePrefix;
        }

        public String getReplacementPrefix()
        {
            return _replacementPrefix;
        }

        public void setReplacementPrefix(String replacementPrefix)
        {
            _replacementPrefix = replacementPrefix;
        }

        public boolean isUpdateDatabase()
        {
            return _updateDatabase;
        }

        public void setUpdateDatabase(boolean updateDatabase)
        {
            _updateDatabase = updateDatabase;
        }
    }

    @RequiresPermission(ReadPermission.class)
    public class GetResourceSettingsForJobAction extends ReadOnlyApiAction<GetResourceSettingsForJobForm>
    {
        @Override
        public ApiResponse execute(GetResourceSettingsForJobForm form, BindException errors)
        {
            if (form.getJobRowIds().isEmpty())
            {
                errors.reject(ERROR_MSG, "Must provide at least one JobId");
                return null;
            }

            List<JSONObject> resourceSettings = new ArrayList<>();
            for (JobResourceSettings settings : SequencePipelineServiceImpl.get().getResourceSettings())
            {
                if (settings.isAvailable(getContainer()))
                {
                    for (ToolParameterDescriptor pd : settings.getParams())
                    {
                        resourceSettings.add(pd.toJSON());
                    }
                }
            }

            Map<String, Set<Object>> valueMap = new HashMap<>();
            for (Integer jobId : form.getJobRowIds())
            {
                PipelineStatusFile sf = PipelineService.get().getStatusFile(jobId);
                if (sf == null)
                {
                    errors.reject(ERROR_MSG, "Unknown job: " + jobId);
                    return null;
                }
                else if (!sf.lookupContainer().hasPermission(getUser(), ReadPermission.class))
                {
                    errors.reject(ERROR_MSG, "The current user does not have permission to view the folder: " + sf.lookupContainer().getPath());
                    return null;
                }

                File log = new File(sf.getFilePath());
                File json = ClusterService.get().getSerializedJobFile(log);
                if (!json.exists())
                {
                    errors.reject(ERROR_MSG, "Unable to find job JSON, expected: " + json.getPath());
                    return null;
                }

                try
                {
                    PipelineJob job = PipelineJob.readFromFile(json);
                    if (!(job instanceof SequenceJob sj))
                    {
                        errors.reject(ERROR_MSG, "Altering cluster params is only supported for sequence jobs");
                        return null;
                    }

                    JSONObject jobParams = sj.getParameterJson();
                    for (JSONObject jsonObj : resourceSettings)
                    {
                        String name = jsonObj.getString("name");
                        String jsonName = "resourceSettings.resourceSettings." + name;
                        if (jobParams.has(jsonName) && jobParams.get(jsonName) != null)
                        {
                            if (!valueMap.containsKey(name))
                            {
                                valueMap.put(name, new HashSet<>());
                            }

                            valueMap.get(name).add(jobParams.get(jsonName));
                        }
                    }
                }
                catch (IOException | PipelineJobException e)
                {
                    errors.reject(ERROR_MSG, "Unable to read pipeline JSON");
                    _log.error(e);
                    return null;
                }
            }

            Map<String, Object> ret = new HashMap<>();
            ret.put("name", "resourceSettings");
            for (JSONObject o : resourceSettings)
            {
                String name = o.getString("name");
                if (valueMap.containsKey(name) && valueMap.get(name).size() == 1)
                {
                    o.put("defaultValue", valueMap.get(name).iterator().next());
                    o.put("value", valueMap.get(name).iterator().next());
                }
            }

            ret.put("parameters", resourceSettings);

            return new ApiSimpleResponse(ret);
        }
    }

    @RequiresPermission(ReadPermission.class)
    public class SetResourceSettingsForJobAction extends MutatingApiAction<GetResourceSettingsForJobForm>
    {
        @Override
        public void validateForm(GetResourceSettingsForJobForm form, Errors errors)
        {
            super.validateForm(form, errors);

            if (form.getJobRowIds().isEmpty())
            {
                errors.reject(ERROR_MSG, "Must provide JobId");
            }
        }

        @Override
        public Object execute(GetResourceSettingsForJobForm form, BindException errors) throws Exception
        {
            for (Integer jobId : form.getJobRowIds())
            {
                PipelineStatusFile sf = PipelineService.get().getStatusFile(jobId);
                if (sf == null)
                {
                    errors.reject(ERROR_MSG, "Unknown job: " + jobId);
                    return null;
                }
                else if (!sf.lookupContainer().hasPermission(getUser(), ReadPermission.class))
                {
                    errors.reject(ERROR_MSG, "The current user does not have permission to view the folder: " + sf.lookupContainer().getPath());
                    return null;
                }

                File log = new File(sf.getFilePath());
                File jobJson = ClusterService.get().getSerializedJobFile(log);
                if (!jobJson.exists())
                {
                    errors.reject(ERROR_MSG, "Unable to find job JSON, expected: " + jobJson.getPath());
                    return null;
                }

                PipelineJob job = null;
                try
                {
                    job = PipelineJob.readFromFile(jobJson);
                    if (!(job instanceof SequenceJob sj))
                    {
                        errors.reject(ERROR_MSG, "Changing cluster parameters is only supported for Sequence jobs");
                        return null;
                    }

                    JSONObject json = sj.getParameterJson();

                    JSONObject paramJson = new JSONObject(form.getParamJson());
                    for (String prop : paramJson.keySet())
                    {
                        json.put(prop, paramJson.get(prop));
                    }

                    try (PrintWriter writer = PrintWriters.getPrintWriter(sj.getParametersFile()))
                    {
                        writer.write(json.toString(1));
                    }

                    File submitScript = ClusterService.get().getExpectedSubmitScript(job);
                    if (submitScript == null)
                    {
                        _log.error("Unable to find submit script for job: " + sf.getRowId());
                    }
                    else if (submitScript.exists())
                    {
                        submitScript.delete();
                    }
                }
                catch (Exception e)
                {
                    if (job != null)
                    {
                        job.getLogger().error("Unable to update job params", e);
                    }
                    else
                    {
                        _log.error("Unable to update job params", e);
                    }
                }
            }

            return null;
        }
    }

    public static class GetResourceSettingsForJobForm
    {
        private String _jobIds;
        private String _paramJson;

        public String getJobIds()
        {
            return _jobIds;
        }

        public void setJobIds(String jobIds)
        {
            _jobIds = jobIds;
        }

        public String getParamJson()
        {
            return _paramJson;
        }

        public void setParamJson(String paramJson)
        {
            _paramJson = paramJson;
        }

        public Collection<Integer> getJobRowIds()
        {
            Set<Integer> ret = new HashSet<>();
            for (String id : getJobIds().split(","))
            {
                ret.add(Integer.parseInt(StringUtils.trimToNull(id)));
            }

            return ret;
        }
    }
}