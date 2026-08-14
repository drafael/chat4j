package com.github.drafael.chat4j.provider.modules;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.capability.chat.impl.MistralChatCompletionClient;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleModuleTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Google AI does not declare global image capability and relies on per-model detection")
    void descriptor_whenGoogleAiProvider_returnsNoStaticImageCapability() {
        var attachmentAuthority = ProviderAttachmentTestSupport.authority();
        var subject = new OpenAiCompatibleModule(
                "Google AI",
                AuthType.ENV_VAR,
                "GEMINI_API_KEY",
                null,
                "https://generativelanguage.googleapis.com/v1beta/openai",
                new CopilotModelMetadataStore(tempDir.resolve("google-ai-metadata")),
                attachmentAuthority
        );

        assertThat(subject.descriptor().capabilities().supportsImageInput()).isFalse();
    }

    @Test
    @DisplayName("Mistral uses the hybrid client that preserves ordinary chat routing")
    void chatCompletionClient_whenMistralProvider_returnsHybridClient() {
        var attachmentAuthority = ProviderAttachmentTestSupport.authority();
        var subject = new OpenAiCompatibleModule(
                "Mistral",
                AuthType.ENV_VAR,
                "MISTRAL_API_KEY",
                null,
                "https://api.mistral.ai/v1",
                new CopilotModelMetadataStore(tempDir.resolve("mistral-metadata")),
                attachmentAuthority
        );

        assertThat(subject.chatCompletionClient()).isInstanceOf(MistralChatCompletionClient.class);
    }

    @Test
    @DisplayName("OpenAI keeps global image capability for broad multimodal model coverage")
    void descriptor_whenOpenAiProvider_returnsStaticImageCapability() {
        var attachmentAuthority = ProviderAttachmentTestSupport.authority();
        var subject = new OpenAiCompatibleModule(
                "OpenAI",
                AuthType.ENV_VAR,
                "OPENAI_API_KEY",
                null,
                "https://api.openai.com/v1",
                new CopilotModelMetadataStore(tempDir.resolve("openai-metadata")),
                attachmentAuthority
        );

        assertThat(subject.descriptor().capabilities().supportsImageInput()).isTrue();
    }
}
