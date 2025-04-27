package com.github.drafael.chat4j.provider.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class LocalServiceHealthTest {

    @Test
    @DisplayName("Local service health is reachable when models endpoint responds")
    void isReachable_whenModelsEndpointResponds_returnsTrue() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());
            assertThat(LocalServiceHealth.isReachable(baseUrl)).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Local service health is unreachable when endpoint is not available")
    void isReachable_whenEndpointIsUnavailable_returnsFalse() {
        assertThat(LocalServiceHealth.isReachable("http://127.0.0.1:65534/v1")).isFalse();
    }
}
