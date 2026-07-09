package com.github.drafael.chat4j.tts.provider;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class JavaNetTtsHttpTransport implements TtsHttpTransport {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    private final HttpClient httpClient;

    public JavaNetTtsHttpTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    JavaNetTtsHttpTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public TtsHttpResponse send(TtsHttpRequest request) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(REQUEST_TIMEOUT);
        request.headers().forEach(builder::header);
        HttpRequest.BodyPublisher bodyPublisher = request.body().length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body());
        HttpRequest httpRequest = builder.method(request.method(), bodyPublisher).build();
        HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        return new TtsHttpResponse(response.statusCode(), response.headers().map(), response.body());
    }
}
