package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.SpeechToTextProviderRegistry;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import com.github.drafael.chat4j.stt.provider.SpeechToTextResult;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    @DisplayName("Local model controls show selectable models for local Speech to Text providers")
    void refreshControlsFromSettings_whenProviderSupportsLocalModels_showsLocalModelList() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        repo.put(SettingsKeys.STT_PROVIDER, "local-test");
        var subject = new SpeechToTextPanel(
                repo,
                tempDir.resolve("default-models"),
                new SpeechToTextProviderRegistry(List.of(new LocalTestSpeechToTextProvider()))
        );

        JPanel localModelsPanel = (JPanel) fieldValue(subject, "localModelsPanel");
        JTable localModelsTable = (JTable) fieldValue(subject, "localModelsTable");
        JButton downloadModelButton = (JButton) fieldValue(subject, "downloadModelButton");

        assertThat(localModelsPanel.isVisible()).isTrue();
        assertThat(localModelsTable.getRowCount()).isEqualTo(2);
        assertThat(localModelsTable.getValueAt(0, 0)).isEqualTo("Local Tiny");
        assertThat(localModelsTable.getValueAt(0, 1)).isEqualTo(Boolean.TRUE);
        assertThat(downloadModelButton.isEnabled()).isTrue();
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
