package com.github.drafael.chat4j.stt.provider.vosk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class VoskModelCatalogClient {

    public static final URI CATALOG_URI = URI.create("https://alphacephei.com/vosk/models/model-list.json");
    private static final int MAX_REDIRECTS = 3;
    private static final long MAX_CATALOG_BYTES = 2L * 1024L * 1024L;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    public VoskModelCatalogClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    VoskModelCatalogClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public String fetchRawJson(SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        return fetchRawJson(CATALOG_URI, 0, cancellationToken);
    }

    public List<VoskModelCatalogEntry> parse(String json) throws Exception {
        List<VoskModelCatalogEntry> entries = OBJECT_MAPPER.readValue(json, new TypeReference<>() {
        });
        return entries.stream()
                .filter(VoskModelCatalogEntry::speechRecognition)
                .toList();
    }

    private String fetchRawJson(URI uri, int redirects, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        validateCatalogUri(uri, false);
        if (cancellationToken != null && cancellationToken.cancelled()) {
            throw new SpeechToTextException("Catalog refresh canceled.");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            URI location = response.headers().firstValue("location")
                    .map(uri::resolve)
                    .orElse(null);
            closeResponseBody(response);
            if (redirects >= MAX_REDIRECTS) {
                throw new SpeechToTextException("Vosk catalog redirected too many times.");
            }
            if (location == null) {
                throw new SpeechToTextException("Vosk catalog redirect was missing a destination.");
            }
            validateCatalogUri(location, true);
            return fetchRawJson(location, redirects + 1, cancellationToken);
        }
        if (status < 200 || status >= 300) {
            closeResponseBody(response);
            throw new SpeechToTextException("Vosk catalog refresh failed: HTTP %d".formatted(status));
        }
        return boundedString(response.body(), cancellationToken);
    }

    private void closeResponseBody(HttpResponse<InputStream> response) {
        try {
            if (response.body() != null) {
                response.body().close();
            }
        } catch (Exception ignored) {
        }
    }

    private void validateCatalogUri(URI uri, boolean redirect) throws SpeechToTextException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"alphacephei.com".equalsIgnoreCase(uri.getHost())) {
            throw new SpeechToTextException(redirect ? "Vosk catalog redirected to an untrusted host." : "Vosk catalog URL is not trusted.");
        }
        if (!"/vosk/models/model-list.json".equals(uri.getPath())) {
            throw new SpeechToTextException("Vosk catalog URL path is not trusted.");
        }
    }

    private String boundedString(InputStream input, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (cancellationToken != null && cancellationToken.cancelled()) {
                    throw new SpeechToTextException("Catalog refresh canceled.");
                }
                total += read;
                if (total > MAX_CATALOG_BYTES) {
                    throw new SpeechToTextException("Vosk catalog response was too large.");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
