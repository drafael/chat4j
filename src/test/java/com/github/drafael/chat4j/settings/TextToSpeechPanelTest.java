package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.tts.TextToSpeechProviderRegistry;
import com.github.drafael.chat4j.tts.TextToSpeechSettings;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.system.SystemTextToSpeechProvider;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class TextToSpeechPanelTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Changing the implicit System model persists the provider")
    void onModelSelected_whenSystemProviderImplicit_persistsSystemProvider() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("tts-model-settings.properties"));
        var subject = new TextToSpeechPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(new AvailableSystemProvider()))
        );
        try {
            SwingUtilities.invokeAndWait(() -> selectCatalogItem(subject, "modelComboBox", "system-model-alt"));

            assertThat(repo.get(TextToSpeechSettings.PROVIDER_KEY)).contains(SystemTextToSpeechProvider.ID);
        } finally {
            subject.removeNotify();
        }
    }

    @Test
    @DisplayName("Changing the implicit System voice persists the provider")
    void onVoiceSelected_whenSystemProviderImplicit_persistsSystemProvider() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("tts-voice-settings.properties"));
        var subject = new TextToSpeechPanel(
                repo,
                new TextToSpeechProviderRegistry(List.of(new AvailableSystemProvider()))
        );
        try {
            SwingUtilities.invokeAndWait(() -> selectCatalogItem(subject, "voiceComboBox", "system-voice-alt"));

            assertThat(repo.get(TextToSpeechSettings.PROVIDER_KEY)).contains(SystemTextToSpeechProvider.ID);
        } finally {
            subject.removeNotify();
        }
    }

    @Test
    @DisplayName("Provider option uses provider unavailable label and message")
    void providerOption_unavailableProvider_usesProviderText() {
        TextToSpeechPanel.ProviderOption option = TextToSpeechPanel.ProviderOption.of(new FakeProvider(), false);

        assertThat(option.label()).isEqualTo("Fake System (unavailable)");
        assertThat(option.unavailableMessage()).isEqualTo("Fake System is unavailable.");
    }

    private void selectCatalogItem(TextToSpeechPanel subject, String fieldName, String itemId) {
        try {
            @SuppressWarnings("unchecked")
            JComboBox<TextToSpeechCatalogItem> comboBox = (JComboBox<TextToSpeechCatalogItem>) fieldValue(
                    subject,
                    fieldName
            );
            for (int i = 0; i < comboBox.getItemCount(); i++) {
                TextToSpeechCatalogItem item = comboBox.getItemAt(i);
                if (itemId.equals(item.id())) {
                    comboBox.setSelectedItem(item);
                    return;
                }
            }
            throw new AssertionError("Catalog item not found: " + itemId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object fieldValue(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class AvailableSystemProvider implements TextToSpeechProvider {
        @Override
        public String id() {
            return SystemTextToSpeechProvider.ID;
        }

        @Override
        public String displayName() {
            return "System";
        }

        @Override
        public String requiredEnvVar() {
            return null;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public TextToSpeechCatalogItem defaultModel() {
            return TextToSpeechCatalogItem.of("system-model", "System Model");
        }

        @Override
        public TextToSpeechCatalogItem defaultVoice() {
            return TextToSpeechCatalogItem.of("system-voice", "System Voice");
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledModels() {
            return List.of(defaultModel(), TextToSpeechCatalogItem.of("system-model-alt", "System Model Alt"));
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledVoices() {
            return List.of(defaultVoice(), TextToSpeechCatalogItem.of("system-voice-alt", "System Voice Alt"));
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchModels() {
            return bundledModels();
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchVoices() {
            return bundledVoices();
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            return new TextToSpeechAudio(new byte[]{1}, "audio/wav", "wav");
        }
    }

    private static final class FakeProvider implements TextToSpeechProvider {
        @Override
        public String id() {
            return "fake-system";
        }

        @Override
        public String displayName() {
            return "Fake System";
        }

        @Override
        public String requiredEnvVar() {
            return null;
        }

        @Override
        public String unavailableLabel() {
            return "Fake System (unavailable)";
        }

        @Override
        public String unavailableMessage() {
            return "Fake System is unavailable.";
        }

        @Override
        public TextToSpeechCatalogItem defaultModel() {
            return TextToSpeechCatalogItem.of("model", "Model");
        }

        @Override
        public TextToSpeechCatalogItem defaultVoice() {
            return TextToSpeechCatalogItem.of("voice", "Voice");
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledModels() {
            return List.of(defaultModel());
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledVoices() {
            return List.of(defaultVoice());
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchModels() {
            return bundledModels();
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchVoices() {
            return bundledVoices();
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            return new TextToSpeechAudio(new byte[]{1}, "audio/wav", "wav");
        }
    }
}
