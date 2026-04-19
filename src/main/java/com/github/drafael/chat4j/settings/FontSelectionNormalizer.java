package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import java.util.Set;

public class FontSelectionNormalizer {

    public AppFontSelection normalizeAppSelection(String requestedFamily, int requestedSize, Set<String> availableFamilies) {
        Validate.notNull(availableFamilies, "availableFamilies must not be null");

        String family = availableFamilies.contains(requestedFamily)
                ? requestedFamily
                : AppearancePanel.DEFAULT_APP_FONT;
        int size = AppearancePanel.normalizeAppFontSize(requestedSize);

        return new AppFontSelection(family, size);
    }

    public String normalizeCodeFamily(String requestedFamily, Set<String> availableFamilies) {
        Validate.notNull(availableFamilies, "availableFamilies must not be null");

        return availableFamilies.contains(requestedFamily)
                ? requestedFamily
                : AppearancePanel.DEFAULT_CODE_FONT;
    }

    public record AppFontSelection(String family, int size) {
    }
}
