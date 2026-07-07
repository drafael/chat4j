package com.github.drafael.chat4j.stt.provider.deepgram;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import java.net.URI;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

public final class DeepgramSttEndpointResolver {

    public static final String DEFAULT_BASE_URL = "https://api.deepgram.com";

    private DeepgramSttEndpointResolver() {
    }

    public static Endpoint resolve() throws SpeechToTextException {
        return resolve(DEFAULT_BASE_URL);
    }

    public static Endpoint resolve(URI baseUri) throws SpeechToTextException {
        return resolve(baseUri == null ? DEFAULT_BASE_URL : baseUri.toString());
    }

    private static Endpoint resolve(String baseUrl) throws SpeechToTextException {
        URI baseUri;
        try {
            baseUri = URI.create(StringUtils.defaultIfBlank(baseUrl, DEFAULT_BASE_URL).trim());
        } catch (IllegalArgumentException e) {
            throw new SpeechToTextException("Deepgram Speech to Text base URL is invalid.", e);
        }
        validate(baseUri);
        return new Endpoint(baseUri, baseUri.resolve("/v1/listen"), baseUri.resolve("/v1/models"));
    }

    private static void validate(URI uri) throws SpeechToTextException {
        if (!uri.isAbsolute() || StringUtils.isBlank(uri.getHost()) || !"https".equalsIgnoreCase(uri.getScheme())
                || !Strings.CI.equals(uri.getHost(), "api.deepgram.com")) {
            throw new SpeechToTextException("Deepgram Speech to Text base URL must be https://api.deepgram.com.");
        }
    }

    public record Endpoint(URI baseUri, URI transcriptionUri, URI modelsUri) {
    }
}
