package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LocalReasoningOptionsTest {

    @ParameterizedTest
    @CsvSource({
            "Ollama, /api/show",
            "LM Studio, /api/v1/models"
    })
    @DisplayName("Local model metadata exposes a real binary selector and its recommended default")
    void resolveLocalReasoningOptions_binaryMetadata_usesAdvertisedValues(String provider, String path) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            String json = provider.equals("Ollama")
                    ? "{\"thinking\":{\"values\":[false,true],\"default\":true}}"
                    : "{\"models\":[{\"key\":\"custom-model\",\"capabilities\":{\"reasoning\":{\"allowed_options\":[\"off\",\"on\"],\"default\":\"on\"}}}]}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var subject = ProviderCapabilityResolver.resolveLocalReasoningOptions(provider, "custom-model",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()), "test");
            assertThat(subject.allowsLegacyFallback()).isFalse();
            assertThat(subject.options()).hasValueSatisfying(options -> {
                assertThat(options.levels()).containsExactly(ReasoningLevel.OFF, ReasoningLevel.MEDIUM);
                assertThat(options.defaultLevel()).isEqualTo(ReasoningLevel.MEDIUM);
                assertThat(options.select(ReasoningLevel.HIGH)).isEqualTo(ReasoningLevel.MEDIUM);
                assertThat(options.select(ReasoningLevel.OFF)).isEqualTo(ReasoningLevel.OFF);
            });
            assertThat(OpenAiReasoningSupport.properties(provider, "custom-model",
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()), "test", ReasoningLevel.OFF))
                    .containsEntry("reasoning_effort", "none");
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"Ollama, OFF, none", "LM Studio, OFF, none", "Ollama, ULTRA, ultra", "LM Studio, ULTRA, ultra"})
    @DisplayName("Fresh local choices replace a cached negative capability before encoding the selected level")
    void resolveLocalReasoningOptions_cachedNegativeThenFreshChoices_updatesRequestEncoding(
            String provider, ReasoningLevel level, String effort
    ) throws Exception {
        var refreshed = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String reasoning = refreshed.get() ? "{\"allowed_options\":[\"off\",\"ultra\"],\"default\":\"ultra\"}" : "false";
            String json = provider.equals("Ollama")
                    ? (refreshed.get() ? "{\"thinking\":{\"values\":[\"off\",\"ultra\"],\"default\":\"ultra\"}}" : "{\"supports_reasoning\":false}")
                    : "{\"models\":[{\"key\":\"custom-model\",\"capabilities\":{\"reasoning\":%s}}]}".formatted(reasoning);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());
            assertThat(ProviderCapabilityResolver.resolveReasoningSupport(provider, "custom-model", endpoint, "test")).contains(false);
            refreshed.set(true);
            var subject = ProviderCapabilityResolver.resolveLocalReasoningOptions(provider, "custom-model", endpoint, "test");
            assertThat(subject.options()).hasValueSatisfying(options -> assertThat(options.select(level)).isEqualTo(level));
            assertThat(OpenAiReasoningSupport.properties(provider, "custom-model", endpoint, "test", level))
                    .containsEntry("reasoning_effort", effort);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "low high max, max, LOW HIGH MAX, MAX",
            "false low high, low, OFF LOW HIGH, LOW",
            "minimal low high, minimal, MINIMAL LOW HIGH, MINIMAL",
            "low high, future, LOW HIGH, LOW",
            "low future, future, LOW, LOW",
            "off low future, future, OFF LOW, LOW",
            "off, off, OFF, OFF",
            "true, true, MEDIUM, MEDIUM"
    })
    @DisplayName("Named Ollama levels remain distinct without inventing an Off setting")
    void ollamaReasoningOptions_namedMetadata_preservesSupportedChoices(String values, String defaultValue, String expected, ReasoningLevel defaultLevel) {
        String json = "{\"thinking\":{\"values\":[\"%s\"],\"default\":\"%s\"}}".formatted(values.replace(" ", "\",\""), defaultValue);
        var subject = ProviderCapabilityJsonParser.ollamaReasoningOptions(json.getBytes(StandardCharsets.UTF_8));
        assertThat(subject.allowsLegacyFallback()).isFalse();
        assertThat(subject.options()).hasValueSatisfying(options -> {
            assertThat(options.levels()).extracting(Enum::name).containsExactly(expected.split(" "));
            assertThat(options.defaultLevel()).isEqualTo(defaultLevel);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[\"future\"]", "[\"off\",\"future\"]", "null", "42", "{}"})
    @DisplayName("Unusable declared choices do not allow a legacy fallback menu")
    void localReasoningOptions_unusableChoices_remainsUnresolved(String values) {
        String ollama = "{\"thinking\":{\"values\":%s}}".formatted(values);
        String lmStudio = "{\"models\":[{\"key\":\"custom-model\",\"capabilities\":{\"reasoning\":{\"allowed_options\":%s}}}]}".formatted(values);
        Stream.of(
                ProviderCapabilityJsonParser.ollamaReasoningOptions(ollama.getBytes(StandardCharsets.UTF_8)),
                ProviderCapabilityJsonParser.lmStudioReasoningOptions(lmStudio.getBytes(StandardCharsets.UTF_8), "custom-model")
        ).forEach(subject -> {
            assertThat(subject.options()).isEmpty();
            assertThat(subject.allowsLegacyFallback()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "{}"})
    @DisplayName("Legacy local capability metadata without a choice field remains eligible for fallback")
    void localReasoningOptions_choicesAbsent_allowsLegacyFallback(String reasoning) {
        String ollama = "{\"thinking\":%s}".formatted(reasoning);
        String lmStudio = "{\"models\":[{\"key\":\"custom-model\",\"capabilities\":{\"reasoning\":%s}}]}".formatted(reasoning);
        Stream.of(
                ProviderCapabilityJsonParser.ollamaReasoningOptions(ollama.getBytes(StandardCharsets.UTF_8)),
                ProviderCapabilityJsonParser.lmStudioReasoningOptions(lmStudio.getBytes(StandardCharsets.UTF_8), "custom-model")
        ).forEach(subject -> {
            assertThat(subject.options()).isEmpty();
            assertThat(subject.allowsLegacyFallback()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null"})
    @DisplayName("Unparseable local responses cannot be mistaken for legacy capability metadata")
    void localReasoningOptions_invalidResponse_remainsUnresolved(String json) {
        var subject = ProviderCapabilityJsonParser.ollamaReasoningOptions(json.getBytes(StandardCharsets.UTF_8));
        assertThat(subject.options()).isEmpty();
        assertThat(subject.allowsLegacyFallback()).isFalse();
    }
}
