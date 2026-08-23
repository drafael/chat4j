package com.github.drafael.chat4j.http;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import lombok.NonNull;

@FunctionalInterface
public interface HttpTransport {
    HttpExchangeResponse send(@NonNull HttpExchangeRequest request, BooleanSupplier isCancelled) throws Exception;

    default HttpCall open(@NonNull HttpExchangeRequest request) {
        return new HttpCall() {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public HttpExchangeResponse await(BooleanSupplier isCancelled) throws Exception {
                BooleanSupplier cancellation = () -> closed.get() || (isCancelled != null && isCancelled.getAsBoolean());
                return send(request, cancellation);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }
}
