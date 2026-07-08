package com.github.drafael.chat4j.stt.provider.assemblyai;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

public final class AssemblyAiSttEndpointResolver {

    public static final String DEFAULT_BASE_URL = "https://api.assemblyai.com";
    public static final String EU_BASE_URL = "https://api.eu.assemblyai.com";

    private AssemblyAiSttEndpointResolver() {
    }

    public static Endpoint resolve(String configuredBaseUrl) throws SpeechToTextException {
        String base = StringUtils.defaultIfBlank(configuredBaseUrl, DEFAULT_BASE_URL).trim();
        URI baseUri;
        try {
            baseUri = URI.create(trimTrailingSlashes(base));
        } catch (IllegalArgumentException e) {
            throw new SpeechToTextException("AssemblyAI Speech to Text base URL is invalid.", e);
        }
        validate(baseUri);
        return new Endpoint(baseUri, baseUri.resolve("/v2/upload"), baseUri.resolve("/v2/transcript"));
    }

    public static Endpoint resolve(URI baseUri) throws SpeechToTextException {
        return resolve(baseUri == null ? DEFAULT_BASE_URL : baseUri.toString());
    }

    public static URI transcriptUri(URI baseUri, String transcriptId) throws SpeechToTextException {
        return resolve(baseUri).transcriptUri(transcriptId);
    }

    private static void validate(URI uri) throws SpeechToTextException {
        if (!uri.isAbsolute() || StringUtils.isBlank(uri.getHost()) || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new SpeechToTextException("AssemblyAI Speech to Text base URL must be an absolute https URL with a host.");
        }
        if (!Strings.CI.equalsAny(uri.getHost(), "api.assemblyai.com", "api.eu.assemblyai.com")) {
            throw new SpeechToTextException("AssemblyAI Speech to Text base URL must use an official AssemblyAI API host.");
        }
    }

    private static String trimTrailingSlashes(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/") && trimmed.length() > "https://x".length()) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String encodeTranscriptId(String transcriptId) throws SpeechToTextException {
        String id = StringUtils.trimToEmpty(transcriptId);
        if (StringUtils.isBlank(id) || id.chars().anyMatch(Character::isISOControl)) {
            throw new SpeechToTextException("AssemblyAI transcript id was invalid.");
        }
        return URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record Endpoint(URI baseUri, URI uploadUri, URI transcriptionUri) {
        public URI transcriptUri(String transcriptId) throws SpeechToTextException {
            return baseUri.resolve("/v2/transcript/%s".formatted(encodeTranscriptId(transcriptId)));
        }
    }
}
