package com.github.drafael.chat4j.http;

import java.util.function.BooleanSupplier;

public interface HttpCall extends AutoCloseable {
    HttpExchangeResponse await(BooleanSupplier isCancelled) throws Exception;

    @Override
    void close();
}
