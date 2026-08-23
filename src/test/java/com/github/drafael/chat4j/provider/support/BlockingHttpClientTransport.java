package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.http.HttpBody;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.BooleanSupplier;

public final class BlockingHttpClientTransport implements HttpTransport {

    private final HttpClient client;

    public BlockingHttpClientTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public HttpExchangeResponse send(HttpExchangeRequest request, BooleanSupplier isCancelled) throws Exception {
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("HTTP request was canceled.");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(request.timeout())
                .method(request.method(), publisher(request.body()));
        request.headers().forEach(builder::header);
        HttpResponse<String> response = client.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8)
        );
        return new HttpExchangeResponse(
                response.statusCode(),
                response.headers() == null ? java.util.Collections.emptyMap() : response.headers().map(),
                response.body() == null
                        ? new byte[0]
                        : response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }

    private HttpRequest.BodyPublisher publisher(HttpBody body) {
        return switch (body) {
            case HttpBody.Empty ignored -> HttpRequest.BodyPublishers.noBody();
            case HttpBody.Bytes bytes -> HttpRequest.BodyPublishers.ofByteArray(bytes.value());
            case HttpBody.File file -> {
                try {
                    yield HttpRequest.BodyPublishers.ofFile(file.path());
                } catch (java.io.IOException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            case HttpBody.Composite composite -> HttpRequest.BodyPublishers.ofByteArray(compositeBytes(composite));
        };
    }

    private byte[] compositeBytes(HttpBody.Composite composite) {
        var output = new ByteArrayOutputStream();
        composite.parts().forEach(part -> {
            try {
                switch (part) {
                    case HttpBody.Empty ignored -> {
                    }
                    case HttpBody.Bytes bytes -> output.write(bytes.value());
                    case HttpBody.File file -> output.write(java.nio.file.Files.readAllBytes(file.path()));
                    case HttpBody.Composite nested -> output.write(compositeBytes(nested));
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        return output.toByteArray();
    }
}
