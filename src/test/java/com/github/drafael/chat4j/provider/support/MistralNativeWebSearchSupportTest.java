package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MistralNativeWebSearchSupportTest {

    @Test
    @DisplayName("Official Mistral endpoints and chat models support native search")
    void supports_whenRuntimeIsOfficialChatModel_returnsTrue() {
        assertThat(MistralNativeWebSearchSupport.supports(
                "Mistral",
                "mistral-small-latest",
                "https://api.mistral.ai/v1/"
        )).isTrue();
        assertThat(MistralNativeWebSearchSupport.conversationsUri("https://api.mistral.ai"))
                .hasValueSatisfying(uri -> assertThat(uri.toString())
                        .isEqualTo("https://api.mistral.ai/v1/conversations"));
    }

    @Test
    @DisplayName("Only the exact Mistral provider identity inherits native search")
    void supports_whenProviderIdentityDiffers_returnsFalse() {
        assertThat(MistralNativeWebSearchSupport.supports(
                "mistral",
                "mistral-small-latest",
                "https://api.mistral.ai/v1"
        )).isFalse();
        assertThat(MistralNativeWebSearchSupport.supports(
                " Mistral ",
                "mistral-small-latest",
                "https://api.mistral.ai/v1"
        )).isFalse();
    }

    @Test
    @DisplayName("Custom endpoints do not inherit Mistral hosted search")
    void supports_whenEndpointIsCustom_returnsFalse() {
        assertThat(MistralNativeWebSearchSupport.supports(
                "Mistral",
                "mistral-small-latest",
                "https://mistral.example/v1"
        )).isFalse();
        assertThat(MistralNativeWebSearchSupport.supports(
                "Mistral",
                "mistral-small-latest",
                "https://api.mistral.ai/v1?proxy=true"
        )).isFalse();
    }

    @Test
    @DisplayName("Non-chat Mistral model families do not advertise hosted search")
    void supportsModel_whenModelIsNonChat_returnsFalse() {
        assertThat(MistralNativeWebSearchSupport.supportsModel("codestral-latest")).isFalse();
        assertThat(MistralNativeWebSearchSupport.supportsModel("devstral-medium-latest")).isFalse();
        assertThat(MistralNativeWebSearchSupport.supportsModel("ministral-8b-latest")).isFalse();
        assertThat(MistralNativeWebSearchSupport.supportsModel("labs-leanstral-1-5")).isFalse();
        assertThat(MistralNativeWebSearchSupport.supportsModel("mistral-code-latest")).isFalse();
        assertThat(MistralNativeWebSearchSupport.supportsModel("mistral-embed")).isFalse();
        assertThat(MistralNativeWebSearchSupport.supportsModel("mistral-moderation-latest")).isFalse();
        assertThat(MistralNativeWebSearchSupport.supportsModel("mistral-ocr-latest")).isFalse();
        assertThat(MistralNativeWebSearchSupport.supportsModel("voxtral-mini-latest")).isFalse();
        assertThat(MistralNativeWebSearchSupport.supportsModel("custom-model")).isFalse();
        assertThat(MistralNativeWebSearchSupport.supportsModel("mistral-small-ocr-latest")).isFalse();
        assertThat(MistralNativeWebSearchSupport.supportsModel("mistral-smallness-latest")).isFalse();
        assertThat(MistralNativeWebSearchSupport.supportsModel(" ")).isFalse();
    }

    @Test
    @DisplayName("Mistral model filtering keeps specialized chat models but excludes non-chat APIs")
    void isChatModel_whenModelFamilyVaries_returnsExpectedValue() {
        assertThat(MistralNativeWebSearchSupport.isChatModel("codestral-latest")).isTrue();
        assertThat(MistralNativeWebSearchSupport.isChatModel("devstral-medium-latest")).isTrue();
        assertThat(MistralNativeWebSearchSupport.isChatModel("mistral-ocr-latest")).isFalse();
        assertThat(MistralNativeWebSearchSupport.isChatModel("voxtral-mini-latest")).isFalse();
    }
}
