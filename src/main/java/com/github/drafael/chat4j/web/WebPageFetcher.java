package com.github.drafael.chat4j.web;

import java.util.function.BooleanSupplier;

public interface WebPageFetcher {

    BrowsedPage fetch(String url, BooleanSupplier isCancelled);
}
