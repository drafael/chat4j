package com.github.drafael.chat4j.stt.provider;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

public record SpeechToTextCatalogItem(String id, String label, String description) {

    public SpeechToTextCatalogItem {
        id = Validate.notBlank(StringUtils.trimToEmpty(id), "id must not be blank");
        label = StringUtils.defaultIfBlank(StringUtils.trimToEmpty(label), id);
        description = StringUtils.trimToEmpty(description);
    }

    public static SpeechToTextCatalogItem of(String id, String label) {
        return new SpeechToTextCatalogItem(id, label, "");
    }

    public static SpeechToTextCatalogItem of(String id, String label, String description) {
        return new SpeechToTextCatalogItem(id, label, description);
    }
}
