package com.github.drafael.chat4j.tts;

import org.apache.commons.lang3.StringUtils;

public record TextToSpeechCatalogItem(String id, String label, String description) {

    public TextToSpeechCatalogItem {
        id = StringUtils.trimToEmpty(id);
        label = StringUtils.defaultIfBlank(StringUtils.trimToEmpty(label), id);
        description = StringUtils.trimToEmpty(description);
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    public static TextToSpeechCatalogItem of(String id, String label) {
        return new TextToSpeechCatalogItem(id, label, "");
    }
}
