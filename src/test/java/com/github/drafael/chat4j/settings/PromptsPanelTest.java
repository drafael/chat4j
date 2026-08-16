package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.prompts.BuiltInPromptCatalog;
import com.github.drafael.chat4j.prompts.PromptCatalogRepo;
import com.github.drafael.chat4j.prompts.PromptTemplate;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class PromptsPanelTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Unchanged prompt panel does not persist a built-in snapshot")
    void savePendingChangesAsync_whenPanelIsUnchanged_doesNotWritePromptCatalog() throws Exception {
        var repo = new RecordingPromptCatalogRepo(promptsFile());
        PromptsPanel subject = callOnEdt(() -> new PromptsPanel(repo));
        try {
            awaitPromptLoad(subject);

            CompletableFuture<Boolean> save = callOnEdt(subject::savePendingChangesAsync);

            assertThat(save.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repo.saved).isFalse();
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Dirty prompt edits are captured on the EDT and saved asynchronously")
    void savePendingChangesAsync_whenPromptEdited_persistsSnapshot() throws Exception {
        var repo = new RecordingPromptCatalogRepo(promptsFile());
        PromptsPanel subject = callOnEdt(() -> new PromptsPanel(repo));
        try {
            awaitPromptLoad(subject);
            JTextField titleField = callOnEdt(
                    () -> findComponentByName(subject, "promptTitleField", JTextField.class)
            );
            runOnEdt(() -> titleField.setText("Edited title"));

            assertThat(callOnEdt(subject::savePendingChangesAsync).get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repo.savedPrompts).isNotNull();
            assertThat(repo.savedPrompts.getFirst().title()).isEqualTo("Edited title");
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A failed prompt save remains dirty and succeeds on retry")
    void savePendingChangesAsync_whenSaveFails_retriesCurrentSnapshot() throws Exception {
        var repo = new RetryingPromptCatalogRepo(promptsFile());
        PromptsPanel subject = callOnEdt(() -> new PromptsPanel(repo));
        try {
            awaitPromptLoad(subject);
            JTextField titleField = callOnEdt(
                    () -> findComponentByName(subject, "promptTitleField", JTextField.class)
            );
            runOnEdt(() -> titleField.setText("Retry title"));

            assertThat(callOnEdt(subject::savePendingChangesAsync).get(5, TimeUnit.SECONDS)).isFalse();
            repo.failSaves = false;
            assertThat(callOnEdt(subject::savePendingChangesAsync).get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repo.savedPrompts.getFirst().title()).isEqualTo("Retry title");
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("An older prompt save cannot clear a newer edit")
    void savePendingChangesAsync_whenEditChangesDuringSave_persistsNewerRevisionNext() throws Exception {
        var repo = new BlockingSavePromptCatalogRepo(promptsFile(), BuiltInPromptCatalog.prompts());
        PromptsPanel subject = callOnEdt(() -> new PromptsPanel(repo));
        try (var cleanup = new BlockingSaveCleanup(repo, subject)) {
            awaitPromptLoad(subject);
            JTextField titleField = callOnEdt(
                    () -> findComponentByName(subject, "promptTitleField", JTextField.class)
            );
            runOnEdt(() -> titleField.setText("First edit"));
            CompletableFuture<Boolean> firstSave = callOnEdt(subject::savePendingChangesAsync);
            cleanup.expect(firstSave);
            assertThat(repo.saveStarted.await(5, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> titleField.setText("Second edit"));

            repo.releaseSave.countDown();
            assertThat(firstSave.get(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Boolean> secondSave = callOnEdt(subject::savePendingChangesAsync);
            cleanup.expect(secondSave);
            assertThat(secondSave.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repo.savedSnapshots.getLast().getFirst().title()).isEqualTo("Second edit");
        }
    }

    @Test
    @DisplayName("Reset followed by an edit saves the newest reset-based snapshot")
    void resetToBuiltIns_whenEditedBeforeResetSaveCompletes_preservesNewerEdit() throws Exception {
        List<PromptTemplate> customPrompts = List.of(new PromptTemplate(
                "custom",
                "Custom",
                "Custom prompt",
                PromptTemplate.DEFAULT_MODEL,
                List.of()
        ));
        var repo = new BlockingSavePromptCatalogRepo(promptsFile(), customPrompts);
        PromptsPanel subject = callOnEdt(() -> new PromptsPanel(repo, () -> true));
        try (var cleanup = new BlockingSaveCleanup(repo, subject)) {
            awaitPromptLoad(subject);
            JButton resetButton = callOnEdt(
                    () -> findComponentByName(subject, "resetPromptsButton", JButton.class)
            );
            JTextField titleField = callOnEdt(
                    () -> findComponentByName(subject, "promptTitleField", JTextField.class)
            );
            runOnEdt(resetButton::doClick);
            cleanup.expectUnobservedSave();
            assertThat(repo.saveStarted.await(5, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> titleField.setText("Edited built-in"));

            repo.releaseSave.countDown();
            CompletableFuture<Boolean> latestSave = callOnEdt(subject::savePendingChangesAsync);
            cleanup.expect(latestSave);
            assertThat(latestSave.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repo.savedSnapshots.getLast().getFirst().title()).isEqualTo("Edited built-in");
        }
    }

    @Test
    @DisplayName("Prompt lists and variables use accessible icon actions with selection-aware removal")
    void actionToolbars_whenItemsChange_preservePromptAndVariableEditingBehavior() throws Exception {
        var repo = new RecordingPromptCatalogRepo(promptsFile());
        PromptsPanel subject = callOnEdt(() -> new PromptsPanel(repo));
        try {
            awaitPromptLoad(subject);
            runOnEdt(() -> {
                JButton addPrompt = findComponentByAccessibleName(subject, "Add prompt", JButton.class);
                JButton removePrompt = findComponentByAccessibleName(
                        subject,
                        "Remove selected prompt",
                        JButton.class
                );
                JList<?> promptList = findComponentByName(subject, "promptList", JList.class);
                assertThat(addPrompt.getText()).isNull();
                assertThat(addPrompt.getIcon()).isNotNull();
                assertThat(removePrompt.isEnabled()).isTrue();
                int promptCount = promptList.getModel().getSize();

                addPrompt.doClick();

                assertThat(promptList.getModel().getSize()).isEqualTo(promptCount + 1);
                assertThat(((PromptTemplate) promptList.getSelectedValue()).title()).isEqualTo("New Prompt");

                JTable variables = findComponentByAccessibleName(subject, "Prompt variables", JTable.class);
                JButton addVariable = findComponentByAccessibleName(subject, "Add variable", JButton.class);
                JButton removeVariable = findComponentByAccessibleName(
                        subject,
                        "Remove selected variable",
                        JButton.class
                );
                int variableCount = variables.getRowCount();
                assertThat(removeVariable.isEnabled()).isFalse();

                addVariable.doClick();
                variables.setRowSelectionInterval(variableCount, variableCount);

                assertThat(variables.getRowCount()).isEqualTo(variableCount + 1);
                assertThat(removeVariable.isEnabled()).isTrue();
                removeVariable.doClick();
                assertThat(variables.getRowCount()).isEqualTo(variableCount);
            });
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Prompt catalog loading does not block the event dispatch thread")
    void constructor_whenPromptLoadBlocks_keepsEdtResponsive() throws Exception {
        var repo = new BlockingPromptCatalogRepo(promptsFile());
        PromptsPanel subject = callOnEdt(() -> new PromptsPanel(repo));
        try (var ignored = new BlockingLoadCleanup(repo, subject)) {
            assertThat(repo.started.await(5, TimeUnit.SECONDS)).isTrue();
            AtomicBoolean sentinelRan = new AtomicBoolean();
            runOnEdt(() -> sentinelRan.set(true));

            assertThat(sentinelRan).isTrue();

            repo.release.countDown();
            assertThat(repo.finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repo.failure.get()).isNull();
            awaitPromptLoad(subject);
        }
    }

    @Test
    @DisplayName("A prompt load completion cannot update a permanently disposed panel")
    void disposePanel_whenPromptLoadCompletesLater_suppressesUiCompletion() throws Exception {
        var repo = new BlockingPromptCatalogRepo(promptsFile());
        PromptsPanel subject = callOnEdt(() -> new PromptsPanel(repo));
        try (var ignored = new BlockingLoadCleanup(repo, subject)) {
            assertThat(repo.started.await(5, TimeUnit.SECONDS)).isTrue();
            runOnEdt(subject::disposePanel);
            repo.release.countDown();
            assertThat(repo.finished.await(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();

            assertThat(callOnEdt(() -> subject.getComponent(0).isEnabled())).isFalse();
        }
    }

    private Path promptsFile() {
        return tempDir.resolve("prompts.json");
    }

    private void awaitPromptLoad(PromptsPanel subject) throws Exception {
        var loadedOnEdt = new CountDownLatch(1);
        runOnEdt(() -> {
            JButton resetButton = findComponentByName(subject, "resetPromptsButton", JButton.class);
            if (resetButton.isEnabled()) {
                loadedOnEdt.countDown();
                return;
            }
            resetButton.addPropertyChangeListener("enabled", event -> {
                if (Boolean.TRUE.equals(event.getNewValue())) {
                    loadedOnEdt.countDown();
                }
            });
        });
        assertThat(loadedOnEdt.await(5, TimeUnit.SECONDS)).isTrue();
        flushEdt();
    }

    private <T extends Component> T findComponentByAccessibleName(Container root, String name, Class<T> type) {
        return components(root, type).stream()
                .filter(component -> component.getAccessibleContext() != null)
                .filter(component -> name.equals(component.getAccessibleContext().getAccessibleName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Component not found: %s".formatted(name)));
    }

    private <T extends Component> List<T> components(Container root, Class<T> type) {
        var result = new ArrayList<T>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                result.add(type.cast(component));
            }
            if (component instanceof Container child) {
                result.addAll(components(child, type));
            }
        }
        return List.copyOf(result);
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
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return value.get();
    }

    private static class RecordingPromptCatalogRepo extends PromptCatalogRepo {
        private volatile boolean saved;
        protected volatile List<PromptTemplate> savedPrompts;

        private RecordingPromptCatalogRepo(Path promptsFile) {
            super(promptsFile);
        }

        @Override
        public List<PromptTemplate> load() {
            return BuiltInPromptCatalog.prompts();
        }

        @Override
        public void save(List<PromptTemplate> prompts) {
            saved = true;
            savedPrompts = List.copyOf(prompts);
        }
    }

    private static final class RetryingPromptCatalogRepo extends RecordingPromptCatalogRepo {
        private volatile boolean failSaves = true;

        private RetryingPromptCatalogRepo(Path promptsFile) {
            super(promptsFile);
        }

        @Override
        public void save(List<PromptTemplate> prompts) {
            if (failSaves) {
                throw new IllegalStateException("forced failure");
            }
            super.save(prompts);
        }
    }

    private static final class BlockingSavePromptCatalogRepo extends PromptCatalogRepo {
        private final CountDownLatch saveStarted = new CountDownLatch(1);
        private final CountDownLatch releaseSave = new CountDownLatch(1);
        private final CountDownLatch saveFinished = new CountDownLatch(1);
        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private final List<List<PromptTemplate>> savedSnapshots = new ArrayList<>();
        private final List<PromptTemplate> loadedPrompts;

        private BlockingSavePromptCatalogRepo(
                Path promptsFile,
                List<PromptTemplate> loadedPrompts
        ) {
            super(promptsFile);
            this.loadedPrompts = loadedPrompts;
        }

        @Override
        public List<PromptTemplate> load() {
            return loadedPrompts;
        }

        @Override
        public void save(List<PromptTemplate> prompts) {
            worker.set(Thread.currentThread());
            saveStarted.countDown();
            try {
                if (!releaseSave.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release prompt save");
                }
                savedSnapshots.add(List.copyOf(prompts));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } finally {
                saveFinished.countDown();
            }
        }
    }

    private final class BlockingLoadCleanup implements AutoCloseable {
        private final BlockingPromptCatalogRepo repo;
        private final PromptsPanel subject;

        private BlockingLoadCleanup(BlockingPromptCatalogRepo repo, PromptsPanel subject) {
            this.repo = repo;
            this.subject = subject;
        }

        @Override
        public void close() throws Exception {
            repo.release.countDown();
            Throwable failure = null;
            failure = captureCleanupFailure(failure, () -> runOnEdt(subject::disposePanel));
            failure = captureCleanupFailure(failure, () ->
                    assertThat(repo.finished.await(5, TimeUnit.SECONDS)).isTrue());
            failure = captureCleanupFailure(failure, () -> {
                Thread worker = repo.worker.get();
                assertThat(worker).isNotNull();
                worker.join(TimeUnit.SECONDS.toMillis(5));
                assertThat(worker.isAlive()).isFalse();
            });
            failure = captureCleanupFailure(failure, PromptsPanelTest.this::flushEdt);
            throwCleanupFailure(failure);
        }
    }

    private final class BlockingSaveCleanup implements AutoCloseable {
        private final BlockingSavePromptCatalogRepo repo;
        private final PromptsPanel subject;
        private CompletableFuture<Boolean> latestSave;
        private boolean saveExpected;

        private BlockingSaveCleanup(BlockingSavePromptCatalogRepo repo, PromptsPanel subject) {
            this.repo = repo;
            this.subject = subject;
        }

        private void expect(CompletableFuture<Boolean> save) {
            latestSave = save;
            saveExpected = true;
        }

        private void expectUnobservedSave() {
            saveExpected = true;
        }

        @Override
        public void close() throws Exception {
            repo.releaseSave.countDown();
            Throwable failure = null;
            if (saveExpected) {
                failure = captureCleanupFailure(failure, () ->
                        assertThat(repo.saveStarted.await(5, TimeUnit.SECONDS)).isTrue());
                failure = captureCleanupFailure(failure, () -> {
                    if (latestSave != null) {
                        latestSave.get(5, TimeUnit.SECONDS);
                    } else {
                        assertThat(repo.saveFinished.await(5, TimeUnit.SECONDS)).isTrue();
                    }
                });
            }
            failure = captureCleanupFailure(failure, () -> runOnEdt(subject::disposePanel));
            failure = captureCleanupFailure(failure, () -> {
                Thread worker = repo.worker.get();
                if (worker != null) {
                    worker.join(TimeUnit.SECONDS.toMillis(5));
                    assertThat(worker.isAlive()).isFalse();
                }
            });
            failure = captureCleanupFailure(failure, PromptsPanelTest.this::flushEdt);
            throwCleanupFailure(failure);
        }
    }

    private Throwable captureCleanupFailure(Throwable failure, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (failure == null) {
                return t;
            }
            failure.addSuppressed(t);
        }
        return failure;
    }

    private void throwCleanupFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
        if (failure != null) {
            throw new AssertionError(failure);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class BlockingPromptCatalogRepo extends PromptCatalogRepo {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private BlockingPromptCatalogRepo(Path promptsFile) {
            super(promptsFile);
        }

        @Override
        public List<PromptTemplate> load() {
            worker.set(Thread.currentThread());
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    failure.set(new AssertionError("Timed out waiting to release prompt loading"));
                }
                return BuiltInPromptCatalog.prompts();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } finally {
                finished.countDown();
            }
        }
    }
}
