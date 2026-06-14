package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.support.ProviderMenuIconRenderer;
import com.github.drafael.chat4j.util.WindowUiRefreshSupport;
import javax.swing.UIManager;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

public class ThemeMenuApplyCoordinator {

    private final ThemeSettingsResolver themeSettingsResolver;
    private final SettingsRepository settingsRepo;
    private final LookAndFeelSwitcher lookAndFeelSwitcher;
    private final IconCacheInvalidator iconCacheInvalidator;
    private final SavedFontsApplier savedFontsApplier;
    private final WindowRefresher windowRefresher;

    public ThemeMenuApplyCoordinator(ThemeSettingsResolver themeSettingsResolver, SettingsRepository settingsRepo) {
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
            @NonNull ThemeSettingsResolver themeSettingsResolver,
            @NonNull SettingsRepository settingsRepo,
            @NonNull LookAndFeelSwitcher lookAndFeelSwitcher,
            @NonNull IconCacheInvalidator iconCacheInvalidator,
            @NonNull SavedFontsApplier savedFontsApplier,
            @NonNull WindowRefresher windowRefresher
    ) {
        this.themeSettingsResolver = themeSettingsResolver;
        this.settingsRepo = settingsRepo;
        this.lookAndFeelSwitcher = lookAndFeelSwitcher;
        this.iconCacheInvalidator = iconCacheInvalidator;
        this.savedFontsApplier = savedFontsApplier;
        this.windowRefresher = windowRefresher;
    }

    public ApplyResult apply(
            String themeName,
            String className,
            @NonNull Runnable onModelsMenuDirty,
            @NonNull Runnable onThemeMenuSelectionSync,
            @NonNull Runnable onFontMenuSelectionSync
    ) {
        Validate.notBlank(themeName, "themeName must not be blank");
        Validate.notBlank(className, "className must not be blank");

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
        void apply(SettingsRepository settingsRepo);
    }

    @FunctionalInterface
    interface WindowRefresher {
        void refreshAllWindows();
    }
}
