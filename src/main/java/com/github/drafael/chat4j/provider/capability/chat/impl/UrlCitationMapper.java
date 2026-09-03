package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.chat.content.ExternalLinkSupport;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

final class UrlCitationMapper {

    private UrlCitationMapper() {
    }

    static Optional<CitationRef> fromUrl(String title, String url, String citedText) {
        return fromUrl(title, url, citedText, null, null);
    }

    static Optional<CitationRef> fromUrl(
            String title,
            String url,
            String citedText,
            Long responseStartIndex,
            Long responseEndIndex
    ) {
        String trimmedUrl = StringUtils.trimToEmpty(url);
        if (!ExternalLinkSupport.isAllowedHttpLink(trimmedUrl)) {
            return Optional.empty();
        }

        return Optional.of(CitationRef.builder()
                .kind(CitationKind.WEB)
                .title(StringUtils.trimToEmpty(title))
                .url(trimmedUrl)
                .citedText(StringUtils.trimToEmpty(citedText))
                .responseStartIndex(responseStartIndex)
                .responseEndIndex(responseEndIndex)
                .build());
    }
}
