package com.github.drafael.chat4j.http;

import com.github.drafael.chat4j.json.JsonCodec;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonHttpClientTest {

    @Test
    @DisplayName("POST serializes a typed request and preserves finite exchange metadata")
    void post_whenCalled_serializesRequestAndPreservesOptions() throws Exception {
        var captured = new AtomicReference<HttpExchangeRequest>();
        HttpTransport transport = (request, cancellation) -> {
            captured.set(request);
            return response("{\"value\":\"ok\"}");
        };
        var subject = new JsonHttpClient(JsonCodec.standard(), transport);
        var options = new HttpExchangeOptions(Duration.ofSeconds(3), 512);

        HttpExchangeResponse response = subject.post(
                URI.create("https://example.test/items"),
                Map.of("Authorization", "secret"),
                new Payload("hello"),
                options,
                () -> false
        );

        assertThat(captured.get().method()).isEqualTo("POST");
        assertThat(captured.get().timeout()).isEqualTo(options.timeout());
        assertThat(captured.get().maxResponseBytes()).isEqualTo(512);
        assertThat(new String(((HttpBody.Bytes) captured.get().body()).value())).isEqualTo("{\"value\":\"hello\"}");
        assertThat(subject.read(response, Payload.class, "invalid")).isEqualTo(new Payload("ok"));
    }

    @Test
    @DisplayName("Optional decoding and invalid-response mapping do not expose payloads")
    void read_whenResponseIsMalformed_mapsStableMessage() {
        HttpExchangeResponse response = response("{\"secret\":");
        var subject = new JsonHttpClient(JsonCodec.standard(), (request, cancellation) -> response);

        assertThat(subject.tryRead(response, Payload.class)).isEmpty();
        assertThatThrownBy(() -> subject.read(response, Payload.class, "Provider response was invalid."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Provider response was invalid.")
                .hasMessageNotContaining("secret");
    }

    private static HttpExchangeResponse response(String body) {
        return new HttpExchangeResponse(200, emptyMap(), body.getBytes());
    }

    private record Payload(String value) {
    }
}
