package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.provider.support.ProviderMenuIconRenderer;
import com.github.drafael.chat4j.storage.SettingsRepo;
import com.github.drafael.chat4j.util.WindowUiRefreshSupport;
import org.apache.commons.lang3.Validate;

import javax.swing.UIManager;

public class ThemeMenuApplyCoordinator {

    private final ThemeSettingsResolver themeSettingsResolver;
    private final SettingsRepo settingsRepo;
    private final LookAndFeelSwitcher lookAndFeelSwitcher;
    private final IconCacheInvalidator iconCacheInvalidator;
    private final SavedFontsApplier savedFontsApplier;
    private final WindowRefresher windowRefresher;

    public ThemeMenuApplyCoordinator(ThemeSettingsResolver themeSettingsResolver, SettingsRepo settingsRepo) {
        this(
                themeSettingsResolver,
                settingsRepo,
                UIManager::setLookAndFeel,
                ProviderMenuIconRenderer::clearCache,
                AppearancePanel::applySavedFonts,
                WindowUiRefreshSupport::refreshAllWindows
        );
    }

    ThemeMenuApplyCoordinator(
            ThemeSettingsResolver themeSettingsResolver,
            SettingsRepo settingsRepo,
            LookAndFeelSwitcher lookAndFeelSwitcher,
            IconCacheInvalidator iconCacheInvalidator,
            SavedFontsApplier savedFontsApplier,
            WindowRefresher windowRefresher
    ) {
        this.themeSettingsResolver = Validate.notNull(themeSettingsResolver, "themeSettingsResolver must not be null");
        this.settingsRepo = Validate.notNull(settingsRepo, "settingsRepo must not be null");
        this.lookAndFeelSwitcher = Validate.notNull(lookAndFeelSwitcher, "lookAndFeelSwitcher must not be null");
        this.iconCacheInvalidator = Validate.notNull(iconCacheInvalidator, "iconCacheInvalidator must not be null");
        this.savedFontsApplier = Validate.notNull(savedFontsApplier, "savedFontsApplier must not be null");
        this.windowRefresher = Validate.notNull(windowRefresher, "windowRefresher must not be null");
    }

    public ApplyResult apply(
            String themeName,
            String className,
            Runnable onModelsMenuDirty,
            Runnable onThemeMenuSelectionSync,
            Runnable onFontMenuSelectionSync
    ) {
        Validate.notBlank(themeName, "themeName must not be blank");
        Validate.notBlank(className, "className must not be blank");
        Validate.notNull(onModelsMenuDirty, "onModelsMenuDirty must not be null");
        Validate.notNull(onThemeMenuSelectionSync, "onThemeMenuSelectionSync must not be null");
        Validate.notNull(onFontMenuSelectionSync, "onFontMenuSelectionSync must not be null");

        try {
            lookAndFeelSwitcher.setLookAndFeel(className);
            iconCacheInvalidator.clearCache();
            onModelsMenuDirty.run();
            savedFontsApplier.apply(settingsRepo);
            windowRefresher.refreshAllWindows();
            themeSettingsResolver.persistSelectedTheme(themeName);
            onThemeMenuSelectionSync.run();
            onFontMenuSelectionSync.run();
            return ApplyResult.successResult();
        } catch (Exception e) {
            return ApplyResult.failureResult(e.getMessage());
        }
    }

    public record ApplyResult(boolean success, String errorMessage) {

        static ApplyResult successResult() {
            return new ApplyResult(true, null);
        }

        static ApplyResult failureResult(String errorMessage) {
            return new ApplyResult(false, errorMessage);
        }
    }

    @FunctionalInterface
    interface LookAndFeelSwitcher {
        void setLookAndFeel(String className) throws Exception;
    }

    @FunctionalInterface
    interface IconCacheInvalidator {
        void clearCache();
    }

    @FunctionalInterface
    interface SavedFontsApplier {
        void apply(SettingsRepo settingsRepo);
    }

    @FunctionalInterface
    interface WindowRefresher {
        void refreshAllWindows();
    }
}
