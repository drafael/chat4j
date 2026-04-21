package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;

public final class CopilotRequestHeaders {

    private static final String COPILOT_INTEGRATION_ID_HEADER = "Copilot-Integration-Id";

    private static final String INTEGRATION_ID_PROPERTY = "chat4j.copilot.integrationId";
    private static final String DEFAULT_INTEGRATION_ID = "copilot-developer-cli";

    private CopilotRequestHeaders() {
    }

    public static String integrationId() {
        return resolveHeaderValue(INTEGRATION_ID_PROPERTY, DEFAULT_INTEGRATION_ID);
    }

    public static Map<String, String> asMap() {
        return Map.of(COPILOT_INTEGRATION_ID_HEADER, integrationId());
    }

    private static String resolveHeaderValue(String propertyName, String defaultValue) {
        return StringUtils.defaultIfBlank(System.getProperty(propertyName), defaultValue).trim();
    }
}
