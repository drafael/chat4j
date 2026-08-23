package com.github.drafael.chat4j.provider.capability.models.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.BaseUrlNormalizer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TogetherModelCatalogClientTest {

    private HttpServer server;
    private final AtomicReference<HttpExchange> capturedExchange = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String responseBody = "[]";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/models", exchange -> {
            capturedExchange.set(exchange);
            requests.incrementAndGet();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        Thread.interrupted();
    }

    @Test
    @DisplayName("Together discovery sends authenticated GET models without a dedicated query")
    void fetchModels_whenCatalogIsValid_sendsExpectedRequestAndReturnsKnownChatIds() {
        responseBody = """
                [
                  {"id":"Qwen/Qwen3.5-9B","object":"model","created":1,"type":"chat"},
                  {"id":"Qwen/Qwen3.7-Max","object":"model","created":2,"type":"chat"}
                ]
                """;
        var subject = new TogetherModelCatalogClient();

        List<String> models = subject.fetchModels(runtime("secret", baseUrl()));

        assertThat(models).containsExactly("Qwen/Qwen3.7-Max", "Qwen/Qwen3.5-9B");
        HttpExchange exchange = capturedExchange.get();
        assertThat(exchange.getRequestMethod()).isEqualTo("GET");
        assertThat(exchange.getRequestURI().getPath()).isEqualTo("/models");
        assertThat(exchange.getRequestURI().getRawQuery()).isNull();
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer secret");
        assertThat(exchange.getRequestHeaders().getFirst("Accept")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("Malformed Together entries are skipped without suppressing valid siblings")
    void fetchModels_whenEntriesVary_keepsOnlyValidKnownExactChatModels() {
        responseBody = """
                [
                  {"id":"MiniMaxAI/MiniMax-M3","object":"model","created":1,"type":"chat"},
                  {"id":"unknown/model","object":"model","created":1,"type":"chat"},
                  {"id":"minimaxai/minimax-m3","object":"model","created":1,"type":"chat"},
                  {"id":"Qwen/Qwen3.5-9B","object":"MODEL","created":1,"type":"chat"},
                  {"id":"Qwen/Qwen3.5-9B","object":"model","created":1.5,"type":"chat"},
                  {"id":"Qwen/Qwen3.5-9B","object":"model","created":1,"type":" chat "},
                  {"id":"Qwen/Qwen3.5-9B","object":"model","created":1,"type":"CHAT"},
                  {"id":"Qwen/Qwen3.5-9B","object":"model","created":1,"type":"image"},
                  {"id":"Qwen/Qwen3.5-9B","object":"model","created":1,"type":"chat"},
                  null
                ]
                """;
        var subject = new TogetherModelCatalogClient();

        assertThat(subject.fetchModels(runtime("secret", "%s/".formatted(baseUrl()))))
                .containsExactly("MiniMaxAI/MiniMax-M3", "Qwen/Qwen3.5-9B");
    }

    @Test
    @DisplayName("Blank Together credentials stop discovery before network access")
    void fetchModels_whenCredentialIsBlank_doesNotSendRequest() {
        var subject = new TogetherModelCatalogClient();

        assertThat(subject.fetchModels(runtime(" ", baseUrl()))).isEmpty();
        assertThat(requests).hasValue(0);
    }

    @Test
    @DisplayName("Invalid Together roots, malformed JSON, empty catalogs, and HTTP failures return no models")
    void fetchModels_whenResponseIsUnusable_returnsEmptyList() {
        var subject = new TogetherModelCatalogClient();

        responseBody = "{}";
        assertThat(subject.fetchModels(runtime("secret", baseUrl()))).isEmpty();
        responseBody = "not-json secret";
        assertThat(subject.fetchModels(runtime("secret", baseUrl()))).isEmpty();
        responseBody = "[]";
        assertThat(subject.fetchModels(runtime("secret", baseUrl()))).isEmpty();
        status = 503;
        assertThat(subject.fetchModels(runtime("secret", baseUrl()))).isEmpty();
    }

    @Test
    @DisplayName("Together discovery diagnostics redact the request-owned credential")
    void fetchModels_whenFailureContainsCredential_redactsDiagnosticLog() throws Exception {
        String key = "secret-do-not-log";
        HttpTransport transport = (request, cancellation) -> {
            throw new IllegalArgumentException("failure %s".formatted(key));
        };
        Logger logger = (Logger) LoggerFactory.getLogger(TogetherModelCatalogClient.class);
        Level originalLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            var subject = new TogetherModelCatalogClient(transport);

            assertThat(subject.fetchModels(runtime(key, baseUrl()))).isEmpty();

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("[REDACTED]"))
                    .noneMatch(message -> message.contains(key));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    @Test
    @DisplayName("Together discovery preserves interruption and bounds every request")
    void fetchModels_whenInterruptedOrTimedOut_returnsEmptyWithBoundedRequest() throws Exception {
        AtomicReference<HttpExchangeRequest> request = new AtomicReference<>();
        HttpTransport timingOutTransport = (sent, cancellation) -> {
            request.set(sent);
            throw new HttpTimeoutException("timed out");
        };
        var subject = new TogetherModelCatalogClient(timingOutTransport);

        assertThat(subject.fetchModels(runtime("secret", baseUrl()))).isEmpty();
        assertThat(request.get().timeout()).isEqualTo(Duration.ofSeconds(4));

        HttpTransport interruptingTransport = (sent, cancellation) -> {
            throw new InterruptedException("interrupted");
        };
        subject = new TogetherModelCatalogClient(interruptingTransport);
        assertThat(subject.fetchModels(runtime("secret", baseUrl()))).isEmpty();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();

        Thread.currentThread().interrupt();
        assertThat(subject.fetchModels(runtime("secret", baseUrl()))).isEmpty();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private String baseUrl() {
        return "http://localhost:%d".formatted(server.getAddress().getPort());
    }

    private ProviderRuntime runtime(String apiKey, String baseUrl) {
        var descriptor = new ProviderDescriptor(
                "Together",
                AuthType.ENV_VAR,
                "TOGETHER_API_KEY",
                null,
                "https://api.together.ai/v1",
                List.of(),
                ProviderCapabilities.chatAndModels(),
                configured -> BaseUrlNormalizer.normalize(configured, "https://api.together.ai/v1")
        );
        return new ProviderRuntime(descriptor, "TOGETHER_API_KEY", baseUrl, apiKey, null);
    }
}
