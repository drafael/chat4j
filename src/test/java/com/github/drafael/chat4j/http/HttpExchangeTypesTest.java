package com.github.drafael.chat4j.http;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpExchangeTypesTest {

    @Test
    @DisplayName("Mutable request and response data is copied and diagnostics remain masked")
    void constructors_whenGivenMutableValues_copyDataAndMaskDiagnostics() {
        byte[] requestBytes = {1, 2};
        byte[] responseBytes = {3, 4};
        var headerValues = new ArrayList<>(List.of("secret"));
        var request = new HttpExchangeRequest(
                "post",
                URI.create("https://example.test"),
                Map.of("Authorization", "secret"),
                HttpBody.bytes(requestBytes),
                Duration.ofSeconds(1),
                10
        );
        var response = new HttpExchangeResponse(201, Map.of("X-Test", headerValues), responseBytes);

        requestBytes[0] = 9;
        responseBytes[0] = 9;
        headerValues.clear();

        assertThat(((HttpBody.Bytes) request.body()).value()).containsExactly(1, 2);
        assertThat(response.body()).containsExactly(3, 4);
        assertThat(response.successful()).isTrue();
        assertThat(response.firstHeader("x-test")).isEqualTo("secret");
        assertThat(request.toString()).doesNotContain("Authorization", "secret");
        assertThat(response.toString()).doesNotContain("secret");
    }
}
