package com.github.drafael.chat4j.stt.provider.vosk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VoskModelCatalogEntry(
        String name,
        String lang,
        @JsonProperty("lang_text") String languageText,
        String type,
        String url,
        String md5,
        long size,
        @JsonProperty("size_text") String sizeText,
        Object obsolete,
        String version
) {

    public boolean speechRecognition() {
        return "small".equals(type) || "big".equals(type) || "big-lgraph".equals(type);
    }

    public boolean obsoleteFlag() {
        if (obsolete instanceof Boolean value) {
            return value;
        }
        if (obsolete instanceof Number value) {
            return value.intValue() != 0;
        }
        return StringUtils.equalsAnyIgnoreCase(StringUtils.trimToEmpty(String.valueOf(obsolete)), "true", "yes", "1");
    }

    public String label() {
        return StringUtils.defaultIfBlank(name, "Vosk model");
    }

    public String displayLanguage() {
        return StringUtils.defaultIfBlank(languageText, StringUtils.defaultString(lang));
    }

    public String displaySize() {
        return StringUtils.defaultIfBlank(sizeText, size > 0 ? "%d MB".formatted(Math.max(1, size / 1024 / 1024)) : "");
    }

    public boolean hasDownloadMetadata() {
        return size > 0 && StringUtils.isNotBlank(url) && StringUtils.isNotBlank(md5);
    }
}
