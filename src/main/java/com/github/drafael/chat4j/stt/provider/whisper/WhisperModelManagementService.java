package com.github.drafael.chat4j.stt.provider.whisper;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.SpeechToTextSettings;
import com.github.drafael.chat4j.stt.model.SpeechToTextModelDirectory;
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

@Slf4j
public class WhisperModelManagementService implements AutoCloseable {

    private static final long CLOSE_WAIT_POLL_SECONDS = 1;

    private final SettingsRepository settingsRepo;
    private final WhisperSpeechToTextSettings settings;
    private final SpeechToTextModelDirectory modelDirectory;
    private final Path tempDirectory;
    private final WhisperInstalledModelScanner scanner;
    private final WhisperModelInstaller installer;
    private final WhisperNativeRuntime nativeRuntime;
    private final WhisperModelUsageTracker usageTracker;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat4j-whisper-models-", 0).factory());
    private final List<Consumer<WhisperModelManagementSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private volatile WhisperModelManagementSnapshot snapshot;
    private volatile Future<?> activeOperation;
    private volatile Thread activeOperationThread;
    private volatile OperationCancellation activeCancellation;
    private volatile boolean runtimeReady = true;
    private volatile String runtimeStatusMessage = "Whisper.cpp native runtime has not been checked yet.";

    public WhisperModelManagementService(@NonNull SettingsRepository settingsRepo, @NonNull Path defaultModelDirectory, @NonNull Path tempDirectory) {
        this(settingsRepo, defaultModelDirectory, tempDirectory, WhisperNativeRuntime.shared(), new WhisperModelUsageTracker());
    }

    public WhisperModelManagementService(
            @NonNull SettingsRepository settingsRepo,
            @NonNull Path defaultModelDirectory,
            @NonNull Path tempDirectory,
            @NonNull WhisperNativeRuntime nativeRuntime,
            @NonNull WhisperModelUsageTracker usageTracker
    ) {
        this.settingsRepo = settingsRepo;
        this.settings = new WhisperSpeechToTextSettings(settingsRepo);
        this.modelDirectory = new SpeechToTextModelDirectory(settingsRepo, defaultModelDirectory);
        this.tempDirectory = tempDirectory;
        this.nativeRuntime = nativeRuntime;
        this.usageTracker = usageTracker;
        this.scanner = new WhisperInstalledModelScanner();
        this.installer = new WhisperModelInstaller();
        this.snapshot = WhisperModelManagementSnapshot.empty(whisperRoot(), tempDirectory);
    }

    public WhisperModelManagementSnapshot snapshot() {
        return snapshot;
    }

    public WhisperModelUsageTracker usageTracker() {
        return usageTracker;
    }

    public Runnable addListener(@NonNull Consumer<WhisperModelManagementSnapshot> listener) {
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
        ensureOpen();
        refreshAsync(selectedProviderIsWhisper());
    }

    public void refreshAsync(boolean probeRuntime) {
        trySubmit("Scanning Whisper.cpp models...", "refresh", "", "", 0, false, () -> {
            if (probeRuntime) {
                probeRuntime();
            }
            refreshSnapshot(false);
        });
    }

    public void downloadAsync(String modelId) {
        ensureOpen();
        WhisperModelCatalogEntry entry = WhisperModelCatalog.find(modelId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown Whisper.cpp model: %s".formatted(modelId)));
        submit("Downloading %s...".formatted(entry.label()), "download", entry.id(), entry.label(), entry.sizeBytes(), true, () -> {
            installer.downloadAndInstall(entry, whisperRoot(), tempDirectory, this::operationCanceled, this::publishOperationStatus);
            refreshSnapshot(false);
            installedModel(entry.id()).filter(WhisperInstalledModel::eligible).ifPresent(this::persistSelectedModel);
            refreshSnapshot(false);
        });
    }

    public void deleteAsync(String modelId) {
        ensureOpen();
        WhisperInstalledModel model = installedModel(modelId).orElseThrow(() -> new IllegalArgumentException("Unknown installed Whisper.cpp model: %s".formatted(modelId)));
        if (usageTracker.inUse(model.id())) {
            throw new IllegalStateException("Finish or cancel transcription before deleting this Whisper.cpp model.");
        }
        submit("Deleting %s...".formatted(model.label()), "delete", model.id(), model.label(), 0, false, () -> {
            if (usageTracker.inUse(model.id())) {
                throw new IllegalStateException("Finish or cancel transcription before deleting this Whisper.cpp model.");
            }
            installer.deleteInstalled(model, whisperRoot());
            if (Objects.equals(selectedModelId(), model.id())) {
                clearSelectionOnly();
            }
            refreshSnapshot(false);
        });
    }

    public void selectModelAsync(String modelId) {
        ensureOpen();
        WhisperInstalledModel model = eligibleInstalledModel(modelId);
        submit("Selecting %s...".formatted(model.label()), "select", model.id(), model.label(), 0, false, () -> {
            persistSelectedModel(model);
            refreshSnapshot(false);
        });
    }

    public void selectModel(String modelId) throws Exception {
        ensureOpen();
        WhisperInstalledModel model = eligibleInstalledModel(modelId);
        persistSelectedModel(model);
        refreshSnapshot(false);
    }

    public void clearSelection() throws Exception {
        ensureOpen();
        clearSelectionOnly();
        refreshSnapshot(false);
    }

    public void cancelActiveOperation() {
        ensureOpen();
        OperationCancellation cancellation = activeCancellation;
        if (cancellation != null) {
            cancellation.cancel();
            installer.abortActiveDownload();
            publish(snapshotWithOperation(snapshot, true, true, snapshot.operationStatus(), snapshot.bytesDownloaded(), snapshot.totalBytes(), snapshot.cancelable()));
        }
    }

    public void validateSelectedNow() {
        ensureOpen();
        try {
            probeRuntime();
            publish(snapshotWithRuntimeStatus(snapshot));
            if (snapshot.selectedModel() == null || !Objects.equals(snapshot.modelRoot(), whisperRoot())) {
                refreshSnapshot(false);
            }
            WhisperModelManagementSnapshot current = snapshot;
            WhisperInstalledModel selected = current.selectedModel();
            if (selected == null) {
                throw new IllegalStateException("Download or select a Whisper.cpp model to enable transcription.");
            }
            if (!runtimeReady) {
                throw new IllegalStateException(runtimeStatusMessage);
            }
            WhisperInstalledModelScanner.ValidationResult validation = scanner.validateInstall(selected.directory(), whisperRoot(), selected.catalogEntry());
            if (validation.status() != WhisperValidationStatus.VALID) {
                throw new IllegalStateException(validation.message());
            }
            if (!current.readyToTranscribe()) {
                throw new IllegalStateException(current.statusMessage());
            }
        } catch (Exception e) {
            throw new IllegalStateException(StringUtils.defaultIfBlank(e.getMessage(), "Could not validate Whisper.cpp model."), e);
        }
    }

    public Optional<WhisperInstalledModel> installedModel(String modelId) {
        return snapshot.installedModels().stream()
                .filter(model -> Objects.equals(model.id(), modelId))
                .findFirst();
    }

    public Path whisperRoot() {
        return settings.modelRoot(modelDirectory.resolve());
    }

    @Override
    public void close() {
        boolean awaitTermination;
        synchronized (this) {
            if (disposed.compareAndSet(false, true)) {
                OperationCancellation cancellation = activeCancellation;
                if (cancellation != null) {
                    cancellation.cancel();
                    installer.abortActiveDownload();
                }
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

    private void submit(String status, String operationType, String modelId, String modelLabel, long totalBytes, boolean cancelable, ThrowingRunnable action) {
        if (!trySubmit(status, operationType, modelId, modelLabel, totalBytes, cancelable, action)) {
            throw new IllegalStateException("Another Whisper.cpp model operation is already in progress.");
        }
    }

    private boolean trySubmit(
            String status,
            String operationType,
            String modelId,
            String modelLabel,
            long totalBytes,
            boolean cancelable,
            ThrowingRunnable action
    ) {
        ensureOpen();
        synchronized (this) {
            ensureOpen();
            if (activeOperation != null && !activeOperation.isDone()) {
                return false;
            }
            OperationCancellation cancellation = new OperationCancellation();
            activeCancellation = cancellation;
            publish(snapshotWithOperation(snapshot, true, false, status, 0, totalBytes, cancelable, operationType, modelId, modelLabel));
            try {
                ensureOpen();
            } catch (IllegalStateException e) {
                activeCancellation = null;
                throw e;
            }
            activeOperation = executor.submit(() -> {
                activeOperationThread = Thread.currentThread();
                try {
                    try {
                        installer.cleanupStalePartials(whisperRoot(), tempDirectory);
                        action.run();
                    } catch (Exception e) {
                        String message = cancellation.cancelled()
                                ? "Whisper.cpp model operation canceled."
                                : StringUtils.defaultIfBlank(e.getMessage(), "Whisper.cpp model operation failed.");
                        publish(snapshotWithOperation(snapshot, false, false, message, snapshot.bytesDownloaded(), snapshot.totalBytes(), false));
                        return;
                    }
                    publish(snapshotWithOperation(snapshot, false, false, "", 0, 0, false));
                } finally {
                    activeCancellation = null;
                    activeOperationThread = null;
                }
            });
            return true;
        }
    }

    private void refreshSnapshot(boolean forceHash) throws Exception {
        List<WhisperModelCatalogEntry> catalog = WhisperModelCatalog.entries();
        Path root = whisperRoot();
        List<WhisperInstalledModel> installed = scanner.scan(root, catalog, forceHash);
        WhisperInstalledModel selected = selectedInstalledModel(installed).orElse(null);
        String status = statusMessage(installed, selected);
        publish(WhisperModelManagementSnapshot.builder()
                .modelRoot(root)
                .tempRoot(tempDirectory)
                .catalog(catalog)
                .installedModels(installed)
                .rows(rows(catalog, installed, selected))
                .selectedModel(selected)
                .runtimeReady(runtimeReady)
                .statusMessage(status)
                .operationType("")
                .operationModelId("")
                .operationModelLabel("")
                .operationStatus("")
                .build());
    }

    private Optional<WhisperInstalledModel> selectedInstalledModel(List<WhisperInstalledModel> installed) {
        String selectedId = selectedModelId();
        if (StringUtils.isBlank(selectedId)) {
            return Optional.empty();
        }
        String savedRoot = settings.savedRoot();
        String savedFingerprint = settings.savedFingerprint();
        return installed.stream()
                .filter(model -> Objects.equals(model.id(), selectedId))
                .filter(model -> StringUtils.isBlank(savedRoot) || Objects.equals(savedRoot, whisperRoot().toString()))
                .filter(model -> StringUtils.isBlank(savedFingerprint) || Objects.equals(savedFingerprint, model.fingerprint()))
                .findFirst();
    }

    private String selectedModelId() {
        return settings.selectedModelId();
    }

    private WhisperInstalledModel eligibleInstalledModel(String modelId) {
        return installedModel(modelId)
                .filter(WhisperInstalledModel::eligible)
                .orElseThrow(() -> new IllegalArgumentException("Select an installed Whisper.cpp model first."));
    }

    private void persistSelectedModel(WhisperInstalledModel model) {
        settings.saveSelectedModel(model, whisperRoot());
    }

    private void clearSelectionOnly() {
        settings.clearSelectedModel();
    }

    private String statusMessage(List<WhisperInstalledModel> installed, WhisperInstalledModel selected) {
        if (!runtimeReady) {
            return runtimeStatusMessage;
        }
        if (installed.stream().noneMatch(WhisperInstalledModel::ready)) {
            return "Download or select a Whisper.cpp model to enable transcription.";
        }
        if (selected == null) {
            return "Select an installed Whisper.cpp model to enable transcription.";
        }
        if (!selected.ready()) {
            return selected.validationMessage();
        }
        return "Using Whisper.cpp model %s locally.".formatted(selected.label());
    }

    private void probeRuntime() {
        runtimeReady = nativeRuntime.ensureLoaded();
        runtimeStatusMessage = nativeRuntime.statusMessage();
    }

    private boolean selectedProviderIsWhisper() {
        return Objects.equals(WhisperSpeechToTextProvider.ID, settingsRepo.get(SpeechToTextSettings.PROVIDER_KEY, ""));
    }

    private List<WhisperLocalModelRow> rows(List<WhisperModelCatalogEntry> catalog, List<WhisperInstalledModel> installed, WhisperInstalledModel selected) {
        Map<String, WhisperInstalledModel> installedById = new LinkedHashMap<>();
        installed.forEach(model -> installedById.put(model.id(), model));
        List<WhisperLocalModelRow> rows = new ArrayList<>();
        catalog.forEach(entry -> {
            WhisperInstalledModel installedModel = installedById.get(entry.id());
            rows.add(new WhisperLocalModelRow(
                    entry.id(),
                    entry.label(),
                    typeLanguage(entry),
                    entry.sizeLabel(),
                    entry,
                    installedModel,
                    selected != null && Objects.equals(selected.id(), entry.id()),
                    installedModel == null,
                    installedModel != null && installedModel.deleteable(),
                    installedModel == null ? remoteStatus(entry) : installedModel.validationMessage()
            ));
        });
        return List.copyOf(rows);
    }

    private String typeLanguage(WhisperModelCatalogEntry entry) {
        String language = entry.englishOnly() ? "English" : "Multilingual";
        if (entry.tinydiarize()) {
            return "%s / tinydiarize".formatted(language);
        }
        return entry.quantized() ? "%s / quantized".formatted(language) : language;
    }

    private String remoteStatus(WhisperModelCatalogEntry entry) {
        return entry.sizeBytes() > 1024L * 1024L * 1024L
                ? "Large download; may require substantial RAM/CPU"
                : entry.description();
    }

    private boolean operationCanceled() {
        OperationCancellation cancellation = activeCancellation;
        return disposed.get() || cancellation != null && cancellation.cancelled();
    }

    private void publishOperationStatus(String status, long bytesDownloaded, long totalBytes, boolean cancelable) {
        publish(snapshotWithOperation(snapshot, true, false, status, bytesDownloaded, totalBytes, cancelable));
    }

    private WhisperModelManagementSnapshot snapshotWithRuntimeStatus(WhisperModelManagementSnapshot base) {
        return base.toBuilder()
                .runtimeReady(runtimeReady)
                .statusMessage(statusMessage(base.installedModels(), base.selectedModel()))
                .build();
    }

    private WhisperModelManagementSnapshot snapshotWithOperation(WhisperModelManagementSnapshot base, boolean inProgress, boolean canceling, String status, long bytesDownloaded, long totalBytes, boolean cancelable) {
        return snapshotWithOperation(base, inProgress, canceling, status, bytesDownloaded, totalBytes, cancelable, base.operationType(), base.operationModelId(), base.operationModelLabel());
    }

    private WhisperModelManagementSnapshot snapshotWithOperation(
            WhisperModelManagementSnapshot base,
            boolean inProgress,
            boolean canceling,
            String status,
            long bytesDownloaded,
            long totalBytes,
            boolean cancelable,
            String operationType,
            String operationModelId,
            String operationModelLabel
    ) {
        return base.toBuilder()
                .operationInProgress(inProgress)
                .operationType(operationType)
                .operationModelId(operationModelId)
                .operationModelLabel(operationModelLabel)
                .bytesDownloaded(bytesDownloaded)
                .totalBytes(totalBytes)
                .cancelable(cancelable)
                .canceling(canceling)
                .operationStatus(status)
                .build();
    }

    private void ensureOpen() {
        if (disposed.get()) {
            throw new IllegalStateException("Whisper.cpp model management is closed.");
        }
    }

    private void publish(WhisperModelManagementSnapshot next) {
        if (disposed.get()) {
            return;
        }
        snapshot = next;
        for (Consumer<WhisperModelManagementSnapshot> listener : listeners) {
            if (disposed.get()) {
                return;
            }
            try {
                listener.accept(next);
            } catch (RuntimeException e) {
                listeners.remove(listener);
                log.warn("Whisper.cpp model-management listener failed", e);
            }
        }
    }

    private static final class OperationCancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        void cancel() {
            cancelled.set(true);
        }

        boolean cancelled() {
            return cancelled.get();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
