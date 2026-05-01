package com.github.drafael.chat4j.web;

public record BrowsedPage(
        String title,
        String url,
        String domain,
        String excerpt,
        boolean success,
        String error
) {

    public BrowsedPage {
        title = title == null ? "" : title;
        url = url == null ? "" : url;
        domain = domain == null ? "" : domain;
        excerpt = excerpt == null ? "" : excerpt;
        error = error == null ? "" : error;
    }
}
