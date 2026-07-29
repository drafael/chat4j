package com.github.drafael.chat4j.mcp;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.NonNull;
import reactor.core.publisher.Mono;

final class FilteringMcpClientTransport implements McpClientTransport {

    private static final Set<String> DROPPED_NOTIFICATIONS = Set.of(
            "notifications/tools/list_changed",
            "notifications/resources/list_changed",
            "notifications/resources/updated",
            "notifications/prompts/list_changed",
            "notifications/message",
            "notifications/progress",
            "notifications/elicitation/complete"
    );

    private final McpClientTransport delegate;
    private volatile Consumer<Throwable> exceptionHandler = ignored -> { };

    FilteringMcpClientTransport(@NonNull McpClientTransport delegate) {
        this.delegate = delegate;
        delegate.setExceptionHandler(error -> exceptionHandler.accept(error));
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    @Override
    public Mono<Void> connect(
            @NonNull Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler
    ) {
        return delegate.connect(message -> message.flatMap(value -> {
            if (value instanceof McpSchema.JSONRPCNotification notification
                    && DROPPED_NOTIFICATIONS.contains(notification.method())) {
                return Mono.empty();
            }
            return handler.apply(Mono.just(value));
        }));
    }

    @Override
    public void setExceptionHandler(Consumer<Throwable> exceptionHandler) {
        this.exceptionHandler = exceptionHandler == null ? ignored -> { } : exceptionHandler;
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public Mono<Void> sendMessage(@NonNull McpSchema.JSONRPCMessage message) {
        return delegate.sendMessage(message);
    }

    @Override
    public <T> T unmarshalFrom(@NonNull Object data, @NonNull TypeRef<T> typeRef) {
        return delegate.unmarshalFrom(data, typeRef);
    }
}
