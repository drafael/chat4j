package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderCapabilityResolverTest {

    @Test
    @DisplayName("supportsImageInput resolves true for OpenAI multimodal-like models")
    void supportsImageInput_whenOpenAiModelLooksMultimodal_returnsTrue() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatAndModels(),
                "OpenAI",
                "gpt-4o-mini"
        );

        assertThat(supported).isTrue();
    }

    @Test
    @DisplayName("supportsImageInput resolves false for Codex-like models even under OpenAI provider")
    void supportsImageInput_whenModelContainsCodex_returnsFalse() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatAndModels(),
                "OpenAI Codex",
                "codex-mini-latest"
        );

        assertThat(supported).isFalse();
    }

    @Test
    @DisplayName("supportsImageInput stays false when only the provider name hints at multimodal support")
    void supportsImageInput_whenModelHasNoImageHints_returnsFalse() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatAndModels(),
                "OpenAI",
                "chat-model-v1"
        );

        assertThat(supported).isFalse();
    }

    @Test
    @DisplayName("supportsImageInput honors explicit capability flag from provider descriptor")
    void supportsImageInput_whenCapabilitiesDeclareImageSupport_returnsTrue() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatModelsAndImages(),
                "Custom Provider",
                "text-only-id"
        );

        assertThat(supported).isTrue();
    }

    @Test
    @DisplayName("supportsFileInput remains false unless provider explicitly declares support")
    void supportsFileInput_whenCapabilitiesDoNotDeclareSupport_returnsFalse() {
        boolean supported = ProviderCapabilityResolver.supportsFileInput(
                ProviderCapabilities.chatAndModels(),
                "OpenAI",
                "gpt-4o"
        );

        assertThat(supported).isFalse();
    }
}
