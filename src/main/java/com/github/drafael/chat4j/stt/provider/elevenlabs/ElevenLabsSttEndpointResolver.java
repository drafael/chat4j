package com.github.drafael.chat4j.stt.provider.elevenlabs;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import java.net.URI;
import org.apache.commons.lang3.StringUtils;

public final class ElevenLabsSttEndpointResolver {

    public static final String DEFAULT_BASE_URL = "https://api.elevenlabs.io";

    private ElevenLabsSttEndpointResolver() {
    }

    public static Endpoint resolve(String configuredBaseUrl) throws SpeechToTextException {
        String base = StringUtils.defaultIfBlank(configuredBaseUrl, DEFAULT_BASE_URL).trim();
        URI baseUri;
        try {
            baseUri = URI.create(trimTrailingSlashes(base));
        } catch (IllegalArgumentException e) {
            throw new SpeechToTextException("ElevenLabs Speech to Text base URL is invalid.", e);
        }
        validate(baseUri);
        return new Endpoint(baseUri, baseUri.resolve("/v1/speech-to-text"), baseUri.resolve("/v1/models"));
    }

    public static Endpoint resolve(URI baseUri) throws SpeechToTextException {
        return resolve(baseUri == null ? DEFAULT_BASE_URL : baseUri.toString());
    }

    private static void validate(URI uri) throws SpeechToTextException {
        if (!uri.isAbsolute() || StringUtils.isBlank(uri.getHost()) || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new SpeechToTextException("ElevenLabs Speech to Text base URL must be an absolute https URL with a host.");
        }
    }

    private static String trimTrailingSlashes(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/") && trimmed.length() > "https://x".length()) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public record Endpoint(URI baseUri, URI transcriptionUri, URI modelsUri) {
    }
}
