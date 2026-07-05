package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

public class JavaNetSttHttpTransport implements SttHttpTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;

    public JavaNetSttHttpTransport() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    JavaNetSttHttpTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public SttHttpResponse send(SttHttpRequest request, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
        request.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.method(request.method(), request.bodyPublisher()).build();
        CompletableFuture<HttpResponse<InputStream>> future = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        Thread watcher = Thread.startVirtualThread(() -> {
            while (!future.isDone()) {
                if (cancellationToken != null && cancellationToken.cancelled()) {
                    future.cancel(true);
                    return;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.cancel(true);
                    return;
                }
            }
        });
        try {
            HttpResponse<InputStream> response = future.join();
            return new SttHttpResponse(response.statusCode(), response.headers().map(), boundedBody(response.body(), request.maxResponseBytes()));
        } catch (CancellationException e) {
            throw new SpeechToTextException("Transcription canceled.", e);
        } finally {
            watcher.interrupt();
        }
    }

    private static byte[] boundedBody(InputStream input, long maxResponseBytes) throws Exception {
        long limit = maxResponseBytes <= 0 ? 1024 * 1024 : maxResponseBytes;
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new SpeechToTextException("Provider response was too large.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
