package com.github.drafael.chat4j;

import com.github.drafael.chat4j.settings.AppFontSizeAdjustCoordinator;
import com.github.drafael.chat4j.settings.AppFontSizeStepResolver;
import com.github.drafael.chat4j.settings.RenderModeChangeCoordinator;
import com.github.drafael.chat4j.settings.RenderModeChangeDispatchCoordinator;
import com.github.drafael.chat4j.settings.RenderModeChangePlanner;
import com.github.drafael.chat4j.settings.RenderModeChangeUiApplyCoordinator;
import com.github.drafael.chat4j.settings.RenderModeSelectionResolver;
import com.github.drafael.chat4j.settings.RenderModeSettingsCoordinator;
import com.github.drafael.chat4j.settings.FontMenuApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionDispatchCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionFlowCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionRefreshCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionSynchronizer;
import com.github.drafael.chat4j.settings.FontPreviewApplier;
import com.github.drafael.chat4j.settings.FontSelectionNormalizer;
import com.github.drafael.chat4j.settings.FontSettingsPersister;
import com.github.drafael.chat4j.settings.FontSettingsResolver;
import com.github.drafael.chat4j.settings.GeneralSettingsApplyCoordinator;
import com.github.drafael.chat4j.settings.GeneralSettingsApplyDispatchCoordinator;
import com.github.drafael.chat4j.settings.GeneralSettingsResolver;
import com.github.drafael.chat4j.settings.GeneralSettingsUiApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionDispatchCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionFlowCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionRefreshCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionSynchronizer;
import com.github.drafael.chat4j.settings.ThemeSettingsResolver;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;

public class MainFrameSettingsWiringFactory {

    public SettingsWiring create(
            @NonNull SettingsRepo settingsRepo,
            @NonNull RenderModeSelectionResolver renderModeSelectionResolver,
            @NonNull RenderModeChangeUiApplyCoordinator renderModeChangeUiApplyCoordinator,
            @NonNull GeneralSettingsUiApplyCoordinator generalSettingsUiApplyCoordinator,
            @NonNull FontSelectionNormalizer fontSelectionNormalizer,
            @NonNull FontPreviewApplier fontPreviewApplier,
            @NonNull FontMenuSelectionSynchronizer fontMenuSelectionSynchronizer,
            @NonNull FontMenuSelectionApplyCoordinator fontMenuSelectionApplyCoordinator,
            @NonNull ThemeMenuSelectionSynchronizer themeMenuSelectionSynchronizer,
            @NonNull ThemeMenuSelectionApplyCoordinator themeMenuSelectionApplyCoordinator
    ) {
        var renderModeSettingsCoordinator = new RenderModeSettingsCoordinator(settingsRepo);
        var renderModeChangeCoordinator = new RenderModeChangeCoordinator(renderModeSettingsCoordinator);
        var renderModeChangeDispatchCoordinator = new RenderModeChangeDispatchCoordinator(
                renderModeChangeCoordinator,
                renderModeChangeUiApplyCoordinator
        );
        var generalSettingsResolver = new GeneralSettingsResolver(settingsRepo, renderModeSettingsCoordinator);
        var generalSettingsApplyCoordinator = new GeneralSettingsApplyCoordinator(
                generalSettingsResolver,
                renderModeSelectionResolver
        );
        var generalSettingsApplyDispatchCoordinator = new GeneralSettingsApplyDispatchCoordinator(
                generalSettingsApplyCoordinator,
                generalSettingsUiApplyCoordinator
        );
        var fontSettingsResolver = new FontSettingsResolver(settingsRepo);
        var fontSettingsPersister = new FontSettingsPersister(settingsRepo);
        var fontMenuApplyCoordinator = new FontMenuApplyCoordinator(
                fontSelectionNormalizer,
                fontPreviewApplier,
                fontSettingsPersister
        );
        var fontMenuSelectionRefreshCoordinator = new FontMenuSelectionRefreshCoordinator(
                fontSettingsResolver,
                fontMenuSelectionSynchronizer
        );
        var fontMenuSelectionDispatchCoordinator =
                new FontMenuSelectionDispatchCoordinator(fontMenuSelectionRefreshCoordinator);
        var fontMenuSelectionFlowCoordinator = new FontMenuSelectionFlowCoordinator(
                fontMenuSelectionDispatchCoordinator,
                fontMenuSelectionApplyCoordinator
        );
        var appFontSizeStepResolver = new AppFontSizeStepResolver();
        var appFontSizeAdjustCoordinator = new AppFontSizeAdjustCoordinator(appFontSizeStepResolver);
        var themeSettingsResolver = new ThemeSettingsResolver(settingsRepo);
        var themeMenuApplyCoordinator = new ThemeMenuApplyCoordinator(themeSettingsResolver, settingsRepo);
        var themeMenuSelectionRefreshCoordinator = new ThemeMenuSelectionRefreshCoordinator(
                themeSettingsResolver,
                themeMenuSelectionSynchronizer
        );
        var themeMenuSelectionDispatchCoordinator =
                new ThemeMenuSelectionDispatchCoordinator(themeMenuSelectionRefreshCoordinator);
        var themeMenuSelectionFlowCoordinator = new ThemeMenuSelectionFlowCoordinator(
                themeMenuSelectionDispatchCoordinator,
                themeMenuSelectionApplyCoordinator
        );

        return new SettingsWiring(
                renderModeSettingsCoordinator,
                renderModeChangeCoordinator,
                renderModeChangeDispatchCoordinator,
                generalSettingsResolver,
                generalSettingsApplyCoordinator,
                generalSettingsApplyDispatchCoordinator,
                fontSettingsResolver,
                fontSettingsPersister,
                fontMenuApplyCoordinator,
                fontMenuSelectionRefreshCoordinator,
                fontMenuSelectionDispatchCoordinator,
                fontMenuSelectionFlowCoordinator,
                appFontSizeStepResolver,
                appFontSizeAdjustCoordinator,
                themeSettingsResolver,
                themeMenuApplyCoordinator,
                themeMenuSelectionRefreshCoordinator,
                themeMenuSelectionDispatchCoordinator,
                themeMenuSelectionFlowCoordinator
        );
    }

    public record SettingsWiring(
            RenderModeSettingsCoordinator renderModeSettingsCoordinator,
            RenderModeChangeCoordinator renderModeChangeCoordinator,
            RenderModeChangeDispatchCoordinator renderModeChangeDispatchCoordinator,
            GeneralSettingsResolver generalSettingsResolver,
            GeneralSettingsApplyCoordinator generalSettingsApplyCoordinator,
            GeneralSettingsApplyDispatchCoordinator generalSettingsApplyDispatchCoordinator,
            FontSettingsResolver fontSettingsResolver,
            FontSettingsPersister fontSettingsPersister,
            FontMenuApplyCoordinator fontMenuApplyCoordinator,
            FontMenuSelectionRefreshCoordinator fontMenuSelectionRefreshCoordinator,
            FontMenuSelectionDispatchCoordinator fontMenuSelectionDispatchCoordinator,
            FontMenuSelectionFlowCoordinator fontMenuSelectionFlowCoordinator,
            AppFontSizeStepResolver appFontSizeStepResolver,
            AppFontSizeAdjustCoordinator appFontSizeAdjustCoordinator,
            ThemeSettingsResolver themeSettingsResolver,
            ThemeMenuApplyCoordinator themeMenuApplyCoordinator,
            ThemeMenuSelectionRefreshCoordinator themeMenuSelectionRefreshCoordinator,
            ThemeMenuSelectionDispatchCoordinator themeMenuSelectionDispatchCoordinator,
            ThemeMenuSelectionFlowCoordinator themeMenuSelectionFlowCoordinator
    ) {
    }
}
