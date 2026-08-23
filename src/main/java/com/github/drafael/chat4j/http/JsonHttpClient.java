package com.github.drafael.chat4j.http;

import com.github.drafael.chat4j.json.JsonCodec;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import lombok.NonNull;

public final class JsonHttpClient {

    private final JsonCodec jsonCodec;
    private final HttpTransport transport;

    public JsonHttpClient(@NonNull JsonCodec jsonCodec, @NonNull HttpTransport transport) {
        this.jsonCodec = jsonCodec;
        this.transport = transport;
    }

    public HttpExchangeResponse get(
            @NonNull URI uri,
            @NonNull Map<String, String> headers,
            @NonNull HttpExchangeOptions options,
            BooleanSupplier isCancelled
    ) throws Exception {
        return transport.send(request("GET", uri, headers, HttpBody.empty(), options), isCancelled);
    }

    public HttpExchangeResponse post(
            @NonNull URI uri,
            @NonNull Map<String, String> headers,
            @NonNull Object requestBody,
            @NonNull HttpExchangeOptions options,
            BooleanSupplier isCancelled
    ) throws Exception {
        return transport.send(request("POST", uri, headers, HttpBody.bytes(jsonCodec.writeBytes(requestBody)), options), isCancelled);
    }

    public <T> T read(@NonNull HttpExchangeResponse response, @NonNull Class<T> responseType, String invalidResponseMessage) {
        try {
            T value = jsonCodec.read(response.body(), responseType);
            if (value == null) {
                throw new IllegalStateException(invalidResponseMessage);
            }
            return value;
        } catch (Exception e) {
            throw new IllegalStateException(invalidResponseMessage);
        }
    }

    public <T> Optional<T> tryRead(@NonNull HttpExchangeResponse response, @NonNull Class<T> responseType) {
        try {
            return Optional.ofNullable(jsonCodec.read(response.body(), responseType));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static HttpExchangeRequest request(
            String method,
            URI uri,
            Map<String, String> headers,
            HttpBody body,
            HttpExchangeOptions options
    ) {
        return new HttpExchangeRequest(method, uri, headers, body, options.timeout(), options.maxResponseBytes());
    }
}
