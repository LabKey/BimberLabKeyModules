/*
 * Copyright (c) 2015 LabKey Corporation
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

package org.labkey.mgap;

import au.com.bytecode.opencsv.CSVReader;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.labkey.api.action.ApiSimpleResponse;
import org.labkey.api.action.ConfirmAction;
import org.labkey.api.action.ExportAction;
import org.labkey.api.action.MutatingApiAction;
import org.labkey.api.action.SimpleRedirectAction;
import org.labkey.api.action.SpringActionController;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.data.ColumnInfo;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.CoreSchema;
import org.labkey.api.data.DbSchema;
import org.labkey.api.data.DbSchemaType;
import org.labkey.api.data.DbScope;
import org.labkey.api.data.Results;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.Sort;
import org.labkey.api.data.Table;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.exp.api.ExpData;
import org.labkey.api.module.AllowedDuringUpgrade;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.pipeline.PipelineUrls;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.QueryAction;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.QueryUpdateService;
import org.labkey.api.query.UserSchema;
import org.labkey.api.reader.Readers;
import org.labkey.api.security.AuthenticationManager;
import org.labkey.api.security.Group;
import org.labkey.api.security.GroupManager;
import org.labkey.api.security.IgnoresTermsOfUse;
import org.labkey.api.security.MutableSecurityPolicy;
import org.labkey.api.security.RequiresNoPermission;
import org.labkey.api.security.RequiresPermission;
import org.labkey.api.security.SecurityManager;
import org.labkey.api.security.SecurityPolicyManager;
import org.labkey.api.security.User;
import org.labkey.api.security.UserManager;
import org.labkey.api.security.ValidEmail;
import org.labkey.api.security.permissions.AdminPermission;
import org.labkey.api.security.permissions.ReadPermission;
import org.labkey.api.security.roles.ReaderRole;
import org.labkey.api.sequenceanalysis.SequenceAnalysisService;
import org.labkey.api.sequenceanalysis.SequenceOutputFile;
import org.labkey.api.sequenceanalysis.pipeline.ReferenceGenome;
import org.labkey.api.settings.AppProps;
import org.labkey.api.settings.LookAndFeelProperties;
import org.labkey.api.studies.StudiesService;
import org.labkey.api.util.ConfigurationException;
import org.labkey.api.util.ExceptionUtil;
import org.labkey.api.util.FileUtil;
import org.labkey.api.util.GUID;
import org.labkey.api.util.HtmlString;
import org.labkey.api.util.MailHelper;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.Path;
import org.labkey.api.util.URLHelper;
import org.labkey.api.view.ActionURL;
import org.labkey.api.view.HtmlView;
import org.labkey.api.view.NotFoundException;
import org.labkey.api.view.UnauthorizedException;
import org.labkey.mgap.pipeline.mGapReleaseGenerator;
import org.labkey.security.xml.GroupEnumType;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.web.servlet.ModelAndView;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.internet.InternetAddress;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


public class mGAPController extends SpringActionController
{
    private static final DefaultActionResolver _actionResolver = new DefaultActionResolver(mGAPController.class);
    public static final String NAME = "mgap";
    public static final Logger _log = LogManager.getLogger(mGAPController.class);

    public mGAPController()
    {
        setActionResolver(_actionResolver);
    }

    @RequiresNoPermission
    @IgnoresTermsOfUse
    @AllowedDuringUpgrade
    public class RequestUserAction extends MutatingApiAction<RequestUserForm>
    {
        @Override
        public void validateForm(RequestUserForm form, Errors errors)
        {
            Container mGapContainer = mGAPManager.get().getMGapContainer();
            if (mGapContainer == null)
            {
                errors.reject(ERROR_MSG, "The mGAP project has not been set on this server.  This is an administrator error.");
                return;
            }

            if (StringUtils.isEmpty(form.getEmail()) || StringUtils.isEmpty(form.getEmailConfirmation()))
            {
                errors.reject(ERROR_REQUIRED, "No email address provided");
            }
            else if (StringUtils.isEmpty(form.getFirstName()) || StringUtils.isEmpty(form.getLastName()) || StringUtils.isEmpty(form.getTitle()) || StringUtils.isEmpty(form.getInstitution()) || StringUtils.isEmpty(form.getReason()))
            {
                errors.reject(ERROR_REQUIRED, "You must provide your first and last name, title, institution, and reason for requesting access");
            }
            else
            {
                try
                {
                    ValidEmail email = new ValidEmail(form.getEmail());
                    if (!form.getEmail().equals(form.getEmailConfirmation()))
                    {
                        errors.reject(ERROR_MSG, "The email addresses you have entered do not match.  Please verify your email addresses below.");
                    }

                    TableInfo ti = mGAPSchema.getInstance().getSchema().getTable(mGAPSchema.TABLE_USER_REQUESTS);

                    //first check if this email exists:
                    SimpleFilter filter = new SimpleFilter(FieldKey.fromString("email"), form.getEmail());
                    filter.addCondition(FieldKey.fromString("container"), mGapContainer.getId());
                    if (new TableSelector(ti, filter, null).exists())
                    {
                        errors.reject(ERROR_MSG, "A login has already been requested for this email.  You should receive a reply shortly from the site administrator.");
                    }
                }
                catch (ValidEmail.InvalidEmailException e)
                {
                    errors.reject(ERROR_MSG, "Your email address is not valid. Please verify your email address below.");
                }
            }
        }

        @Override
        public Object execute(RequestUserForm form, BindException errors) throws Exception
        {
            ApiSimpleResponse response = new ApiSimpleResponse();

            try
            {
                TableInfo ti = mGAPSchema.getInstance().getSchema().getTable(mGAPSchema.TABLE_USER_REQUESTS);
                Map<String, Object> row = new HashMap<>();
                row.put("email", form.getEmail());
                row.put("firstName", form.getFirstName());
                row.put("lastName", form.getLastName());
                row.put("title", form.getTitle());
                row.put("institution", form.getInstitution());
                row.put("reason", form.getReason());
                row.put("container", mGAPManager.get().getMGapContainer().getId());

                Table.insert(UserManager.getGuestUser(), ti, row);

                Set<User> users = mGAPManager.get().getNotificationUsers();
                if (users != null && !users.isEmpty())
                {
                    try
                    {
                        Set<Address> emails = new HashSet<>();
                        for (User u : users)
                        {
                            emails.add(new InternetAddress(u.getEmail()));
                        }

                        MailHelper.MultipartMessage mail = MailHelper.createMultipartMessage();
                        Container c = mGAPManager.get().getMGapContainer();
                        if (c == null)
                        {
                            _log.warn("mGAP container was not set, using: " + c.getPath());
                            c = getContainer();
                        }

                        DetailsURL url = DetailsURL.fromString("/query/executeQuery.view?schemaName=mgap&query.queryName=userRequests&query.viewName=Pending Requests", c);
                        mail.setEncodedHtmlContent("A user requested an account on mGap.  <a href=\"" + AppProps.getInstance().getBaseServerUrl() + url.getActionURL().toString()+ "\">Click here to view/approve this request</a>");
                        mail.setFrom(getReplyEmail(getContainer()));
                        mail.setSubject("mGap Account Request");
                        mail.addRecipients(Message.RecipientType.TO, emails.toArray(new Address[emails.size()]));

                        MailHelper.send(mail, getUser(), c);
                    }
                    catch (Exception e)
                    {
                        ExceptionUtil.logExceptionToMothership(null, e);
                    }
                }


            }
            catch (ConfigurationException e)
            {
                errors.reject(ERROR_MSG, "There was a problem sending the registration email.  Please contact your administrator.");
                _log.error("Error adding self registered user", e);
            }

            response.put("success", !errors.hasErrors());
            if (!errors.hasErrors())
                response.put("email", form.getEmail());

            return response;
        }
    }

    public static class RequestUserForm
    {
        private String email;
        private String emailConfirmation;
        private String firstName;
        private String lastName;
        private String title;
        private String institution;
        private String reason;

        public void setEmail(String email)
        {
            this.email = email;
        }

        public String getEmail()
        {
            return this.email;
        }

        public void setEmailConfirmation(String email)
        {
            this.emailConfirmation = email;
        }

        public String getEmailConfirmation()
        {
            return this.emailConfirmation;
        }

        public String getFirstName()
        {
            return firstName;
        }

        public void setFirstName(String firstName)
        {
            this.firstName = firstName;
        }

        public String getLastName()
        {
            return lastName;
        }

        public void setLastName(String lastName)
        {
            this.lastName = lastName;
        }

        public String getTitle()
        {
            return title;
        }

        public void setTitle(String title)
        {
            this.title = title;
        }

        public String getInstitution()
        {
            return institution;
        }

        public void setInstitution(String institution)
        {
            this.institution = institution;
        }

        public String getReason()
        {
            return reason;
        }

        public void setReason(String reason)
        {
            this.reason = reason;
        }
    }

    @RequiresPermission(AdminPermission.class)
    public class ApproveUserRequestsAction extends MutatingApiAction<ApproveUserRequestsForm>
    {
        @Override
        public void validateForm(ApproveUserRequestsForm form, Errors errors)
        {
            Container mGapContainer = mGAPManager.get().getMGapContainer();
            if (mGapContainer == null)
            {
                errors.reject(ERROR_MSG, "The mGAP project has not been set on this server.  This is an administrator error.");
                return;
            }

            if (form.getRequestIds() == null || form.getRequestIds().length == 0)
            {
                errors.reject(ERROR_MSG, "No request IDs provided");
            }

            TableInfo ti = mGAPSchema.getInstance().getSchema().getTable(mGAPSchema.TABLE_USER_REQUESTS);
            for (int requestId : form.getRequestIds())
            {
                TableSelector ts = new TableSelector(ti, PageFlowUtil.set("userId"), new SimpleFilter(FieldKey.fromString("rowId"), requestId), null);
                if (!ts.exists())
                {
                    errors.reject(ERROR_MSG, "No request found for request ID: " + requestId);
                    break;
                }

                //Note: if using LDAP, users will potentially get created automatically
                //Integer userId = ts.getObject(Integer.class);
                //if (userId != null)
                //{
                //    errors.reject(ERROR_MSG, "A user already exists for the request: " + requestId);
                //    break;
                //}
            }
        }

        @Override
        public Object execute(ApproveUserRequestsForm form, BindException errors) throws Exception
        {
            ApiSimpleResponse response = new ApiSimpleResponse();
            MutableSecurityPolicy policy = new MutableSecurityPolicy(mGAPManager.get().getMGapContainer().getPolicy());
            List<SecurityManager.NewUserStatus> newUserStatusList = new ArrayList<>();
            List<User> existingUsersGivenAccess = new ArrayList<>();
            try (DbScope.Transaction transaction = CoreSchema.getInstance().getScope().ensureTransaction())
            {
                TableInfo ti = mGAPSchema.getInstance().getSchema().getTable(mGAPSchema.TABLE_USER_REQUESTS);
                for (int requestId : form.getRequestIds())
                {
                    TableSelector ts = new TableSelector(ti, new SimpleFilter(FieldKey.fromString("rowId"), requestId), null);
                    Map<String, Object> map = ts.getMap(requestId);

                    User u;
                    if (map.get("userId") != null)
                    {
                        Integer userId = (Integer)map.get("userId");
                        u = UserManager.getUser(userId);
                        existingUsersGivenAccess.add(u);
                    }
                    else
                    {
                        ValidEmail ve = new ValidEmail((String)map.get("email"));
                        u = UserManager.getUser(ve);
                        if (u != null)
                        {
                            existingUsersGivenAccess.add(u);
                        }
                        else
                        {
                            SecurityManager.NewUserStatus st = SecurityManager.addUser(ve, getUser());
                            u = st.getUser();
                            u.setFirstName((String)map.get("firstName"));
                            u.setLastName((String)map.get("lastName"));
                            UserManager.updateUser(getUser(), u);

                            if (st.isLdapEmail())
                            {
                                existingUsersGivenAccess.add(st.getUser());
                            }
                            else
                            {
                                newUserStatusList.add(st);
                            }
                        }
                    }

                    Map<String, Object> row = new HashMap<>();
                    row.put("rowId", requestId);
                    row.put("userId", u.getUserId());
                    Table.update(getUser(), ti, row, requestId);

                    if (!policy.hasPermission(u, ReadPermission.class))
                    {
                        policy.addRoleAssignment(u, ReaderRole.class);
                    }
                    else
                    {
                        _log.info("user already has read permission on mGAP container: " + u.getDisplayName(getUser()));
                    }
                }

                SecurityPolicyManager.savePolicy(policy, getUser());

                transaction.commit();
            }

            Set<User> allUsers = new HashSet<>();
            allUsers.addAll(existingUsersGivenAccess);

            //send emails:
            for (SecurityManager.NewUserStatus st : newUserStatusList)
            {
                SecurityManager.sendRegistrationEmail(getViewContext(), st.getEmail(), null, st, null);
                allUsers.add(st.getUser());
            }

            Container mGapContainer = mGAPManager.get().getMGapContainer();
            for (User u : existingUsersGivenAccess)
            {
                boolean isLDAP = AuthenticationManager.isLdapEmail(new ValidEmail(u.getEmail()));

                MailHelper.MultipartMessage mail = MailHelper.createMultipartMessage();
                mail.setEncodedHtmlContent("Your account request has been approved for mGAP!  " + "<a href=\"" + AppProps.getInstance().getBaseServerUrl() + mGapContainer.getStartURL(getUser()) + "\">Click here to access the site.</a>" + (isLDAP ? "  Use your normal OHSU email/password to login." : ""));
                mail.setFrom(getReplyEmail(getContainer()));
                mail.setSubject("mGap Account Request");
                mail.addRecipients(Message.RecipientType.TO, u.getEmail());

                MailHelper.send(mail, getUser(), getContainer());
            }

            Group g = GroupManager.getGroup(mGapContainer, mGAPManager.GROUP_NAME, GroupEnumType.SITE);
            if (g == null)
            {
                g = SecurityManager.createGroup(ContainerManager.getRoot(), mGAPManager.GROUP_NAME);
            }

            SecurityManager.addMembers(g, allUsers);

            response.put("success", !errors.hasErrors());

            return response;
        }
    }

    private String getReplyEmail(Container c)
    {
        LookAndFeelProperties lfp = LookAndFeelProperties.getInstance(getContainer());
        String email = lfp.getSystemEmailAddress();
        if (email == null)
        {
            return AppProps.getInstance().getAdministratorContactEmail(true);
        }

        return email;
    }

    public static class ApproveUserRequestsForm
    {
        private int[] requestIds;

        public int[] getRequestIds()
        {
            return requestIds;
        }

        public void setRequestIds(int[] requestIds)
        {
            this.requestIds = requestIds;
        }
    }

    private static Map<String, Object> getReleaseRow(User u, ReleaseForm form, Errors errors)
    {
        TableInfo ti = DbSchema.get(mGAPSchema.NAME, DbSchemaType.Module).getTable(mGAPSchema.TABLE_VARIANT_CATALOG_RELEASES);
        Map<String, Object> row = new TableSelector(ti, new SimpleFilter(FieldKey.fromString("rowId"), form.getReleaseId()), null).getMap();
        if (row == null)
        {
            errors.reject(ERROR_MSG, "Unknown release: " + form.getReleaseId());
            return null;
        }

        Container rowContainer = ContainerManager.getForId((String)row.get("container"));
        if (rowContainer == null)
        {
            errors.reject(ERROR_MSG, "Unknown row container: " + form.getReleaseId());
            return null;
        }
        else if (!rowContainer.hasPermission(u, ReadPermission.class))
        {
            throw new UnauthorizedException("Cannot read the folder: " + rowContainer.getPath());
        }

        return row;
    }

    private static SequenceOutputFile getOutputFile(Map<String, Object> row, ReleaseForm form, Errors errors)
    {
        SequenceOutputFile so = SequenceOutputFile.getForId((Integer)row.get("vcfId"));
        if (so == null)
        {
            errors.reject(ERROR_MSG, "Unknown VCF file ID: " + form.getReleaseId());
            return null;
        }
        else if (so.getFile() == null || !so.getFile().exists())
        {
            errors.reject(ERROR_MSG, "VCF file does not exist: " + (so.getFile() == null ? form.getReleaseId() : so.getFile().getPath()));
            return null;
        }

        return so;
    }

    @RequiresPermission(ReadPermission.class)
    @IgnoresTermsOfUse
    public static class DownloadBundleAction extends ExportAction<DownloadBundleForm>
    {
        @Override
        public void export(DownloadBundleForm form, HttpServletResponse response, BindException errors) throws Exception
        {
            Map<String, Object> row = getReleaseRow(getUser(), form, errors);
            if (errors.hasErrors())
            {
                return;
            }

            SequenceOutputFile so = getOutputFile(row, form, errors);
            if (errors.hasErrors())
            {
                return;
            }

            Set<File> toZip = new HashSet<>();
            String zipName = "mGap_VariantCatalog_v" + FileUtil.makeLegalName((String)row.get("version"));
            zipName = zipName.replaceAll(" ", "_");

            toZip.add(so.getFile());
            toZip.add(new File(so.getFile().getPath() + ".tbi"));

            if (form.getIncludeGenome())
            {
                ReferenceGenome genome = SequenceAnalysisService.get().getReferenceGenome((Integer)row.get("genomeId"), getUser());
                if (genome == null)
                {
                    errors.reject(ERROR_MSG, "Unknown genome: " + row.get("genomeId"));
                    return;
                }

                toZip.add(genome.getSourceFastaFile());
                toZip.add(genome.getFastaIndex());
                toZip.add(genome.getSequenceDictionary());
            }

            response.reset();
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + zipName + ".zip\"");

            try (ZipOutputStream out = new ZipOutputStream(response.getOutputStream()))
            {
                for (File f : toZip)
                {
                    ZipEntry entry = new ZipEntry(f.getName());
                    out.putNextEntry(entry);

                    try (InputStream in = new BufferedInputStream(new FileInputStream(f)))
                    {
                        FileUtil.copyData(in, out);
                    }
                }
            }

            mGapAuditTypeProvider.addAuditEntry(getContainer(), getUser(), zipName, "Variant Catalog", row.get("version") == null ? null : row.get("version").toString());
        }
    }

    public static class ReleaseForm
    {
        private Integer _releaseId;

        public Integer getReleaseId()
        {
            return _releaseId;
        }

        public void setReleaseId(Integer releaseId)
        {
            _releaseId = releaseId;
        }
    }

    public static class DownloadBundleForm extends ReleaseForm
    {
        private Boolean _includeGenome;

        public Boolean getIncludeGenome()
        {
            return _includeGenome;
        }

        public void setIncludeGenome(Boolean includeGenome)
        {
            _includeGenome = includeGenome;
        }
    }

    @RequiresNoPermission
    @IgnoresTermsOfUse
    @AllowedDuringUpgrade
    public class RequestHelpAction extends MutatingApiAction<RequestHelpForm>
    {
        @Override
        public void validateForm(RequestHelpForm form, Errors errors)
        {
            Container mGapContainer = mGAPManager.get().getMGapContainer();
            if (mGapContainer == null)
            {
                errors.reject(ERROR_MSG, "The mGAP project has not been set on this server.  This is an administrator error.");
                return;
            }

            if (StringUtils.isEmpty(form.getEmail()) || StringUtils.isEmpty(form.getComment()))
            {
                errors.reject(ERROR_REQUIRED, "Must provide both an email address and question/comment");
            }
            else
            {
                try
                {
                    new ValidEmail(form.getEmail());
                }
                catch (ValidEmail.InvalidEmailException e)
                {
                    errors.reject(ERROR_MSG, "Your email address is not valid. Please verify your email address below.");
                }
            }
        }

        @Override
        public Object execute(RequestHelpForm form, BindException errors) throws Exception
        {
            Set<User> users = mGAPManager.get().getNotificationUsers();
            if (users != null && !users.isEmpty())
            {
                try
                {
                    Set<Address> emails = new HashSet<>();
                    for (User u : users)
                    {
                        emails.add(new InternetAddress(u.getEmail()));
                    }

                    MailHelper.MultipartMessage mail = MailHelper.createMultipartMessage();
                    mail.setEncodedHtmlContent("A support request was submitted from mGap by: " + form.getEmail() + "<br><br>Message:<br>" + form.getComment());
                    mail.setFrom(form.getEmail());
                    mail.setSubject("mGap Help Request");
                    mail.addRecipients(Message.RecipientType.TO, emails.toArray(new Address[emails.size()]));

                    MailHelper.send(mail, getUser(), getContainer());
                }
                catch (Exception e)
                {
                    ExceptionUtil.logExceptionToMothership(null, e);
                }
            }
            else
            {
                _log.error("A help request was received by mGAP, but the admin emails have not been configured.  The request from: " + form.getEmail());
                _log.error(form.getComment());
            }

            return new ApiSimpleResponse("success", true);
        }
    }

    public static class RequestHelpForm
    {
        private String _email;
        private String _comment;

        public String getEmail()
        {
            return _email;
        }

        public void setEmail(String email)
        {
            _email = email;
        }

        public String getComment()
        {
            return _comment;
        }

        public void setComment(String comment)
        {
            _comment = comment;
        }
    }

    @RequiresPermission(AdminPermission.class)
    public static class UpdateSnpEffAction extends ConfirmAction<Object>
    {
        @Override
        public ModelAndView getConfirmView(Object o, BindException errors) throws Exception
        {
            setTitle("Update Update SnpEff Annotation");

            return new HtmlView("Do you want to continue?");
        }

        @Override
        public boolean handlePost(Object o, BindException errors) throws Exception
        {
            UserSchema us = QueryService.get().getUserSchema(getUser(), getContainer(), mGAPSchema.NAME);
            TableSelector ts = new TableSelector(us.getTable(mGAPSchema.TABLE_VARIANT_CATALOG_RELEASES), PageFlowUtil.set("rowId", "objectId", "container"), null, new Sort("-releaseDate"));
            ts.setMaxRows(1);
            int releaseRowId;
            String releaseObjectId;
            String releaseContainerId;
            try (Results rs = ts.getResults())
            {
                rs.first();
                releaseRowId = rs.getInt("rowId");
                releaseObjectId = rs.getString("objectId");
                releaseContainerId = rs.getString("container");
            }

            List<Map<String, Object>> toInsert = new ArrayList<>();

            Sort sort = new Sort(FieldKey.fromString("contig"));
            sort.appendSortColumn(FieldKey.fromString("position"), Sort.SortDirection.ASC, false);

            Integer outputFileId = new TableSelector(mGAPSchema.getInstance().getSchema().getTable(mGAPSchema.TABLE_VARIANT_CATALOG_RELEASES), Collections.singleton("vcfId")).getObject(releaseRowId, Integer.class);
            ExpData data = SequenceOutputFile.getForId(outputFileId).getExpData();
            File vcf = data.getFile();

            try (VCFFileReader reader = new VCFFileReader(vcf))
            {
                try (CloseableIterator<VariantContext> it = reader.iterator())
                {
                    Map<String, Long> map = new HashMap<>();
                    while (it.hasNext())
                    {
                        VariantContext vc = it.next();
                        if (!vc.hasAttribute("ANN"))
                        {
                            continue;
                        }

                        String ann = vc.getAttributeAsString("ANN", null);
                        Set<String> codingPotential = new HashSet<>();
                        String[] tokens = ann.split(",");
                        for (String v : tokens)
                        {
                            String[] split = v.split("\\|");
                            String[] types = split[1].split("&");
                            codingPotential.addAll(Arrays.asList(types));
                        }

                        mGapReleaseGenerator.Processor.filterCodingPotential(codingPotential);
                        //coding potential:
                        String type = StringUtils.join(new TreeSet<>(codingPotential), ";");
                        Long v = map.getOrDefault(type, 0L);
                        v++;
                        map.put(type, v);
                    }

                    for (String type : map.keySet())
                    {
                        _log.info(type);
                        Map<String, Object> row = new CaseInsensitiveHashMap<>();
                        row.put("releaseId", releaseObjectId);
                        row.put("category", "CodingPotential");
                        row.put("metricName", type);
                        row.put("value", map.get(type));
                        row.put("container", releaseContainerId);
                        row.put("objectid", new GUID().toString());
                        toInsert.add(row);
                    }
                }
            }

            _log.info("total inserts: " + toInsert.size());
            try
            {
                SimpleFilter deleteFilter = new SimpleFilter(FieldKey.fromString("releaseId"), releaseObjectId);
                deleteFilter.addCondition(FieldKey.fromString("container"), releaseContainerId);
                deleteFilter.addCondition(FieldKey.fromString("category"), "CodingPotential");
                List<String> toDelete = new TableSelector(us.getTable(mGAPSchema.TABLE_RELEASE_STATS), PageFlowUtil.set("objectId"), deleteFilter, null).getArrayList(String.class);
                _log.info("deleting existing rows: " + toDelete.size());

                Container target = ContainerManager.getForId(releaseContainerId);
                us = QueryService.get().getUserSchema(getUser(), target, mGAPSchema.NAME);
                QueryUpdateService qus = us.getTable(mGAPSchema.TABLE_RELEASE_STATS).getUpdateService();
                qus.setBulkLoad(true);

                List<Map<String, Object>> rows = new ArrayList<>();
                toDelete.forEach(x -> {
                    Map<String, Object> map = new CaseInsensitiveHashMap<>();
                    map.put("objectId", x);
                    rows.add(map);
                });

                qus.deleteRows(getUser(), target, rows, null, Collections.emptyMap());

                BatchValidationException bve = new BatchValidationException();
                qus.insertRows(getUser(), target, toInsert, bve, null, new HashMap<>());
                if (bve.hasErrors())
                {
                    throw bve;
                }

                _log.info("complete");
            }
            catch (Exception e)
            {
                _log.error(e);
                throw e;
            }

            return true;
        }

        @Override
        public void validateCommand(Object o, Errors errors)
        {

        }

        @Override
        public @NotNull URLHelper getSuccessURL(Object o)
        {
            return getContainer().getStartURL(getUser());
        }
    }

    public static class GenomeBrowserForm
    {
        private String _databaseId;
        private String _species;
        private String _trackName;
        private String _target = "browser";

        public String getDatabaseId()
        {
            return _databaseId;
        }

        public void setDatabaseId(String databaseId)
        {
            _databaseId = databaseId;
        }

        public String getSpecies()
        {
            return _species;
        }

        public void setSpecies(String species)
        {
            _species = species;
        }

        public String getTrackName()
        {
            return _trackName;
        }

        public void setTrackName(String trackName)
        {
            _trackName = trackName;
        }

        public String getTarget()
        {
            return _target;
        }

        public void setTarget(String target)
        {
            _target = target;
        }
    }

    @RequiresPermission(ReadPermission.class)
    public class GenomeBrowserAction extends SimpleRedirectAction<GenomeBrowserForm>
    {
        @Override
        public URLHelper getRedirectURL(GenomeBrowserForm form)
        {
            Container target = mGAPManager.get().getMGapContainer();
            if (target == null)
            {
                throw new NotFoundException("No mGAP Project is configured on this server");
            }

            if (!target.hasPermission(getUser(), ReadPermission.class))
            {
                throw new UnauthorizedException("The current user does not have read permission on the folder: " + target.getPath());
            }

            JSONObject ctx = ModuleLoader.getInstance().getModule(mGAPModule.class).getPageContextJson(getViewContext());
            if (ctx.isNull("mgapJBrowse"))
            {
                throw new NotFoundException("There is no mGAP release on this server");
            }

            String jbrowseDatabaseId = StringUtils.trimToNull(form.getDatabaseId());
            String species = StringUtils.trimToNull(form.getSpecies());
            if (jbrowseDatabaseId == null)
            {
                jbrowseDatabaseId = ctx.getString("human".equals(species) ? "mgapJBrowseHuman": "mgapJBrowse");
            }

            if (jbrowseDatabaseId == null)
            {
                throw new NotFoundException("No databaseId provided");
            }

            String actionName = form.getTarget() == null ? "browser" : form.getTarget();
            if (!"browser".equals(actionName) && !"variantSearch".equals(actionName))
            {
                throw new IllegalArgumentException("Unknown target: " + actionName);
            }

            Map<String, String[]> params = new HashMap<>(getViewContext().getRequest().getParameterMap());
            params.put("session", new String[]{jbrowseDatabaseId});

            // This requires trackId
            if ("variantSearch".equals(actionName))
            {
                Collection<String> trackIDs = getTracks(target, jbrowseDatabaseId, ctx.getString("mgapReleaseGUID"), Collections.singletonList("mGAP Release"));
                if (!trackIDs.isEmpty())
                {
                    if (trackIDs.size() > 1)
                    {
                        _log.error("More than one track found matching 'mGAP Release' for release: " + ctx.getString("mgapReleaseGUID"));
                    }

                    params.put("trackId", new String[]{trackIDs.iterator().next()});
                }
                else
                {
                    throw new IllegalArgumentException("Unable to find primary track for release: " + ctx.getString("mgapReleaseGUID"));
                }
            }

            String trackName = StringUtils.trimToNull(form.getTrackName());
            if (trackName != null)
            {
                List<String> trackNames = Arrays.asList(trackName.split(","));
                Collection<String> trackIDs = getTracks(target, jbrowseDatabaseId, ctx.getString("mgapReleaseGUID"), trackNames);
                if (!trackIDs.isEmpty())
                {
                    params.put("tracks", new String[]{StringUtils.join(trackIDs, ",")});
                }
            }

            ActionURL ret = DetailsURL.fromString("/jbrowse/" + actionName + ".view", target).getActionURL();
            params.forEach((key, value) -> {
                Arrays.stream(value).forEach(v -> {
                    // This is a convenience to allow shorter URLs for active sample filters:
                    if (key.equals("sampleFilters"))
                    {
                        String newVal = v;
                        if (newVal.startsWith("mgap:"))
                        {
                            newVal = newVal.replaceAll("^mgap:", "mGAP Release:");
                        }

                        ret.addParameter(key, newVal);
                    }
                    else
                    {
                        ret.addParameter(key, v);
                    }
                });
            });

            return ret;
        }

        public Collection<String> getTracks(Container target, String jbrowseSession, String releaseId, List<String> trackNames)
        {
            Set<String> ret = new LinkedHashSet<>();

            UserSchema mgap = QueryService.get().getUserSchema(getUser(), target, mGAPSchema.NAME);
            UserSchema jbrowse = QueryService.get().getUserSchema(getUser(), target, "jbrowse");

            //find the selected tracks:
            SimpleFilter trackFilter = new SimpleFilter(FieldKey.fromString("releaseId"), releaseId);
            trackFilter.addCondition(FieldKey.fromString("trackName"), trackNames, CompareType.IN);
            List<Integer> outputFileIds = new TableSelector(mgap.getTable(mGAPSchema.TABLE_TRACKS_PER_RELEASE), PageFlowUtil.set("vcfId"), trackFilter, null).getArrayList(Integer.class);

            //now database members from these outputFileIds:
            TableInfo databaseMembers = jbrowse.getTable("database_members");
            Map<FieldKey, ColumnInfo> cols = QueryService.get().getColumns(databaseMembers, PageFlowUtil.set(FieldKey.fromString("jsonfile/relpath")));
            if (!outputFileIds.isEmpty())
            {
                SimpleFilter dbFilter = new SimpleFilter(FieldKey.fromString("database"), jbrowseSession);
                dbFilter.addCondition(FieldKey.fromString("jsonfile/outputfile"), outputFileIds, CompareType.IN);
                List<String> relPaths = new TableSelector(databaseMembers, cols.values(), dbFilter, null).getArrayList(String.class);
                if (relPaths != null && !relPaths.isEmpty())
                {
                    relPaths.forEach(r -> ret.add(r.contains("/") ? r.split("/")[1] : r));
                }
                else
                {
                    _log.error("Unable to find jsonfiles for tracks: " + StringUtils.join(trackNames, ";") + ", with outputIDs: " + StringUtils.join(outputFileIds, ","));
                }
            }
            else
            {
                _log.error("Unable to find tracks: " + StringUtils.join(trackNames, ";"));
            }

            //any tracks that are defaultVisible for this session, which should include the primary track:
            SimpleFilter filter = new SimpleFilter(FieldKey.fromString("database"), jbrowseSession);
            filter.addCondition(FieldKey.fromString("jsonfile/trackJson"), PageFlowUtil.set("visibleByDefault\":true", "visibleByDefault\": true"), CompareType.CONTAINS_ONE_OF);
            List<String> relPaths = new TableSelector(databaseMembers, cols.values(), filter, null).getArrayList(String.class);
            if (relPaths != null && !relPaths.isEmpty())
            {
                relPaths.forEach(r -> ret.add(r.contains("/") ? r.split("/")[1] : r));
            }
            else
            {
                _log.error("Unable to find any defaultVisible tracks for database: " + jbrowseSession);
            }

            //find genome ID.  this could be across folders, so use DB schema
            DbSchema jbrowseSchema = DbSchema.get("jbrowse", DbSchemaType.Module);
            TableInfo databasesTable = jbrowseSchema.getTable("databases");
            Integer genomeId = new TableSelector(databasesTable, PageFlowUtil.set("libraryId"), new SimpleFilter(FieldKey.fromString("objectid"), jbrowseSession), null).getObject(Integer.class);

            //then find the base jbrowse session for this genome.
            SimpleFilter filterPrimaryDb = new SimpleFilter(FieldKey.fromString("libraryId"), genomeId);
            filterPrimaryDb.addCondition(FieldKey.fromString("primarydb"), true);
            String defaultSessionContainerId = new TableSelector(databasesTable, PageFlowUtil.set("container"), filterPrimaryDb, null).getObject(String.class);
            if (defaultSessionContainerId != null)
            {
                //any tracks that are defaultVisible for the primary DB:
                Container defaultSessionContainer = ContainerManager.getForId(defaultSessionContainerId);
                UserSchema dsJbrowseSchema = defaultSessionContainer.equals(target) ? jbrowse : QueryService.get().getUserSchema(getUser(), defaultSessionContainer, "jbrowse");
                SimpleFilter filterTracks = new SimpleFilter(FieldKey.fromString("trackJson"), PageFlowUtil.set("visibleByDefault\":true", "visibleByDefault\": true"), CompareType.CONTAINS_ONE_OF);
                filterTracks.addCondition(FieldKey.fromString("trackid/library_id"), genomeId);
                List<Integer> trackIds = new TableSelector(dsJbrowseSchema.getTable("jsonfiles"), PageFlowUtil.set("trackid"), filterTracks, null).getArrayList(Integer.class);
                if (trackIds != null && !trackIds.isEmpty())
                {
                    trackIds.forEach(x -> ret.add("track-" + x));
                }
            }
            else
            {
                _log.error("Unable to find the default jbrowse session associated with genome: " + genomeId);
            }
            return ret;
        }
    }

    @RequiresPermission(AdminPermission.class)
    public static class UpdateAnnotationsAction extends ConfirmAction<Object>
    {
        @Override
        public ModelAndView getConfirmView(Object o, BindException errors) throws Exception
        {
            setTitle("Update Annotation Table");

            HtmlView view = new HtmlView("This will update the annotation table using the VariantAnnotation github repo. Do you want to continue?");
            return view;
        }

        @Override
        public boolean handlePost(Object o, BindException errors) throws Exception
        {
            List<Map<String, Object>> toAdd = new ArrayList<>();

            final List<String> urls = Arrays.asList(
                    "https://raw.githubusercontent.com/bimberlabinternal/VariantAnnotation/master/fieldConfig.txt",
                    "https://raw.githubusercontent.com/bimberlabinternal/VariantAnnotation/master/otherFieldConfig.txt"
            );

            for (String urlStr : urls)
            {
                final URL url = new URL(urlStr);
                try (CSVReader reader = new CSVReader(Readers.getReader(url.openStream()), '\t'))
                {
                    String[] line;
                    List<String> header = null;
                    int idx = 0;
                    while ((line = reader.readNext()) != null)
                    {
                        idx++;
                        if (idx == 1)
                        {
                            header = Arrays.asList(line);
                            continue;
                        }

                        Map<String, Object> row = new CaseInsensitiveHashMap<>();

                        row.put("category", line[header.indexOf("Category")]);
                        row.put("label", line[header.indexOf("Label")]);
                        row.put("dataSource", line[header.indexOf("DataSource")]);
                        row.put("infoKey", line[header.indexOf("ID")]);
                        row.put("sourceField", line[header.indexOf("SourceField")]);
                        row.put("dataType", line[header.indexOf("Type")]);
                        row.put("dataNumber", line[header.indexOf("Number")]);
                        row.put("description", line[header.indexOf("Description")]);
                        row.put("url", line[header.indexOf("URL")]);
                        row.put("toolName", line[header.indexOf("ToolName")]);

                        getOptionalField(line, header, "DataURL", row, "dataurl");
                        getOptionalField(line, header, "Hidden", row, "hidden");
                        getOptionalField(line, header, "FormatString", row, "formatString");
                        getOptionalField(line, header, "AllowableValues", row, "allowableValues");
                        getOptionalField(line, header, "IsIndexed", row, "isIndexed");

                        toAdd.add(row);
                    }
                }
            }

            UserSchema us = QueryService.get().getUserSchema(getUser(), getContainer(), mGAPSchema.NAME);
            TableInfo ti = us.getTable(mGAPSchema.TABLE_VARIANT_ANNOTATIONS);
            ti.getUpdateService().truncateRows(getUser(), getContainer(), null, null);

            BatchValidationException bve = new BatchValidationException();
            ti.getUpdateService().insertRows(getUser(), getContainer(), toAdd, bve, null, null);
            if (bve.hasErrors())
            {
                throw bve;
            }

            return true;
        }

        private void getOptionalField(String[] line, List<String> header, String name, Map<String, Object> row, String rowKey)
        {
            if (header.contains(name) && line.length > header.indexOf(name))
            {
                row.put(rowKey, line[header.indexOf(name)]);
            }
        }

        @Override
        public void validateCommand(Object o, Errors errors)
        {

        }

        @Override
        public @NotNull URLHelper getSuccessURL(Object o)
        {
            return QueryService.get().urlFor(getUser(), getContainer(), QueryAction.executeQuery, mGAPSchema.NAME, mGAPSchema.TABLE_VARIANT_ANNOTATIONS);
        }
    }

    @RequiresPermission(AdminPermission.class)
    public class ImportStudyAction extends ConfirmAction<Object>
    {
        @Override
        public ModelAndView getConfirmView(Object o, BindException errors) throws Exception
        {
            setTitle("Import mGAP Study");

            return new HtmlView(HtmlString.unsafe("This will import the default mGAP study in this folder and set the EHRStudyContainer property to point to this container. Do you want to continue?"));
        }

        @Override
        public boolean handlePost(Object o, BindException errors) throws Exception
        {
            StudiesService.get().importFolderDefinition(getContainer(), getUser(), ModuleLoader.getInstance().getModule(mGAPModule.NAME), new Path("referenceStudy"));

            return true;
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