package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

final class UrlCitationMapper {

    private UrlCitationMapper() {
    }

    static Optional<CitationRef> fromUrl(String title, String url, String citedText) {
        String trimmedUrl = StringUtils.trimToEmpty(url);
        if (!isHttpUrl(trimmedUrl)) {
            return Optional.empty();
        }

        return Optional.of(CitationRef.builder()
                .kind(CitationKind.WEB)
                .title(StringUtils.trimToEmpty(title))
                .url(trimmedUrl)
                .citedText(StringUtils.trimToEmpty(citedText))
                .build());
    }

    private static boolean isHttpUrl(String url) {
        if (StringUtils.isBlank(url) || url.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String scheme = StringUtils.defaultString(uri.getScheme()).toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme)) && StringUtils.isNotBlank(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }
}
