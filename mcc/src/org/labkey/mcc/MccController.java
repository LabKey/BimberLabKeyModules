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
import org.labkey.api.action.ApiSimpleResponse;
import org.labkey.api.action.MutatingApiAction;
import org.labkey.api.action.SpringActionController;
import org.labkey.api.data.Container;
import org.labkey.api.data.CoreSchema;
import org.labkey.api.data.DbScope;
import org.labkey.api.data.SimpleFilter;
import org.labkey.api.data.Table;
import org.labkey.api.data.TableInfo;
import org.labkey.api.data.TableSelector;
import org.labkey.api.module.AllowedDuringUpgrade;
import org.labkey.api.query.DetailsURL;
import org.labkey.api.query.FieldKey;
import org.labkey.api.security.AuthenticationManager;
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
import org.labkey.api.settings.AppProps;
import org.labkey.api.settings.LookAndFeelProperties;
import org.labkey.api.util.ConfigurationException;
import org.labkey.api.util.ExceptionUtil;
import org.labkey.api.util.MailHelper;
import org.labkey.api.util.PageFlowUtil;
import org.springframework.validation.BindException;
import org.springframework.validation.Errors;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            Container mccContainer = MccManager.get().getMCCContainer();
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
                row.put("container", MccManager.get().getMCCContainer().getId());

                Table.insert(UserManager.getGuestUser(), ti, row);

                Set<User> users = MccManager.get().getNotificationUsers();
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
                        Container c = MccManager.get().getMCCContainer();
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

    public static class RequestUserForm extends Object
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
            Container mccContainer = MccManager.get().getMCCContainer();
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
            MutableSecurityPolicy policy = new MutableSecurityPolicy(MccManager.get().getMCCContainer().getPolicy());
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

                    if (!policy.hasPermission(u, ReadPermission.class))
                    {
                        policy.addRoleAssignment(u, ReaderRole.class);
                    }
                    else
                    {
                        _log.info("user already has read permission on MCC container: " + u.getDisplayName(getUser()));
                    }
                }

                SecurityPolicyManager.savePolicy(policy);

                transaction.commit();
            }

            //send emails:
            for (SecurityManager.NewUserStatus st : newUserStatusList)
            {
                SecurityManager.sendRegistrationEmail(getViewContext(), st.getEmail(), null, st, null);
            }

            for (User u : existingUsersGivenAccess)
            {
                Container mccContainer = MccManager.get().getMCCContainer();
                boolean isLDAP = AuthenticationManager.isLdapEmail(new ValidEmail(u.getEmail()));

                MailHelper.MultipartMessage mail = MailHelper.createMultipartMessage();
                mail.setEncodedHtmlContent("Your account request has been approved for MCC!  " + "<a href=\"" + AppProps.getInstance().getBaseServerUrl() + mccContainer.getStartURL(getUser()).toString() + "\">Click here to access the site.</a>" + (isLDAP ? "  Use your normal OHSU email/password to login." : ""));
                mail.setFrom(getReplyEmail(getContainer()));
                mail.setSubject("MCC Account Request");
                mail.addRecipients(Message.RecipientType.TO, u.getEmail());

                MailHelper.send(mail, getUser(), getContainer());
            }

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
            Container mccContainer = MccManager.get().getMCCContainer();
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
            Set<User> users = MccManager.get().getNotificationUsers();
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
}
