/*
 * Copyright (c) 2020 LabKey Corporation
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

package org.labkey.mcc;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.labkey.api.action.ApiSimpleResponse;
import org.labkey.api.action.ConfirmAction;
import org.labkey.api.action.MutatingApiAction;
import org.labkey.api.action.SpringActionController;
import org.labkey.api.collections.CaseInsensitiveHashMap;
import org.labkey.api.collections.CaseInsensitiveHashSet;
import org.labkey.api.data.CompareType;
import org.labkey.api.data.Container;
import org.labkey.api.data.ContainerManager;
import org.labkey.api.data.CoreSchema;
import org.labkey.api.data.DbScope;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.Table;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.module.AllowedDuringUpgrade;
import org.labkey.api.module.Module;
import org.labkey.api.module.ModuleLoader;
import org.labkey.api.module.ModuleProperty;
import org.labkey.api.pipeline.PipelineUrls;
import org.labkey.api.query.BatchValidationException;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.query.FieldKey;
import org.labkey.api.query.InvalidKeyException;
import org.labkey.api.query.QueryService;
import org.labkey.api.query.QueryUpdateServiceException;
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
import org.labkey.api.security.roles.RoleManager;
import org.labkey.api.settings.AppProps;
import org.labkey.api.settings.LookAndFeelProperties;
import org.labkey.api.studies.StudiesService;
import org.labkey.api.study.Dataset;
import org.labkey.api.study.Study;
import org.labkey.api.study.StudyService;
import org.labkey.api.util.ConfigurationException;
import org.labkey.api.util.ExceptionUtil;
import org.labkey.api.util.GUID;
import org.labkey.api.util.HtmlString;
import org.labkey.api.util.MailHelper;
import org.labkey.api.util.PageFlowUtil;
import org.labkey.api.util.Path;
import org.labkey.api.util.URLHelper;
import org.labkey.api.view.HtmlView;
import org.labkey.mcc.etl.ZimsImportTask;
import org.labkey.mcc.security.MccDataAdminPermission;
import org.labkey.mcc.security.MccDataAdminRole;
import org.labkey.mcc.security.MccFinalReviewerRole;
import org.labkey.mcc.security.MccRabReviewerRole;
import org.labkey.mcc.security.MccRequestAdminPermission;
import org.labkey.mcc.security.MccRequesterRole;
import org.labkey.security.xml.GroupEnumType;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;
import org.springframework.web.servlet.ModelAndView;

import jakarta.mail.Address;
import jakarta.mail.Message;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MccController extends SpringActionController
{
    private static final DefaultActionResolver _actionResolver = new DefaultActionResolver(MccController.class);
    public static final String NAME = "mcc";

    private static final Logger _log = LogManager.getLogger(MccController.class);

    public MccController()
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
            Container mccContainer = MccManager.get().getMCCContainer(getContainer());
            if (mccContainer == null)
            {
                errors.reject(ERROR_MSG, "The MCC project has not been set on this server.  This is an administrator error.");
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

                    TableInfo ti = MccSchema.getInstance().getSchema().getTable(MccSchema.TABLE_USER_REQUESTS);

                    //first check if this email exists:
                    SimpleFilter filter = new SimpleFilter(FieldKey.fromString("email"), form.getEmail());
                    filter.addCondition(FieldKey.fromString("container"), mccContainer.getId());
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
                TableInfo ti = MccSchema.getInstance().getSchema().getTable(MccSchema.TABLE_USER_REQUESTS);
                Map<String, Object> row = new HashMap<>();
                row.put("email", form.getEmail());
                row.put("firstName", form.getFirstName());
                row.put("lastName", form.getLastName());
                row.put("title", form.getTitle());
                row.put("institution", form.getInstitution());
                row.put("reason", form.getReason());
                row.put("container", MccManager.get().getMCCContainer(getContainer()).getId());

                Table.insert(UserManager.getGuestUser(), ti, row);

                Set<Address> emails = MccManager.get().getNotificationUserEmails(getContainer());
                if (emails != null && !emails.isEmpty())
                {
                    try
                    {
                        MailHelper.MultipartMessage mail = MailHelper.createMultipartMessage();
                        Container c = MccManager.get().getMCCContainer(getContainer());
                        if (c == null)
                        {
                            c = getContainer();
                            _log.warn("MCC container was not set, using: " + c.getPath());
                        }

                        DetailsURL url = DetailsURL.fromString("/query/executeQuery.view?schemaName=mcc&query.queryName=userRequests&query.viewName=Pending Requests", c);
                        mail.setEncodedHtmlContent("A user requested an account on MCC.  <a href=\"" + AppProps.getInstance().getBaseServerUrl() + url.getActionURL().toString()+ "\">Click here to view/approve this request</a>");
                        mail.setFrom(getReplyEmail(getContainer()));
                        mail.setSubject("MCC Account Request");
                        mail.addRecipients(Message.RecipientType.TO, emails.toArray(new Address[0]));

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
            Container mccContainer = MccManager.get().getMCCContainer(getContainer());
            if (mccContainer == null)
            {
                errors.reject(ERROR_MSG, "The MCC project has not been set on this server.  This is an administrator error.");
                return;
            }

            if (form.getRequestIds() == null || form.getRequestIds().length == 0)
            {
                errors.reject(ERROR_MSG, "No request IDs provided");
            }

            TableInfo ti = MccSchema.getInstance().getSchema().getTable(MccSchema.TABLE_USER_REQUESTS);
            for (int requestId : form.getRequestIds())
            {
                TableSelector ts = new TableSelector(ti, PageFlowUtil.set("userId"), new SimpleFilter(FieldKey.fromString("rowId"), requestId), null);
                if (!ts.exists())
                {
                    errors.reject(ERROR_MSG, "No request found for request ID: " + requestId);
                    break;
                }
            }
        }

        @Override
        public Object execute(ApproveUserRequestsForm form, BindException errors) throws Exception
        {
            ApiSimpleResponse response = new ApiSimpleResponse();
            List<SecurityManager.NewUserStatus> newUserStatusList = new ArrayList<>();
            List<User> existingUsersGivenAccess = new ArrayList<>();
            try (DbScope.Transaction transaction = CoreSchema.getInstance().getScope().ensureTransaction())
            {
                TableInfo ti = MccSchema.getInstance().getSchema().getTable(MccSchema.TABLE_USER_REQUESTS);
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
                }

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

            Container mccContainer = MccManager.get().getMCCContainer(getContainer());
            for (User u : existingUsersGivenAccess)
            {
                boolean isLDAP = AuthenticationManager.isLdapOrSsoEmail(new ValidEmail(u.getEmail()));

                MailHelper.MultipartMessage mail = MailHelper.createMultipartMessage();
                mail.setEncodedHtmlContent("Your account request has been approved for MCC!  " + "<a href=\"" + AppProps.getInstance().getBaseServerUrl() + mccContainer.getStartURL(getUser()) + "\">Click here to access the site.</a>" + (isLDAP ? "  Use your normal OHSU email/password to login." : ""));
                mail.setFrom(getReplyEmail(getContainer()));
                mail.setSubject("MCC Account Request");
                mail.addRecipients(Message.RecipientType.TO, u.getEmail());

                MailHelper.send(mail, getUser(), getContainer());
            }

            Group g1 = GroupManager.getGroup(mccContainer, MccManager.MCC_GROUP_NAME, GroupEnumType.SITE);
            if (g1 == null)
            {
                g1 = SecurityManager.createGroup(ContainerManager.getRoot(), MccManager.MCC_GROUP_NAME);
            }

            Group g2 = GroupManager.getGroup(mccContainer, MccManager.REQUEST_GROUP_NAME, GroupEnumType.SITE);
            if (g2 == null)
            {
                g2 = SecurityManager.createGroup(ContainerManager.getRoot(), MccManager.REQUEST_GROUP_NAME);
            }

            SecurityManager.addMembers(g1, allUsers);
            SecurityManager.addMembers(g2, allUsers);

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

    @RequiresNoPermission
    @IgnoresTermsOfUse
    @AllowedDuringUpgrade
    public class RequestHelpAction extends MutatingApiAction<RequestHelpForm>
    {
        @Override
        public void validateForm(RequestHelpForm form, Errors errors)
        {
            Container mccContainer = MccManager.get().getMCCContainer(getContainer());
            if (mccContainer == null)
            {
                errors.reject(ERROR_MSG, "The MCC project has not been set on this server.  This is an administrator error.");
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
            Set<Address> emails = MccManager.get().getNotificationUserEmails(getContainer());
            if (emails != null && !emails.isEmpty())
            {
                try
                {
                    MailHelper.MultipartMessage mail = MailHelper.createMultipartMessage();
                    mail.setEncodedHtmlContent("A support request was submitted from MCC by: " + form.getEmail() + "<br><br>Message:<br>" + form.getComment());
                    mail.setFrom(form.getEmail());
                    mail.setSubject("MCC Help Request");
                    mail.addRecipients(Message.RecipientType.TO, emails.toArray(new Address[0]));

                    MailHelper.send(mail, getUser(), getContainer());
                }
                catch (Exception e)
                {
                    ExceptionUtil.logExceptionToMothership(null, e);
                }
            }
            else
            {
                _log.error("A help request was received by MCC, but the admin emails have not been configured.  The request from: " + form.getEmail());
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
    public static class ConfigureMccAction extends ConfirmAction<Object>
    {
        @Override
        public ModelAndView getConfirmView(Object o, BindException errors) throws Exception
        {
            setTitle("Configure MCC");

            return new HtmlView(HtmlString.unsafe("This will ensure various settings required by MCC are configured on this server. Do you want to continue?"));
        }

        @Override
        public boolean handlePost(Object o, BindException errors) throws Exception
        {
            for (String gn : Arrays.asList(MccManager.REQUEST_GROUP_NAME, MccManager.ANIMAL_GROUP_NAME, MccManager.REQUEST_REVIEW_GROUP_NAME, MccManager.FINAL_REVIEW_GROUP_NAME, MccManager.ADMIN_GROUP_NAME))
            {
                Group g1 = GroupManager.getGroup(ContainerManager.getRoot(), gn, GroupEnumType.SITE);
                if (g1 == null)
                {
                    SecurityManager.createGroup(ContainerManager.getRoot(), gn);
                }
            }

            // Ensure groups have target roles:
            Container requestContainer = MccManager.get().getMCCRequestContainer(getContainer());
            if (requestContainer != null)
            {
                Group requestGroup = GroupManager.getGroup(ContainerManager.getRoot(), MccManager.REQUEST_GROUP_NAME, GroupEnumType.SITE);
                if (!requestContainer.getPolicy().getAssignedRoles(requestGroup).contains(RoleManager.getRole(MccRequesterRole.class)))
                {
                    MutableSecurityPolicy policy = new MutableSecurityPolicy(requestContainer.getPolicy());
                    policy.addRoleAssignment(requestGroup, RoleManager.getRole(MccRequesterRole.class));
                    SecurityPolicyManager.savePolicy(policy, getUser());
                }

                Group reviewGroup = GroupManager.getGroup(ContainerManager.getRoot(), MccManager.REQUEST_REVIEW_GROUP_NAME, GroupEnumType.SITE);
                if (!requestContainer.getPolicy().getAssignedRoles(reviewGroup).contains(RoleManager.getRole(MccRabReviewerRole.class)))
                {
                    MutableSecurityPolicy policy = new MutableSecurityPolicy(requestContainer.getPolicy());
                    policy.addRoleAssignment(reviewGroup, RoleManager.getRole(MccRabReviewerRole.class));
                    SecurityPolicyManager.savePolicy(policy, getUser());
                }

                Group finalGroup = GroupManager.getGroup(ContainerManager.getRoot(), MccManager.FINAL_REVIEW_GROUP_NAME, GroupEnumType.SITE);
                if (!requestContainer.getPolicy().getAssignedRoles(finalGroup).contains(RoleManager.getRole(MccFinalReviewerRole.class)))
                {
                    MutableSecurityPolicy policy = new MutableSecurityPolicy(requestContainer.getPolicy());
                    policy.addRoleAssignment(finalGroup, RoleManager.getRole(MccFinalReviewerRole.class));
                    SecurityPolicyManager.savePolicy(policy, getUser());
                }

                Group adminGroup = GroupManager.getGroup(ContainerManager.getRoot(), MccManager.ADMIN_GROUP_NAME, GroupEnumType.SITE);
                if (!requestContainer.getPolicy().getAssignedRoles(adminGroup).contains(RoleManager.getRole(MccDataAdminRole.class)))
                {
                    MutableSecurityPolicy policy = new MutableSecurityPolicy(requestContainer.getPolicy());
                    policy.addRoleAssignment(adminGroup, RoleManager.getRole(MccDataAdminRole.class));
                    SecurityPolicyManager.savePolicy(policy, getUser());
                }
            }

            Container dataContainer = MccManager.get().getMCCContainer(getContainer());
            if (dataContainer != null)
            {
                Group adminGroup = GroupManager.getGroup(ContainerManager.getRoot(), MccManager.ADMIN_GROUP_NAME, GroupEnumType.SITE);
                if (!dataContainer.getPolicy().getAssignedRoles(adminGroup).contains(RoleManager.getRole(MccDataAdminRole.class)))
                {
                    MutableSecurityPolicy policy = new MutableSecurityPolicy(dataContainer.getPolicy());
                    policy.addRoleAssignment(adminGroup, RoleManager.getRole(MccDataAdminRole.class));
                    SecurityPolicyManager.savePolicy(policy, getUser());
                }
            }

            return true;
        }

        @Override
        public void validateCommand(Object o, Errors errors)
        {
            Container mccContainer = MccManager.get().getMCCContainer(getContainer());
            if (mccContainer == null)
            {
                errors.reject(ERROR_MSG, "The MCC data container property has not been set");
            }

            Container requestContainer = MccManager.get().getMCCRequestContainer(getContainer());
            if (requestContainer == null)
            {
                errors.reject(ERROR_MSG, "The MCC request container property has not been set");
            }
        }

        @NotNull
        @Override
        public URLHelper getSuccessURL(Object o)
        {
            return getContainer().getStartURL(getUser());
        }
    }

    @RequiresPermission(AdminPermission.class)
    public class ResetZimsRuntimeAction extends ConfirmAction<Object>
    {
        @Override
        public ModelAndView getConfirmView(Object o, BindException errors) throws Exception
        {
            setTitle("Reset ZIMs Last Run Time");

            return new HtmlView(HtmlString.unsafe("This will reset the last run time for ZIMs import, causing any existing XML files to be re-imported. Do you want to continue?"));
        }

        @Override
        public boolean handlePost(Object o, BindException errors) throws Exception
        {
            ZimsImportTask.saveLastRun(getContainer(), null);

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

    @RequiresPermission(AdminPermission.class)
    public class ImportStudyAction extends ConfirmAction<Object>
    {
        @Override
        public ModelAndView getConfirmView(Object o, BindException errors) throws Exception
        {
            setTitle("Import MCC Study");

            return new HtmlView(HtmlString.unsafe("This will import the default MCC study in this folder and set the EHRStudyContainer property to point to this container. Do you want to continue?"));
        }

        @Override
        public boolean handlePost(Object o, BindException errors) throws Exception
        {
            Module ehr = ModuleLoader.getInstance().getModule("ehr");
            ModuleProperty mp = ehr.getModuleProperties().get("EHRStudyContainer");
            mp.saveValue(getUser(), getContainer(), getContainer().getPath());

            ModuleProperty mp2 = ehr.getModuleProperties().get("DefaultAnimalHistoryReport");
            mp2.saveValue(getUser(), getContainer(), "demographics");

            StudiesService.get().importFolderDefinition(getContainer(), getUser(), ModuleLoader.getInstance().getModule(MccModule.NAME), new Path("referenceStudy"));

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

    @RequiresPermission(MccRequestAdminPermission.class)
    public class NotifyReviewersAction extends MutatingApiAction<NotifyReviewersForm>
    {
        @Override
        public Object execute(NotifyReviewersForm form, BindException errors) throws Exception
        {
            if (form.getRowIds() == null || form.getRowIds().length == 0)
            {
                errors.reject(ERROR_MSG, "No rowIds provided");
                return null;
            }

            List<Integer> rowIds = Arrays.stream(form.getRowIds()).collect(Collectors.toList());
            List<Integer> userIds = new TableSelector(MccSchema.getInstance().getSchema().getTable(MccSchema.TABLE_REQUEST_REVIEWS), PageFlowUtil.set("reviewerId"), new SimpleFilter(FieldKey.fromString("rowid"), rowIds, CompareType.IN), null).getArrayList(Integer.class);
            if (userIds.size() != form.getRowIds().length)
            {
                errors.reject(ERROR_MSG, "Not all users in this request were found");
                return null;
            }

            Set<Address> emails = new HashSet<>();
            for (int userId : userIds)
            {
                User u = UserManager.getUser(userId);
                if (u == null)
                {
                    _log.error("Unknown user: " + userId);
                    errors.reject(ERROR_MSG, "Unknown user: " + userId);
                    return null;
                }

                ValidEmail validEmail = new ValidEmail(u.getEmail());
                emails.add(validEmail.getAddress());
            }

            try
            {
                MailHelper.MultipartMessage mail = MailHelper.createMultipartMessage();
                mail.setFrom("mcc-do-not-reply@ohsu.edu");
                mail.setSubject("MCC Animal Request Reviews");

                Container rc = MccManager.get().getMCCRequestContainer(getContainer());
                DetailsURL url = DetailsURL.fromString("/mcc/rabRequestReview.view", rc);
                mail.setEncodedHtmlContent("You have been assigned one or more MCC Animal Requests to review. <a href=\"" + AppProps.getInstance().getBaseServerUrl() + url.getActionURL() + "\">Please click here to view and complete these assignments</a>");
                mail.addRecipients(Message.RecipientType.BCC, emails.toArray(new Address[0]));
                mail.addRecipients(Message.RecipientType.TO, "mcc-do-not-reply@ohsu.edu");

                MailHelper.send(mail, getUser(), getContainer());
            }
            catch (Exception e)
            {
                _log.error("Unable to send MCC email", e);
            }

            return new ApiSimpleResponse("success", true);
        }
    }

    public static class NotifyReviewersForm
    {
        Integer[] rowIds;

        public Integer[] getRowIds()
        {
            return rowIds;
        }

        public void setRowIds(Integer[] rowIds)
        {
            this.rowIds = rowIds;
        }
    }

    @RequiresPermission(MccDataAdminPermission.class)
    public class RenameIdsAction extends MutatingApiAction<RenameIdsForm>
    {
        @Override
        public Object execute(RenameIdsForm form, BindException errors) throws Exception
        {
            if (form.getOriginalIds() == null || form.getOriginalIds().length == 0)
            {
                errors.reject(ERROR_MSG, "Must provide a list of IDs to change");
                return null;
            }

            if (form.getNewIds() == null || form.getOriginalIds().length != form.getNewIds().length)
            {
                errors.reject(ERROR_MSG, "Differing number of Original/New IDs were provided");
                return null;
            }

            Study s = StudyService.get().getStudy(getContainer());
            if (s == null)
            {
                errors.reject(ERROR_MSG, "There is no study in this container");
                return null;
            }

            final Map<String, String> oldToNew = new CaseInsensitiveHashMap<>();
            IntStream.range(0, form.getOriginalIds().length).forEach(i -> oldToNew.put(form.getOriginalIds()[i], form.getNewIds()[i]));

            final Set<String> idsUpdated = new CaseInsensitiveHashSet();
            final AtomicInteger totalRecordsUpdated = new AtomicInteger();
            final TableInfo mccAliases = QueryService.get().getUserSchema(getUser(), getContainer(), MccSchema.NAME).getTable(MccSchema.TABLE_ANIMAL_MAPPING);

            try (DbScope.Transaction transaction = DbScope.getLabKeyScope().ensureTransaction())
            {
                // NOTE: include this value so it will get added to the audit trail. This is a loose way to connect changes made in this transaction
                final String batchId = "MCC.Rename." + new GUID();
                Set<String> messages = new HashSet<>();
                for (Dataset ds : s.getDatasets())
                {
                    TableInfo ti = ds.getTableInfo(getUser());
                    TableSelector ts = new TableSelector(ti, PageFlowUtil.set("Id", "lsid"), new SimpleFilter(FieldKey.fromString("Id"), oldToNew.keySet(), CompareType.IN), null);
                    if (!ts.exists())
                    {
                        continue;
                    }

                    List<Map<String, Object>> toUpdate = new ArrayList<>();
                    List<Map<String, Object>> oldKeys = new ArrayList<>();
                    ts.forEachResults(rs -> {
                        if (ds.isDemographicData())
                        {
                            // test if a record exists with the new ID
                            if (new TableSelector(ti, new SimpleFilter(FieldKey.fromString("Id"), oldToNew.get(rs.getString(FieldKey.fromString("Id")))), null).exists())
                            {
                                messages.add("Existing record for ID: " + oldToNew.get(rs.getString(FieldKey.fromString("Id"))) + " for dataset: " + ds.getLabel() + " in container: " + getContainer().getPath() + ", skipping rename");
                                return;
                            }
                        }

                        toUpdate.add(Map.of("lsid", rs.getString(FieldKey.fromString("lsid")), "Id", oldToNew.get(rs.getString(FieldKey.fromString("Id"))), "_batchId_", batchId));
                        oldKeys.add(Map.of("lsid", rs.getString(FieldKey.fromString("lsid"))));
                        idsUpdated.add(rs.getString(FieldKey.fromString("Id")));
                        totalRecordsUpdated.getAndIncrement();
                    });

                    if (!toUpdate.isEmpty())
                    {
                        try
                        {
                            ti.getUpdateService().updateRows(getUser(), getContainer(), toUpdate, oldKeys, null, null);
                        }
                        catch (InvalidKeyException | BatchValidationException | QueryUpdateServiceException | SQLException e)
                        {
                            _log.error("Error updating MCC dataset rows", e);
                            errors.reject(ERROR_MSG, "Error updating MCC dataset rows: " + e.getMessage());
                            return null;
                        }
                    }
                }

                for (String oldId : oldToNew.keySet())
                {
                    // Find the MCC ID of the new ID:
                    String mccIdForOldId = new TableSelector(mccAliases, PageFlowUtil.set("externalAlias"), new SimpleFilter(FieldKey.fromString("subjectname"), oldId), null).getObject(String.class);
                    String mccIdForNewId = new TableSelector(mccAliases, PageFlowUtil.set("externalAlias"), new SimpleFilter(FieldKey.fromString("subjectname"), oldToNew.get(oldId)), null).getObject(String.class);

                    if (mccIdForOldId == null)
                    {
                        // This should not really happen...
                        _log.error("An MCC rename was performed where the original ID lacked an MCC alias: " + oldId);
                        messages.add("Missing MCC alias: " + oldId);
                    }

                    if (mccIdForNewId == null)
                    {
                        if (mccIdForOldId != null)
                        {
                            // Create record for the new ID pointing to the MCC ID of the original
                            List<Map<String, Object>> toInsert = Arrays.asList(Map.of(
                                    "subjectname", mccIdForOldId,
                                    "_batchId_", batchId
                            ));
                            BatchValidationException bve = new BatchValidationException();
                            mccAliases.getUpdateService().insertRows(getUser(), getContainer(), toInsert, bve, null, null);
                            if (bve.hasErrors())
                            {
                                throw bve;
                            }
                        }
                    }
                    else if (mccIdForOldId != null)
                    {
                        messages.add("Both IDs have existing MCC aliases, no changes were made: " + oldId + " / " + oldToNew.get(oldId));
                    }
                }

                transaction.commit();

                return new ApiSimpleResponse(Map.of(
                        "success", true,
                        "totalIdsUpdated", idsUpdated.size(),
                        "totalRecordsUpdated", totalRecordsUpdated.get(),
                        "messages", StringUtils.join(messages, "<br>")
                ));
            }
            catch (Exception e)
            {
                _log.error("Error renaming MCC IDs", e);

                return new ApiSimpleResponse(Map.of(
                        "success", false,
                        "error", e.getMessage()
                ));
            }


        }
    }

    public static class RenameIdsForm
    {
        private String[] _originalIds;
        private String[] _newIds;

        public String[] getOriginalIds()
        {
            return _originalIds;
        }

        public void setOriginalIds(String[] originalIds)
        {
            _originalIds = originalIds;
        }

        public String[] getNewIds()
        {
            return _newIds;
        }

        public void setNewIds(String[] newIds)
        {
            _newIds = newIds;
        }
    }
}
