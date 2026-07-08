package com.github.drafael.chat4j.stt.provider.sphinx4;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public class Sphinx4ModelManagementService implements AutoCloseable {

    public static final String PROVIDER_ID = "sphinx4";
    private static final String FINGERPRINT_KEY = SettingsKeys.STT_PREFIX + "sphinx4.model.fingerprint";
    private static final String ROOT_KEY = SettingsKeys.STT_PREFIX + "sphinx4.model.root";

    private final SettingsRepository settingsRepo;
    private final SpeechToTextModelDirectory modelDirectory;
    private final Path tempDirectory;
    private final Sphinx4BundledCatalogLoader bundledCatalogLoader;
    private final Sphinx4ModelValidator validator;
    private final Sphinx4InstalledModelScanner scanner;
    private final Sphinx4ModelInstaller installer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat4j-sphinx4-models-", 0).factory());
    private final List<Consumer<Sphinx4ModelManagementSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private volatile Sphinx4ModelManagementSnapshot snapshot;
    private volatile Future<?> activeOperation;
    private volatile boolean forceHeavySelectedValidation;

    public Sphinx4ModelManagementService(
            @NonNull SettingsRepository settingsRepo,
            @NonNull Path defaultModelDirectory,
            @NonNull Path tempDirectory
    ) {
        this(settingsRepo, defaultModelDirectory, tempDirectory, new Sphinx4ModelValidator());
    }

    Sphinx4ModelManagementService(
            @NonNull SettingsRepository settingsRepo,
            @NonNull Path defaultModelDirectory,
            @NonNull Path tempDirectory,
            @NonNull Sphinx4ModelValidator validator
    ) {
        this.settingsRepo = settingsRepo;
        this.modelDirectory = new SpeechToTextModelDirectory(settingsRepo, defaultModelDirectory);
        this.tempDirectory = tempDirectory;
        this.bundledCatalogLoader = new Sphinx4BundledCatalogLoader();
        this.validator = validator;
        this.scanner = new Sphinx4InstalledModelScanner(validator);
        this.installer = new Sphinx4ModelInstaller(validator);
        this.snapshot = Sphinx4ModelManagementSnapshot.empty(sphinx4Root(), tempDirectory);
    }

    public Sphinx4ModelManagementSnapshot snapshot() {
        return snapshot;
    }

    public Runnable addListener(Consumer<Sphinx4ModelManagementSnapshot> listener) {
        listeners.add(listener);
        listener.accept(snapshot);
        return () -> listeners.remove(listener);
    }

    public void refreshAsync() {
        try {
            submit("Scanning Sphinx4 models...", () -> refreshSnapshot(true));
        } catch (IllegalStateException ignored) {
        }
    }

    public void refreshCatalogAsync() {
        try {
            submit("Refreshing Sphinx4 catalog...", () -> refreshSnapshot(true));
        } catch (IllegalStateException ignored) {
        }
    }

    public void downloadAsync(String modelId) {
        Sphinx4ModelCatalogEntry entry = snapshot.catalog().stream()
                .filter(candidate -> Objects.equals(candidate.id(), modelId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Sphinx4 model: %s".formatted(modelId)));
        submit("Downloading %s...".formatted(entry.label()), () -> {
            installer.downloadAndInstall(entry, sphinx4Root(), tempDirectory, disposed::get, this::publishOperationStatus);
            forceHeavySelectedValidation = true;
            refreshSnapshot(true);
        });
    }

    public void importAsync(Path source) {
        String requestedName = source == null || source.getFileName() == null ? "model" : source.getFileName().toString();
        AtomicReference<String> completionStatus = new AtomicReference<>("");
        submit("Importing %s...".formatted(requestedName), () -> {
            installer.importFolder(source, sphinx4Root(), requestedName, disposed::get, status -> {
                if (StringUtils.startsWith(status, "Imported ")) {
                    completionStatus.set(status);
                }
                publishOperationStatus(status);
            });
            forceHeavySelectedValidation = true;
            refreshSnapshot(true);
        }, completionStatus::get);
    }

    public void deleteAsync(String modelId) {
        Sphinx4InstalledModel model = installedModel(modelId).orElseThrow(() -> new IllegalArgumentException("Unknown installed Sphinx4 model: %s".formatted(modelId)));
        submit("Deleting %s...".formatted(model.label()), () -> {
            installer.deleteInstalled(model, sphinx4Root());
            if (Objects.equals(selectedModelId(), model.id())) {
                clearSelection();
            }
            refreshSnapshot(false);
        });
    }

    public void selectModelAsync(String modelId) {
        Sphinx4InstalledModel model = selectableInstalledModel(modelId);
        submit("Selecting %s...".formatted(model.label()), () -> {
            Sphinx4InstalledModel validated = validateInstalledModelForSelection(modelId);
            persistSelectedModel(validated);
            refreshSnapshot(true);
        });
    }

    public void clearSelection() throws Exception {
        settingsRepo.updateBatch(batch -> {
            batch.remove(SettingsKeys.sttModelIdKey(PROVIDER_ID));
            batch.remove(SettingsKeys.sttModelLabelKey(PROVIDER_ID));
            batch.remove(FINGERPRINT_KEY);
            batch.remove(ROOT_KEY);
        });
        refreshSnapshot(false);
    }

    public Optional<Sphinx4InstalledModel> installedModel(String modelId) {
        return snapshot.installedModels().stream()
                .filter(model -> Objects.equals(model.id(), modelId))
                .findFirst();
    }

    public void validateSelectedNow() {
        try {
            forceHeavySelectedValidation = true;
            refreshSnapshot(true);
        } catch (Exception e) {
            throw new IllegalStateException(StringUtils.defaultIfBlank(e.getMessage(), "Could not validate Sphinx4 model."), e);
        } finally {
            forceHeavySelectedValidation = false;
        }
        Sphinx4ModelManagementSnapshot current = snapshot;
        if (current.selectedModel() == null) {
            throw new IllegalStateException("Download or add a Sphinx4 model to enable transcription.");
        }
        if (!current.readyToTranscribe()) {
            throw new IllegalStateException(current.statusMessage());
        }
    }

    public Path sphinx4Root() {
        return modelDirectory.resolve().resolve(PROVIDER_ID).toAbsolutePath().normalize();
    }

    @Override
    public void close() {
        if (disposed.compareAndSet(false, true)) {
            Future<?> operation = activeOperation;
            if (operation != null) {
                operation.cancel(true);
            }
            executor.shutdownNow();
            listeners.clear();
        }
    }

    private Sphinx4InstalledModel selectableInstalledModel(String modelId) {
        return installedModel(modelId)
                .filter(Sphinx4InstalledModel::selectable)
                .orElseThrow(() -> new IllegalArgumentException("Select an installed Sphinx4 model first."));
    }

    private Sphinx4InstalledModel validateInstalledModelForSelection(String modelId) throws Exception {
        List<Sphinx4ModelCatalogEntry> catalog = bundledCatalogLoader.load();
        List<Sphinx4InstalledModel> installed = scanner.scan(sphinx4Root(), catalog, true, selectedModelId(), modelId);
        Sphinx4InstalledModel validated = installed.stream()
                .filter(model -> Objects.equals(model.id(), modelId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Select an installed Sphinx4 model first."));
        publish(new Sphinx4ModelManagementSnapshot(
                sphinx4Root(),
                tempDirectory,
                List.copyOf(catalog),
                List.copyOf(installed),
                rows(catalog, installed, selectedInstalledModel(installed).orElse(null)),
                selectedInstalledModel(installed).orElse(null),
                true,
                statusMessage(installed, selectedInstalledModel(installed).orElse(null)),
                true,
                "Validating %s...".formatted(validated.label())
        ));
        if (!validated.ready()) {
            throw new IllegalArgumentException(StringUtils.defaultIfBlank(validated.validationMessage(), "Sphinx4 model did not validate."));
        }
        return validated;
    }

    private void persistSelectedModel(Sphinx4InstalledModel model) {
        settingsRepo.updateBatch(batch -> {
            batch.put(SettingsKeys.sttModelIdKey(PROVIDER_ID), model.id());
            batch.put(SettingsKeys.sttModelLabelKey(PROVIDER_ID), model.label());
            batch.put(FINGERPRINT_KEY, model.fingerprint());
            batch.put(ROOT_KEY, sphinx4Root().toString());
        });
    }

    private void submit(String status, ThrowingRunnable action) {
        submit(status, action, () -> "");
    }

    private void submit(String status, ThrowingRunnable action, Supplier<String> completionStatus) {
        if (disposed.get()) {
            return;
        }
        synchronized (this) {
            if (activeOperation != null && !activeOperation.isDone()) {
                throw new IllegalStateException("Another Sphinx4 model operation is already in progress.");
            }
            publish(snapshotWithOperation(snapshot, true, status));
            activeOperation = executor.submit(() -> {
                try {
                    installer.cleanupStalePartials(sphinx4Root(), tempDirectory);
                    action.run();
                } catch (Exception e) {
                    publish(snapshotWithOperation(snapshot, false, StringUtils.defaultIfBlank(e.getMessage(), "Sphinx4 model operation failed.")));
                    return;
                }
                publish(snapshotWithOperation(snapshot, false, StringUtils.defaultString(completionStatus.get())));
            });
        }
    }

    private void refreshSnapshot(boolean heavySelectedValidation) throws Exception {
        List<Sphinx4ModelCatalogEntry> catalog = bundledCatalogLoader.load();
        Path root = sphinx4Root();
        String selectedId = selectedModelId();
        List<Sphinx4InstalledModel> installed = scanner.scan(root, catalog, heavySelectedValidation || forceHeavySelectedValidation, selectedId);
        Sphinx4InstalledModel selected = selectedInstalledModel(installed).orElse(null);
        String status = statusMessage(installed, selected);
        publish(new Sphinx4ModelManagementSnapshot(
                root,
                tempDirectory,
                List.copyOf(catalog),
                List.copyOf(installed),
                rows(catalog, installed, selected),
                selected,
                true,
                status,
                snapshot.operationInProgress(),
                snapshot.operationStatus()
        ));
    }

    private Optional<Sphinx4InstalledModel> selectedInstalledModel(List<Sphinx4InstalledModel> installed) {
        String selectedId = selectedModelId();
        if (StringUtils.isBlank(selectedId)) {
            return installed.stream().filter(Sphinx4InstalledModel::ready).findFirst();
        }
        String savedRoot = settingsRepo.get(ROOT_KEY, "");
        String savedFingerprint = settingsRepo.get(FINGERPRINT_KEY, "");
        return installed.stream()
                .filter(model -> Objects.equals(model.id(), selectedId))
                .filter(model -> !model.custom() || StringUtils.isBlank(savedRoot) || Objects.equals(savedRoot, sphinx4Root().toString()))
                .filter(model -> !model.custom() || StringUtils.isBlank(savedFingerprint) || Objects.equals(savedFingerprint, model.fingerprint()))
                .findFirst();
    }

    private String selectedModelId() {
        return settingsRepo.get(SettingsKeys.sttModelIdKey(PROVIDER_ID), "");
    }

    private String statusMessage(List<Sphinx4InstalledModel> installed, Sphinx4InstalledModel selected) {
        if (installed.isEmpty()) {
            return "Download or add a Sphinx4 model to enable transcription.";
        }
        if (selected == null) {
            return "Select an installed Sphinx4 model to enable transcription.";
        }
        if (!selected.ready()) {
            return selected.validationMessage();
        }
        return "Using Sphinx4 model %s locally.".formatted(selected.label());
    }

    private List<Sphinx4LocalModelRow> rows(List<Sphinx4ModelCatalogEntry> catalog, List<Sphinx4InstalledModel> installed, Sphinx4InstalledModel selected) {
        Map<String, Sphinx4InstalledModel> installedByOfficialId = new LinkedHashMap<>();
        installed.stream().filter(model -> !model.custom()).forEach(model -> installedByOfficialId.put(model.id(), model));
        List<Sphinx4LocalModelRow> rows = new ArrayList<>();
        catalog.forEach(entry -> {
            Sphinx4InstalledModel installedModel = installedByOfficialId.get(entry.id());
            rows.add(new Sphinx4LocalModelRow(
                    entry.id(),
                    entry.label(),
                    entry.displayLanguage(),
                    entry.verifiedDownload() ? "verified" : "catalog",
                    entry.displaySize(),
                    entry,
                    installedModel,
                    selected != null && Objects.equals(selected.id(), entry.id()),
                    installedModel == null && entry.canDownload(),
                    installedModel != null && installedModel.deleteable(),
                    installedModel == null ? remoteStatus(entry) : installedModel.validationMessage()
            ));
        });
        installed.stream().filter(Sphinx4InstalledModel::custom).forEach(model -> rows.add(new Sphinx4LocalModelRow(
                model.id(),
                model.label(),
                model.language(),
                "local",
                "",
                null,
                model,
                selected != null && Objects.equals(selected.id(), model.id()),
                false,
                model.deleteable(),
                model.validationMessage()
        )));
        return List.copyOf(rows);
    }

    private String remoteStatus(Sphinx4ModelCatalogEntry entry) {
        return entry.canDownload() ? "Available to download" : "Catalog only; manual import required";
    }

    private void publishOperationStatus(String status) {
        publish(snapshotWithOperation(snapshot, true, status));
    }

    private Sphinx4ModelManagementSnapshot snapshotWithOperation(Sphinx4ModelManagementSnapshot base, boolean inProgress, String status) {
        return new Sphinx4ModelManagementSnapshot(
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

    private void publish(Sphinx4ModelManagementSnapshot next) {
        if (disposed.get()) {
            return;
        }
        snapshot = next;
        listeners.forEach(listener -> listener.accept(next));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
