package com.github.drafael.chat4j.bootstrap;

import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMTMaterialLighterIJTheme;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.MainFrame;
import com.github.drafael.chat4j.logging.LoggingBootstrap;
import com.github.drafael.chat4j.mcp.McpConfigurationRepository;
import com.github.drafael.chat4j.mcp.McpManager;
import com.github.drafael.chat4j.persistence.CacheRootHandle;
import com.github.drafael.chat4j.persistence.CacheStorageInitializer;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.catalog.CatalogSnapshotStore;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.persistence.db.DatabaseBootstrap;
import com.github.drafael.chat4j.persistence.db.PersistenceDataSourceFactory;
import com.github.drafael.chat4j.persistence.db.SqlDialect;
import com.github.drafael.chat4j.persistence.db.SqlDialects;
import com.github.drafael.chat4j.persistence.db.StorageBackend;
import com.github.drafael.chat4j.persistence.migration.PersistenceBackendMigrationService;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCache;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import com.github.drafael.chat4j.provider.support.CredentialMutationService;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.McpSecretVault;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.settings.AppearancePanel;
import com.github.drafael.chat4j.settings.ThemeSettings;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Coordinates application startup from platform/bootstrap setup through first window display.
 */
@Slf4j
public final class ApplicationBootstrap {

    private final EnvironmentBootstrapper environmentBootstrapper;
    private final JcefStartupInitializer jcefStartupInitializer;

    public ApplicationBootstrap() {
        this(new EnvironmentBootstrapper(), new JcefStartupInitializer());
    }

    ApplicationBootstrap(EnvironmentBootstrapper environmentBootstrapper) {
        this(environmentBootstrapper, new JcefStartupInitializer());
    }

    ApplicationBootstrap(EnvironmentBootstrapper environmentBootstrapper, JcefStartupInitializer jcefStartupInitializer) {
        this.environmentBootstrapper = environmentBootstrapper;
        this.jcefStartupInitializer = jcefStartupInitializer;
    }

    public void start() {
        log.info("Starting Chat4J bootstrap");

        runStage("platform_config", this::configurePlatformIntegration);
        runStage("early_look_and_feel", this::configureEarlyLookAndFeel);

        long environmentStageStartedAt = beginStage("environment_init_async");
        var environmentTask = new FutureTask<>(environmentBootstrapper::initialize);
        Thread environmentThread = Thread.ofVirtual().name("chat4j-environment-bootstrap").start(environmentTask);

        StorageServices storage;
        try {
            storage = runStage("storage_init", this::initializeCredentialIndependentStorage);
        } catch (RuntimeException | Error e) {
            cancelEnvironmentTask(environmentTask, environmentThread);
            throw e;
        }

        EnvironmentInitResult environment = awaitEnvironment(
                environmentTask,
                environmentThread,
                environmentStageStartedAt
        );
        runStage("logging_reconfigure", () -> LoggingBootstrap.initialize(environment.shellEnv()));

        AppServices services = runStage(
                "service_assembly",
                () -> assembleServices(storage, environment.shellEnv(), System.getenv())
        );
        var sharedServicesTransferred = new AtomicBoolean();
        try {
            boolean shouldWarnUser = EnvironmentBootstrapper.shouldWarnUser(
                    environment.macJpackageLaunch(),
                    environment.shellEnv(),
                    services.credentialResolver().hasAnyProviderCredentials()
            );
            log.info(
                    "Environment bootstrap complete: shellEnvEntries={} warningRequired={}",
                    environment.shellEnv().size(),
                    shouldWarnUser
            );

            runStage("appearance_apply", () -> applySavedAppearance(services.settingsRepo()));
            runStage("jcef_startup_init", () -> jcefStartupInitializer.initializeIfNeeded(services.settingsRepo()));
            runStage("main_window_show", () -> showMainWindow(services, sharedServicesTransferred));
            runStage("environment_warning", () -> showEnvironmentWarningIfNeeded(shouldWarnUser));
            log.info("Chat4J bootstrap finished");
        } catch (RuntimeException | Error e) {
            if (!sharedServicesTransferred.get()) {
                closeSharedServicesAfterFailure(services, e);
            }
            throw e;
        }
    }

    private void configurePlatformIntegration() {
        if (SystemInfo.isMacOS) {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", "Chat4J");
            System.setProperty("apple.awt.application.appearance", "system");
        }
    }

    private void configureEarlyLookAndFeel() {
        System.setProperty("flatlaf.useNativeLibrary", "true");
        AppearancePanel.installAccentColorGetter();
        FlatMTMaterialLighterIJTheme.setup();
    }

    private StorageServices initializeCredentialIndependentStorage() {
        StoragePaths storagePaths = StoragePaths.defaultPaths();
        SettingsRepository settingsRepository = new SettingsRepository(storagePaths);
        CacheStorageInitializer.CacheStorage cacheStorage =
                new CacheStorageInitializer(storagePaths, settingsRepository).initialize();
        ProviderModelCacheService providerModelCacheService =
                new ProviderModelCacheService(new ProviderModelCache(cacheStorage.root()));
        ModelFavoritesService modelFavoritesService = new ModelFavoritesService(settingsRepository);

        DataSource dataSource;
        SqlDialect sqlDialect;
        try {
            StorageBackend activeBackend =
                    new PersistenceBackendMigrationService(storagePaths, settingsRepository).migrateIfNeeded();
            sqlDialect = SqlDialects.forBackend(activeBackend);
            dataSource = PersistenceDataSourceFactory.create(storagePaths, activeBackend);
            new DatabaseBootstrap(storagePaths, dataSource, sqlDialect).init();
        } catch (Exception e) {
            String message = "Failed to initialize database: %s".formatted(ExceptionUtils.getMessage(e));
            log.error(message);
            throw new IllegalStateException(message, e);
        }

        try {
            Files.createDirectories(storagePaths.attachmentsDirectory());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize managed attachment storage.", e);
        }
        ConversationRepository conversationRepository = new ConversationRepository(
                dataSource,
                storagePaths.attachmentsDirectory(),
                sqlDialect
        );
        try {
            conversationRepository.cleanupUnreferencedManagedAttachmentFiles();
        } catch (Exception e) {
            log.warn("Failed to clean orphaned managed attachments during startup", e);
        }
        return new StorageServices(
                conversationRepository,
                settingsRepository,
                providerModelCacheService,
                modelFavoritesService,
                storagePaths,
                cacheStorage.root(),
                cacheStorage.snapshots()
        );
    }

    private AppServices assembleServices(
            StorageServices storage,
            Map<String, String> shellEnvironment,
            Map<String, String> processEnvironment
    ) {
        ApiTokenVault tokenVault = new ApiTokenVault(storage.storagePaths());
        CredentialResolver credentialResolver = new CredentialResolver(
                tokenVault,
                processEnvironment,
                shellEnvironment
        );
        CredentialMutationService credentialMutationService =
                new CredentialMutationService(tokenVault, credentialResolver);
        try {
            Map<String, String> subprocessEnvironment = assembleSubprocessEnvironment(
                    processEnvironment,
                    shellEnvironment
            );
            CopilotModelMetadataStore copilotModelMetadataStore =
                    new CopilotModelMetadataStore(storage.cacheRoot());
            copilotModelMetadataStore.prime();
            CopilotAuthResolver copilotAuthResolver = new CopilotAuthResolver();
            CodexAuthResolver codexAuthResolver = new CodexAuthResolver();
            ProviderAttachmentSupport attachmentSupport = createAttachmentSupport(storage.storagePaths());
            ProviderRegistry providerRegistry = new ProviderRegistry(
                    copilotAuthResolver,
                    codexAuthResolver,
                    copilotModelMetadataStore,
                    credentialResolver,
                    subprocessEnvironment,
                    attachmentSupport
            );
            storage.providerModelCacheService().primeFromDisk(
                    providerRegistry.allProviders().stream()
                            .map(ProviderRegistry.ProviderDef::name)
                            .toList()
            );
            storage.modelFavoritesService().primeFromSettings();
            log.info("Storage initialized and model cache primed");
            McpManager mcpManager = new McpManager(
                    new McpConfigurationRepository(storage.storagePaths().mcpFile()),
                    new McpSecretVault(tokenVault),
                    subprocessEnvironment,
                    storage.storagePaths().appConfigDirectory()
            );

            return new AppServices(
                    storage.conversationRepository(),
                    storage.settingsRepository(),
                    storage.providerModelCacheService(),
                    storage.modelFavoritesService(),
                    storage.storagePaths(),
                    storage.catalogSnapshots(),
                    providerRegistry,
                    copilotAuthResolver,
                    codexAuthResolver,
                    copilotModelMetadataStore,
                    credentialResolver,
                    credentialMutationService,
                    subprocessEnvironment,
                    attachmentSupport,
                    mcpManager
            );
        } catch (RuntimeException | Error e) {
            closeSecretsAfterFailure(credentialMutationService, e);
            throw e;
        }
    }

    private static ProviderAttachmentSupport createAttachmentSupport(StoragePaths storagePaths) {
        try {
            Files.createDirectories(storagePaths.attachmentsDirectory());
            return new ProviderAttachmentSupport(storagePaths.attachmentsDirectory());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize managed attachment storage.", e);
        }
    }

    static void closeSharedServicesAfterFailure(AppServices services, Throwable startupFailure) {
        try {
            services.mcpManager().close();
        } catch (Throwable cleanupFailure) {
            startupFailure.addSuppressed(cleanupFailure);
            log.warn("Failed to close MCP services after startup failure: {}",
                    cleanupFailure.getClass().getSimpleName());
        }
        closeSecretsAfterFailure(services.credentialMutationService(), startupFailure);
    }

    static void closeSecretsAfterFailure(
            CredentialMutationService credentialMutationService,
            Throwable startupFailure
    ) {
        try {
            credentialMutationService.closeSecrets();
        } catch (Throwable cleanupFailure) {
            startupFailure.addSuppressed(cleanupFailure);
            log.warn(
                    "Failed to close credential secrets after startup failure: {}",
                    cleanupFailure.getClass().getSimpleName()
            );
        }
    }

    static Map<String, String> assembleSubprocessEnvironment(
            Map<String, String> processEnvironment,
            Map<String, String> shellEnvironment
    ) {
        return assembleSubprocessEnvironment(processEnvironment, shellEnvironment, SystemInfo.isWindows);
    }

    static Map<String, String> assembleSubprocessEnvironment(
            Map<String, String> processEnvironment,
            Map<String, String> shellEnvironment,
            boolean windows
    ) {
        Map<String, String> merged = new LinkedHashMap<>();
        shellEnvironment.forEach((name, value) -> putEnvironment(merged, name, value, windows));
        processEnvironment.forEach((name, value) -> putEnvironment(merged, name, value, windows));

        String shellPath = environmentValue(shellEnvironment, "PATH", windows);
        if (StringUtils.isNotBlank(shellPath)) {
            putEnvironment(merged, "PATH", shellPath, windows);
        }
        return Map.copyOf(merged);
    }

    private static void putEnvironment(
            Map<String, String> environment,
            String name,
            String value,
            boolean windows
    ) {
        if (windows) {
            environment.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        }
        environment.put(name, value);
    }

    private static String environmentValue(Map<String, String> environment, String name, boolean windows) {
        String exact = environment.get(name);
        if (exact != null || !windows) {
            return exact;
        }
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    void applySavedAppearance(SettingsRepository settingsRepository) {
        try {
            AppearancePanel.restoreAccentColor(settingsRepository);

            String themeName = settingsRepository.get(ThemeSettings.THEME_NAME_KEY, ThemeSettings.DEFAULT_THEME);
            String className = AppearancePanel.classNameForTheme(themeName);
            if (className != null) {
                UIManager.setLookAndFeel(className);
            }

            AppearancePanel.applySavedFonts(settingsRepository);
        } catch (Exception e) {
            log.warn("Failed to apply saved appearance settings: {}", ExceptionUtils.getMessage(e));
        }
    }

    private void showMainWindow(AppServices services, AtomicBoolean sharedServicesTransferred) {
        Runnable createAndShow = () -> {
            MainFrame frame = null;
            try {
                frame = new MainFrame(
                    services.conversationRepo(),
                    services.settingsRepo(),
                    services.providerModelCacheService(),
                    services.modelFavoritesService(),
                    services.storagePaths(),
                    services.catalogSnapshots(),
                    services.providerRegistry(),
                    services.copilotAuthResolver(),
                    services.codexAuthResolver(),
                    services.copilotModelMetadataStore(),
                    services.credentialResolver(),
                    services.credentialMutationService(),
                    services.subprocessEnvironment(),
                    services.attachmentSupport(),
                    services.mcpManager()
                );
                frame.acceptSharedServiceOwnership();
                sharedServicesTransferred.set(true);
                frame.setVisible(true);
            } catch (RuntimeException | Error e) {
                if (frame != null) {
                    try {
                        frame.disposeAfterStartupFailure();
                    } catch (Throwable cleanupFailure) {
                        e.addSuppressed(cleanupFailure);
                        log.warn(
                                "Failed to dispose the partially shown main window: {}",
                                cleanupFailure.getClass().getSimpleName()
                        );
                    }
                }
                throw e;
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            createAndShow.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(createAndShow);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating the main window.", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Could not create the main window.", cause);
        }
    }

    private void showEnvironmentWarningIfNeeded(boolean shouldWarnUser) {
        if (!shouldWarnUser) {
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
                    • Enter and save API tokens in Settings after Chat4J opens
                    • Run Chat4J from the terminal: java --enable-preview -jar chat4j.jar
                    • Set keys via: launchctl setenv ANTHROPIC_API_KEY sk-...
                    • Use a local provider like LM Studio or Ollama (no API key required)
                    • Run diagnostics: bash "/Applications/Chat4J.app/Contents/app/tools/chat4j-doctor.sh" --app "/Applications/Chat4J.app"
                    """,
                "Environment Warning",
                JOptionPane.WARNING_MESSAGE
        ));
    }

    private EnvironmentInitResult awaitEnvironment(
            FutureTask<EnvironmentInitResult> environmentTask,
            Thread environmentThread,
            long stageStartedAtNanos
    ) {
        try {
            EnvironmentInitResult environment = environmentTask.get();
            environmentThread.join();
            completeStage("environment_init_async", stageStartedAtNanos);
            return environment;
        } catch (InterruptedException e) {
            cancelEnvironmentTask(environmentTask, environmentThread);
            Thread.currentThread().interrupt();
            failStage("environment_init_async", stageStartedAtNanos, e);
            throw new IllegalStateException("Environment bootstrap was interrupted.", e);
        } catch (ExecutionException e) {
            cancelEnvironmentTask(environmentTask, environmentThread);
            failStage("environment_init_async", stageStartedAtNanos, e);
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Environment bootstrap failed.", cause);
        }
    }

    void cancelEnvironmentTask(FutureTask<?> environmentTask, Thread environmentThread) {
        environmentTask.cancel(true);
        environmentThread.interrupt();
        boolean restoreInterrupt = Thread.interrupted();
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        try {
            while (environmentThread.isAlive()) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                try {
                    environmentThread.join(
                            Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
                    );
                } catch (InterruptedException e) {
                    restoreInterrupt = true;
                }
            }
            if (environmentThread.isAlive()) {
                log.warn("Environment bootstrap task did not settle within the cleanup deadline");
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
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
        } catch (RuntimeException | Error e) {
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

    private void failStage(String stageName, long stageStartedAtNanos, Throwable throwable) {
        log.warn(
                "Startup stage failed: {} ({}ms): {}",
                stageName,
                elapsedMillis(stageStartedAtNanos),
                ExceptionUtils.getMessage(throwable),
                throwable
        );
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private record StorageServices(
            ConversationRepository conversationRepository,
            SettingsRepository settingsRepository,
            ProviderModelCacheService providerModelCacheService,
            ModelFavoritesService modelFavoritesService,
            StoragePaths storagePaths,
            CacheRootHandle cacheRoot,
            CatalogSnapshotStore catalogSnapshots
    ) {
    }
}
