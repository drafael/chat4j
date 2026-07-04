package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.*;
import java.nio.file.Path;
import javax.swing.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class GeneralPanelTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Prompt addendum updates persist to Agent Mode system prompt setting")
    void updatePromptAddendum_whenTextChanges_persistsSetting() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("general-panel-agent-prompt-append");
        GeneralPanel subject = new GeneralPanel(settingsRepo);

        JTextArea promptArea = findComponentByName(subject, "agentSystemPromptAppendArea", JTextArea.class);
        SwingUtilities.invokeAndWait(() -> promptArea.setText("Always include key files in summaries."));

        assertThat(settingsRepo.get(SettingsKeys.CHAT_AGENT_SYSTEM_PROMPT_APPEND))
                .contains("Always include key files in summaries.");
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
}
