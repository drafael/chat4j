package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.persistence.db.ChatStorageSettings;
import com.github.drafael.chat4j.persistence.db.StorageBackend;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class GeneralPanelTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Invalid initial send-key value is normalized and persisted to the default")
    void constructor_whenSendKeyInvalid_normalizesAndPersistsDefault() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("general-panel-invalid-send-key");
        settingsRepo.put("chat4j.chat.input.sendKey", "Space");
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(settingsRepo));
        try {
            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get("chat4j.chat.input.sendKey")).contains("Enter");
        } finally {
            runOnEdt(subject::disposePanel);
        }
    }

    @Test
    @DisplayName("Invalid initial render mode value is normalized and persisted to preview")
    void constructor_whenRenderModeInvalid_normalizesAndPersistsPreview() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("general-panel-invalid-render-mode");
        settingsRepo.put("chat4j.chat.render.mode", "side-by-side");
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(settingsRepo));
        try {
            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get("chat4j.chat.render.mode")).contains(RenderMode.PREVIEW.settingValue());
        } finally {
            runOnEdt(subject::disposePanel);
        }
    }

    @Test
    @DisplayName("General settings no longer contain the Agent Mode prompt addendum")
    void constructor_whenPanelCreated_excludesAgentModePrompt() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("general-panel-without-agent-mode");
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(settingsRepo));
        try {
            assertThat(callOnEdt(() -> findComponentByNameOrNull(
                    subject,
                    "agentSystemPromptAppendArea",
                    Component.class
            ))).isNull();
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Combo binding save failures show an error without reporting saved")
    void updateSendKey_whenSaveFails_showsErrorOnly() throws Exception {
        var settingsRepo = new ThrowingSettingsRepo(tempDir.resolve("general-panel-combo-failure.properties"));
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(settingsRepo));
        try {
            JComboBox<String> sendKey = callOnEdt(() -> findComponentByName(subject, "sendKeyComboBox", JComboBox.class));
            runOnEdt(() -> sendKey.setSelectedItem(ChatBehaviorSettings.SEND_CTRL_ENTER));

            assertThat(awaitSave(subject)).isFalse();
            assertThat(callOnEdt(() -> subject.statusLabel().getText()))
                    .contains("Failed to save send key setting").doesNotContain("Saved");
            assertThat(callOnEdt(sendKey::getSelectedItem)).isEqualTo(ChatBehaviorSettings.SEND_CTRL_ENTER);
        } finally {
            runOnEdt(subject::disposePanel);
        }
    }

    @Test
    @DisplayName("A failed current selection remains visible and is retried on close")
    void savePendingChangesAsync_whenCurrentSelectionFailed_retriesVisibleIntent() throws Exception {
        var settingsRepo = new RetryingSettingsRepo(tempDir.resolve("general-panel-retry.properties"));
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(settingsRepo));
        try {
            JComboBox<String> sendKey = callOnEdt(
                    () -> findComponentByName(subject, "sendKeyComboBox", JComboBox.class)
            );
            runOnEdt(() -> sendKey.setSelectedItem(ChatBehaviorSettings.SEND_CTRL_ENTER));

            assertThat(awaitSave(subject)).isFalse();
            assertThat(callOnEdt(sendKey::getSelectedItem)).isEqualTo(ChatBehaviorSettings.SEND_CTRL_ENTER);

            settingsRepo.failWrites = false;
            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get("chat4j.chat.input.sendKey")).contains(ChatBehaviorSettings.SEND_CTRL_ENTER);
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A successful save does not hide another setting's current failure")
    void updateDifferentSettings_whenOneSaveFails_keepsFailureVisible() throws Exception {
        var settingsRepo = new SelectiveFailureSettingsRepo(tempDir.resolve("general-panel-cross-target.properties"));
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(settingsRepo));
        try {
            JCheckBox autoScroll = callOnEdt(
                    () -> findComponentByName(subject, "autoScrollCheckBox", JCheckBox.class)
            );
            JComboBox<String> sendKey = callOnEdt(
                    () -> findComponentByName(subject, "sendKeyComboBox", JComboBox.class)
            );
            runOnEdt(() -> {
                autoScroll.doClick();
                sendKey.setSelectedItem(ChatBehaviorSettings.SEND_CTRL_ENTER);
            });

            assertThat(awaitSave(subject)).isFalse();
            assertThat(callOnEdt(() -> subject.statusLabel().getText()))
                    .contains("Failed to save auto-scroll setting").doesNotContain("Saved");
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Checkbox binding save failures show an error without reporting saved")
    void updateAutoScroll_whenSaveFails_showsErrorOnly() throws Exception {
        var settingsRepo = new ThrowingSettingsRepo(tempDir.resolve("general-panel-checkbox-failure.properties"));
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(settingsRepo));
        try {
            JCheckBox autoScroll = callOnEdt(() -> findComponentByName(subject, "autoScrollCheckBox", JCheckBox.class));
            runOnEdt(autoScroll::doClick);

            assertThat(awaitSave(subject)).isFalse();
            assertThat(callOnEdt(() -> subject.statusLabel().getText()))
                    .contains("Failed to save auto-scroll setting").doesNotContain("Saved");
        } finally {
            runOnEdt(subject::disposePanel);
        }
    }

    @Test
    @DisplayName("Failed pending storage writes do not open restart prompt or report saved")
    void updateStorageBackend_whenPendingWriteFails_doesNotPromptOrShowSaved() throws Exception {
        var promptCalled = new AtomicBoolean(false);
        var settingsRepo = new ThrowingSettingsRepo(tempDir.resolve("general-panel-storage-failure.properties"));
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(
                settingsRepo,
                () -> {
                },
                new ChatBehaviorSettings(settingsRepo),
                new RenderModeSettings(settingsRepo),
                new ChatStorageSettings(settingsRepo),
                (activeBackend, selectedBackend) -> {
                    promptCalled.set(true);
                    return RestartRequiredDialog.Choice.LATER;
                }
        ));
        try {
            JComboBox<StorageBackend> storageBackend = callOnEdt(
                    () -> findComponentByName(subject, "storageBackendComboBox", JComboBox.class));
            runOnEdt(() -> storageBackend.setSelectedItem(StorageBackend.H2));

            assertThat(awaitSave(subject)).isFalse();
            assertThat(promptCalled).isFalse();
            assertThat(callOnEdt(() -> subject.statusLabel().getText()))
                    .contains("Failed to save chat storage setting");
        } finally {
            runOnEdt(subject::disposePanel);
        }
    }

    @Test
    @DisplayName("Storage exit waits until other failed General settings are saved")
    void updateStorageBackend_whenAnotherSettingFailed_defersExitUntilRetrySucceeds() throws Exception {
        var settingsRepo = new SelectiveFailureSettingsRepo(
                tempDir.resolve("general-panel-deferred-storage-exit.properties")
        );
        var promptCalled = new AtomicBoolean();
        var exitCalled = new AtomicBoolean();
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(
                settingsRepo,
                () -> exitCalled.set(true),
                new ChatBehaviorSettings(settingsRepo),
                new RenderModeSettings(settingsRepo),
                new ChatStorageSettings(settingsRepo),
                (activeBackend, selectedBackend) -> {
                    promptCalled.set(true);
                    return RestartRequiredDialog.Choice.EXIT_NOW;
                }
        ));
        try {
            JCheckBox autoScroll = callOnEdt(
                    () -> findComponentByName(subject, "autoScrollCheckBox", JCheckBox.class)
            );
            JComboBox<StorageBackend> storageBackend = callOnEdt(
                    () -> findComponentByName(subject, "storageBackendComboBox", JComboBox.class)
            );
            runOnEdt(() -> {
                autoScroll.doClick();
                storageBackend.setSelectedItem(StorageBackend.H2);
            });

            assertThat(awaitSave(subject)).isFalse();
            assertThat(promptCalled).isFalse();
            assertThat(exitCalled).isFalse();

            settingsRepo.failAutoScroll = false;
            assertThat(awaitSave(subject)).isTrue();
            assertThat(promptCalled).isTrue();
            assertThat(exitCalled).isTrue();
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A deferred restart callback failure does not retry an already durable storage change")
    void savePendingChangesAsync_whenDeferredStorageCallbackFails_keepsDurableStorageChange() throws Exception {
        var settingsRepo = new SelectiveFailureSettingsRepo(
                tempDir.resolve("general-panel-deferred-storage-callback-failure.properties")
        );
        var promptCalls = new AtomicInteger();
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(
                settingsRepo,
                () -> {
                },
                new ChatBehaviorSettings(settingsRepo),
                new RenderModeSettings(settingsRepo),
                new ChatStorageSettings(settingsRepo),
                (activeBackend, selectedBackend) -> {
                    if (promptCalls.incrementAndGet() == 1) {
                        throw new IllegalStateException("forced callback failure");
                    }
                    return RestartRequiredDialog.Choice.LATER;
                }
        ));
        try {
            JCheckBox autoScroll = callOnEdt(
                    () -> findComponentByName(subject, "autoScrollCheckBox", JCheckBox.class)
            );
            JComboBox<StorageBackend> storageBackend = callOnEdt(
                    () -> findComponentByName(subject, "storageBackendComboBox", JComboBox.class)
            );
            runOnEdt(() -> {
                autoScroll.doClick();
                storageBackend.setSelectedItem(StorageBackend.H2);
            });

            assertThat(awaitSave(subject)).isFalse();
            settingsRepo.failAutoScroll = false;
            assertThat(awaitSave(subject)).isTrue();
            assertThat(callOnEdt(() -> subject.statusLabel().getText()))
                    .contains("Chat storage was saved, but the restart prompt failed");
            assertThat(new ChatStorageSettings(settingsRepo).load().pendingMigrationTarget()).contains(StorageBackend.H2);

            JComboBox<String> sendKey = callOnEdt(
                    () -> findComponentByName(subject, "sendKeyComboBox", JComboBox.class)
            );
            runOnEdt(() -> sendKey.setSelectedItem(ChatBehaviorSettings.SEND_CTRL_ENTER));
            assertThat(awaitSave(subject)).isTrue();
            assertThat(promptCalls).hasValue(1);
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A failing restart callback does not turn a durable storage change into a failed save")
    void updateStorageBackend_whenRestartCallbackFails_reportsFollowUpFailure() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("general-panel-restart-callback-failure");
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(
                settingsRepo,
                () -> {
                },
                new ChatBehaviorSettings(settingsRepo),
                new RenderModeSettings(settingsRepo),
                new ChatStorageSettings(settingsRepo),
                (activeBackend, selectedBackend) -> {
                    throw new IllegalStateException("forced callback failure");
                }
        ));
        try {
            JComboBox<StorageBackend> storageBackend = callOnEdt(
                    () -> findComponentByName(subject, "storageBackendComboBox", JComboBox.class)
            );
            runOnEdt(() -> storageBackend.setSelectedItem(StorageBackend.H2));

            assertThat(awaitSave(subject)).isTrue();
            assertThat(new ChatStorageSettings(settingsRepo).load().pendingMigrationTarget()).contains(StorageBackend.H2);
            assertThat(callOnEdt(() -> subject.statusLabel().getText()))
                    .contains("chat storage was saved, but the follow-up action failed");
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Storage persistence stays off the EDT and prompts only after durable success")
    void updateStorageBackend_whenWriteBlocks_keepsEdtResponsiveAndDelaysPrompt() throws Exception {
        var settingsRepo = new BlockingSettingsRepo(tempDir.resolve("general-panel-blocking.properties"));
        var promptCalled = new AtomicBoolean();
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(
                settingsRepo,
                () -> {
                },
                new ChatBehaviorSettings(settingsRepo),
                new RenderModeSettings(settingsRepo),
                new ChatStorageSettings(settingsRepo),
                (activeBackend, selectedBackend) -> {
                    promptCalled.set(true);
                    return RestartRequiredDialog.Choice.LATER;
                }
        ));
        try {
            JComboBox<StorageBackend> storageBackend = callOnEdt(
                    () -> findComponentByName(subject, "storageBackendComboBox", JComboBox.class));
            runOnEdt(() -> storageBackend.setSelectedItem(StorageBackend.H2));
            assertThat(settingsRepo.writeStarted.await(5, TimeUnit.SECONDS)).isTrue();

            AtomicBoolean sentinelRan = new AtomicBoolean();
            runOnEdt(() -> sentinelRan.set(true));
            assertThat(sentinelRan).isTrue();
            assertThat(promptCalled).isFalse();

            settingsRepo.releaseWrite.countDown();
            assertThat(awaitSave(subject)).isTrue();
            assertThat(new ChatStorageSettings(settingsRepo).load().pendingMigrationTarget()).contains(StorageBackend.H2);
            assertThat(promptCalled).isTrue();
        } finally {
            settingsRepo.releaseWrite.countDown();
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A stale failed write cannot override a newer value for the same setting")
    void updateAutoScroll_whenOlderWriteFails_keepsNewerSuccessfulValue() throws Exception {
        var settingsRepo = new BlockingFirstAutoScrollSettingsRepo(
                tempDir.resolve("general-panel-stale-auto-scroll.properties")
        );
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(settingsRepo));
        try {
            JCheckBox autoScroll = callOnEdt(
                    () -> findComponentByName(subject, "autoScrollCheckBox", JCheckBox.class)
            );
            runOnEdt(() -> {
                autoScroll.doClick();
                autoScroll.doClick();
            });
            assertThat(settingsRepo.firstWriteStarted.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Boolean> pendingSave = callOnEdt(subject::savePendingChangesAsync);
            settingsRepo.releaseFirstWrite.countDown();

            assertThat(pendingSave.get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertThat(settingsRepo.get("chat4j.chat.behavior.autoScroll")).contains("true");
            assertThat(callOnEdt(() -> subject.statusLabel().getText())).doesNotContain("Failed");
        } finally {
            settingsRepo.releaseFirstWrite.countDown();
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Permanent disposal lets an accepted storage write finish without showing its restart prompt")
    void disposePanel_whenStorageWriteCompletesLater_suppressesRestartPrompt() throws Exception {
        var settingsRepo = new BlockingStorageSettingsRepo(
                tempDir.resolve("general-panel-disposed-storage.properties")
        );
        var promptCalled = new AtomicBoolean();
        GeneralPanel subject = callOnEdt(() -> new GeneralPanel(
                settingsRepo,
                () -> {
                },
                new ChatBehaviorSettings(settingsRepo),
                new RenderModeSettings(settingsRepo),
                new ChatStorageSettings(settingsRepo),
                (activeBackend, selectedBackend) -> {
                    promptCalled.set(true);
                    return RestartRequiredDialog.Choice.LATER;
                }
        ));
        try {
            JComboBox<StorageBackend> storageBackend = callOnEdt(
                    () -> findComponentByName(subject, "storageBackendComboBox", JComboBox.class)
            );
            runOnEdt(() -> storageBackend.setSelectedItem(StorageBackend.H2));
            assertThat(settingsRepo.writeStarted.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Boolean> pendingSave = callOnEdt(subject::savePendingChangesAsync);

            runOnEdt(subject::disposePanel);
            settingsRepo.releaseWrite.countDown();

            assertThat(pendingSave.get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertThat(new ChatStorageSettings(settingsRepo).load().pendingMigrationTarget()).contains(StorageBackend.H2);
            assertThat(promptCalled).isFalse();
        } finally {
            settingsRepo.releaseWrite.countDown();
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    private boolean awaitSave(GeneralPanel subject) throws Exception {
        boolean saved = callOnEdt(subject::savePendingChangesAsync).get(10, TimeUnit.SECONDS);
        flushEdt();
        return saved;
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private <T extends Component> T findComponentByName(Container root, String name, Class<T> type) {
        T found = findComponentByNameOrNull(root, name, type);
        assertThat(found).isNotNull();
        return found;
    }

    private <T extends Component> T findComponentByNameOrNull(Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (name.equals(component.getName()) && type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container container) {
                T found = findComponentByNameOrNull(container, name, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void runOnEdt(Runnable action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private void flushEdt() throws Exception {
        runOnEdt(() -> {
        });
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        var value = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(action.call());
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
        return value.get();
    }

    private static final class BlockingSettingsRepo extends SettingsRepository {
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseWrite = new CountDownLatch(1);

        private BlockingSettingsRepo(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void put(String key, String value) {
            writeStarted.countDown();
            try {
                if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release settings write");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to release settings write", e);
            }
            super.put(key, value);
        }
    }

    private static final class BlockingFirstAutoScrollSettingsRepo extends SettingsRepository {
        private static final String AUTO_SCROLL_KEY = "chat4j.chat.behavior.autoScroll";

        private final CountDownLatch firstWriteStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        private final AtomicInteger autoScrollWrites = new AtomicInteger();

        private BlockingFirstAutoScrollSettingsRepo(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void put(String key, String value) {
            if (AUTO_SCROLL_KEY.equals(key) && autoScrollWrites.incrementAndGet() == 1) {
                firstWriteStarted.countDown();
                await(releaseFirstWrite, "first auto-scroll write");
                throw new IllegalStateException("forced stale failure");
            }
            super.put(key, value);
        }
    }

    private static final class BlockingStorageSettingsRepo extends SettingsRepository {
        private static final String PENDING_BACKEND_KEY = "chat.storage.backend.pending";

        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseWrite = new CountDownLatch(1);

        private BlockingStorageSettingsRepo(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void put(String key, String value) {
            if (PENDING_BACKEND_KEY.equals(key)) {
                writeStarted.countDown();
                await(releaseWrite, "storage write");
            }
            super.put(key, value);
        }
    }

    private static void await(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release %s".formatted(operation));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to release %s".formatted(operation), e);
        }
    }

    private static final class RetryingSettingsRepo extends SettingsRepository {
        private volatile boolean failWrites = true;

        private RetryingSettingsRepo(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void put(String key, String value) {
            if (failWrites) {
                throw new IllegalStateException("forced failure");
            }
            super.put(key, value);
        }
    }

    private static final class SelectiveFailureSettingsRepo extends SettingsRepository {
        private volatile boolean failAutoScroll = true;

        private SelectiveFailureSettingsRepo(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void put(String key, String value) {
            if (failAutoScroll && "chat4j.chat.behavior.autoScroll".equals(key)) {
                throw new IllegalStateException("forced failure");
            }
            super.put(key, value);
        }
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void put(String key, String value) {
            throw new IllegalStateException("forced failure");
        }

        @Override
        public void remove(String key) {
            throw new IllegalStateException("forced failure");
        }
    }
}
