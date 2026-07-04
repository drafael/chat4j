package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.tts.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.TextToSpeechProvider;
import com.github.drafael.chat4j.tts.TextToSpeechRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextToSpeechPanelTest {

    @Test
    @DisplayName("Provider option uses provider unavailable label and message")
    void providerOption_unavailableProvider_usesProviderText() {
        TextToSpeechPanel.ProviderOption option = TextToSpeechPanel.ProviderOption.of(new FakeProvider(), false);

        assertThat(option.label()).isEqualTo("Fake System (unavailable)");
        assertThat(option.unavailableMessage()).isEqualTo("Fake System is unavailable.");
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
