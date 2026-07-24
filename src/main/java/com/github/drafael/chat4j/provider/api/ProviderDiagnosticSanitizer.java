package com.github.drafael.chat4j.provider.api;

import java.net.URI;
import org.apache.commons.lang3.StringUtils;

public final class ProviderDiagnosticSanitizer {

    private static final String CONFIGURED_ENDPOINT = "configured-endpoint";

    private ProviderDiagnosticSanitizer() {
    }

    public static String safeOrigin(String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (StringUtils.isBlank(scheme) || StringUtils.isBlank(host)) {
                return CONFIGURED_ENDPOINT;
            }
            String displayHost = host.contains(":") ? "[%s]".formatted(host) : host;
            return uri.getPort() < 0
                    ? "%s://%s".formatted(scheme, displayHost)
                    : "%s://%s:%d".formatted(scheme, displayHost, uri.getPort());
        } catch (IllegalArgumentException e) {
            return CONFIGURED_ENDPOINT;
        }
    }
}
