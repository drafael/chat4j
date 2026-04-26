package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.MainFrame;
import com.github.drafael.chat4j.logging.LoggingBootstrap;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.settings.AppearancePanel;
import com.github.drafael.chat4j.settings.ThemeSettingsResolver;
import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.DatabaseBootstrap;
import com.github.drafael.chat4j.storage.H2DataSourceFactory;
import com.github.drafael.chat4j.storage.ModelCache;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import com.github.drafael.chat4j.storage.SettingsDbToPropertiesMigrationCoordinator;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import com.github.drafael.chat4j.storage.StoragePaths;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTGitHubIJTheme;
import com.formdev.flatlaf.util.SystemInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

import javax.sql.DataSource;
import javax.swing.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Coordinates application startup from platform/bootstrap setup through first window display.
 */
@Slf4j
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
        log.info("Starting Chat4J bootstrap");

        runStage("platform_config", this::configurePlatformIntegration);
        runStage("early_look_and_feel", this::configureEarlyLookAndFeel);

        // Run environment loading in parallel with storage initialization.
        // Environment loading spawns a login shell which is the slowest step.
        long environmentStageStartedAt = beginStage("environment_init_async");
        CompletableFuture<EnvironmentInitResult> environmentFuture =
                CompletableFuture.supplyAsync(environmentBootstrapper::initialize);

        AppServices services = runStage("storage_init", this::initializeStorage);

        // Environment must be resolved before providers are queried in the main window.
        EnvironmentInitResult environment = awaitEnvironment(environmentFuture, environmentStageStartedAt);
        runStage("logging_reconfigure", () -> LoggingBootstrap.initialize(environment.shellEnv()));

        log.info("Environment bootstrap complete: shellEnvEntries={} warningRequired={}",
                environment.shellEnv().size(), environment.shouldWarnUser());

        runStage("appearance_apply", () -> applySavedAppearance(services.settingsRepo()));
        runStage("main_window_show", () -> showMainWindow(services));
        runStage("environment_warning", () -> showEnvironmentWarningIfNeeded(environment));
        log.info("Chat4J bootstrap finished");
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
        SettingsRepo settingsRepo = new SettingsRepo(storagePaths);
        SettingsDbToPropertiesMigrationCoordinator settingsMigrationCoordinator =
                new SettingsDbToPropertiesMigrationCoordinator(dataSource, settingsRepo);
        ProviderModelCacheService providerModelCacheService =
                new ProviderModelCacheService(new ModelCache(storagePaths));
        ModelFavoritesService modelFavoritesService = new ModelFavoritesService(settingsRepo);

        try {
            settingsMigrationCoordinator.migrateIfNeeded();
            databaseBootstrap.init();
        } catch (Exception e) {
            String message = "Failed to initialize database: %s".formatted(ExceptionUtils.getMessage(e));
            log.error(message);
            showStartupErrorAndExit(message);
        }

        providerModelCacheService.primeFromDisk(
                ProviderRegistry.allProviders().stream()
                        .map(ProviderRegistry.ProviderDef::name)
                        .toList());
        modelFavoritesService.primeFromSettings();
        log.info("Storage initialized and model cache primed");

        return new AppServices(conversationRepo, settingsRepo, providerModelCacheService, modelFavoritesService);
    }

    private void applySavedAppearance(SettingsRepo settingsRepo) {
        try {
            AppearancePanel.restoreAccentColor(settingsRepo);

            String themeName = settingsRepo.get(SettingsKeys.THEME_NAME, ThemeSettingsResolver.DEFAULT_THEME);
            String className = AppearancePanel.classNameForTheme(themeName);
            if (className != null) {
                UIManager.setLookAndFeel(className);
            }

            AppearancePanel.applySavedFonts(settingsRepo);
        } catch (Exception e) {
            log.warn("Failed to apply saved appearance settings: {}", ExceptionUtils.getMessage(e));
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

        log.warn("Environment bootstrap could not resolve provider API keys from shell profile");

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
                \u2022 Run diagnostics: bash "/Applications/Chat4J.app/Contents/app/tools/chat4j-doctor.sh" --app "/Applications/Chat4J.app"
                """,
            "Environment Warning",
            JOptionPane.WARNING_MESSAGE
        ));
    }

    private EnvironmentInitResult awaitEnvironment(
            CompletableFuture<EnvironmentInitResult> environmentFuture,
            long stageStartedAtNanos
    ) {
        try {
            EnvironmentInitResult environment = environmentFuture.join();
            completeStage("environment_init_async", stageStartedAtNanos);
            return environment;
        } catch (Exception e) {
            failStage("environment_init_async", stageStartedAtNanos, e);
            throw e;
        }
    }

    private void runStage(String stageName, Runnable action) {
        runStage(stageName, () -> {
            action.run();
            return null;
        });
    }

    private <T> T runStage(String stageName, Supplier<T> action) {
        long stageStartedAtNanos = beginStage(stageName);
        try {
            T result = action.get();
            completeStage(stageName, stageStartedAtNanos);
            return result;
        } catch (Exception e) {
            failStage(stageName, stageStartedAtNanos, e);
            throw e;
        }
    }

    private long beginStage(String stageName) {
        log.info("Startup stage started: {}", stageName);
        return System.nanoTime();
    }

    private void completeStage(String stageName, long stageStartedAtNanos) {
        log.info("Startup stage completed: {} ({}ms)", stageName, elapsedMillis(stageStartedAtNanos));
    }

    private void failStage(String stageName, long stageStartedAtNanos, Exception e) {
        log.warn(
                "Startup stage failed: {} ({}ms): {}",
                stageName,
                elapsedMillis(stageStartedAtNanos),
                ExceptionUtils.getMessage(e)
        );
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
