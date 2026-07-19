package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.catalog.CatalogSnapshotStore;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.SpeechToTextProviderRegistry;
import com.github.drafael.chat4j.stt.SpeechToTextSettings;
import com.github.drafael.chat4j.stt.SpeechToTextSettingsSnapshot;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogStore;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import com.github.drafael.chat4j.stt.provider.SpeechToTextResult;
import com.github.drafael.chat4j.stt.model.SpeechToTextModelDirectory;
import com.github.drafael.chat4j.stt.provider.assemblyai.AssemblyAiSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.deepgram.DeepgramSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.elevenlabs.ElevenLabsSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.groq.GroqSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.vosk.VoskInstalledModel;
import com.github.drafael.chat4j.stt.provider.vosk.VoskLocalModelRow;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementService;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementSnapshot;
import com.github.drafael.chat4j.stt.provider.vosk.VoskValidationStatus;
import com.github.drafael.chat4j.stt.provider.vosk.VoskSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperBinding;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperContextHandle;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementService;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelUsageTracker;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperNativeRuntime;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperSpeechToTextProvider;
import io.github.freshsupasulley.whisperjni.WhisperFullParams;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static java.util.stream.IntStream.range;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpeechToTextPanelTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("A newer save for the same Speech to Text setting supersedes an older failure")
    void savePendingChanges_whenSameSettingSaveSupersedesFailure_reportsSuccess() throws Exception {
        var subject = callOnEdt(() -> new SpeechToTextPanel(new SettingsRepository(tempDir.resolve("settings.properties")), tempDir.resolve("default-models")));
        var firstSaveStarted = new CountDownLatch(1);
        var releaseFirstSave = new CountDownLatch(1);
        var secondSaveSucceeded = new CountDownLatch(1);

        try {
            scheduleSave(subject, () -> {
                firstSaveStarted.countDown();
                assertThat(releaseFirstSave.await(2, TimeUnit.SECONDS)).isTrue();
                throw new IllegalStateException("stale failure");
            }, () -> {
            });
            assertThat(firstSaveStarted.await(2, TimeUnit.SECONDS)).isTrue();

            scheduleSave(subject, () -> {
            }, secondSaveSucceeded::countDown);
            releaseFirstSave.countDown();
            assertThat(secondSaveSucceeded.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(subject.savePendingChanges()).isTrue();
            assertThat(subject.lastSaveError()).isBlank();
        } finally {
            releaseFirstSave.countDown();
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("A successful save does not hide an unrelated Speech to Text persistence failure")
    void savePendingChanges_whenUnrelatedSaveSucceedsAfterFailure_reportsFailure() throws Exception {
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                new SettingsRepository(tempDir.resolve("settings-unrelated-save-failure.properties")),
                tempDir.resolve("default-models")
        ));
        var failedSaveStarted = new CountDownLatch(1);
        var releaseFailedSave = new CountDownLatch(1);
        try {
            scheduleSave(subject, "model-directory", () -> {
                failedSaveStarted.countDown();
                assertThat(releaseFailedSave.await(2, TimeUnit.SECONDS)).isTrue();
                throw new IllegalStateException("directory save failed");
            }, () -> {
            });
            assertThat(failedSaveStarted.await(2, TimeUnit.SECONDS)).isTrue();

            scheduleSave(subject, "max-duration", () -> {
            }, () -> {
            });
            releaseFailedSave.countDown();

            assertThat(subject.savePendingChanges()).isFalse();
            assertThat(subject.lastSaveError()).isEqualTo("directory save failed");
            waitUntil(() -> statusLabelText(subject).contains("directory save failed"));
        } finally {
            releaseFailedSave.countDown();
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("Queued Speech to Text provider selections persist the latest selection")
    void savePendingChanges_whenProviderSelectionsOverlap_persistsLatestProvider() throws Exception {
        var repo = new BlockingProviderSettingsRepository(tempDir.resolve("settings-provider-race.properties"));
        Path defaultModels = tempDir.resolve("default-models");
        try (
                var voskModels = new VoskModelManagementService(repo, defaultModels, tempDir.resolve("vosk-temp"));
                var whisperModels = new WhisperModelManagementService(repo, defaultModels, tempDir.resolve("whisper-temp"), fakeWhisperRuntime(), new WhisperModelUsageTracker())
        ) {
            SpeechToTextPanel subject = callOnEdt(() -> new SpeechToTextPanel(repo, defaultModels, voskModels, whisperModels));
            try {
                runOnEdt(() -> selectProvider(subject, GroqSpeechToTextProvider.ID));
                assertThat(repo.firstProviderSaveStarted.await(2, TimeUnit.SECONDS)).isTrue();

                runOnEdt(() -> selectProvider(subject, DeepgramSpeechToTextProvider.ID));
                repo.releaseFirstProviderSave.countDown();

                assertThat(subject.savePendingChanges()).isTrue();
                runOnEdt(() -> {
                });
                assertThat(repo.get(SpeechToTextSettings.PROVIDER_KEY)).contains(DeepgramSpeechToTextProvider.ID);
            } finally {
                repo.releaseFirstProviderSave.countDown();
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Selecting Vosk refreshes local models")
    void onProviderSelected_whenVoskSelected_refreshesLocalModels() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-select-refresh.properties"));
        Path models = tempDir.resolve("vosk-select-refresh-models");
        try (var voskModels = new RefreshCountingVoskModelManagementService(repo, models, tempDir.resolve("vosk-select-refresh-temp"))) {
            var subject = callOnEdt(() -> new SpeechToTextPanel(repo, models, voskModels));
            try {
                SwingUtilities.invokeAndWait(() -> selectProvider(subject, VoskSpeechToTextProvider.ID));

                assertThat(subject.savePendingChanges()).isTrue();
                assertThat(voskModels.refreshRequested.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Selecting Whisper refreshes local models")
    void onProviderSelected_whenWhisperSelected_refreshesLocalModels() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-whisper-select-refresh.properties"));
        Path models = tempDir.resolve("whisper-select-refresh-models");
        try (
                var voskModels = new VoskModelManagementService(repo, models, tempDir.resolve("vosk-select-refresh-temp"));
                var whisperModels = new RefreshCountingWhisperModelManagementService(repo, models, tempDir.resolve("whisper-select-refresh-temp"), fakeWhisperRuntime())
        ) {
            var subject = callOnEdt(() -> new SpeechToTextPanel(repo, models, voskModels, whisperModels));
            try {
                SwingUtilities.invokeAndWait(() -> selectProvider(subject, WhisperSpeechToTextProvider.ID));

                assertThat(subject.savePendingChanges()).isTrue();
                assertThat(whisperModels.refreshRequested.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(whisperModels.probeRuntime).isTrue();
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Vosk refresh failures do not block provider selection saves")
    void onProviderSelected_whenVoskRefreshFails_stillSavesProviderSelection() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-refresh-fails.properties"));
        Path models = tempDir.resolve("vosk-refresh-fails-models");
        try (var voskModels = new ThrowingRefreshVoskModelManagementService(repo, models, tempDir.resolve("vosk-refresh-fails-temp"))) {
            var subject = callOnEdt(() -> new SpeechToTextPanel(repo, models, voskModels));
            try {
                SwingUtilities.invokeAndWait(() -> selectProvider(subject, VoskSpeechToTextProvider.ID));

                assertThat(subject.savePendingChanges()).isTrue();
                assertThat(repo.get(SpeechToTextSettings.PROVIDER_KEY)).contains(VoskSpeechToTextProvider.ID);
                assertThat(subject.lastSaveError()).isBlank();
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Queued Vosk completion callbacks skip UI updates after panel removal")
    void handleVoskModelSnapshot_whenPanelRemovedBeforeCallback_skipsStatusUpdate() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-removed-callback.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, VoskSpeechToTextProvider.ID);
        Path models = tempDir.resolve("vosk-removed-callback-models");
        try (var voskModels = new CompletingVoskModelManagementService(repo, models, tempDir.resolve("vosk-removed-callback-temp"))) {
            var subject = callOnEdt(() -> new SpeechToTextPanel(repo, models, voskModels));
            SwingUtilities.invokeAndWait(() -> {
            });

            runWhileEventDispatchThreadBlocked(() -> {
                SwingUtilities.invokeLater(subject::removeNotify);
                voskModels.completeWithStatus("Vosk completion after removal");
            });

            assertThat(statusLabelText(subject)).doesNotContain("Vosk completion after removal");
        }
    }

    @Test
    @DisplayName("Coalesced Vosk start and completion callbacks still apply completion status")
    void handleVoskModelSnapshot_whenStartAndCompletionCoalesce_appliesCompletionStatus() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-coalesced-completion.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, VoskSpeechToTextProvider.ID);
        Path models = tempDir.resolve("vosk-coalesced-completion-models");
        try (var voskModels = new RapidCompletingVoskModelManagementService(repo, models, tempDir.resolve("vosk-coalesced-completion-temp"))) {
            var subject = callOnEdt(() -> new SpeechToTextPanel(repo, models, voskModels));
            SwingUtilities.invokeAndWait(() -> {
            });

            runWhileEventDispatchThreadBlocked(() -> voskModels.startAndCompleteWithStatus("Vosk failed after download"));

            assertThat(statusLabelText(subject)).contains("Vosk failed after download");

            voskModels.publishIdleSnapshot();
            SwingUtilities.invokeAndWait(() -> {
            });

            assertThat(statusLabelText(subject)).contains("Vosk failed after download");
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("Stale Vosk completion does not overwrite another provider status")
    void handleVoskModelSnapshot_whenProviderChangedBeforeCompletion_skipsStatusUpdate() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-stale-completion.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, VoskSpeechToTextProvider.ID);
        Path models = tempDir.resolve("vosk-stale-completion-models");
        try (var voskModels = new CompletingVoskModelManagementService(repo, models, tempDir.resolve("vosk-stale-completion-temp"))) {
            var subject = callOnEdt(() -> new SpeechToTextPanel(repo, models, voskModels));
            SwingUtilities.invokeAndWait(() -> {
            });
            repo.put(SpeechToTextSettings.PROVIDER_KEY, GroqSpeechToTextProvider.ID);

            runWhileEventDispatchThreadBlocked(() -> voskModels.completeWithStatus("Stale Vosk completion"));

            assertThat(statusLabelText(subject)).doesNotContain("Stale Vosk completion");
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("Speech to Text saves are ignored after panel removal")
    void scheduleSave_whenPanelAlreadyRemoved_ignoresSave() throws Exception {
        var subject = callOnEdt(() -> new SpeechToTextPanel(new SettingsRepository(tempDir.resolve("settings-removed-save.properties")), tempDir.resolve("default-models")));
        var saveCalled = new CountDownLatch(1);

        runOnEdt(subject::removeNotify);
        scheduleSave(subject, saveCalled::countDown, () -> {
        });

        assertThat(saveCalled.getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Completed Speech to Text saves skip UI callbacks after panel removal")
    void scheduleSave_whenPanelRemovedBeforeSuccessCallback_skipsSuccessCallback() throws Exception {
        var subject = callOnEdt(() -> new SpeechToTextPanel(new SettingsRepository(tempDir.resolve("settings-removed.properties")), tempDir.resolve("default-models")));
        var saveStarted = new CountDownLatch(1);
        var releaseSave = new CountDownLatch(1);
        var saveCompleted = new CountDownLatch(1);
        var successCallback = new CountDownLatch(1);
        boolean[] removed = {false};

        try {
            scheduleSave(subject, () -> {
                saveStarted.countDown();
                try {
                    assertThat(releaseSave.await(2, TimeUnit.SECONDS)).isTrue();
                } finally {
                    saveCompleted.countDown();
                }
            }, successCallback::countDown);
            assertThat(saveStarted.await(2, TimeUnit.SECONDS)).isTrue();

            runWhileEventDispatchThreadBlocked(() -> {
                releaseSave.countDown();
                assertThat(saveCompleted.await(2, TimeUnit.SECONDS)).isTrue();
                subject.removeNotify();
                removed[0] = true;
            });
            SwingUtilities.invokeAndWait(() -> {
            });

            assertThat(successCallback.getCount()).isEqualTo(1);
        } finally {
            releaseSave.countDown();
            if (!removed[0]) {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Pending Speech to Text token save failure uses original token field after rebuild")
    void savePendingChangesAsync_whenTokenFieldRebuiltBeforeFailure_usesOriginalError() throws Exception {
        var subject = callOnEdt(() -> new SpeechToTextPanel(new SettingsRepository(tempDir.resolve("settings-token-rebuild.properties")), tempDir.resolve("default-models")));
        ApiTokenFieldPanel originalField = mock(ApiTokenFieldPanel.class);
        ApiTokenFieldPanel replacementField = mock(ApiTokenFieldPanel.class);
        CompletableFuture<Boolean> tokenSave = new CompletableFuture<>();
        var tokenSaveStarted = new CountDownLatch(1);
        when(originalField.dirty()).thenReturn(true);
        when(originalField.savePendingChangesAsync()).thenAnswer(invocation -> {
            tokenSaveStarted.countDown();
            return tokenSave;
        });
        when(originalField.lastSaveError()).thenReturn("original Speech to Text token save failed");
        when(replacementField.lastSaveError()).thenReturn("replacement Speech to Text token save failed");
        try {
            runOnEdt(() -> setField(subject, "tokenField", originalField));
            CompletableFuture<Boolean> pendingSave = subject.savePendingChangesAsync();
            assertThat(tokenSaveStarted.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> setField(subject, "tokenField", replacementField));

            tokenSave.complete(false);

            assertThat(pendingSave.get(2, TimeUnit.SECONDS)).isFalse();
            assertThat(subject.lastSaveError()).isEqualTo("original Speech to Text token save failed");
        } finally {
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("Synchronous Speech to Text save rejects event dispatch thread use")
    void savePendingChanges_whenCalledOnEdt_returnsFailureWithoutBlocking() throws Exception {
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                new SettingsRepository(tempDir.resolve("settings-dirty-token-edt.properties")),
                tempDir.resolve("default-models")
        ));
        ApiTokenFieldPanel field = mock(ApiTokenFieldPanel.class);
        when(field.dirty()).thenReturn(true);
        when(field.savePendingChangesAsync()).thenReturn(CompletableFuture.completedFuture(true));
        try {
            runOnEdt(() -> setField(subject, "tokenField", field));

            assertThat(callOnEdt(subject::savePendingChanges)).isFalse();
            assertThat(subject.lastSaveError())
                    .isEqualTo("Pending Speech to Text changes must be saved asynchronously on the event dispatch thread.");
            assertThat(subject.savePendingChangesAsync().get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(subject.lastSaveError()).isBlank();
        } finally {
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("Failed Speech to Text directory saves do not poison later successful saves")
    void savePendingChanges_whenDirectorySaveFailsThenSucceeds_allowsCloseAfterCorrection() throws Exception {
        Path settingsFile = tempDir.resolve("settings.properties");
        Path existingFile = Files.writeString(tempDir.resolve("not-a-directory"), "content");
        Path validDirectory = tempDir.resolve("models");
        var subject = callOnEdt(() -> new SpeechToTextPanel(new SettingsRepository(settingsFile), tempDir.resolve("default-models")));
        try {
            SwingUtilities.invokeAndWait(() -> setModelDirectoryAndSave(subject, existingFile));
            waitUntil(() -> !subject.lastSaveError().isBlank());
            assertThat(subject.savePendingChanges()).isFalse();

            SwingUtilities.invokeAndWait(() -> setModelDirectoryAndSave(subject, validDirectory));

            assertThat(subject.savePendingChanges()).isTrue();
            assertThat(Files.isDirectory(validDirectory)).isTrue();
        } finally {
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("Local model controls are hidden for cloud Speech to Text providers")
    void refreshControlsFromSettings_whenProviderDoesNotSupportLocalModels_hidesLocalModelControls() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, GroqSpeechToTextProvider.ID);
        Path defaultModels = tempDir.resolve("default-models");
        try (
                var voskModels = new VoskModelManagementService(repo, defaultModels, tempDir.resolve("vosk-temp"));
                var whisperModels = new WhisperModelManagementService(repo, defaultModels, tempDir.resolve("whisper-temp"), fakeWhisperRuntime(), new WhisperModelUsageTracker())
        ) {
            SpeechToTextPanel subject = callOnEdt(() -> new SpeechToTextPanel(repo, defaultModels, voskModels, whisperModels));
            try {
                runOnEdt(() -> {
                    JPanel localModelsPanel = (JPanel) fieldValue(subject, "localModelsPanel");

                    assertThat(localModelsPanel.isVisible()).isFalse();
                });
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Vosk local model table reports Boolean classes only for Boolean columns")
    void refreshControlsFromSettings_whenVoskSelected_usesCorrectColumnClasses() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, VoskSpeechToTextProvider.ID);
        Path defaultModels = tempDir.resolve("default-models");
        try (
                var voskModels = new VoskModelManagementService(repo, defaultModels, tempDir.resolve("vosk-temp"));
                var whisperModels = new WhisperModelManagementService(repo, defaultModels, tempDir.resolve("whisper-temp"), fakeWhisperRuntime(), new WhisperModelUsageTracker())
        ) {
            SpeechToTextPanel subject = callOnEdt(() -> new SpeechToTextPanel(repo, defaultModels, voskModels, whisperModels));
            try {
                runOnEdt(() -> {
                    JTable localModelsTable = (JTable) fieldValue(subject, "localModelsTable");

                    assertThat(localModelsTable.getColumnClass(0)).isEqualTo(String.class);
                    assertThat(localModelsTable.getColumnClass(1)).isEqualTo(String.class);
                    assertThat(localModelsTable.getColumnClass(2)).isEqualTo(String.class);
                    assertThat(localModelsTable.getColumnClass(3)).isEqualTo(String.class);
                    assertThat(localModelsTable.getColumnClass(4)).isEqualTo(Boolean.class);
                    assertThat(localModelsTable.getColumnClass(5)).isEqualTo(Boolean.class);
                    assertThat(localModelsTable.getColumnClass(6)).isEqualTo(String.class);
                });
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Plausible Vosk models appear in table but not dropdown")
    void refreshControlsFromSettings_whenVoskModelIsPlausibleUnverified_excludesFromModelDropdown() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-plausible.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, VoskSpeechToTextProvider.ID);
        Path models = tempDir.resolve("vosk-plausible-models");
        try (
                var voskModels = new PlausibleVoskModelManagementService(repo, models, tempDir.resolve("vosk-plausible-temp"));
                var whisperModels = new WhisperModelManagementService(repo, models, tempDir.resolve("whisper-temp"), fakeWhisperRuntime(), new WhisperModelUsageTracker())
        ) {
            SpeechToTextPanel subject = callOnEdt(() -> new SpeechToTextPanel(repo, models, voskModels, whisperModels));
            try {
                runOnEdt(() -> {
                    @SuppressWarnings("unchecked")
                    JComboBox<SpeechToTextCatalogItem> modelComboBox = (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
                    JTable localModelsTable = (JTable) fieldValue(subject, "localModelsTable");

                    int plausibleRow = tableRowWithModel(localModelsTable, "custom-plausible");
                    assertThat(modelComboBox.getItemCount()).isZero();
                    assertThat(plausibleRow).isGreaterThanOrEqualTo(0);
                    assertThat(localModelsTable.getValueAt(plausibleRow, 4)).isEqualTo(Boolean.TRUE);
                    assertThat(localModelsTable.getValueAt(plausibleRow, 5)).isEqualTo(Boolean.FALSE);
                });
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Vosk refresh operations do not block closing Speech to Text settings")
    void savePendingChanges_whenVoskRefreshInProgress_allowsClose() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-scan.properties"));
        Path models = tempDir.resolve("scan-models");
        try (var voskModels = new RefreshingVoskModelManagementService(
                repo,
                models,
                tempDir.resolve("scan-temp"),
                VoskModelManagementService.SCAN_OPERATION_STATUS
        )) {
            var subject = callOnEdt(() -> new SpeechToTextPanel(repo, models, voskModels));
            try {
                assertThat(subject.savePendingChanges()).isTrue();
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
        try (var voskModels = new RefreshingVoskModelManagementService(
                repo,
                models,
                tempDir.resolve("catalog-temp"),
                VoskModelManagementService.CATALOG_REFRESH_OPERATION_STATUS
        )) {
            var subject = callOnEdt(() -> new SpeechToTextPanel(repo, models, voskModels));
            try {
                assertThat(subject.savePendingChanges()).isTrue();
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Resolved Vosk operations do not keep blocking Speech to Text settings close")
    void savePendingChanges_whenVoskOperationFinishes_allowsClose() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-resolved.properties"));
        Path models = tempDir.resolve("resolved-models");
        try (var voskModels = new RecoveringBusyVoskModelManagementService(repo, models, tempDir.resolve("resolved-temp"))) {
            var subject = callOnEdt(() -> new SpeechToTextPanel(repo, models, voskModels));
            try {
                assertThat(subject.savePendingChanges()).isFalse();

                voskModels.finishOperation();

                assertThat(subject.savePendingChanges()).isTrue();
                assertThat(subject.lastSaveError()).isBlank();
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Vosk model directory changes are blocked while a model operation is active")
    void saveModelDirectory_whenVoskOperationInProgress_rejectsDirectoryChange() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-busy.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, VoskSpeechToTextProvider.ID);
        Path models = tempDir.resolve("busy-models");
        Path requested = tempDir.resolve("other-models");
        try (
                var voskModels = new BusyVoskModelManagementService(repo, models, tempDir.resolve("busy-temp"));
                var whisperModels = new WhisperModelManagementService(repo, models, tempDir.resolve("whisper-temp"), fakeWhisperRuntime(), new WhisperModelUsageTracker())
        ) {
            SpeechToTextPanel subject = callOnEdt(() -> new SpeechToTextPanel(repo, models, voskModels, whisperModels));
            try {
                runOnEdt(() -> {
                    JTextField modelDirectoryField = (JTextField) fieldValue(subject, "modelDirectoryField");
                    JButton browseButton = (JButton) fieldValue(subject, "modelDirectoryBrowseButton");
                    JPanel helperPanel = (JPanel) fieldValue(subject, "helperPanel");
                    JProgressBar progressBar = (JProgressBar) fieldValue(subject, "localModelProgressBar");

                    assertThat(modelDirectoryField.isEnabled()).isFalse();
                    assertThat(browseButton.isEnabled()).isFalse();
                    assertThat(helperPanel.isVisible()).isFalse();
                    assertThat(progressBar.isVisible()).isTrue();
                    assertThat(progressBar.isIndeterminate()).isTrue();
                    assertThat(progressBar.isStringPainted()).isFalse();

                    setModelDirectoryAndSave(subject, requested);

                    assertThat(repo.get(SpeechToTextModelDirectory.SETTINGS_KEY, "")).isBlank();
                    assertThat(modelDirectoryField.getText()).isEqualTo(models.toString());
                });
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Vosk model selection starts an async operation instead of saving on the UI handler")
    void onModelSelected_whenVoskModelSelected_startsAsyncSelectionOperation() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-vosk-select.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, VoskSpeechToTextProvider.ID);
        Path models = tempDir.resolve("select-models");
        try (
                var voskModels = new AsyncSelectionVoskModelManagementService(repo, models, tempDir.resolve("select-temp"));
                var whisperModels = new WhisperModelManagementService(repo, models, tempDir.resolve("whisper-temp"), fakeWhisperRuntime(), new WhisperModelUsageTracker())
        ) {
            SpeechToTextPanel subject = callOnEdt(() -> new SpeechToTextPanel(repo, models, voskModels, whisperModels));
            try {
                runOnEdt(() -> {
                    @SuppressWarnings("unchecked")
                    JComboBox<SpeechToTextCatalogItem> modelComboBox = (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
                    JButton refreshButton = (JButton) fieldValue(subject, "refreshButton");

                    invokePanelMethod(subject, "onModelSelected");

                    assertThat(voskModels.selectedModelId()).isEqualTo("custom-ready");
                    assertThat(repo.get(sttModelIdKey(VoskSpeechToTextProvider.ID), "")).isBlank();
                    assertThat(modelComboBox.isEnabled()).isFalse();
                    assertThat(refreshButton.isEnabled()).isFalse();
                });
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Default Speech to Text providers include AssemblyAI before Vosk")
    void reloadProviderOptions_whenDefaultRegistryLoaded_ordersAssemblyAiBeforeVosk() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-provider-order.properties"));
        Path defaultModels = tempDir.resolve("default-models");
        try (
                var voskModels = new VoskModelManagementService(repo, defaultModels, tempDir.resolve("vosk-temp"));
                var whisperModels = new WhisperModelManagementService(repo, defaultModels, tempDir.resolve("whisper-temp"), fakeWhisperRuntime(), new WhisperModelUsageTracker())
        ) {
            SpeechToTextPanel subject = callOnEdt(() -> new SpeechToTextPanel(repo, defaultModels, voskModels, whisperModels));
            try {
                runOnEdt(() -> {
                    @SuppressWarnings("unchecked")
                    JComboBox<Object> providerComboBox = (JComboBox<Object>) fieldValue(subject, "providerComboBox");

                    assertThat(providerComboBox.getItemCount()).isEqualTo(7);
                    assertThat(providerIdOf(providerComboBox.getItemAt(0))).isEqualTo("off");
                    assertThat(providerIdOf(providerComboBox.getItemAt(1))).isEqualTo("groq");
                    assertThat(providerIdOf(providerComboBox.getItemAt(2))).isEqualTo("elevenlabs");
                    assertThat(providerIdOf(providerComboBox.getItemAt(3))).isEqualTo("deepgram");
                    assertThat(providerIdOf(providerComboBox.getItemAt(4))).isEqualTo("assemblyai");
                    assertThat(providerIdOf(providerComboBox.getItemAt(5))).isEqualTo("whisper");
                    assertThat(providerIdOf(providerComboBox.getItemAt(6))).isEqualTo("vosk");
                });
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("ElevenLabs catalog refresh uses ElevenLabs endpoint context")
    void refreshCatalogs_whenElevenLabsSelected_usesElevenLabsEndpointContext() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs-refresh.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        var provider = new CapturingElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider))));

        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> new SpeechToTextCatalogStore(repo).cachedModels(ElevenLabsSpeechToTextProvider.ID).stream()
                .anyMatch(model -> "scribe_v2".equals(model.id())));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertThat(provider.context.get().baseUri()).hasToString("https://api.elevenlabs.io");
        assertThat(provider.context.get().transcriptionUri()).hasToString("https://api.elevenlabs.io/v1/speech-to-text");
        runOnEdt(subject::removeNotify);
    }

    @Test
    @DisplayName("Successful ElevenLabs refresh removes an unavailable saved model and persists the default")
    void refreshCatalogs_whenSavedElevenLabsModelIsUnavailable_repairsAuthoritativeSelection() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs-authoritative-refresh.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "account-a-model");
        repo.put("chat4j.stt.elevenlabs.model.label", "Account A Model");
        new SpeechToTextCatalogStore(repo).saveModels(
                ElevenLabsSpeechToTextProvider.ID,
                List.of(SpeechToTextCatalogItem.of("account-a-model", "Account A Model"))
        );
        var provider = new AuthoritativeElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(provider))
        ));
        try {
            runOnEdt(() -> invokePanelMethod(subject, "refreshCatalogs", true));
            assertThat(provider.completed.await(2, TimeUnit.SECONDS)).isTrue();
            Thread refreshThread = provider.refreshThread.get();
            assertThat(refreshThread).isNotNull();
            refreshThread.join(2_000);
            assertThat(refreshThread.isAlive()).isFalse();
            assertThat(subject.savePendingChanges()).isTrue();
            SwingUtilities.invokeAndWait(() -> {
            });
            waitUntil(() -> repo.get(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "")
                    .equals(provider.defaultModel().id()));
            waitUntil(() -> callOnEdt(() -> {
                @SuppressWarnings("unchecked")
                JComboBox<SpeechToTextCatalogItem> modelComboBox =
                        (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
                return modelComboBox.getSelectedItem() instanceof SpeechToTextCatalogItem selected
                        && provider.defaultModel().id().equals(selected.id());
            }));

            runOnEdt(() -> {
                @SuppressWarnings("unchecked")
                JComboBox<SpeechToTextCatalogItem> modelComboBox =
                        (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
                assertThat(comboModelIds(modelComboBox))
                        .contains("account-b-model", provider.defaultModel().id())
                        .doesNotContain("account-a-model");
                assertThat(((SpeechToTextCatalogItem) modelComboBox.getSelectedItem()).id())
                        .isEqualTo(provider.defaultModel().id());
            });
            assertThat(repo.get(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID)))
                    .contains(provider.defaultModel().id());
            assertThat(repo.get("chat4j.stt.elevenlabs.model.label"))
                    .contains(provider.defaultModel().label());
        } finally {
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("Credential cancellation rejects an STT repair already waiting to persist")
    void prepareCatalogRefresh_whenCredentialChangeCancelsBlockedRepair_doesNotRestoreSelection() throws Exception {
        var repo = new BlockingConditionalSettingsRepository(
                tempDir.resolve("settings-cancel-blocked-repair.properties")
        );
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "account-a-model");
        repo.put("chat4j.stt.elevenlabs.model.label", "Account A Model");
        new SpeechToTextCatalogStore(repo).saveModels(
                ElevenLabsSpeechToTextProvider.ID,
                List.of(SpeechToTextCatalogItem.of("account-a-model", "Account A Model"))
        );
        var provider = new AuthoritativeElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(provider))
        ));
        try {
            SpeechToTextSettingsSnapshot selection = callOnEdt(() ->
                    ((SpeechToTextSettings) fieldValue(subject, "settings")).resolve());
            repo.blockNextConditionalUpdate = true;
            runOnEdt(() -> invokeCatalogRefresh(
                    subject,
                    selection,
                    List.of(SpeechToTextCatalogItem.of("account-b-model", "Account B Model"))
            ));
            assertThat(repo.conditionalUpdateStarted.await(2, TimeUnit.SECONDS)).isTrue();

            runOnEdt(() -> invokePanelMethod(subject, "cancelCatalogRefreshes"));
            new SpeechToTextCatalogStore(repo).invalidate(ElevenLabsSpeechToTextProvider.ID);
            repo.releaseConditionalUpdate.countDown();
            assertThat(subject.savePendingChanges()).isTrue();
            SwingUtilities.invokeAndWait(() -> {
            });

            assertThat(repo.get(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID))).isEmpty();
            assertThat(repo.get("chat4j.stt.elevenlabs.model.label")).isEmpty();
        } finally {
            repo.releaseConditionalUpdate.countDown();
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("Credential cancellation rejects a pending STT model selection save")
    void onModelSelected_whenCredentialChangeCancelsBlockedSave_doesNotRestoreSelection() throws Exception {
        var repo = new BlockingConditionalSettingsRepository(
                tempDir.resolve("settings-cancel-blocked-model-save.properties")
        );
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "scribe_v2");
        repo.put("chat4j.stt.elevenlabs.model.label", "Scribe v2");
        new SpeechToTextCatalogStore(repo).saveModels(
                ElevenLabsSpeechToTextProvider.ID,
                List.of(SpeechToTextCatalogItem.of("account-a-model", "Account A Model"))
        );
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(new AuthoritativeElevenLabsProvider()))
        ));
        try {
            repo.blockNextConditionalUpdate = true;
            runOnEdt(() -> selectModel(subject, "account-a-model"));
            assertThat(repo.conditionalUpdateStarted.await(2, TimeUnit.SECONDS)).isTrue();

            runOnEdt(() -> invokePanelMethod(subject, "cancelCatalogRefreshes"));
            new SpeechToTextCatalogStore(repo).invalidate(ElevenLabsSpeechToTextProvider.ID);
            repo.releaseConditionalUpdate.countDown();
            assertThat(subject.savePendingChanges()).isTrue();
            SwingUtilities.invokeAndWait(() -> {
            });

            assertThat(repo.get(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID))).isEmpty();
            assertThat(repo.get("chat4j.stt.elevenlabs.model.label")).isEmpty();
        } finally {
            repo.releaseConditionalUpdate.countDown();
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("Ordinary catalog refresh preserves a blocked current model selection")
    void onModelSelected_whenCatalogRefreshStarts_preservesAvailableSelection() throws Exception {
        var repo = new BlockingConditionalSettingsRepository(
                tempDir.resolve("settings-model-save-during-refresh.properties")
        );
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "account-a-model");
        repo.put("chat4j.stt.elevenlabs.model.label", "Account A Model");
        new SpeechToTextCatalogStore(repo).saveModels(
                ElevenLabsSpeechToTextProvider.ID,
                List.of(
                        SpeechToTextCatalogItem.of("account-a-model", "Account A Model"),
                        SpeechToTextCatalogItem.of("account-b-model", "Account B Model")
                )
        );
        var provider = new AuthoritativeElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(provider))
        ));
        try {
            repo.blockNextConditionalUpdate = true;
            runOnEdt(() -> selectModel(subject, "account-b-model"));
            assertThat(repo.conditionalUpdateStarted.await(2, TimeUnit.SECONDS)).isTrue();

            runOnEdt(() -> invokePanelMethod(subject, "refreshCatalogs", true));
            assertThat(provider.completed.await(2, TimeUnit.SECONDS)).isTrue();
            Thread refreshThread = provider.refreshThread.get();
            assertThat(refreshThread).isNotNull();
            refreshThread.join(2_000);
            assertThat(refreshThread.isAlive()).isFalse();
            SwingUtilities.invokeAndWait(() -> {
            });

            repo.releaseConditionalUpdate.countDown();
            assertThat(subject.savePendingChanges()).isTrue();
            waitUntil(() -> repo.get(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "")
                    .equals("account-b-model"));
            assertThat(repo.get("chat4j.stt.elevenlabs.model.label")).contains("Account B Model");
        } finally {
            repo.releaseConditionalUpdate.countDown();
            runOnEdt(subject::removeNotify);
            interruptAndJoin(provider.refreshThread.get());
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    @Test
    @DisplayName("A queued user model save restores its selection after catalog repair completes")
    void onModelSelected_whenCatalogRepairCompletesFirst_restoresUserSelection() throws Exception {
        var repo = new BlockingNthConditionalSettingsRepository(
                tempDir.resolve("settings-catalog-repair-before-model-save.properties")
        );
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "account-a-model");
        repo.put("chat4j.stt.elevenlabs.model.label", "Account A Model");
        new SpeechToTextCatalogStore(repo).saveModels(
                ElevenLabsSpeechToTextProvider.ID,
                List.of(
                        SpeechToTextCatalogItem.of("account-a-model", "Account A Model"),
                        SpeechToTextCatalogItem.of("account-b-model", "Account B Model")
                )
        );
        var provider = new AuthoritativeElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(provider))
        ));
        try {
            SpeechToTextSettingsSnapshot selection = callOnEdt(() ->
                    ((SpeechToTextSettings) fieldValue(subject, "settings")).resolve());
            repo.blockAfterSuccessfulConditionalUpdates(2);
            runOnEdt(() -> {
                invokeCatalogRefresh(
                        subject,
                        selection,
                        List.of(SpeechToTextCatalogItem.of("account-b-model", "Account B Model"))
                );
                selectModel(subject, "account-b-model");
            });
            assertThat(repo.conditionalUpdateStarted.await(2, TimeUnit.SECONDS)).isTrue();
            waitUntil(() -> callOnEdt(() -> {
                @SuppressWarnings("unchecked")
                JComboBox<SpeechToTextCatalogItem> modelComboBox =
                        (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
                return modelComboBox.getSelectedItem() instanceof SpeechToTextCatalogItem selected
                        && provider.defaultModel().id().equals(selected.id());
            }));

            repo.releaseConditionalUpdate.countDown();
            assertThat(subject.savePendingChanges()).isTrue();
            waitUntil(() -> callOnEdt(() -> {
                @SuppressWarnings("unchecked")
                JComboBox<SpeechToTextCatalogItem> modelComboBox =
                        (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
                return modelComboBox.getSelectedItem() instanceof SpeechToTextCatalogItem selected
                        && "account-b-model".equals(selected.id());
            }));
            assertThat(repo.get(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID)))
                    .contains("account-b-model");
        } finally {
            repo.releaseConditionalUpdate.countDown();
            runOnEdt(subject::removeNotify);
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    @Test
    @DisplayName("A pending unavailable STT model save is repaired even when the persisted model was initially valid")
    void prepareCatalogRefresh_whenUnavailableModelSavePending_persistsAuthoritativeFallbackLast() throws Exception {
        var repo = new BlockingConditionalSettingsRepository(
                tempDir.resolve("settings-valid-model-pending-save.properties")
        );
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "scribe_v2");
        repo.put("chat4j.stt.elevenlabs.model.label", "Scribe v2");
        new SpeechToTextCatalogStore(repo).saveModels(
                ElevenLabsSpeechToTextProvider.ID,
                List.of(SpeechToTextCatalogItem.of("account-a-model", "Account A Model"))
        );
        var provider = new AuthoritativeElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(provider))
        ));
        try {
            repo.blockNextConditionalUpdate = true;
            runOnEdt(() -> selectModel(subject, "account-a-model"));
            assertThat(repo.conditionalUpdateStarted.await(2, TimeUnit.SECONDS)).isTrue();
            SpeechToTextSettingsSnapshot selection = callOnEdt(() ->
                    ((SpeechToTextSettings) fieldValue(subject, "settings")).resolve());

            runOnEdt(() -> invokeCatalogRefresh(
                    subject,
                    selection,
                    List.of(SpeechToTextCatalogItem.of("account-b-model", "Account B Model"))
            ));
            repo.releaseConditionalUpdate.countDown();
            assertThat(subject.savePendingChanges()).isTrue();
            SwingUtilities.invokeAndWait(() -> {
            });

            assertThat(repo.get(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID)))
                    .contains(provider.defaultModel().id());
            runOnEdt(() -> {
                @SuppressWarnings("unchecked")
                JComboBox<SpeechToTextCatalogItem> modelComboBox =
                        (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
                assertThat(comboModelIds(modelComboBox)).doesNotContain("account-a-model");
                assertThat(((SpeechToTextCatalogItem) modelComboBox.getSelectedItem()).id())
                        .isEqualTo(provider.defaultModel().id());
            });
        } finally {
            repo.releaseConditionalUpdate.countDown();
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("Catalog persistence does not hide an earlier user model save failure")
    void prepareCatalogRefresh_whenEarlierModelSaveFails_preservesUserSaveFailure() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-model-failure-before-catalog.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "account-a-model");
        new SpeechToTextCatalogStore(repo).saveModels(
                ElevenLabsSpeechToTextProvider.ID,
                List.of(SpeechToTextCatalogItem.of("account-a-model", "Account A Model"))
        );
        var provider = new AuthoritativeElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(provider))
        ));
        var modelSaveStarted = new CountDownLatch(1);
        var releaseModelSave = new CountDownLatch(1);
        try {
            scheduleSave(
                    subject,
                    "model-selection:%s".formatted(ElevenLabsSpeechToTextProvider.ID),
                    () -> {
                        modelSaveStarted.countDown();
                        assertThat(releaseModelSave.await(2, TimeUnit.SECONDS)).isTrue();
                        throw new IllegalStateException("model selection save failed");
                    },
                    () -> {
                    }
            );
            assertThat(modelSaveStarted.await(2, TimeUnit.SECONDS)).isTrue();
            SpeechToTextSettingsSnapshot selection = callOnEdt(() ->
                    ((SpeechToTextSettings) fieldValue(subject, "settings")).resolve());
            runOnEdt(() -> invokeCatalogRefresh(
                    subject,
                    selection,
                    List.of(SpeechToTextCatalogItem.of("account-b-model", "Account B Model"))
            ));

            releaseModelSave.countDown();
            assertThat(subject.savePendingChanges()).isFalse();
            assertThat(subject.lastSaveError()).isEqualTo("model selection save failed");
            assertThat(new SpeechToTextCatalogStore(repo).cachedModels(ElevenLabsSpeechToTextProvider.ID))
                    .extracting(SpeechToTextCatalogItem::id)
                    .contains("account-b-model");
        } finally {
            releaseModelSave.countDown();
            runOnEdt(subject::removeNotify);
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    @Test
    @DisplayName("Thrown catalog persistence failure is reported without blocking a later user model save")
    void prepareCatalogRefresh_whenCatalogWriteThrows_reportsNonBlockingFailure() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-catalog-failure-before-model.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "scribe_v2");
        new SpeechToTextCatalogStore(repo).saveModels(
                ElevenLabsSpeechToTextProvider.ID,
                List.of(SpeechToTextCatalogItem.of("scribe_v2", "Scribe v2"))
        );
        var provider = new AuthoritativeElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(provider))
        ));
        try {
            SpeechToTextSettingsSnapshot selection = callOnEdt(() ->
                    ((SpeechToTextSettings) fieldValue(subject, "settings")).resolve());
            runOnEdt(() -> invokeCatalogRefresh(
                    subject,
                    selection,
                    List.of(SpeechToTextCatalogItem.of("oversized-model", "x".repeat(70_000)))
            ));
            scheduleSave(
                    subject,
                    "model-selection:%s".formatted(ElevenLabsSpeechToTextProvider.ID),
                    () -> repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "scribe_v2"),
                    () -> {
                    }
            );

            assertThat(subject.savePendingChanges()).isTrue();
            waitUntil(() -> statusLabelText(subject).contains("Could not cache ElevenLabs Speech to Text models"));
            assertThat(subject.lastSaveError()).isBlank();
            assertThat(new SpeechToTextCatalogStore(repo).cachedModels(ElevenLabsSpeechToTextProvider.ID))
                    .extracting(SpeechToTextCatalogItem::id)
                    .doesNotContain("oversized-model");
        } finally {
            runOnEdt(subject::removeNotify);
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    @Test
    @DisplayName("Catalog cache failure still applies a successfully repaired model")
    void prepareCatalogRefresh_whenRepairSucceedsAndCacheFails_appliesRepairedSelection() throws Exception {
        var repo = new FailingConditionalSettingsRepository(
                tempDir.resolve("settings-repair-before-cache-failure.properties")
        );
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "account-a-model");
        repo.put("chat4j.stt.elevenlabs.model.label", "Account A Model");
        new SpeechToTextCatalogStore(repo).saveModels(
                ElevenLabsSpeechToTextProvider.ID,
                List.of(SpeechToTextCatalogItem.of("account-a-model", "Account A Model"))
        );
        var provider = new AuthoritativeElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(provider))
        ));
        try {
            SpeechToTextSettingsSnapshot selection = callOnEdt(() ->
                    ((SpeechToTextSettings) fieldValue(subject, "settings")).resolve());
            repo.failAfterSuccessfulConditionalUpdates(1);
            runOnEdt(() -> invokeCatalogRefresh(
                    subject,
                    selection,
                    List.of(SpeechToTextCatalogItem.of("account-b-model", "Account B Model"))
            ));

            assertThat(subject.savePendingChanges()).isTrue();
            waitUntil(() -> statusLabelText(subject).contains("Could not cache ElevenLabs Speech to Text models"));
            assertThat(repo.get(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID)))
                    .contains(provider.defaultModel().id());
            runOnEdt(() -> {
                @SuppressWarnings("unchecked")
                JComboBox<SpeechToTextCatalogItem> modelComboBox =
                        (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
                assertThat(((SpeechToTextCatalogItem) modelComboBox.getSelectedItem()).id())
                        .isEqualTo(provider.defaultModel().id());
            });
        } finally {
            runOnEdt(subject::removeNotify);
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    @Test
    @DisplayName("Authoritative STT repair runs after an older pending model save")
    void refreshCatalogs_whenStaleModelSavePending_persistsAuthoritativeFallbackLast() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs-pending-model-save.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "account-a-model");
        repo.put("chat4j.stt.elevenlabs.model.label", "Account A Model");
        new SpeechToTextCatalogStore(repo).saveModels(
                ElevenLabsSpeechToTextProvider.ID,
                List.of(SpeechToTextCatalogItem.of("account-a-model", "Account A Model"))
        );
        var provider = new AuthoritativeElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(provider))
        ));
        var staleSaveStarted = new CountDownLatch(1);
        var releaseStaleSave = new CountDownLatch(1);
        var staleSuccessCallbacks = new AtomicInteger();
        try {
            runOnEdt(() -> scheduleSave(
                    subject,
                    "model-selection:%s".formatted(ElevenLabsSpeechToTextProvider.ID),
                    () -> {
                        staleSaveStarted.countDown();
                        assertThat(releaseStaleSave.await(2, TimeUnit.SECONDS)).isTrue();
                        repo.updateBatch(batch -> {
                            batch.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "account-a-model");
                            batch.put("chat4j.stt.elevenlabs.model.label", "Account A Model");
                        });
                    },
                    staleSuccessCallbacks::incrementAndGet
            ));
            assertThat(staleSaveStarted.await(2, TimeUnit.SECONDS)).isTrue();

            runOnEdt(() -> invokePanelMethod(subject, "refreshCatalogs", true));
            assertThat(provider.completed.await(2, TimeUnit.SECONDS)).isTrue();
            Thread refreshThread = provider.refreshThread.get();
            assertThat(refreshThread).isNotNull();
            refreshThread.join(2_000);
            assertThat(refreshThread.isAlive()).isFalse();
            SwingUtilities.invokeAndWait(() -> {
            });

            releaseStaleSave.countDown();
            waitUntil(() -> repo.get(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "")
                    .equals(provider.defaultModel().id()));
            SwingUtilities.invokeAndWait(() -> {
            });

            assertThat(staleSuccessCallbacks).hasValue(1);
            assertThat(repo.get("chat4j.stt.elevenlabs.model.label"))
                    .contains(provider.defaultModel().label());
            runOnEdt(() -> {
                @SuppressWarnings("unchecked")
                JComboBox<SpeechToTextCatalogItem> modelComboBox =
                        (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
                assertThat(((SpeechToTextCatalogItem) modelComboBox.getSelectedItem()).id())
                        .isEqualTo(provider.defaultModel().id());
            });
        } finally {
            releaseStaleSave.countDown();
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("Authoritative repair preserves a queued provider change refresh")
    void refreshCatalogs_whenProviderChangeQueuedBeforeRepair_refreshesChangedProvider() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-provider-change-before-repair.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "account-a-model");
        new SpeechToTextCatalogStore(repo).saveModels(
                ElevenLabsSpeechToTextProvider.ID,
                List.of(SpeechToTextCatalogItem.of("account-a-model", "Account A Model"))
        );
        var provider = new AuthoritativeElevenLabsProvider();
        var changedProvider = new CapturingDeepgramProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(provider, changedProvider))
        ));
        var blockingSaveStarted = new CountDownLatch(1);
        var releaseBlockingSave = new CountDownLatch(1);
        var providerSaveCallbacks = new AtomicInteger();
        try {
            runOnEdt(() -> scheduleSave(
                    subject,
                    "test-blocking",
                    () -> {
                        blockingSaveStarted.countDown();
                        assertThat(releaseBlockingSave.await(2, TimeUnit.SECONDS)).isTrue();
                    },
                    () -> {
                    }));
            assertThat(blockingSaveStarted.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> scheduleSave(
                    subject,
                    "provider",
                    () -> repo.put(SpeechToTextSettings.PROVIDER_KEY, DeepgramSpeechToTextProvider.ID),
                    providerSaveCallbacks::incrementAndGet
            ));

            runOnEdt(() -> invokePanelMethod(subject, "refreshCatalogs", true));
            assertThat(provider.completed.await(2, TimeUnit.SECONDS)).isTrue();
            Thread refreshThread = provider.refreshThread.get();
            assertThat(refreshThread).isNotNull();
            refreshThread.join(2_000);
            assertThat(refreshThread.isAlive()).isFalse();
            SwingUtilities.invokeAndWait(() -> {
            });

            releaseBlockingSave.countDown();
            assertThat(subject.savePendingChanges()).isTrue();
            SwingUtilities.invokeAndWait(() -> {
            });

            runOnEdt(() -> {
                @SuppressWarnings("unchecked")
                JComboBox<Object> providerComboBox = (JComboBox<Object>) fieldValue(subject, "providerComboBox");
                assertThat(providerIdOf(providerComboBox.getSelectedItem()))
                        .isEqualTo(DeepgramSpeechToTextProvider.ID);
            });
            assertThat(changedProvider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
            Thread changedProviderRefreshThread = changedProvider.refreshThread.get();
            assertThat(changedProviderRefreshThread).isNotNull();
            changedProviderRefreshThread.join(2_000);
            assertThat(changedProviderRefreshThread.isAlive()).isFalse();
            assertThat(subject.savePendingChanges()).isTrue();
            SwingUtilities.invokeAndWait(() -> {
            });
            assertThat(providerSaveCallbacks).hasValue(1);
        } finally {
            releaseBlockingSave.countDown();
            runOnEdt(subject::removeNotify);
            provider.completed.await(2, TimeUnit.SECONDS);
            interruptAndJoin(provider.refreshThread.get());
            changedProvider.refreshed.await(2, TimeUnit.SECONDS);
            interruptAndJoin(changedProvider.refreshThread.get());
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    @Test
    @DisplayName("In-flight ElevenLabs catalog refresh does not persist after panel removal")
    void refreshCatalogs_whenPanelRemovedBeforeElevenLabsRefreshCompletes_doesNotPersistCatalog() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs-stale-refresh.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        var provider = new BlockingElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider))));

        assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();
        runOnEdt(subject::removeNotify);
        provider.release.countDown();
        Thread refreshThread = provider.refreshThread.get();
        assertThat(refreshThread).isNotNull();
        refreshThread.join(2_000);
        assertThat(refreshThread.isAlive()).isFalse();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertThat(repo.get(sttCatalogModelsKey(ElevenLabsSpeechToTextProvider.ID), "")).isBlank();
        assertThat(repo.get(sttCatalogUpdatedAtKey(ElevenLabsSpeechToTextProvider.ID), "")).isBlank();
    }

    @Test
    @DisplayName("Closing after catalog commit keeps the repaired authoritative model on reopen")
    void prepareCatalogRefresh_whenPanelRemovedBeforePersistenceCallback_reopensWithRepairedSelection() throws Exception {
        var repo = new BlockingConditionalSettingsRepository(
                tempDir.resolve("settings-close-after-catalog-commit.properties")
        );
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "account-a-model");
        repo.put("chat4j.stt.elevenlabs.model.label", "Account A Model");
        new SpeechToTextCatalogStore(repo).saveModels(
                ElevenLabsSpeechToTextProvider.ID,
                List.of(SpeechToTextCatalogItem.of("account-a-model", "Account A Model"))
        );
        var provider = new AuthoritativeElevenLabsProvider();
        var registry = new SpeechToTextProviderRegistry(List.of(provider));
        var subject = callOnEdt(() -> new SpeechToTextPanel(repo, tempDir.resolve("default-models"), registry));
        boolean[] removed = {false};
        try {
            SpeechToTextSettingsSnapshot selection = callOnEdt(() ->
                    ((SpeechToTextSettings) fieldValue(subject, "settings")).resolve());
            repo.blockNextConditionalUpdate = true;
            runOnEdt(() -> invokeCatalogRefresh(
                    subject,
                    selection,
                    List.of(SpeechToTextCatalogItem.of("account-b-model", "Account B Model"))
            ));
            assertThat(repo.conditionalUpdateStarted.await(2, TimeUnit.SECONDS)).isTrue();

            runWhileEventDispatchThreadBlocked(() -> {
                repo.releaseConditionalUpdate.countDown();
                waitUntil(() -> repo.get(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), "")
                        .equals(provider.defaultModel().id()));
                waitUntil(() -> new SpeechToTextCatalogStore(repo)
                        .cachedModels(ElevenLabsSpeechToTextProvider.ID).stream()
                        .anyMatch(model -> "account-b-model".equals(model.id())));
                subject.removeNotify();
                removed[0] = true;
            });

            var reopened = callOnEdt(() -> new SpeechToTextPanel(
                    repo,
                    tempDir.resolve("default-models"),
                    registry
            ));
            try {
                runOnEdt(() -> {
                    @SuppressWarnings("unchecked")
                    JComboBox<SpeechToTextCatalogItem> modelComboBox =
                            (JComboBox<SpeechToTextCatalogItem>) fieldValue(reopened, "modelComboBox");
                    assertThat(comboModelIds(modelComboBox)).doesNotContain("account-a-model");
                    assertThat(((SpeechToTextCatalogItem) modelComboBox.getSelectedItem()).id())
                            .isEqualTo(provider.defaultModel().id());
                });
            } finally {
                runOnEdt(reopened::removeNotify);
            }
        } finally {
            repo.releaseConditionalUpdate.countDown();
            if (!removed[0]) {
                runOnEdt(subject::removeNotify);
            }
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    @Test
    @DisplayName("Speech to Text token changes cancel stale cloud catalog refresh before invalidation")
    void prepareForCredentialChange_whenCloudRefreshInFlight_preventsStaleCatalogPersistence() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs-token-refresh.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        var provider = new CredentialRefreshingElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider))));
        try {
            assertThat(provider.firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> ((ApiTokenFieldPanel) fieldValue(subject, "tokenField")).prepareForCredentialChange());
            new SpeechToTextCatalogStore(repo).invalidate(ElevenLabsSpeechToTextProvider.ID);
            provider.releaseFirst.countDown();
            assertThat(provider.firstFinished.await(2, TimeUnit.SECONDS)).isTrue();
            waitUntil(() -> provider.calls.get() >= 1);

            assertThat(new SpeechToTextCatalogStore(repo).cachedModels(ElevenLabsSpeechToTextProvider.ID))
                    .extracting(SpeechToTextCatalogItem::id)
                    .doesNotContain("stale-scribe");
            assertThat(repo.get(sttCatalogUpdatedAtKey(ElevenLabsSpeechToTextProvider.ID), "")).isBlank();

            runOnEdt(() -> ((ApiTokenFieldPanel) fieldValue(subject, "tokenField")).reloadAfterPeerCredentialChanged());

            assertThat(provider.secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
            waitUntil(() -> new SpeechToTextCatalogStore(repo).cachedModels(ElevenLabsSpeechToTextProvider.ID).stream()
                    .anyMatch(model -> "fresh-scribe".equals(model.id())));
            assertThat(new SpeechToTextCatalogStore(repo).cachedModels(ElevenLabsSpeechToTextProvider.ID))
                    .extracting(SpeechToTextCatalogItem::id)
                    .contains("fresh-scribe")
                    .doesNotContain("stale-scribe");
        } finally {
            provider.releaseFirst.countDown();
            runOnEdt(subject::removeNotify);
        }
    }

    @Test
    @DisplayName("Failed explicit ElevenLabs refresh preserves cached catalog")
    void refreshCatalogs_whenElevenLabsRefreshFails_preservesCachedCatalog() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs-cache.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        var selected = SpeechToTextCatalogItem.of("account-a-model", "Account A Model");
        var cached = List.of(selected, SpeechToTextCatalogItem.of("scribe_v2", "Scribe v2"));
        new SpeechToTextCatalogStore(repo).saveModels(ElevenLabsSpeechToTextProvider.ID, cached);
        repo.put(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID), selected.id());
        repo.put("chat4j.stt.elevenlabs.model.label", selected.label());
        String originalModels = repo.get(sttCatalogModelsKey(ElevenLabsSpeechToTextProvider.ID), "");
        String originalUpdatedAt = repo.get(sttCatalogUpdatedAtKey(ElevenLabsSpeechToTextProvider.ID), "");
        var provider = new FailingElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider))));

        SwingUtilities.invokeAndWait(() -> invokePanelMethod(subject, "refreshCatalogs", true));
        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertThat(repo.get(sttCatalogModelsKey(ElevenLabsSpeechToTextProvider.ID), "")).isEqualTo(originalModels);
        assertThat(repo.get(sttCatalogUpdatedAtKey(ElevenLabsSpeechToTextProvider.ID), "")).isEqualTo(originalUpdatedAt);
        assertThat(repo.get(sttModelIdKey(ElevenLabsSpeechToTextProvider.ID))).contains(selected.id());
        runOnEdt(() -> {
            @SuppressWarnings("unchecked")
            JComboBox<SpeechToTextCatalogItem> modelComboBox =
                    (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
            assertThat(comboModelIds(modelComboBox)).contains(selected.id());
            assertThat(((SpeechToTextCatalogItem) modelComboBox.getSelectedItem()).id()).isEqualTo(selected.id());
        });
        waitUntil(() -> statusLabelText(subject).contains("Could not refresh ElevenLabs Speech to Text models."));
        runOnEdt(subject::removeNotify);
    }

    @Test
    @DisplayName("Failed automatic ElevenLabs refresh preserves cached catalog without noisy status")
    void refreshControlsFromSettings_whenAutomaticElevenLabsRefreshFails_preservesCacheQuietly() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-elevenlabs-auto-cache.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, ElevenLabsSpeechToTextProvider.ID);
        var cached = List.of(SpeechToTextCatalogItem.of("scribe_v2", "Scribe v2"), SpeechToTextCatalogItem.of("scribe_v1", "Scribe v1 (deprecated)"));
        new SpeechToTextCatalogStore(repo).saveModels(ElevenLabsSpeechToTextProvider.ID, cached);
        repo.put(sttCatalogUpdatedAtKey(ElevenLabsSpeechToTextProvider.ID), "2000-01-01T00:00:00Z");
        CatalogSnapshotStore.forSettings(repo).cleanupOrphans();
        CatalogSnapshotStore.forSettings(repo).preloadActiveCatalogs();
        String originalModels = repo.get(sttCatalogModelsKey(ElevenLabsSpeechToTextProvider.ID), "");
        String originalUpdatedAt = repo.get(sttCatalogUpdatedAtKey(ElevenLabsSpeechToTextProvider.ID), "");
        var provider = new FailingElevenLabsProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider))));

        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertThat(repo.get(sttCatalogModelsKey(ElevenLabsSpeechToTextProvider.ID), "")).isEqualTo(originalModels);
        assertThat(repo.get(sttCatalogUpdatedAtKey(ElevenLabsSpeechToTextProvider.ID), "")).isEqualTo(originalUpdatedAt);
        assertThat(statusLabelText(subject)).doesNotContain("Could not refresh ElevenLabs Speech to Text models.");
        runOnEdt(subject::removeNotify);
    }

    @Test
    @DisplayName("Deepgram catalog refresh uses Deepgram endpoint context")
    void refreshCatalogs_whenDeepgramSelected_usesDeepgramEndpointContext() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-deepgram-refresh.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, DeepgramSpeechToTextProvider.ID);
        var provider = new CapturingDeepgramProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider))));

        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> new SpeechToTextCatalogStore(repo).cachedModels(DeepgramSpeechToTextProvider.ID).stream()
                .anyMatch(model -> "nova-3".equals(model.id())));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertThat(provider.context.get().baseUri()).hasToString("https://api.deepgram.com");
        assertThat(provider.context.get().transcriptionUri()).hasToString("https://api.deepgram.com/v1/listen");
        runOnEdt(subject::removeNotify);
    }

    @Test
    @DisplayName("Failed explicit Deepgram refresh preserves cached catalog")
    void refreshCatalogs_whenDeepgramRefreshFails_preservesCachedCatalog() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-deepgram-cache.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, DeepgramSpeechToTextProvider.ID);
        var cached = List.of(SpeechToTextCatalogItem.of("nova-3", "Deepgram Nova 3"), SpeechToTextCatalogItem.of("nova-2-general", "Deepgram Nova 2 General"));
        new SpeechToTextCatalogStore(repo).saveModels(DeepgramSpeechToTextProvider.ID, cached);
        String originalModels = repo.get(sttCatalogModelsKey(DeepgramSpeechToTextProvider.ID), "");
        String originalUpdatedAt = repo.get(sttCatalogUpdatedAtKey(DeepgramSpeechToTextProvider.ID), "");
        var provider = new FailingDeepgramProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider))));

        SwingUtilities.invokeAndWait(() -> invokePanelMethod(subject, "refreshCatalogs", true));
        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertThat(repo.get(sttCatalogModelsKey(DeepgramSpeechToTextProvider.ID), "")).isEqualTo(originalModels);
        assertThat(repo.get(sttCatalogUpdatedAtKey(DeepgramSpeechToTextProvider.ID), "")).isEqualTo(originalUpdatedAt);
        waitUntil(() -> statusLabelText(subject).contains("Could not refresh Deepgram Speech to Text models."));
        runOnEdt(subject::removeNotify);
    }

    @Test
    @DisplayName("Failed automatic Deepgram refresh preserves cached catalog without noisy status")
    void refreshControlsFromSettings_whenAutomaticDeepgramRefreshFails_preservesCacheQuietly() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-deepgram-auto-cache.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, DeepgramSpeechToTextProvider.ID);
        var cached = List.of(SpeechToTextCatalogItem.of("nova-3", "Deepgram Nova 3"), SpeechToTextCatalogItem.of("nova-2-general", "Deepgram Nova 2 General"));
        new SpeechToTextCatalogStore(repo).saveModels(DeepgramSpeechToTextProvider.ID, cached);
        repo.put(sttCatalogUpdatedAtKey(DeepgramSpeechToTextProvider.ID), "2000-01-01T00:00:00Z");
        CatalogSnapshotStore.forSettings(repo).cleanupOrphans();
        CatalogSnapshotStore.forSettings(repo).preloadActiveCatalogs();
        String originalModels = repo.get(sttCatalogModelsKey(DeepgramSpeechToTextProvider.ID), "");
        String originalUpdatedAt = repo.get(sttCatalogUpdatedAtKey(DeepgramSpeechToTextProvider.ID), "");
        var provider = new FailingDeepgramProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider))));

        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
        });

        assertThat(repo.get(sttCatalogModelsKey(DeepgramSpeechToTextProvider.ID), "")).isEqualTo(originalModels);
        assertThat(repo.get(sttCatalogUpdatedAtKey(DeepgramSpeechToTextProvider.ID), "")).isEqualTo(originalUpdatedAt);
        assertThat(statusLabelText(subject)).doesNotContain("Could not refresh Deepgram Speech to Text models.");
        runOnEdt(subject::removeNotify);
    }

    @Test
    @DisplayName("Deepgram helper copy explains cloud upload and token storage options")
    void updateAvailability_whenDeepgramAvailable_showsDeepgramPrivacyCopy() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-deepgram-helper.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, DeepgramSpeechToTextProvider.ID);
        new SpeechToTextCatalogStore(repo).saveModels(DeepgramSpeechToTextProvider.ID, List.of(SpeechToTextCatalogItem.of("nova-3", "Deepgram Nova 3")));
        var subject = callOnEdt(() -> new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(new DeepgramTestProvider()))));
        assertThat(helperLabelText(subject))
                .contains("Using Deepgram.")
                .contains("Recorded audio is sent to Deepgram for transcription.");
        runOnEdt(subject::removeNotify);
    }

    @Test
    @DisplayName("AssemblyAI catalog refresh uses AssemblyAI endpoint context")
    void refreshCatalogs_whenAssemblyAiSelected_usesAssemblyAiEndpointContext() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-assemblyai-refresh.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, AssemblyAiSpeechToTextProvider.ID);
        new SpeechToTextCatalogStore(repo).saveModels(AssemblyAiSpeechToTextProvider.ID, List.of(SpeechToTextCatalogItem.of("assemblyai-auto", "AssemblyAI Automatic")));
        var provider = new CapturingAssemblyAiProvider();
        var subject = callOnEdt(() -> new SpeechToTextPanel(repo, tempDir.resolve("default-models"), new SpeechToTextProviderRegistry(List.of(provider))));

        SwingUtilities.invokeAndWait(() -> invokePanelMethod(subject, "refreshCatalogs", true));
        assertThat(provider.refreshed.await(2, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> statusLabelText(subject).contains("Speech to Text catalogs refreshed"));
        SwingUtilities.invokeAndWait(() -> {
        });
        assertThat(provider.context.get().baseUri()).hasToString("https://api.assemblyai.com");
        assertThat(provider.context.get().transcriptionUri()).hasToString("https://api.assemblyai.com/v2/transcript");
        runOnEdt(subject::removeNotify);
    }

    @Test
    @DisplayName("AssemblyAI helper copy explains cloud upload and token storage options")
    void updateAvailability_whenAssemblyAiAvailable_showsAssemblyAiPrivacyCopy() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-assemblyai-helper.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, AssemblyAiSpeechToTextProvider.ID);
        new SpeechToTextCatalogStore(repo).saveModels(AssemblyAiSpeechToTextProvider.ID, new AssemblyAiTestProvider().bundledModels());
        Path defaultModels = tempDir.resolve("assemblyai-helper-models");
        try (
                var voskModels = new VoskModelManagementService(repo, defaultModels, tempDir.resolve("assemblyai-helper-vosk-temp"));
                var whisperModels = new WhisperModelManagementService(repo, defaultModels, tempDir.resolve("assemblyai-helper-whisper-temp"), fakeWhisperRuntime(), new WhisperModelUsageTracker())
        ) {
            var subject = callOnEdt(() -> new SpeechToTextPanel(
                    repo,
                    defaultModels,
                    new SpeechToTextProviderRegistry(List.of(new AssemblyAiTestProvider())),
                    voskModels,
                    whisperModels
            ));
            try {
                assertThat(helperLabelText(subject))
                        .contains("Using AssemblyAI.")
                        .contains("Recorded audio is sent to AssemblyAI for transcription.");
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("AssemblyAI ignores stale cached catalog entries")
    void refreshControlsFromSettings_whenAssemblyAiCacheContainsUnknownModels_showsBundledModelsOnly() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-assemblyai-stale-cache.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, AssemblyAiSpeechToTextProvider.ID);
        new SpeechToTextCatalogStore(repo).saveModels(AssemblyAiSpeechToTextProvider.ID, List.of(
                SpeechToTextCatalogItem.of("assemblyai-auto", "Automatic"),
                SpeechToTextCatalogItem.of("stale-model", "Stale Model"),
                SpeechToTextCatalogItem.of("bad\tmodel", "Bad Model")
        ));
        Path defaultModels = tempDir.resolve("default-models");
        try (
                var voskModels = new VoskModelManagementService(repo, defaultModels, tempDir.resolve("vosk-temp"));
                var whisperModels = new WhisperModelManagementService(repo, defaultModels, tempDir.resolve("whisper-temp"), fakeWhisperRuntime(), new WhisperModelUsageTracker())
        ) {
            SpeechToTextPanel subject = callOnEdt(() -> new SpeechToTextPanel(
                    repo,
                    defaultModels,
                    new SpeechToTextProviderRegistry(List.of(new AssemblyAiTestProvider())),
                    voskModels,
                    whisperModels
            ));
            try {
                runOnEdt(() -> {
                    @SuppressWarnings("unchecked")
                    JComboBox<SpeechToTextCatalogItem> modelComboBox = (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");

                    assertThat(modelComboBox.getItemCount()).isEqualTo(3);
                    assertThat(comboModelIds(modelComboBox)).containsExactly("assemblyai-auto", "universal-3-5-pro", "universal-2");
                });
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Whisper local model controls hide Vosk-only import action")
    void refreshControlsFromSettings_whenWhisperSelected_hidesImportButton() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-whisper-import.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, WhisperSpeechToTextProvider.ID);
        Path defaultModels = tempDir.resolve("default-models");
        try (
                var voskModels = new VoskModelManagementService(repo, defaultModels, tempDir.resolve("vosk-temp"));
                var whisperModels = new WhisperModelManagementService(repo, defaultModels, tempDir.resolve("whisper-temp"), fakeWhisperRuntime(), new WhisperModelUsageTracker())
        ) {
            SpeechToTextPanel subject = callOnEdt(() -> new SpeechToTextPanel(repo, defaultModels, voskModels, whisperModels));
            try {
                runOnEdt(() -> {
                    JButton importModelButton = (JButton) fieldValue(subject, "importModelButton");

                    assertThat(importModelButton.isVisible()).isFalse();
                    assertThat(importModelButton.getToolTipText()).isNull();
                    assertThat(importModelButton.getAccessibleContext().getAccessibleName()).isBlank();
                });
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    @Test
    @DisplayName("Local model controls show selectable models for local Speech to Text providers")
    void refreshControlsFromSettings_whenProviderSupportsLocalModels_showsLocalModelList() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SpeechToTextSettings.PROVIDER_KEY, "local-test");
        new SpeechToTextCatalogStore(repo).saveModels("local-test", emptyList());
        Path defaultModels = tempDir.resolve("default-models");
        try (
                var voskModels = new VoskModelManagementService(repo, defaultModels, tempDir.resolve("vosk-temp"));
                var whisperModels = new WhisperModelManagementService(repo, defaultModels, tempDir.resolve("whisper-temp"), fakeWhisperRuntime(), new WhisperModelUsageTracker())
        ) {
            SpeechToTextPanel subject = callOnEdt(() -> new SpeechToTextPanel(
                    repo,
                    defaultModels,
                    new SpeechToTextProviderRegistry(List.of(new LocalTestSpeechToTextProvider())),
                    voskModels,
                    whisperModels
            ));
            try {
                runOnEdt(() -> assertLocalTestProviderModelList(subject));
            } finally {
                runOnEdt(subject::removeNotify);
            }
        }
    }

    private void assertLocalTestProviderModelList(SpeechToTextPanel subject) throws Exception {
        JPanel localModelsPanel = (JPanel) fieldValue(subject, "localModelsPanel");
        JTable localModelsTable = (JTable) fieldValue(subject, "localModelsTable");
        JButton downloadModelButton = (JButton) fieldValue(subject, "downloadModelButton");

        assertThat(localModelsPanel.isVisible()).isTrue();
        assertThat(localModelsTable.getRowCount()).isEqualTo(2);
        assertThat(localModelsTable.getValueAt(0, 0)).isEqualTo("Local Tiny");
        assertThat(localModelsTable.getValueAt(0, 1)).isEqualTo(Boolean.TRUE);
        assertThat(downloadModelButton.getText()).isEqualTo("Download");
        assertThat(downloadModelButton.isEnabled()).isTrue();
    }

    private String sttModelIdKey(String providerId) {
        return "chat4j.stt.%s.model.id".formatted(providerId);
    }

    private String sttCatalogModelsKey(String providerId) {
        return "chat4j.stt.catalog.%s.modelsFile".formatted(providerId);
    }

    private String sttCatalogUpdatedAtKey(String providerId) {
        return "chat4j.stt.catalog.%s.updatedAt".formatted(providerId);
    }

    private List<String> comboModelIds(JComboBox<SpeechToTextCatalogItem> comboBox) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < comboBox.getItemCount(); i++) {
            ids.add(comboBox.getItemAt(i).id());
        }
        return ids;
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

    private void invokeCatalogRefresh(
            SpeechToTextPanel subject,
            SpeechToTextSettingsSnapshot selection,
            List<SpeechToTextCatalogItem> models
    ) {
        try {
            Method method = SpeechToTextPanel.class.getDeclaredMethod(
                    "prepareCatalogRefreshSafely",
                    long.class,
                    SpeechToTextSettingsSnapshot.class,
                    List.class,
                    boolean.class
            );
            method.setAccessible(true);
            method.invoke(subject, 0L, selection, models, false);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String providerIdOf(Object providerOption) throws Exception {
        Method method = providerOption.getClass().getDeclaredMethod("providerId");
        method.setAccessible(true);
        return (String) method.invoke(providerOption);
    }

    private void selectProvider(SpeechToTextPanel subject, String expectedProviderId) {
        try {
            @SuppressWarnings("unchecked")
            JComboBox<Object> providerComboBox = (JComboBox<Object>) fieldValue(subject, "providerComboBox");
            for (int i = 0; i < providerComboBox.getItemCount(); i++) {
                Object item = providerComboBox.getItemAt(i);
                if (expectedProviderId.equals(providerIdOf(item))) {
                    providerComboBox.setSelectedItem(item);
                    return;
                }
            }
            throw new AssertionError("Provider option not found: " + expectedProviderId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void selectModel(SpeechToTextPanel subject, String expectedModelId) {
        try {
            @SuppressWarnings("unchecked")
            JComboBox<SpeechToTextCatalogItem> modelComboBox =
                    (JComboBox<SpeechToTextCatalogItem>) fieldValue(subject, "modelComboBox");
            SpeechToTextCatalogItem selected = range(0, modelComboBox.getItemCount())
                    .mapToObj(modelComboBox::getItemAt)
                    .filter(item -> expectedModelId.equals(item.id()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Model not found: %s".formatted(expectedModelId)));
            modelComboBox.setSelectedItem(selected);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void scheduleSave(SpeechToTextPanel subject, ThrowingAction action, Runnable onSuccess) throws Exception {
        scheduleSave(subject, "test-setting", action, onSuccess);
    }

    private void scheduleSave(
            SpeechToTextPanel subject,
            String target,
            ThrowingAction action,
            Runnable onSuccess
    ) throws Exception {
        Class<?> throwingRunnableClass = Class.forName("com.github.drafael.chat4j.settings.SpeechToTextPanel$ThrowingRunnable");
        Object throwingRunnable = Proxy.newProxyInstance(
                throwingRunnableClass.getClassLoader(),
                new Class<?>[] {throwingRunnableClass},
                (proxy, method, args) -> {
                    action.run();
                    return null;
                }
        );
        Method method = SpeechToTextPanel.class.getDeclaredMethod(
                "scheduleSave",
                String.class,
                throwingRunnableClass,
                Runnable.class
        );
        method.setAccessible(true);
        method.invoke(subject, target, throwingRunnable, onSuccess);
    }

    private void runOnEdt(ThrowingAction action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }

    private void runWhileEventDispatchThreadBlocked(ThrowingAction action) throws Exception {
        var edtBlocked = new CountDownLatch(1);
        var releaseEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                releaseEdt.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertThat(edtBlocked.await(2, TimeUnit.SECONDS)).isTrue();
            action.run();
        } finally {
            releaseEdt.countDown();
        }
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    private String statusLabelText(SpeechToTextPanel subject) throws Exception {
        return callOnEdt(() -> subject.statusLabel().getText());
    }

    private String helperLabelText(SpeechToTextPanel subject) throws Exception {
        return callOnEdt(() -> ((JTextArea) fieldValue(subject, "helperLabel")).getText());
    }

    private void waitUntil(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.met() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.met()).isTrue();
    }

    private static void interruptAndJoin(Thread worker) throws InterruptedException {
        if (worker == null) {
            return;
        }
        worker.interrupt();
        worker.join(2_000);
        assertThat(worker.isAlive()).isFalse();
    }

    private Object fieldValue(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private WhisperNativeRuntime fakeWhisperRuntime() {
        return new WhisperNativeRuntime(() -> new WhisperBinding() {
            @Override
            public void loadLibrary() {
            }

            @Override
            public WhisperContextHandle init(Path modelFile) {
                return null;
            }

            @Override
            public int full(WhisperContextHandle context, WhisperFullParams params, float[] samples, int numSamples) {
                return 0;
            }

            @Override
            public int fullNSegments(WhisperContextHandle context) {
                return 0;
            }

            @Override
            public String fullGetSegmentText(WhisperContextHandle context, int index) {
                return "";
            }
        });
    }

    private static final class BlockingProviderSettingsRepository extends SettingsRepository {
        private final CountDownLatch firstProviderSaveStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstProviderSave = new CountDownLatch(1);
        private boolean blockedFirstProviderSave;

        private BlockingProviderSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void put(String key, String value) {
            if (SpeechToTextSettings.PROVIDER_KEY.equals(key) && GroqSpeechToTextProvider.ID.equals(value) && !blockedFirstProviderSave) {
                blockedFirstProviderSave = true;
                firstProviderSaveStarted.countDown();
                try {
                    assertThat(releaseFirstProviderSave.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            super.put(key, value);
        }
    }

    private static final class FailingConditionalSettingsRepository extends SettingsRepository {
        private final AtomicInteger conditionalUpdatesUntilFailure = new AtomicInteger();

        private FailingConditionalSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        private void failAfterSuccessfulConditionalUpdates(int successfulUpdates) {
            conditionalUpdatesUntilFailure.set(successfulUpdates + 1);
        }

        @Override
        public boolean updateBatchIf(BooleanSupplier condition, Consumer<BatchUpdate> updates) {
            int remaining = conditionalUpdatesUntilFailure.get();
            if (remaining > 0 && conditionalUpdatesUntilFailure.decrementAndGet() == 0) {
                throw new IllegalStateException("conditional update failed");
            }
            return super.updateBatchIf(condition, updates);
        }
    }

    private static final class BlockingNthConditionalSettingsRepository extends SettingsRepository {
        private final CountDownLatch conditionalUpdateStarted = new CountDownLatch(1);
        private final CountDownLatch releaseConditionalUpdate = new CountDownLatch(1);
        private final AtomicInteger conditionalUpdatesUntilBlock = new AtomicInteger();

        private BlockingNthConditionalSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        private void blockAfterSuccessfulConditionalUpdates(int successfulUpdates) {
            conditionalUpdatesUntilBlock.set(successfulUpdates + 1);
        }

        @Override
        public boolean updateBatchIf(BooleanSupplier condition, Consumer<BatchUpdate> updates) {
            int remaining = conditionalUpdatesUntilBlock.get();
            if (remaining > 0 && conditionalUpdatesUntilBlock.decrementAndGet() == 0) {
                conditionalUpdateStarted.countDown();
                try {
                    assertThat(releaseConditionalUpdate.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return super.updateBatchIf(condition, updates);
        }
    }

    private static final class BlockingConditionalSettingsRepository extends SettingsRepository {
        private final CountDownLatch conditionalUpdateStarted = new CountDownLatch(1);
        private final CountDownLatch releaseConditionalUpdate = new CountDownLatch(1);
        private volatile boolean blockNextConditionalUpdate;

        private BlockingConditionalSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public boolean updateBatchIf(BooleanSupplier condition, Consumer<BatchUpdate> updates) {
            if (blockNextConditionalUpdate) {
                blockNextConditionalUpdate = false;
                conditionalUpdateStarted.countDown();
                try {
                    assertThat(releaseConditionalUpdate.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return super.updateBatchIf(condition, updates);
        }
    }

    private static final class RefreshCountingVoskModelManagementService extends VoskModelManagementService {
        private final CountDownLatch refreshRequested = new CountDownLatch(1);

        private RefreshCountingVoskModelManagementService(SettingsRepository settingsRepo, Path modelRoot, Path tempRoot) {
            super(settingsRepo, modelRoot, tempRoot);
        }

        @Override
        public void refreshAsync() {
            refreshRequested.countDown();
        }
    }

    private static final class RefreshCountingWhisperModelManagementService extends WhisperModelManagementService {
        private final CountDownLatch refreshRequested = new CountDownLatch(1);
        private boolean probeRuntime;

        private RefreshCountingWhisperModelManagementService(
                SettingsRepository settingsRepo,
                Path modelRoot,
                Path tempRoot,
                WhisperNativeRuntime nativeRuntime
        ) {
            super(settingsRepo, modelRoot, tempRoot, nativeRuntime, new WhisperModelUsageTracker());
        }

        @Override
        public void refreshAsync(boolean probeRuntime) {
            this.probeRuntime = probeRuntime;
            refreshRequested.countDown();
        }
    }

    private static final class ThrowingRefreshVoskModelManagementService extends VoskModelManagementService {
        private ThrowingRefreshVoskModelManagementService(SettingsRepository settingsRepo, Path modelRoot, Path tempRoot) {
            super(settingsRepo, modelRoot, tempRoot);
        }

        @Override
        public void refreshAsync() {
            throw new IllegalStateException("refresh unavailable");
        }
    }

    private static final class CompletingVoskModelManagementService extends VoskModelManagementService {
        private final Path modelRoot;
        private final Path tempRoot;
        private Consumer<VoskModelManagementSnapshot> listener = snapshot -> {
        };

        private CompletingVoskModelManagementService(SettingsRepository settingsRepo, Path modelRoot, Path tempRoot) {
            super(settingsRepo, modelRoot, tempRoot);
            this.modelRoot = modelRoot;
            this.tempRoot = tempRoot;
        }

        @Override
        public VoskModelManagementSnapshot snapshot() {
            return newBusySnapshot(modelRoot, tempRoot);
        }

        @Override
        public Runnable addListener(Consumer<VoskModelManagementSnapshot> listener) {
            this.listener = listener;
            listener.accept(snapshot());
            return () -> this.listener = snapshot -> {
            };
        }

        private void completeWithStatus(String status) {
            listener.accept(new VoskModelManagementSnapshot(
                    modelRoot.resolve(VoskSpeechToTextProvider.ID),
                    tempRoot,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    null,
                    true,
                    status,
                    false,
                    status
            ));
        }
    }

    private static final class RapidCompletingVoskModelManagementService extends VoskModelManagementService {
        private final Path modelRoot;
        private final Path tempRoot;
        private Consumer<VoskModelManagementSnapshot> listener = snapshot -> {
        };

        private RapidCompletingVoskModelManagementService(SettingsRepository settingsRepo, Path modelRoot, Path tempRoot) {
            super(settingsRepo, modelRoot, tempRoot);
            this.modelRoot = modelRoot;
            this.tempRoot = tempRoot;
        }

        @Override
        public VoskModelManagementSnapshot snapshot() {
            return VoskModelManagementSnapshot.empty(modelRoot.resolve(VoskSpeechToTextProvider.ID), tempRoot);
        }

        @Override
        public Runnable addListener(Consumer<VoskModelManagementSnapshot> listener) {
            this.listener = listener;
            listener.accept(snapshot());
            return () -> this.listener = snapshot -> {
            };
        }

        private void startAndCompleteWithStatus(String status) {
            listener.accept(newBusySnapshot(modelRoot, tempRoot));
            listener.accept(new VoskModelManagementSnapshot(
                    modelRoot.resolve(VoskSpeechToTextProvider.ID),
                    tempRoot,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    null,
                    true,
                    status,
                    false,
                    status
            ));
        }

        private void publishIdleSnapshot() {
            listener.accept(snapshot());
        }
    }

    private static final class PlausibleVoskModelManagementService extends VoskModelManagementService {
        private final VoskModelManagementSnapshot plausibleSnapshot;

        private PlausibleVoskModelManagementService(SettingsRepository settingsRepo, Path modelRoot, Path tempRoot) {
            super(settingsRepo, modelRoot, tempRoot);
            Path root = modelRoot.resolve(VoskSpeechToTextProvider.ID);
            Path modelDirectory = root.resolve("custom-plausible");
            var installedModel = new VoskInstalledModel(
                    "local:custom-plausible",
                    "custom-plausible",
                    modelDirectory,
                    modelDirectory,
                    null,
                    true,
                    false,
                    true,
                    VoskValidationStatus.PLAUSIBLE_UNVERIFIED,
                    "Model layout looks plausible but needs validation before recording.",
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
                    installedModel.validationMessage()
            );
            plausibleSnapshot = new VoskModelManagementSnapshot(
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
            return plausibleSnapshot;
        }

        @Override
        public Runnable addListener(Consumer<VoskModelManagementSnapshot> listener) {
            listener.accept(plausibleSnapshot);
            return () -> {
            };
        }
    }

    private static final class RefreshingVoskModelManagementService extends VoskModelManagementService {
        private final VoskModelManagementSnapshot refreshingSnapshot;

        private RefreshingVoskModelManagementService(SettingsRepository settingsRepo, Path modelRoot, Path tempRoot, String status) {
            super(settingsRepo, modelRoot, tempRoot);
            refreshingSnapshot = new VoskModelManagementSnapshot(
                    modelRoot.resolve(VoskSpeechToTextProvider.ID),
                    tempRoot,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    null,
                    true,
                    status,
                    true,
                    status
            );
        }

        @Override
        public VoskModelManagementSnapshot snapshot() {
            return refreshingSnapshot;
        }

        @Override
        public Runnable addListener(Consumer<VoskModelManagementSnapshot> listener) {
            listener.accept(refreshingSnapshot);
            return () -> {
            };
        }
    }

    private static final class RecoveringBusyVoskModelManagementService extends VoskModelManagementService {
        private volatile VoskModelManagementSnapshot currentSnapshot;

        private RecoveringBusyVoskModelManagementService(SettingsRepository settingsRepo, Path modelRoot, Path tempRoot) {
            super(settingsRepo, modelRoot, tempRoot);
            currentSnapshot = newBusySnapshot(modelRoot, tempRoot);
        }

        @Override
        public VoskModelManagementSnapshot snapshot() {
            return currentSnapshot;
        }

        @Override
        public Runnable addListener(Consumer<VoskModelManagementSnapshot> listener) {
            listener.accept(currentSnapshot);
            return () -> {
            };
        }

        private void finishOperation() {
            currentSnapshot = VoskModelManagementSnapshot.empty(currentSnapshot.modelRoot(), currentSnapshot.tempRoot());
        }
    }

    private static final class BusyVoskModelManagementService extends VoskModelManagementService {
        private final VoskModelManagementSnapshot busySnapshot;

        private BusyVoskModelManagementService(SettingsRepository settingsRepo, Path modelRoot, Path tempRoot) {
            super(settingsRepo, modelRoot, tempRoot);
            busySnapshot = newBusySnapshot(modelRoot, tempRoot);
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

    private static VoskModelManagementSnapshot newBusySnapshot(Path modelRoot, Path tempRoot) {
        return new VoskModelManagementSnapshot(
                modelRoot.resolve(VoskSpeechToTextProvider.ID),
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

    private static final class AsyncSelectionVoskModelManagementService extends VoskModelManagementService {
        private VoskModelManagementSnapshot currentSnapshot;
        private Consumer<VoskModelManagementSnapshot> listener = snapshot -> {
        };
        private String selectedModelId = "";

        private AsyncSelectionVoskModelManagementService(SettingsRepository settingsRepo, Path modelRoot, Path tempRoot) {
            super(settingsRepo, modelRoot, tempRoot);
            Path root = modelRoot.resolve(VoskSpeechToTextProvider.ID);
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
            return ElevenLabsSpeechToTextProvider.ID;
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

    private static final class AuthoritativeElevenLabsProvider extends ElevenLabsTestProvider {
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicReference<Thread> refreshThread = new AtomicReference<>();

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
            refreshThread.set(Thread.currentThread());
            try {
                return List.of(SpeechToTextCatalogItem.of("account-b-model", "Account B Model"));
            } finally {
                completed.countDown();
            }
        }
    }

    private static final class CredentialRefreshingElevenLabsProvider extends ElevenLabsTestProvider {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch firstFinished = new CountDownLatch(1);
        private final CountDownLatch secondStarted = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String requiredEnvVar() {
            return "CHAT4J_TEST_ELEVENLABS_TOKEN";
        }

        @Override
        public boolean available(CredentialSource credentialSource) {
            return true;
        }

        @Override
        public String availableMessage() {
            return "Using ElevenLabs with test credentials.";
        }

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) throws Exception {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release first catalog refresh.");
                }
                firstFinished.countDown();
                return List.of(SpeechToTextCatalogItem.of("stale-scribe", "Stale Scribe"));
            }
            secondStarted.countDown();
            return List.of(SpeechToTextCatalogItem.of("fresh-scribe", "Fresh Scribe"));
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
            return DeepgramSpeechToTextProvider.ID;
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
        private final AtomicReference<Thread> refreshThread = new AtomicReference<>();

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
            this.context.set(context);
            refreshThread.set(Thread.currentThread());
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

    private static class AssemblyAiTestProvider implements SpeechToTextProvider {

        @Override
        public String id() {
            return AssemblyAiSpeechToTextProvider.ID;
        }

        @Override
        public String displayName() {
            return "AssemblyAI";
        }

        @Override
        public String requiredEnvVar() {
            return "";
        }

        @Override
        public SpeechToTextCatalogItem defaultModel() {
            return SpeechToTextCatalogItem.of("assemblyai-auto", "AssemblyAI Automatic (Universal-3.5 Pro → Universal-2)");
        }

        @Override
        public List<SpeechToTextCatalogItem> bundledModels() {
            return List.of(
                    defaultModel(),
                    SpeechToTextCatalogItem.of("universal-3-5-pro", "AssemblyAI Universal-3.5 Pro"),
                    SpeechToTextCatalogItem.of("universal-2", "AssemblyAI Universal-2")
            );
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

    private static final class CapturingAssemblyAiProvider extends AssemblyAiTestProvider {
        private final CountDownLatch refreshed = new CountDownLatch(1);
        private final AtomicReference<SpeechToTextProviderContext> context = new AtomicReference<>();

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
            this.context.set(context);
            refreshed.countDown();
            return bundledModels();
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
        boolean met() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
