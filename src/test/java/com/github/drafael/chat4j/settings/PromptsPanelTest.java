package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class PromptsPanelTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Unchanged prompt panel does not persist a built-in snapshot")
    void savePendingChanges_whenPanelIsUnchanged_doesNotWritePromptCatalog() throws Exception {
        SettingsRepo settingsRepo = new SettingsRepo(tempDir.resolve("settings.properties"));
        AtomicBoolean saved = new AtomicBoolean(false);

        SwingUtilities.invokeAndWait(() -> {
            var subject = new PromptsPanel(settingsRepo);
            saved.set(subject.savePendingChanges());
        });

        assertThat(saved).isTrue();
        assertThat(settingsRepo.get(SettingsKeys.PROMPT_CATALOG)).isEmpty();
    }
}
