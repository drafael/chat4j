package com.github.drafael.chat4j.provider.modules;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleModuleTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Google AI does not declare global image capability and relies on per-model detection")
    void descriptor_whenGoogleAiProvider_returnsNoStaticImageCapability() {
        var subject = new OpenAiCompatibleModule(
                "Google AI",
                AuthType.ENV_VAR,
                "GEMINI_API_KEY",
                null,
                "https://generativelanguage.googleapis.com/v1beta/openai",
                new CopilotModelMetadataStore(tempDir.resolve("google-ai-metadata"))
        );

        assertThat(subject.descriptor().capabilities().supportsImageInput()).isFalse();
    }

    @Test
    @DisplayName("OpenAI keeps global image capability for broad multimodal model coverage")
    void descriptor_whenOpenAiProvider_returnsStaticImageCapability() {
        var subject = new OpenAiCompatibleModule(
                "OpenAI",
                AuthType.ENV_VAR,
                "OPENAI_API_KEY",
                null,
                "https://api.openai.com/v1",
                new CopilotModelMetadataStore(tempDir.resolve("openai-metadata"))
        );

        assertThat(subject.descriptor().capabilities().supportsImageInput()).isTrue();
    }
}
