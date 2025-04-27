package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.MainFrame;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.settings.AppearancePanel;
import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.DatabaseBootstrap;
import com.github.drafael.chat4j.storage.H2DataSourceFactory;
import com.github.drafael.chat4j.storage.ModelCache;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import com.github.drafael.chat4j.storage.SettingsRepo;
import com.github.drafael.chat4j.storage.StoragePaths;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTGitHubIJTheme;
import com.formdev.flatlaf.util.SystemInfo;

import javax.sql.DataSource;
import javax.swing.*;

/**
 * Coordinates application startup from platform/bootstrap setup through first window display.
 */
public final class ApplicationBootstrap {

    private final EnvironmentBootstrapper environmentBootstrapper;

    public ApplicationBootstrap() {
        this(new EnvironmentBootstrapper());
    }

    ApplicationBootstrap(EnvironmentBootstrapper environmentBootstrapper) {
        this.environmentBootstrapper = environmentBootstrapper;
    }

    /**
     * Executes startup in a fixed order so additional startup concerns can be inserted safely.
     */
    public void start() {
        configurePlatformIntegration();
        configureEarlyLookAndFeel();

        EnvironmentInitResult environment = environmentBootstrapper.initialize();
        AppServices services = initializeStorage();

        applySavedAppearance(services.settingsRepo());
        showMainWindow(services);
        showEnvironmentWarningIfNeeded(environment);
    }

    private void configurePlatformIntegration() {
        if (SystemInfo.isMacOS) {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", "Chat4J");
            System.setProperty("apple.awt.application.appearance", "system");
            // Title bar properties are set on rootPane in MainFrame
        }
    }

    private void configureEarlyLookAndFeel() {
        System.setProperty("flatlaf.useNativeLibrary", "true");
        AppearancePanel.installAccentColorGetter();
        FlatMTGitHubIJTheme.setup();
    }

    private AppServices initializeStorage() {
        StoragePaths storagePaths = StoragePaths.defaultPaths();
        DataSource dataSource = H2DataSourceFactory.create(storagePaths);
        DatabaseBootstrap databaseBootstrap = new DatabaseBootstrap(storagePaths, dataSource);
        ConversationRepo conversationRepo = new ConversationRepo(dataSource);
        SettingsRepo settingsRepo = new SettingsRepo(dataSource);
        ProviderModelCacheService providerModelCacheService =
                new ProviderModelCacheService(new ModelCache(storagePaths));
        ModelFavoritesService modelFavoritesService = new ModelFavoritesService(settingsRepo);

        try {
            databaseBootstrap.init();
        } catch (Exception e) {
            showStartupErrorAndExit("Failed to initialize database: " + e.getMessage());
        }

        providerModelCacheService.primeFromDisk(
                ProviderRegistry.allProviders().stream()
                        .map(ProviderRegistry.ProviderDef::name)
                        .toList());
        modelFavoritesService.primeFromSettings();

        return new AppServices(conversationRepo, settingsRepo, providerModelCacheService, modelFavoritesService);
    }

    private void applySavedAppearance(SettingsRepo settingsRepo) {
        try {
            AppearancePanel.restoreAccentColor(settingsRepo);

            String themeName = settingsRepo.get("theme", "GitHub");
            String className = AppearancePanel.classNameForTheme(themeName);
            if (className != null) {
                UIManager.setLookAndFeel(className);
            }

            AppearancePanel.applySavedFonts(settingsRepo);
        } catch (Exception ignored) {
            // Keep default theme if settings cannot be read
        }
    }

    private void showMainWindow(AppServices services) {
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame(
                services.conversationRepo(),
                services.settingsRepo(),
                services.providerModelCacheService(),
                services.modelFavoritesService());
            frame.setVisible(true);
        });
    }

    private void showStartupErrorAndExit(String message) {
        JOptionPane.showMessageDialog(
            null,
            message,
            "Startup Error",
            JOptionPane.ERROR_MESSAGE
        );
        System.exit(1);
    }

    private void showEnvironmentWarningIfNeeded(EnvironmentInitResult environment) {
        if (!environment.shouldWarnUser()) {
            return;
        }

        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
            null,
            """
                Chat4J could not load environment variables from your shell profile.

                API keys (ANTHROPIC_API_KEY, OPENAI_API_KEY, etc.) may not be available,
                and providers requiring them might be disabled.

                To fix this, either:
                \u2022 Run Chat4J from the terminal: java --enable-preview -jar chat4j.jar
                \u2022 Set keys via: launchctl setenv ANTHROPIC_API_KEY sk-...
                \u2022 Use a local provider like LM Studio or Ollama (no API key required)
                """,
            "Environment Warning",
            JOptionPane.WARNING_MESSAGE
        ));
    }
}
