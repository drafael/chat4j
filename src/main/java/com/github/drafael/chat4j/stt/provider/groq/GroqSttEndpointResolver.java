package com.github.drafael.chat4j.stt.provider.groq;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import java.net.URI;
import org.apache.commons.lang3.StringUtils;

public final class GroqSttEndpointResolver {

    public static final String DEFAULT_BASE_URL = "https://api.groq.com/openai/v1";

    private GroqSttEndpointResolver() {
    }

    public static Endpoint resolve(String configuredBaseUrl) throws SpeechToTextException {
        String base = StringUtils.defaultIfBlank(configuredBaseUrl, DEFAULT_BASE_URL).trim();
        URI baseUri;
        try {
            baseUri = URI.create(trimTrailingSlashes(base));
        } catch (IllegalArgumentException e) {
            throw new SpeechToTextException("Groq Speech to Text base URL is invalid.", e);
        }
        validate(baseUri);
        return new Endpoint(baseUri, baseUri.resolve("%s/audio/transcriptions".formatted(ensureSlashPath(baseUri))), baseUri.resolve("%s/models".formatted(ensureSlashPath(baseUri))));
    }

    private static void validate(URI uri) throws SpeechToTextException {
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || StringUtils.isBlank(uri.getHost()) || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new SpeechToTextException("Groq Speech to Text base URL must be an absolute http(s) URL with a host.");
        }
    }

    private static String trimTrailingSlashes(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/") && trimmed.length() > "https://x".length()) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String ensureSlashPath(URI uri) {
        String path = StringUtils.defaultString(uri.getPath());
        return path.endsWith("/") ? path : "%s/".formatted(path);
    }

    public record Endpoint(URI baseUri, URI transcriptionUri, URI modelsUri) {
    }
}
