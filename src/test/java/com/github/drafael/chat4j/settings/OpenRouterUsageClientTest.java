package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import java.util.ArrayDeque;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenRouterUsageClientTest {

    @Test
    @DisplayName("Key and credit responses are combined without exposing HTTP mechanics to the panel")
    void fetch_whenKeyAndCreditResponsesAreValid_returnsUsageSnapshot() {
        var responses = new ArrayDeque<HttpExchangeResponse>();
        responses.add(response("""
                {"data":{"limit":100,"usage":25,"limit_remaining":75,"limit_reset":"tomorrow"}}
                """));
        responses.add(response("""
                {"data":{"total_credits":80,"total_usage":30}}
                """));
        HttpTransport transport = (request, cancellation) -> responses.removeFirst();
        var subject = new OpenRouterUsageClient(transport);

        OpenRouterUsageClient.Snapshot snapshot = subject.fetch("secret");

        assertThat(snapshot.errorMessage()).isNull();
        assertThat(snapshot.limit()).isEqualTo(100);
        assertThat(snapshot.remaining()).isEqualTo(75);
        assertThat(snapshot.usedPercent()).isEqualTo(25);
        assertThat(snapshot.balance()).isEqualTo(50);
        assertThat(snapshot.note()).isEqualTo("Resets tomorrow");
    }

    @Test
    @DisplayName("A missing key response data object produces a safe error snapshot")
    void fetch_whenKeyResponseHasNoData_returnsErrorSnapshot() {
        HttpTransport transport = (request, cancellation) -> response("{}");
        var subject = new OpenRouterUsageClient(transport);

        OpenRouterUsageClient.Snapshot snapshot = subject.fetch("secret");

        assertThat(snapshot.errorMessage()).contains("Missing data object");
        assertThat(snapshot.usedPercent()).isEqualTo(-1);
    }

    private static HttpExchangeResponse response(String body) {
        return new HttpExchangeResponse(200, Map.of(), body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
