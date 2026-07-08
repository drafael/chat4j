package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.SpeechToTextProviderRegistry;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogStore;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import com.github.drafael.chat4j.stt.provider.SpeechToTextResult;
import com.github.drafael.chat4j.stt.provider.vosk.VoskInstalledModel;
import com.github.drafael.chat4j.stt.provider.vosk.VoskLocalModelRow;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementService;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementSnapshot;
import com.github.drafael.chat4j.stt.provider.vosk.VoskValidationStatus;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class SpeechToTextPanelTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Stale failed Speech to Text saves do not override newer successful saves")
    void savePendingChanges_whenStaleSaveFailsAfterNewerSuccess_ignoresStaleFailure() throws Exception {
        var subject = new SpeechToTextPanel(new SettingsRepository(tempDir.resolve("settings.properties")), tempDir.resolve("default-models"));
        var firstSaveStarted = new CountDownLatch(1);
        var releaseFirstSave = new CountDownLatch(1);
        var secondSaveSucceeded = new CountDownLatch(1);

        scheduleSave(subject, () -> {
            firstSaveStarted.countDown();
            assertThat(releaseFirstSave.await(2, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("stale failure");
        }, () -> {
        });
        assertThat(firstSaveStarted.await(2, TimeUnit.SECONDS)).isTrue();

        scheduleSave(subject, () -> {
        }, secondSaveSucceeded::countDown);
        assertThat(secondSaveSucceeded.await(2, TimeUnit.SECONDS)).isTrue();
        releaseFirstSave.countDown();

        assertThat(subject.savePendingChanges()).isTrue();
        assertThat(subject.lastSaveError()).isBlank();
    }

    @Test
    @DisplayName("Failed Speech to Text directory saves do not poison later successful saves")
    void savePendingChanges_whenDirectorySaveFailsThenSucceeds_allowsCloseAfterCorrection() throws Exception {
        Path settingsFile = tempDir.resolve("settings.properties");
        Path existingFile = Files.writeString(tempDir.resolve("not-a-directory"), "content");
        Path validDirectory = tempDir.resolve("models");
        var subject = new SpeechToTextPanel(new SettingsRepository(settingsFile), tempDir.resolve("default-models"));

        SwingUtilities.invokeAndWait(() -> setModelDirectoryAndSave(subject, existingFile));
        waitUntil(() -> !subject.lastSaveError().isBlank());
        assertThat(subject.savePendingChanges()).isFalse();

        SwingUtilities.invokeAndWait(() -> setModelDirectoryAndSave(subject, validDirectory));

        assertThat(subject.savePendingChanges()).isTrue();
        assertThat(Files.isDirectory(validDirectory)).isTrue();
    }

    @Test
    @DisplayName("Local model controls are hidden for cloud Speech to Text providers")
    void refreshControlsFromSettings_whenProviderDoesNotSupportLocalModels_hidesLocalModelControls() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_GROQ);
        var subject = new SpeechToTextPanel(repo, tempDir.resolve("default-models"));

        JPanel localModelsPanel = (JPanel) fieldValue(subject, "localModelsPanel");

        assertThat(localModelsPanel.isVisible()).isFalse();
    }

    @Test
    @DisplayName("Vosk local model table reports Boolean classes only for Boolean columns")
    void refreshControlsFromSettings_whenVoskSelected_usesCorrectColumnClasses() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_VOSK);
        var subject = new SpeechToTextPanel(repo, tempDir.resolve("default-models"));

        JTable localModelsTable = (JTable) fieldValue(subject, "localModelsTable");

        assertThat(localModelsTable.getColumnClass(0)).isEqualTo(String.class);
        assertThat(localModelsTable.getColumnClass(1)).isEqualTo(String.class);
        assertThat(localModelsTable.getColumnClass(2)).isEqualTo(String.class);
        assertThat(localModelsTable.getColumnClass(3)).isEqualTo(String.class);
        assertThat(localModelsTable.getColumnClass(4)).isEqualTo(Boolean.class);
        assertThat(localModelsTable.getColumnClass(5)).isEqualTo(Boolean.class);
        assertThat(localModelsTable.getColumnClass(6)).isEqualTo(String.class);
    }

    @Test
    @DisplayName("Plausible Vosk models appear in table but not dropdown")
    void refreshControlsFromSettings_whenVoskModelIsPlausibleUnverified_excludesFromModelDropdown() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-plausible.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_VOSK);
        Path models = tempDir.resolve("vosk-plausible-models");
        Files.createDirectories(models.resolve("vosk").resolve("custom-plausible").resolve("am"));
        var voskModels = new VoskModelManagementService(repo, models, tempDir.resolve("vosk-plausible-temp"));
        voskModels.refreshAsync();
        waitUntil(() -> !voskModels.snapshot().rows().isEmpty());

        var subject = new SpeechToTextPanel(repo, models, voskModels);
        @SuppressWarnings("unchecked")
        JComboBox<SpeechToTextCatalogItem> modelComboBox = (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
        JTable localModelsTable = (JTable) fieldValue(subject, "localModelsTable");

        int plausibleRow = tableRowWithModel(localModelsTable, "custom-plausible");
        assertThat(modelComboBox.getItemCount()).isZero();
        assertThat(plausibleRow).isGreaterThanOrEqualTo(0);
        assertThat(localModelsTable.getValueAt(plausibleRow, 4)).isEqualTo(Boolean.TRUE);
        assertThat(localModelsTable.getValueAt(plausibleRow, 5)).isEqualTo(Boolean.FALSE);
        voskModels.close();
    }

    @Test
    @DisplayName("Vosk model directory changes are blocked while a model operation is active")
    void saveModelDirectory_whenVoskOperationInProgress_rejectsDirectoryChange() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-busy.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_VOSK);
        Path models = tempDir.resolve("busy-models");
        Path requested = tempDir.resolve("other-models");
        var voskModels = new BusyVoskModelManagementService(repo, models, tempDir.resolve("busy-temp"));
        var subject = new SpeechToTextPanel(repo, models, voskModels);
        JTextField modelDirectoryField = (JTextField) fieldValue(subject, "modelDirectoryField");
        JButton browseButton = (JButton) fieldValue(subject, "modelDirectoryBrowseButton");

        assertThat(modelDirectoryField.isEnabled()).isFalse();
        assertThat(browseButton.isEnabled()).isFalse();

        SwingUtilities.invokeAndWait(() -> setModelDirectoryAndSave(subject, requested));

        assertThat(repo.get(SettingsKeys.STT_MODELS_DIR, "")).isBlank();
        assertThat(modelDirectoryField.getText()).isEqualTo(models.toString());
        voskModels.close();
    }

    @Test
    @DisplayName("Vosk model selection starts an async operation instead of saving on the UI handler")
    void onModelSelected_whenVoskModelSelected_startsAsyncSelectionOperation() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-select.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_VOSK);
        Path models = tempDir.resolve("select-models");
        var voskModels = new AsyncSelectionVoskModelManagementService(repo, models, tempDir.resolve("select-temp"));
        var subject = new SpeechToTextPanel(repo, models, voskModels);
        @SuppressWarnings("unchecked")
        JComboBox<SpeechToTextCatalogItem> modelComboBox = (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
        JButton refreshButton = (JButton) fieldValue(subject, "refreshButton");

        SwingUtilities.invokeAndWait(() -> invokePanelMethod(subject, "onModelSelected"));

        assertThat(voskModels.selectedModelId()).isEqualTo("custom-ready");
        assertThat(repo.get(SettingsKeys.sttModelIdKey(VoskModelManagementService.PROVIDER_ID), "")).isBlank();
        assertThat(modelComboBox.isEnabled()).isFalse();
        assertThat(refreshButton.isEnabled()).isFalse();
        voskModels.close();
    }

    @Test
    @DisplayName("Default Speech to Text providers include Deepgram in the intended order")
    void reloadProviderOptions_whenDefaultRegistryLoaded_ordersDeepgramBetweenElevenLabsAndVosk() throws Exception {
        var subject = new SpeechToTextPanel(new SettingsRepository(tempDir.resolve("settings-provider-order.properties")), tempDir.resolve("default-models"));
        @SuppressWarnings("unchecked")
        JComboBox<Object> providerComboBox = (JComboBox<Object>) fieldValue(subject, "providerComboBox");

        assertThat(providerComboBox.getItemCount()).isEqualTo(5);
        assertThat(providerId(providerComboBox.getItemAt(0))).isEqualTo("off");
        assertThat(providerId(providerComboBox.getItemAt(1))).isEqualTo("groq");
        assertThat(providerId(providerComboBox.getItemAt(2))).isEqualTo("elevenlabs");
        assertThat(providerId(providerComboBox.getItemAt(3))).isEqualTo("deepgram");
        assertThat(providerId(providerComboBox.getItemAt(4))).isEqualTo("vosk");
    }

    @Test
    @DisplayName("ElevenLabs catalog refresh uses ElevenLabs endpoint context")
    void refreshCatalogs_whenElevenLabsSelected_usesElevenLabsEndpointContext() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs-refresh.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_ELEVENLABS);
        var provider = new CapturingElevenLabsProvider();
        var subject = new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider)));

        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> repo.get(SettingsKeys.sttCatalogModelsKey(SettingsKeys.STT_PROVIDER_ELEVENLABS), "").contains("scribe_v2"));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertThat(provider.context.get().baseUri()).hasToString("https://api.elevenlabs.io");
        assertThat(provider.context.get().transcriptionUri()).hasToString("https://api.elevenlabs.io/v1/speech-to-text");
        subject.removeNotify();
    }

    @Test
    @DisplayName("In-flight ElevenLabs catalog refresh does not persist after panel removal")
    void refreshCatalogs_whenPanelRemovedBeforeElevenLabsRefreshCompletes_doesNotPersistCatalog() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs-stale-refresh.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_ELEVENLABS);
        var provider = new BlockingElevenLabsProvider();
        var subject = new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider)));

        assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
        subject.removeNotify();
        provider.release.countDown();
        Thread refreshThread = provider.refreshThread.get();
        assertThat(refreshThread).isNotNull();
        refreshThread.join(2_000);
        assertThat(refreshThread.isAlive()).isFalse();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertThat(repo.get(SettingsKeys.sttCatalogModelsKey(SettingsKeys.STT_PROVIDER_ELEVENLABS), "")).isBlank();
        assertThat(repo.get(SettingsKeys.sttCatalogUpdatedAtKey(SettingsKeys.STT_PROVIDER_ELEVENLABS), "")).isBlank();
    }

    @Test
    @DisplayName("Failed explicit ElevenLabs refresh preserves cached catalog")
    void refreshCatalogs_whenElevenLabsRefreshFails_preservesCachedCatalog() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs-cache.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_ELEVENLABS);
        var cached = List.of(SpeechToTextCatalogItem.of("scribe_v2", "Scribe v2"), SpeechToTextCatalogItem.of("scribe_v1", "Scribe v1 (deprecated)"));
        new SpeechToTextCatalogStore(repo).saveModels(SettingsKeys.STT_PROVIDER_ELEVENLABS, cached);
        String originalModels = repo.get(SettingsKeys.sttCatalogModelsKey(SettingsKeys.STT_PROVIDER_ELEVENLABS), "");
        String originalUpdatedAt = repo.get(SettingsKeys.sttCatalogUpdatedAtKey(SettingsKeys.STT_PROVIDER_ELEVENLABS), "");
        var provider = new FailingElevenLabsProvider();
        var subject = new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider)));

        SwingUtilities.invokeAndWait(() -> invokePanelMethod(subject, "refreshCatalogs", true));
        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertThat(repo.get(SettingsKeys.sttCatalogModelsKey(SettingsKeys.STT_PROVIDER_ELEVENLABS), "")).isEqualTo(originalModels);
        assertThat(repo.get(SettingsKeys.sttCatalogUpdatedAtKey(SettingsKeys.STT_PROVIDER_ELEVENLABS), "")).isEqualTo(originalUpdatedAt);
        waitUntil(() -> subject.statusLabel().getText().contains("Could not refresh ElevenLabs Speech to Text models."));
        subject.removeNotify();
    }

    @Test
    @DisplayName("Failed automatic ElevenLabs refresh preserves cached catalog without noisy status")
    void refreshControlsFromSettings_whenAutomaticElevenLabsRefreshFails_preservesCacheQuietly() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs-auto-cache.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_ELEVENLABS);
        var cached = List.of(SpeechToTextCatalogItem.of("scribe_v2", "Scribe v2"), SpeechToTextCatalogItem.of("scribe_v1", "Scribe v1 (deprecated)"));
        new SpeechToTextCatalogStore(repo).saveModels(SettingsKeys.STT_PROVIDER_ELEVENLABS, cached);
        repo.put(SettingsKeys.sttCatalogUpdatedAtKey(SettingsKeys.STT_PROVIDER_ELEVENLABS), "2000-01-01T00:00:00Z");
        String originalModels = repo.get(SettingsKeys.sttCatalogModelsKey(SettingsKeys.STT_PROVIDER_ELEVENLABS), "");
        String originalUpdatedAt = repo.get(SettingsKeys.sttCatalogUpdatedAtKey(SettingsKeys.STT_PROVIDER_ELEVENLABS), "");
        var provider = new FailingElevenLabsProvider();
        var subject = new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider)));

        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertThat(repo.get(SettingsKeys.sttCatalogModelsKey(SettingsKeys.STT_PROVIDER_ELEVENLABS), "")).isEqualTo(originalModels);
        assertThat(repo.get(SettingsKeys.sttCatalogUpdatedAtKey(SettingsKeys.STT_PROVIDER_ELEVENLABS), "")).isEqualTo(originalUpdatedAt);
        assertThat(subject.statusLabel().getText()).doesNotContain("Could not refresh ElevenLabs Speech to Text models.");
        subject.removeNotify();
    }

    @Test
    @DisplayName("Deepgram catalog refresh uses Deepgram endpoint context")
    void refreshCatalogs_whenDeepgramSelected_usesDeepgramEndpointContext() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-deepgram-refresh.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_DEEPGRAM);
        var provider = new CapturingDeepgramProvider();
        var subject = new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider)));

        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> repo.get(SettingsKeys.sttCatalogModelsKey(SettingsKeys.STT_PROVIDER_DEEPGRAM), "").contains("nova-3"));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertThat(provider.context.get().baseUri()).hasToString("https://api.deepgram.com");
        assertThat(provider.context.get().transcriptionUri()).hasToString("https://api.deepgram.com/v1/listen");
        subject.removeNotify();
    }

    @Test
    @DisplayName("Failed explicit Deepgram refresh preserves cached catalog")
    void refreshCatalogs_whenDeepgramRefreshFails_preservesCachedCatalog() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-deepgram-cache.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_DEEPGRAM);
        var cached = List.of(SpeechToTextCatalogItem.of("nova-3", "Deepgram Nova 3"), SpeechToTextCatalogItem.of("nova-2-general", "Deepgram Nova 2 General"));
        new SpeechToTextCatalogStore(repo).saveModels(SettingsKeys.STT_PROVIDER_DEEPGRAM, cached);
        String originalModels = repo.get(SettingsKeys.sttCatalogModelsKey(SettingsKeys.STT_PROVIDER_DEEPGRAM), "");
        String originalUpdatedAt = repo.get(SettingsKeys.sttCatalogUpdatedAtKey(SettingsKeys.STT_PROVIDER_DEEPGRAM), "");
        var provider = new FailingDeepgramProvider();
        var subject = new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider)));

        SwingUtilities.invokeAndWait(() -> invokePanelMethod(subject, "refreshCatalogs", true));
        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertThat(repo.get(SettingsKeys.sttCatalogModelsKey(SettingsKeys.STT_PROVIDER_DEEPGRAM), "")).isEqualTo(originalModels);
        assertThat(repo.get(SettingsKeys.sttCatalogUpdatedAtKey(SettingsKeys.STT_PROVIDER_DEEPGRAM), "")).isEqualTo(originalUpdatedAt);
        waitUntil(() -> subject.statusLabel().getText().contains("Could not refresh Deepgram Speech to Text models."));
        subject.removeNotify();
    }

    @Test
    @DisplayName("Failed automatic Deepgram refresh preserves cached catalog without noisy status")
    void refreshControlsFromSettings_whenAutomaticDeepgramRefreshFails_preservesCacheQuietly() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-deepgram-auto-cache.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_DEEPGRAM);
        var cached = List.of(SpeechToTextCatalogItem.of("nova-3", "Deepgram Nova 3"), SpeechToTextCatalogItem.of("nova-2-general", "Deepgram Nova 2 General"));
        new SpeechToTextCatalogStore(repo).saveModels(SettingsKeys.STT_PROVIDER_DEEPGRAM, cached);
        repo.put(SettingsKeys.sttCatalogUpdatedAtKey(SettingsKeys.STT_PROVIDER_DEEPGRAM), "2000-01-01T00:00:00Z");
        String originalModels = repo.get(SettingsKeys.sttCatalogModelsKey(SettingsKeys.STT_PROVIDER_DEEPGRAM), "");
        String originalUpdatedAt = repo.get(SettingsKeys.sttCatalogUpdatedAtKey(SettingsKeys.STT_PROVIDER_DEEPGRAM), "");
        var provider = new FailingDeepgramProvider();
        var subject = new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider)));

        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertThat(repo.get(SettingsKeys.sttCatalogModelsKey(SettingsKeys.STT_PROVIDER_DEEPGRAM), "")).isEqualTo(originalModels);
        assertThat(repo.get(SettingsKeys.sttCatalogUpdatedAtKey(SettingsKeys.STT_PROVIDER_DEEPGRAM), "")).isEqualTo(originalUpdatedAt);
        assertThat(subject.statusLabel().getText()).doesNotContain("Could not refresh Deepgram Speech to Text models.");
        subject.removeNotify();
    }

    @Test
    @DisplayName("Deepgram helper copy explains cloud upload and key storage")
    void updateAvailability_whenDeepgramAvailable_showsDeepgramPrivacyCopy() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-deepgram-helper.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, SettingsKeys.STT_PROVIDER_DEEPGRAM);
        new SpeechToTextCatalogStore(repo).saveModels(SettingsKeys.STT_PROVIDER_DEEPGRAM, List.of(SpeechToTextCatalogItem.of("nova-3", "Deepgram Nova 3")));
        var subject = new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(new DeepgramTestProvider())));
        SwingUtilities.invokeAndWait(() -> {
        });
        var helperLabel = (JLabel) fieldValue(subject, "helperLabel");

        assertThat(helperLabel.getText()).isEqualTo("Recorded audio is sent to Deepgram for transcription. No API key is stored by Chat4J.");
        subject.removeNotify();
    }

    @Test
    @DisplayName("Local model controls show selectable models for local Speech to Text providers")
    void refreshControlsFromSettings_whenProviderSupportsLocalModels_showsLocalModelList() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, "local-test");
        var subject = new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(new LocalTestSpeechToTextProvider()))
        );
        try {
            JPanel localModelsPanel = (JPanel) fieldValue(subject, "localModelsPanel");
            JTable localModelsTable = (JTable) fieldValue(subject, "localModelsTable");
            JButton downloadModelButton = (JButton) fieldValue(subject, "downloadModelButton");

            assertThat(localModelsPanel.isVisible()).isTrue();
            assertThat(localModelsTable.getRowCount()).isEqualTo(2);
            assertThat(localModelsTable.getValueAt(0, 0)).isEqualTo("Local Tiny");
            assertThat(localModelsTable.getValueAt(0, 1)).isEqualTo(Boolean.TRUE);
            assertThat(downloadModelButton.isEnabled()).isTrue();
        } finally {
            subject.removeNotify();
        }
    }

    private int tableRowWithModel(JTable table, String model) {
        for (int row = 0; row < table.getRowCount(); row++) {
            if (model.equals(table.getValueAt(row, 0))) {
                return row;
            }
        }
        return -1;
    }

    private void setModelDirectoryAndSave(SpeechToTextPanel subject, Path path) {
        try {
            JTextField field = (JTextField) fieldValue(subject, "modelDirectoryField");
            field.setText(path.toString());
            Method method = SpeechToTextPanel.class.getDeclaredMethod("saveModelDirectory");
            method.setAccessible(true);
            method.invoke(subject);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void invokePanelMethod(SpeechToTextPanel subject, String name) {
        try {
            Method method = SpeechToTextPanel.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(subject);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void invokePanelMethod(SpeechToTextPanel subject, String name, boolean argument) {
        try {
            Method method = SpeechToTextPanel.class.getDeclaredMethod(name, boolean.class);
            method.setAccessible(true);
            method.invoke(subject, argument);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String providerId(Object providerOption) throws Exception {
        Method method = providerOption.getClass().getDeclaredMethod("providerId");
        method.setAccessible(true);
        return (String) method.invoke(providerOption);
    }

    private void scheduleSave(SpeechToTextPanel subject, ThrowingAction action, Runnable onSuccess) throws Exception {
        Class<?> throwingRunnableClass = Class.forName("com.github.drafael.chat4j.settings.SpeechToTextPanel$ThrowingRunnable");
        Object throwingRunnable = Proxy.newProxyInstance(
                throwingRunnableClass.getClassLoader(),
                new Class<?>[] {throwingRunnableClass},
                (proxy, method, args) -> {
                    action.run();
                    return null;
                }
        );
        Method method = SpeechToTextPanel.class.getDeclaredMethod("scheduleSave", throwingRunnableClass, Runnable.class);
        method.setAccessible(true);
        method.invoke(subject, throwingRunnable, onSuccess);
    }

    private void waitUntil(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.met() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.met()).isTrue();
    }

    private Object fieldValue(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class BusyVoskModelManagementService extends VoskModelManagementService {
        private final VoskModelManagementSnapshot busySnapshot;

        private BusyVoskModelManagementService(SettingsRepository settingsRepo, Path modelRoot, Path tempRoot) {
            super(settingsRepo, modelRoot, tempRoot);
            busySnapshot = new VoskModelManagementSnapshot(
                    modelRoot.resolve(VoskModelManagementService.PROVIDER_ID),
                    tempRoot,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    null,
                    true,
                    "Downloading Vosk model...",
                    true,
                    "Downloading Vosk model..."
            );
        }

        @Override
        public VoskModelManagementSnapshot snapshot() {
            return busySnapshot;
        }

        @Override
        public Runnable addListener(Consumer<VoskModelManagementSnapshot> listener) {
            listener.accept(busySnapshot);
            return () -> {
            };
        }
    }

    private static final class AsyncSelectionVoskModelManagementService extends VoskModelManagementService {
        private VoskModelManagementSnapshot currentSnapshot;
        private Consumer<VoskModelManagementSnapshot> listener = snapshot -> {
        };
        private String selectedModelId = "";

        private AsyncSelectionVoskModelManagementService(SettingsRepository settingsRepo, Path modelRoot, Path tempRoot) {
            super(settingsRepo, modelRoot, tempRoot);
            Path root = modelRoot.resolve(VoskModelManagementService.PROVIDER_ID);
            Path modelDirectory = root.resolve("custom-ready");
            var installedModel = new VoskInstalledModel(
                    "custom-ready",
                    "Custom Ready",
                    modelDirectory,
                    modelDirectory,
                    null,
                    true,
                    false,
                    true,
                    VoskValidationStatus.VALID,
                    "Ready",
                    "fingerprint"
            );
            var row = new VoskLocalModelRow(
                    installedModel.id(),
                    installedModel.label(),
                    "Custom",
                    "local",
                    "",
                    null,
                    installedModel,
                    false,
                    false,
                    true,
                    false,
                    "Ready"
            );
            currentSnapshot = new VoskModelManagementSnapshot(
                    root,
                    tempRoot,
                    emptyList(),
                    List.of(installedModel),
                    List.of(row),
                    null,
                    true,
                    "Select an installed Vosk model to enable transcription.",
                    false,
                    ""
            );
        }

        @Override
        public VoskModelManagementSnapshot snapshot() {
            return currentSnapshot;
        }

        @Override
        public Runnable addListener(Consumer<VoskModelManagementSnapshot> listener) {
            this.listener = listener;
            listener.accept(currentSnapshot);
            return () -> this.listener = snapshot -> {
            };
        }

        @Override
        public void selectModel(String modelId) {
            throw new AssertionError("Vosk selection should use the async operation path.");
        }

        @Override
        public void selectModelAsync(String modelId) {
            selectedModelId = modelId;
            currentSnapshot = new VoskModelManagementSnapshot(
                    currentSnapshot.modelRoot(),
                    currentSnapshot.tempRoot(),
                    currentSnapshot.catalog(),
                    currentSnapshot.installedModels(),
                    currentSnapshot.rows(),
                    currentSnapshot.selectedModel(),
                    currentSnapshot.runtimeReady(),
                    currentSnapshot.statusMessage(),
                    true,
                    "Selecting Custom Ready..."
            );
            listener.accept(currentSnapshot);
        }

        private String selectedModelId() {
            return selectedModelId;
        }
    }

    private static class ElevenLabsTestProvider implements SpeechToTextProvider {

        @Override
        public String id() {
            return SettingsKeys.STT_PROVIDER_ELEVENLABS;
        }

        @Override
        public String displayName() {
            return "ElevenLabs";
        }

        @Override
        public String requiredEnvVar() {
            return "";
        }

        @Override
        public SpeechToTextCatalogItem defaultModel() {
            return SpeechToTextCatalogItem.of("scribe_v2", "Scribe v2");
        }

        @Override
        public List<SpeechToTextCatalogItem> bundledModels() {
            return List.of(defaultModel());
        }

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) throws Exception {
            return bundledModels();
        }

        @Override
        public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) {
            return new SpeechToTextResult("test");
        }
    }

    private static final class CapturingElevenLabsProvider extends ElevenLabsTestProvider {
        private final CountDownLatch refreshed = new CountDownLatch(1);
        private final AtomicReference<SpeechToTextProviderContext> context = new AtomicReference<>();

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
            this.context.set(context);
            refreshed.countDown();
            return List.of(SpeechToTextCatalogItem.of("scribe_v2", "Scribe v2"));
        }
    }

    private static final class BlockingElevenLabsProvider extends ElevenLabsTestProvider {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<Thread> refreshThread = new AtomicReference<>();

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) throws Exception {
            refreshThread.set(Thread.currentThread());
            started.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release catalog refresh.");
            }
            return List.of(SpeechToTextCatalogItem.of("stale-scribe", "Stale Scribe"));
        }
    }

    private static final class FailingElevenLabsProvider extends ElevenLabsTestProvider {
        private final CountDownLatch refreshed = new CountDownLatch(1);

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) throws Exception {
            refreshed.countDown();
            throw new IllegalStateException("catalog unavailable");
        }
    }

    private static class DeepgramTestProvider implements SpeechToTextProvider {

        @Override
        public String id() {
            return SettingsKeys.STT_PROVIDER_DEEPGRAM;
        }

        @Override
        public String displayName() {
            return "Deepgram";
        }

        @Override
        public String requiredEnvVar() {
            return "";
        }

        @Override
        public SpeechToTextCatalogItem defaultModel() {
            return SpeechToTextCatalogItem.of("nova-3", "Deepgram Nova 3");
        }

        @Override
        public List<SpeechToTextCatalogItem> bundledModels() {
            return List.of(defaultModel());
        }

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) throws Exception {
            return bundledModels();
        }

        @Override
        public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) {
            return new SpeechToTextResult("test");
        }
    }

    private static final class CapturingDeepgramProvider extends DeepgramTestProvider {
        private final CountDownLatch refreshed = new CountDownLatch(1);
        private final AtomicReference<SpeechToTextProviderContext> context = new AtomicReference<>();

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
            this.context.set(context);
            refreshed.countDown();
            return List.of(SpeechToTextCatalogItem.of("nova-3", "Deepgram Nova 3"));
        }
    }

    private static final class FailingDeepgramProvider extends DeepgramTestProvider {
        private final CountDownLatch refreshed = new CountDownLatch(1);

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) throws Exception {
            refreshed.countDown();
            throw new IllegalStateException("catalog unavailable");
        }
    }

    private static final class LocalTestSpeechToTextProvider implements SpeechToTextProvider {

        @Override
        public String id() {
            return "local-test";
        }

        @Override
        public String displayName() {
            return "Local Test";
        }

        @Override
        public String requiredEnvVar() {
            return "";
        }

        @Override
        public boolean supportsLocalModels() {
            return true;
        }

        @Override
        public SpeechToTextCatalogItem defaultModel() {
            return bundledModels().getFirst();
        }

        @Override
        public List<SpeechToTextCatalogItem> bundledModels() {
            return List.of(
                    SpeechToTextCatalogItem.of("local-tiny", "Local Tiny"),
                    SpeechToTextCatalogItem.of("local-base", "Local Base")
            );
        }

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
            return bundledModels();
        }

        @Override
        public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) {
            return new SpeechToTextResult("test");
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean met();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
