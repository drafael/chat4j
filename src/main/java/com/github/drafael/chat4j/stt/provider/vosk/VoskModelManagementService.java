package com.github.drafael.chat4j.stt.provider.vosk;

import com.github.drafael.chat4j.persistence.catalog.CatalogSnapshotStore;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.model.SpeechToTextModelDirectory;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.vosk.LibVosk;
import org.vosk.LogLevel;

@Slf4j
public class VoskModelManagementService implements AutoCloseable {

    public static final String SCAN_OPERATION_STATUS = "Scanning Vosk models...";
    public static final String CATALOG_REFRESH_OPERATION_STATUS = "Refreshing Vosk catalog...";
    private static final long CLOSE_WAIT_POLL_SECONDS = 1;

    private final VoskSpeechToTextSettings settings;
    private final SpeechToTextModelDirectory modelDirectory;
    private final Path tempDirectory;
    private final VoskModelCatalogClient catalogClient;
    private final VoskModelCatalogCache catalogCache;
    private final VoskBundledCatalogLoader bundledCatalogLoader;
    private final VoskModelValidator validator;
    private final VoskInstalledModelScanner scanner;
    private final VoskModelInstaller installer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat4j-vosk-models-", 0).factory());
    private final List<Consumer<VoskModelManagementSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private volatile VoskModelManagementSnapshot snapshot;
    private volatile Future<?> activeOperation;
    private volatile Thread activeOperationThread;
    private volatile boolean runtimeReady;
    private volatile String runtimeStatusMessage = "Checking Vosk native runtime...";

    public VoskModelManagementService(
            @NonNull SettingsRepository settingsRepo,
            @NonNull Path defaultModelDirectory,
            @NonNull Path tempDirectory
    ) {
        this(settingsRepo, defaultModelDirectory, tempDirectory, CatalogSnapshotStore.forSettings(settingsRepo));
    }

    public VoskModelManagementService(
            @NonNull SettingsRepository settingsRepo,
            @NonNull Path defaultModelDirectory,
            @NonNull Path tempDirectory,
            @NonNull CatalogSnapshotStore catalogSnapshots
    ) {
        this.settings = new VoskSpeechToTextSettings(settingsRepo);
        this.modelDirectory = new SpeechToTextModelDirectory(settingsRepo, defaultModelDirectory);
        this.tempDirectory = tempDirectory;
        this.catalogClient = new VoskModelCatalogClient();
        this.catalogCache = new VoskModelCatalogCache(catalogSnapshots);
        this.bundledCatalogLoader = new VoskBundledCatalogLoader(catalogClient);
        this.validator = new VoskModelValidator();
        this.scanner = new VoskInstalledModelScanner(validator);
        this.installer = new VoskModelInstaller(validator);
        this.snapshot = VoskModelManagementSnapshot.empty(voskRoot(), tempDirectory);
    }

    public VoskModelManagementSnapshot snapshot() {
        return snapshot;
    }

    public Runnable addListener(@NonNull Consumer<VoskModelManagementSnapshot> listener) {
        synchronized (this) {
            ensureOpen();
            listeners.add(listener);
        }
        try {
            listener.accept(snapshot);
        } catch (RuntimeException e) {
            listeners.remove(listener);
            throw e;
        }
        return () -> listeners.remove(listener);
    }

    public void refreshAsync() {
        trySubmit(SCAN_OPERATION_STATUS, () -> {
            probeRuntime();
            refreshSnapshot(false);
        });
    }

    public void refreshCatalogAsync() {
        trySubmit(CATALOG_REFRESH_OPERATION_STATUS, () -> {
            probeRuntime();
            refreshSnapshot(true);
        });
    }

    public void downloadAsync(String modelId) {
        ensureOpen();
        VoskModelCatalogEntry entry = snapshot.catalog().stream()
                .filter(candidate -> Objects.equals(candidate.name(), modelId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Vosk model: %s".formatted(modelId)));
        submit("Downloading %s...".formatted(entry.label()), () -> {
            installer.downloadAndInstall(entry, voskRoot(), tempDirectory, disposed::get, this::publishOperationStatus);
            refreshSnapshot(false);
        });
    }

    public void importAsync(Path source) {
        ensureOpen();
        String requestedName = source == null || source.getFileName() == null ? "model" : source.getFileName().toString();
        submit("Importing %s...".formatted(requestedName), () -> {
            installer.importFolder(source, voskRoot(), requestedName, disposed::get, this::publishOperationStatus);
            refreshSnapshot(false);
        });
    }

    public void deleteAsync(String modelId) {
        ensureOpen();
        VoskInstalledModel model = installedModel(modelId).orElseThrow(() -> new IllegalArgumentException("Unknown installed model: %s".formatted(modelId)));
        submit("Deleting %s...".formatted(model.label()), () -> {
            installer.deleteInstalled(model, voskRoot());
            if (Objects.equals(selectedModelId(), model.id())) {
                clearSelection();
            }
            refreshSnapshot(false);
        });
    }

    public void selectModel(String modelId) throws Exception {
        ensureOpen();
        VoskInstalledModel model = eligibleInstalledModel(modelId);
        persistSelectedModel(model);
        if (model.validationStatus() == VoskValidationStatus.PLAUSIBLE_UNVERIFIED) {
            validateAsync(model.id());
        }
        refreshSnapshot(false);
    }

    public void selectModelAsync(String modelId) {
        ensureOpen();
        VoskInstalledModel model = eligibleInstalledModel(modelId);
        submit("Selecting %s...".formatted(model.label()), () -> {
            persistSelectedModel(model);
            refreshSnapshot(false);
        });
    }

    public void clearSelection() throws Exception {
        ensureOpen();
        settings.clearSelectedModel();
        refreshSnapshot(false);
    }

    public Optional<VoskInstalledModel> installedModel(String modelId) {
        return snapshot.installedModels().stream()
                .filter(model -> Objects.equals(model.id(), modelId))
                .findFirst();
    }

    private VoskInstalledModel eligibleInstalledModel(String modelId) {
        return installedModel(modelId)
                .filter(VoskInstalledModel::eligible)
                .orElseThrow(() -> new IllegalArgumentException("Select an installed Vosk model first."));
    }

    private void persistSelectedModel(VoskInstalledModel model) {
        settings.saveSelectedModel(model, voskRoot().toAbsolutePath().normalize());
    }

    public void validateAsync(String modelId) {
        ensureOpen();
        submit("Validating %s...".formatted(modelId), () -> refreshSnapshot(false));
    }

    public void validateSelectedNow() {
        ensureOpen();
        try {
            probeRuntime();
            refreshSnapshot(false);
        } catch (Exception e) {
            throw new IllegalStateException(StringUtils.defaultIfBlank(e.getMessage(), "Could not validate Vosk model."), e);
        }
        VoskModelManagementSnapshot current = snapshot;
        if (current.selectedModel() == null) {
            throw new IllegalStateException("Download or add a Vosk model to enable transcription.");
        }
        if (!current.readyToTranscribe()) {
            throw new IllegalStateException(current.statusMessage());
        }
    }

    public Path voskRoot() {
        return modelDirectory.resolve().resolve(VoskSpeechToTextProvider.ID).toAbsolutePath().normalize();
    }

    @Override
    public void close() {
        boolean awaitTermination;
        synchronized (this) {
            if (disposed.compareAndSet(false, true)) {
                installer.abortActiveDownload();
                Future<?> operation = activeOperation;
                if (operation != null) {
                    operation.cancel(true);
                }
                executor.shutdownNow();
                listeners.clear();
            }
            awaitTermination = Thread.currentThread() != activeOperationThread && !executor.isTerminated();
        }
        if (awaitTermination) {
            awaitExecutorTermination();
        }
    }

    private void awaitExecutorTermination() {
        boolean interrupted = false;
        while (true) {
            try {
                if (executor.awaitTermination(CLOSE_WAIT_POLL_SECONDS, TimeUnit.SECONDS)) {
                    break;
                }
                executor.shutdownNow();
            } catch (InterruptedException e) {
                interrupted = true;
                executor.shutdownNow();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void submit(String status, ThrowingRunnable action) {
        if (!trySubmit(status, action)) {
            throw new IllegalStateException("Another Vosk model operation is already in progress.");
        }
    }

    private boolean trySubmit(String status, ThrowingRunnable action) {
        ensureOpen();
        synchronized (this) {
            ensureOpen();
            if (activeOperation != null && !activeOperation.isDone()) {
                return false;
            }
            publish(snapshotWithOperation(snapshot, true, status));
            ensureOpen();
            activeOperation = executor.submit(() -> {
                activeOperationThread = Thread.currentThread();
                try {
                    try {
                        installer.cleanupStalePartials(voskRoot(), tempDirectory);
                        action.run();
                    } catch (Exception e) {
                        publish(snapshotWithOperation(snapshot, false, StringUtils.defaultIfBlank(e.getMessage(), "Vosk model operation failed.")));
                        return;
                    }
                    publish(snapshotWithOperation(snapshot, false, ""));
                } finally {
                    activeOperationThread = null;
                }
            });
            return true;
        }
    }

    private void refreshSnapshot(boolean online) throws Exception {
        List<VoskModelCatalogEntry> catalog = catalog(online);
        Path root = voskRoot();
        List<VoskInstalledModel> installed = scanner.scan(root, catalog);
        VoskInstalledModel selected = selectedInstalledModel(installed).orElse(null);
        String status = statusMessage(installed, selected);
        publish(new VoskModelManagementSnapshot(
                root,
                tempDirectory,
                List.copyOf(catalog),
                List.copyOf(installed),
                rows(catalog, installed, selected),
                selected,
                runtimeReady,
                status,
                false,
                ""
        ));
    }

    private List<VoskModelCatalogEntry> catalog(boolean online) throws Exception {
        if (online) {
            try {
                String json = catalogClient.fetchRawJson(disposed::get);
                if (!catalogCache.saveRawJson(json)) {
                    throw new IllegalStateException("Unable to save Vosk catalog snapshot");
                }
                return catalogClient.parse(json);
            } catch (Exception ignored) {
            }
        }
        Optional<String> cached = catalogCache.rawJson();
        if (cached.isPresent()) {
            try {
                return catalogClient.parse(cached.orElseThrow());
            } catch (Exception ignored) {
            }
        }
        return bundledCatalogLoader.load();
    }

    private Optional<VoskInstalledModel> selectedInstalledModel(List<VoskInstalledModel> installed) {
        String selectedId = selectedModelId();
        if (StringUtils.isBlank(selectedId)) {
            return installed.stream().filter(VoskInstalledModel::ready).findFirst();
        }
        String savedRoot = settings.savedRoot();
        String savedFingerprint = settings.savedFingerprint();
        return installed.stream()
                .filter(model -> Objects.equals(model.id(), selectedId))
                .filter(model -> !model.custom() || StringUtils.isBlank(savedRoot) || Objects.equals(savedRoot, voskRoot().toString()))
                .filter(model -> !model.custom() || StringUtils.isBlank(savedFingerprint) || Objects.equals(savedFingerprint, model.fingerprint()))
                .findFirst();
    }

    private String selectedModelId() {
        return settings.selectedModelId();
    }

    private String statusMessage(List<VoskInstalledModel> installed, VoskInstalledModel selected) {
        if (installed.isEmpty()) {
            return "Download or add a Vosk model to enable transcription.";
        }
        if (selected == null) {
            return "Select an installed Vosk model to enable transcription.";
        }
        if (!selected.ready()) {
            return selected.validationMessage();
        }
        if (!runtimeReady) {
            return runtimeStatusMessage;
        }
        return "Using Vosk model %s locally.".formatted(selected.label());
    }

    private void probeRuntime() {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            runtimeReady = true;
            runtimeStatusMessage = "Vosk runtime is ready.";
        } catch (LinkageError | RuntimeException e) {
            runtimeReady = false;
            runtimeStatusMessage = "Vosk native runtime is unavailable on this platform or package.";
        }
    }

    private List<VoskLocalModelRow> rows(List<VoskModelCatalogEntry> catalog, List<VoskInstalledModel> installed, VoskInstalledModel selected) {
        Map<String, VoskInstalledModel> installedByOfficialName = new LinkedHashMap<>();
        installed.stream().filter(model -> !model.custom()).forEach(model -> installedByOfficialName.put(model.id(), model));
        List<VoskLocalModelRow> rows = new ArrayList<>();
        catalog.stream()
                .filter(VoskModelCatalogEntry::speechRecognition)
                .filter(entry -> !entry.obsoleteFlag() || installedByOfficialName.containsKey(entry.name()))
                .forEach(entry -> {
                    VoskInstalledModel installedModel = installedByOfficialName.get(entry.name());
                    boolean downloadable = installedModel == null && !entry.obsoleteFlag() && entry.hasDownloadMetadata();
                    rows.add(new VoskLocalModelRow(
                            entry.name(),
                            entry.label(),
                            entry.displayLanguage(),
                            entry.type(),
                            entry.displaySize(),
                            entry,
                            installedModel,
                            selected != null && Objects.equals(selected.id(), entry.name()),
                            downloadable,
                            installedModel != null && installedModel.deleteable(),
                            entry.obsoleteFlag(),
                            installedModel == null ? remoteStatus(entry) : installedModel.validationMessage()
                    ));
                });
        installed.stream().filter(VoskInstalledModel::custom).forEach(model -> rows.add(new VoskLocalModelRow(
                model.id(),
                model.label(),
                "Custom",
                "local",
                "",
                null,
                model,
                selected != null && Objects.equals(selected.id(), model.id()),
                false,
                model.deleteable(),
                false,
                model.validationMessage()
        )));
        return List.copyOf(rows);
    }

    private String remoteStatus(VoskModelCatalogEntry entry) {
        return entry.hasDownloadMetadata() ? "Available to download" : "Missing required download metadata";
    }

    private void publishOperationStatus(String status) {
        publish(snapshotWithOperation(snapshot, true, status));
    }

    private VoskModelManagementSnapshot snapshotWithOperation(VoskModelManagementSnapshot base, boolean inProgress, String status) {
        return new VoskModelManagementSnapshot(
                base.modelRoot(),
                base.tempRoot(),
                base.catalog(),
                base.installedModels(),
                base.rows(),
                base.selectedModel(),
                base.runtimeReady(),
                base.statusMessage(),
                inProgress,
                status
        );
    }

    private void ensureOpen() {
        if (disposed.get()) {
            throw new IllegalStateException("Vosk model management is closed.");
        }
    }

    private void publish(VoskModelManagementSnapshot next) {
        if (disposed.get()) {
            return;
        }
        snapshot = next;
        for (Consumer<VoskModelManagementSnapshot> listener : listeners) {
            if (disposed.get()) {
                return;
            }
            try {
                listener.accept(next);
            } catch (RuntimeException e) {
                listeners.remove(listener);
                log.warn("Vosk model-management listener failed", e);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
