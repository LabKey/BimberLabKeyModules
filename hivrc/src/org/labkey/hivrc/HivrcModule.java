package org.labkey.hivrc;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.labkey.api.data.Container;
import org.labkey.api.ldk.ExtendedSimpleModule;
import org.labkey.api.util.HtmlString;
import org.labkey.api.view.BaseWebPartFactory;
import org.labkey.api.view.HtmlView;
import org.labkey.api.view.JspView;
import org.labkey.api.view.Portal;
import org.labkey.api.view.ViewContext;
import org.labkey.api.view.WebPartFactory;
import org.labkey.api.view.WebPartView;
import org.labkey.hivrc.query.AnalysisModel;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

public class HivrcModule extends ExtendedSimpleModule
{
    public static final String NAME = "HIVRC";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public @Nullable Double getSchemaVersion()
    {
        return 23.001;
    }

    @Override
    public boolean hasScripts()
    {
        return true;
    }

    @Override
    @NotNull
    protected Collection<WebPartFactory> createWebPartFactories()
    {
        return Arrays.asList(
                new BaseWebPartFactory("HIVRC Analysis Header")
                {
                    @Override
                    public WebPartView<?> getWebPartView(@NotNull ViewContext portalCtx, @NotNull Portal.WebPart webPart)
                    {
                        if (!portalCtx.getContainer().isWorkbook())
                        {
                            return new HtmlView(HtmlString.of("This container is not a workbook"));
                        }

                        AnalysisModel model = HivrcManager.get().getAnalysisModel(portalCtx.getContainer(), true);
                        if (model == null)
                        {
                            model = AnalysisModel.createNew(portalCtx.getContainer());
                        }

                        JspView<AnalysisModel> view = new JspView<>("/org/labkey/hivrc/view/analysisHeader.jsp", model);
                        view.setTitle("HIVRC Analysis Summary");
                        view.setFrame(WebPartView.FrameType.NONE);

                        return view;
                    }

                    @Override
                    public boolean isAvailable(Container c, String scope, String location)
                    {
                        return false;
                    }
                }
        );
    }

    @Override
    protected void init()
    {
        addController(HivrcController.NAME, HivrcController.class);
    }

    @Override
    @NotNull
    public Collection<String> getSummary(Container c)
    {
        return Collections.emptyList();
    }

    @Override
    @NotNull
    public Set<String> getSchemaNames()
    {
        return Collections.singleton(HivrcSchema.NAME);
    }
}