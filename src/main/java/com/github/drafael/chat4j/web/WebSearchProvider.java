package com.github.drafael.chat4j.web;

import java.util.function.BooleanSupplier;

public interface WebSearchProvider {

    String id();

    boolean available();

    WebSearchResponse search(WebSearchRequest request) throws Exception;

    default WebSearchResponse search(WebSearchRequest request, BooleanSupplier isCancelled) throws Exception {
        return search(request);
    }
}
