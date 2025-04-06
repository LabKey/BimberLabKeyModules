<%@ page import="org.apache.commons.lang3.StringUtils" %>
<%@ page import="org.labkey.api.view.HttpView" %>
<%@ page import="org.labkey.api.view.JspView" %>
<%@ page import="org.labkey.api.view.template.ClientDependencies" %>
<%@ page import="org.labkey.hivrc.query.AnalysisModel" %>
<%@ page import="java.util.Arrays" %>
<%@ page extends="org.labkey.api.jsp.JspBase" %>
<%!
    @Override
    public void addClientDependencies(ClientDependencies dependencies)
    {
        dependencies.add("Ext4");
        dependencies.add("laboratory.context");
        dependencies.add("laboratory/panel/WorkbookHeaderPanel.js");
        dependencies.add("hivrc/panel/AnalysisHeaderPanel.js");
        dependencies.add("editInPlaceElement.css");
        dependencies.add("editInPlaceElement.js");
    }
%>
<%
    JspView<AnalysisModel> me = HttpView.currentView();
    AnalysisModel model = me.getModelBean();
    Integer workbookId = model.getWorkbookId();
    String wpId = "wp_" + me.getWebPartRowId();
%>

<style type="text/css">
    .wb-name
    {
        color: #999999;
    }
</style>

<div id=<%=q(wpId)%>></div>

<script type="text/javascript" nonce="<%=getScriptNonce()%>">
    Ext4.onReady(function(){
        var webpartId = <%=q(wpId)%>;
        var workbookId = <%=h(workbookId)%>;
        var title = <%=q(getContainer().getTitle())%> || '';

        //set page title
        var titleId = Ext4.id();
        var markup = '<span class="wb-name">' + workbookId + ':&nbsp;</span><span class="labkey-edit-in-place" id="' + titleId + '">' + Ext4.util.Format.htmlEncode(title) + '</span>';
        var elem = document.querySelector('.lk-body-title h3');
        if (elem){
            elem.innerHTML = markup;
            //elem.style.display = 'table';
        }

        Ext4.create('HIVRC.panel.AnalysisHeaderPanel', {
            description: <%=q(getContainer().getDescription())%>,
            materials: <%=q(model.getMaterials())%>,
            methods: <%=q(model.getMethods())%>,
            results: <%=q(model.getResults())%>,
            tags: <%=unsafe(model.getTags() == null || model.getTags().length == 0 ? "null" : "['" + unsafe(StringUtils.join(Arrays.asList(model.getTags()), "','")) + "']")%>
        }).render(webpartId);

        if (LABKEY.Security.currentUser.canInsert) {
            Ext4.create('LABKEY.ext.EditInPlaceElement', {
                applyTo: titleId,
                widthBuffer: 0,
                updateConfig: {
                    url: LABKEY.ActionURL.buildURL("core", "updateTitle"),
                    jsonDataPropName: 'title'
                },
                listeners: {
                    beforecomplete: function (newText) {
                        return (newText.length > 0);
                    }
                }
            });
        }
    });
</script>
