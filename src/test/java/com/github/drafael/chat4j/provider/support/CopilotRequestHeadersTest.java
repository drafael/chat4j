package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotRequestHeadersTest {

    private static final String INTEGRATION_ID_PROPERTY = "chat4j.copilot.integrationId";

    private final String originalIntegrationId = System.getProperty(INTEGRATION_ID_PROPERTY);

    @AfterEach
    void tearDown() {
        restoreProperty(INTEGRATION_ID_PROPERTY, originalIntegrationId);
    }

    @Test
    @DisplayName("Copilot request headers default to Chat4J runtime-safe values")
    void defaults_whenPropertiesMissing_returnsRuntimeDefaults() {
        System.clearProperty(INTEGRATION_ID_PROPERTY);

        assertThat(CopilotRequestHeaders.integrationId()).isEqualTo("copilot-developer-cli");
        assertThat(CopilotRequestHeaders.asMap())
                .containsEntry("Copilot-Integration-Id", "copilot-developer-cli")
                .hasSize(1);
    }

    @Test
    @DisplayName("Copilot request headers honor explicit system property overrides")
    void values_whenPropertiesProvided_returnsTrimmedPropertyValues() {
        System.setProperty(INTEGRATION_ID_PROPERTY, " custom-integration ");

        assertThat(CopilotRequestHeaders.integrationId()).isEqualTo("custom-integration");
    }

    private void restoreProperty(String propertyName, String value) {
        if (value == null) {
            System.clearProperty(propertyName);
        } else {
            System.setProperty(propertyName, value);
        }
    }
}
