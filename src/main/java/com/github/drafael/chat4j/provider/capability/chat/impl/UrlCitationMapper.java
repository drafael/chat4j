package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

final class UrlCitationMapper {

    private UrlCitationMapper() {
    }

    static Optional<CitationRef> fromUrl(String title, String url, String citedText) {
        if (StringUtils.isBlank(url)) {
            return Optional.empty();
        }

        return Optional.of(CitationRef.builder()
                .kind(CitationKind.WEB)
                .title(StringUtils.trimToEmpty(title))
                .url(StringUtils.trimToEmpty(url))
                .citedText(StringUtils.trimToEmpty(citedText))
                .build());
    }
}
