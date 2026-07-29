package com.github.drafael.chat4j.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class FilteringMcpClientTransportTest {

    @Test
    @DisplayName("List-change and progress notifications are dropped without background handling")
    void connect_whenOptionalNotificationArrives_dropsNotification() {
        var delegate = new FakeTransport();
        var subject = new FilteringMcpClientTransport(delegate);
        var handled = new AtomicInteger();
        subject.connect(message -> message.doOnNext(ignored -> handled.incrementAndGet())).block();

        delegate.emit(new McpSchema.JSONRPCNotification("notifications/tools/list_changed")).block();
        delegate.emit(new McpSchema.JSONRPCNotification("notifications/progress")).block();

        assertThat(handled).hasValue(0);
    }

    @Test
    @DisplayName("Ping and unsupported requests remain available to the SDK protocol handler")
    void connect_whenRequestArrives_forwardsRequestToProtocolHandler() {
        var delegate = new FakeTransport();
        var subject = new FilteringMcpClientTransport(delegate);
        var handled = new AtomicInteger();
        subject.connect(message -> message.map(value -> {
            handled.incrementAndGet();
            McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) value;
            return "ping".equals(request.method())
                    ? McpSchema.JSONRPCResponse.result(request.id(), emptyMap())
                    : McpSchema.JSONRPCResponse.error(
                            request.id(),
                            new McpSchema.JSONRPCResponse.JSONRPCError(-32601, "Method not found")
                    );
        })).block();

        McpSchema.JSONRPCResponse ping = (McpSchema.JSONRPCResponse) delegate
                .emit(new McpSchema.JSONRPCRequest("ping", 1)).block();
        McpSchema.JSONRPCResponse unsupported = (McpSchema.JSONRPCResponse) delegate
                .emit(new McpSchema.JSONRPCRequest("sampling/createMessage", 2)).block();

        assertThat(handled).hasValue(2);
        assertThat(ping.result()).isEqualTo(emptyMap());
        assertThat(unsupported.error().code()).isEqualTo(-32601);
    }

    private static final class FakeTransport implements McpClientTransport {
        private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

        @Override
        public List<String> protocolVersions() {
            return List.of("2025-06-18");
        }

        @Override
        public Mono<Void> connect(
                Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler
        ) {
            this.handler = handler;
            return Mono.empty();
        }

        private Mono<McpSchema.JSONRPCMessage> emit(McpSchema.JSONRPCMessage message) {
            return handler.apply(Mono.just(message));
        }

        @Override
        public void setExceptionHandler(Consumer<Throwable> handler) {
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.empty();
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            throw new UnsupportedOperationException();
        }
    }
}
