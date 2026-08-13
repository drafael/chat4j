package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekNativeWebSearchSupportTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.deepseek.com",
            "https://api.deepseek.com/",
            "https://api.deepseek.com/v1",
            "https://API.DEEPSEEK.COM:443/v1/"
    })
    @DisplayName("Official DeepSeek API URL variants derive the canonical Anthropic endpoint")
    void anthropicBaseUrl_whenOfficialEndpointVariant_returnsCanonicalEndpoint(String baseUrl) {
        assertThat(DeepSeekNativeWebSearchSupport.anthropicBaseUrl(baseUrl))
                .contains("https://api.deepseek.com/anthropic");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.deepseek.com",
            "https://evil.api.deepseek.com",
            "https://api.deepseek.com:8443",
            "https://user@api.deepseek.com",
            "https://api.deepseek.com/v2",
            "https://api.deepseek.com?x=1",
            "https://api.deepseek.com#fragment"
    })
    @DisplayName("Noncanonical DeepSeek endpoints are rejected")
    void canonicalBaseUrl_whenEndpointIsUnsupported_returnsEmpty(String baseUrl) {
        assertThat(DeepSeekNativeWebSearchSupport.canonicalBaseUrl(baseUrl)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-v4-flash", "DEEPSEEK-V4-PRO", " deepseek-v4-pro[1m] "})
    @DisplayName("Only exact documented V4 model identifiers are accepted")
    void supportsModel_whenModelIsAllowlisted_returnsTrue(String modelId) {
        assertThat(DeepSeekNativeWebSearchSupport.supportsModel(modelId)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash[1m]", "vendor/deepseek-v4-pro", "deepseek-v4-pro-extra"})
    @DisplayName("Legacy, namespaced, and unverified model identifiers are rejected")
    void supportsModel_whenModelIsNotAllowlisted_returnsFalse(String modelId) {
        assertThat(DeepSeekNativeWebSearchSupport.supportsModel(modelId)).isFalse();
    }

    @Test
    @DisplayName("Provider name must identify DeepSeek exactly")
    void supports_whenProviderNameOnlyContainsDeepSeek_returnsFalse() {
        assertThat(DeepSeekNativeWebSearchSupport.supports(
                "DeepSeek Proxy",
                "deepseek-v4-pro",
                "https://api.deepseek.com"
        )).isFalse();
    }
}
