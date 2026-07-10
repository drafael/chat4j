package com.github.drafael.chat4j.persistence.settings;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

public final class SettingsKeySlugs {

    private SettingsKeySlugs() {
    }

    public static String providerSlug(String providerName) {
        String normalized = StringUtils.defaultString(providerName).trim().toLowerCase(Locale.ROOT);
        String slug = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return StringUtils.defaultIfBlank(slug, "unknown");
    }
}
