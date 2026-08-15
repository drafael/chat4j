package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AgentModePanelTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Prompt addendum updates persist to the Agent Mode system prompt setting")
    void updatePromptAddendum_whenTextChanges_persistsSetting() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("agent-mode-panel-prompt-append");
        AgentModePanel subject = callOnEdt(() -> new AgentModePanel(settingsRepo));
        try {
            JTextArea promptArea = callOnEdt(() -> promptArea(subject));
            runOnEdt(() -> promptArea.setText("Always include key files in summaries."));

            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get("chat4j.chat.agent.systemPromptAppend"))
                    .contains("Always include key files in summaries.");
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Prompt addendum save failures remain visible for close-time retry")
    void updatePromptAddendum_whenSaveFails_showsErrorAndRetries() throws Exception {
        var settingsRepo = new RetryingSettingsRepo(tempDir.resolve("agent-mode-panel-prompt-failure.properties"));
        AgentModePanel subject = callOnEdt(() -> new AgentModePanel(settingsRepo));
        try {
            JTextArea promptArea = callOnEdt(() -> promptArea(subject));
            runOnEdt(() -> promptArea.setText("Always include key files in summaries."));

            assertThat(awaitSave(subject)).isFalse();
            assertThat(callOnEdt(() -> subject.statusLabel().getText()))
                    .contains("Failed to save prompt addendum setting").doesNotContain("Saved");

            settingsRepo.failWrites = false;
            assertThat(awaitSave(subject)).isTrue();
            assertThat(settingsRepo.get("chat4j.chat.agent.systemPromptAppend"))
                    .contains("Always include key files in summaries.");
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("The Agent Mode panel displays the saved prompt addendum")
    void constructor_whenPromptExists_displaysSavedValue() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("agent-mode-panel-saved-prompt");
        settingsRepo.put("chat4j.chat.agent.systemPromptAppend", "Keep answers concise.");
        AgentModePanel subject = callOnEdt(() -> new AgentModePanel(settingsRepo));
        try {
            assertThat(callOnEdt(() -> promptArea(subject).getText())).isEqualTo("Keep answers concise.");
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Prompt read failures keep the Agent Mode panel available with an empty addendum")
    void constructor_whenPromptReadFails_usesEmptyPromptAddendum() throws Exception {
        var settingsRepo = new ThrowingReadSettingsRepo(tempDir.resolve("agent-mode-panel-read-failure.properties"));
        AgentModePanel subject = callOnEdt(() -> new AgentModePanel(settingsRepo));
        try {
            assertThat(callOnEdt(() -> promptArea(subject).getText())).isEmpty();
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    private boolean awaitSave(AgentModePanel subject) throws Exception {
        boolean saved = callOnEdt(subject::savePendingChangesAsync).get(10, TimeUnit.SECONDS);
        flushEdt();
        return saved;
    }

    private JTextArea promptArea(AgentModePanel subject) {
        return findComponentByName(subject, "agentSystemPromptAppendArea", JTextArea.class);
    }

    private <T extends Component> T findComponentByName(Container root, String name, Class<T> type) {
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
        throw new AssertionError("Component not found: %s".formatted(name));
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

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
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

    private static final class ThrowingReadSettingsRepo extends SettingsRepository {

        private ThrowingReadSettingsRepo(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public Optional<String> get(String key) {
            throw new IllegalStateException("forced failure");
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

        @Override
        public void remove(String key) {
            if (failWrites) {
                throw new IllegalStateException("forced failure");
            }
            super.remove(key);
        }
    }
}
