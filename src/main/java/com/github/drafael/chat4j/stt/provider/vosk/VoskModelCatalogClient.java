package com.github.drafael.chat4j.stt.provider.vosk;

import com.github.drafael.chat4j.http.HttpBody;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport;
import com.github.drafael.chat4j.persistence.catalog.CatalogJsonStructure;
import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import java.net.URI;
import com.github.drafael.chat4j.http.JavaNetHttpTransport.RedirectPolicy;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NonNull;

public class VoskModelCatalogClient {

    public static final URI CATALOG_URI = URI.create("https://alphacephei.com/vosk/models/model-list.json");
    private static final int MAX_REDIRECTS = 3;
    private static final long MAX_CATALOG_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_CATALOG_ITEMS = 10_000;
    private static final int MAX_CATALOG_TOKENS = 500_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final HttpTransport DEFAULT_TRANSPORT = JavaNetHttpTransport.create(Duration.ofSeconds(10), RedirectPolicy.NEVER);

    private final HttpTransport transport;

    public VoskModelCatalogClient() {
        this(DEFAULT_TRANSPORT);
    }

    VoskModelCatalogClient(@NonNull HttpTransport transport) {
        this.transport = transport;
    }

    public String fetchRawJson(SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        return fetchRawJson(CATALOG_URI, 0, cancellationToken);
    }

    public List<VoskModelCatalogEntry> parse(String json) throws Exception {
        VoskModelCatalogEntry[] entries = CatalogJsonStructure.readBoundedArray(
                json,
                VoskModelCatalogEntry[].class,
                MAX_CATALOG_ITEMS,
                MAX_CATALOG_TOKENS
        ).orElseThrow(() -> new SpeechToTextException("Vosk catalog exceeds structural limits."));
        return java.util.Arrays.stream(entries)
                .filter(VoskModelCatalogEntry::speechRecognition)
                .toList();
    }

    private String fetchRawJson(URI uri, int redirects, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        validateCatalogUri(uri, false);
        if (cancelled(cancellationToken)) {
            throw new SpeechToTextException("Catalog refresh canceled.");
        }
        var request = new HttpExchangeRequest(
                "GET",
                uri,
                Map.of("Accept", "application/json"),
                HttpBody.empty(),
                TIMEOUT,
                MAX_CATALOG_BYTES
        );
        HttpExchangeResponse response;
        try {
            response = transport.send(request, () -> cancelled(cancellationToken));
        } catch (Exception e) {
            if (cancelled(cancellationToken)) {
                throw new SpeechToTextException("Catalog refresh canceled.", e);
            }
            if ("HTTP response was too large.".equals(e.getMessage())) {
                throw new SpeechToTextException("Vosk catalog response was too large.", e);
            }
            throw e;
        }
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            String locationHeader = response.firstHeader("location");
            URI location = locationHeader.isBlank() ? null : uri.resolve(locationHeader);
            if (redirects >= MAX_REDIRECTS) {
                throw new SpeechToTextException("Vosk catalog redirected too many times.");
            }
            if (location == null) {
                throw new SpeechToTextException("Vosk catalog redirect was missing a destination.");
            }
            validateCatalogUri(location, true);
            return fetchRawJson(location, redirects + 1, cancellationToken);
        }
        if (!response.successful()) {
            throw new SpeechToTextException("Vosk catalog refresh failed: HTTP %d".formatted(status));
        }
        return decodeUtf8(response.body());
    }

    private void validateCatalogUri(URI uri, boolean redirect) throws SpeechToTextException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"alphacephei.com".equalsIgnoreCase(uri.getHost())) {
            throw new SpeechToTextException(redirect ? "Vosk catalog redirected to an untrusted host." : "Vosk catalog URL is not trusted.");
        }
        if (!"/vosk/models/model-list.json".equals(uri.getPath())) {
            throw new SpeechToTextException("Vosk catalog URL path is not trusted.");
        }
    }

    private String decodeUtf8(byte[] body) throws SpeechToTextException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new SpeechToTextException("Vosk catalog response was not valid UTF-8.", e);
        }
    }

    private static boolean cancelled(SpeechToTextProviderContext.CancellationToken cancellationToken) {
        return cancellationToken != null && cancellationToken.cancelled();
    }
}
