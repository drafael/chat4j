package com.github.drafael.chat4j.provider.modules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleModuleTest {

    @Test
    @DisplayName("Google AI does not declare global image capability and relies on per-model detection")
    void descriptor_whenGoogleAiProvider_returnsNoStaticImageCapability() {
        var subject = new OpenAiCompatibleModule(
                "Google AI",
                "GEMINI_API_KEY",
                null,
                "https://generativelanguage.googleapis.com/v1beta/openai"
        );

        assertThat(subject.descriptor().capabilities().supportsImageInput()).isFalse();
    }

    @Test
    @DisplayName("OpenAI keeps global image capability for broad multimodal model coverage")
    void descriptor_whenOpenAiProvider_returnsStaticImageCapability() {
        var subject = new OpenAiCompatibleModule(
                "OpenAI",
                "OPENAI_API_KEY",
                null,
                "https://api.openai.com/v1"
        );

        assertThat(subject.descriptor().capabilities().supportsImageInput()).isTrue();
    }
}
