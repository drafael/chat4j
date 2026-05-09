package com.github.drafael.chat4j.settings;


import lombok.NonNull;
import java.util.Set;

public class FontSelectionNormalizer {

    public AppFontSelection normalizeAppSelection(String requestedFamily, int requestedSize, @NonNull Set<String> availableFamilies) {

        String family = availableFamilies.contains(requestedFamily)
                ? requestedFamily
                : AppearancePanel.DEFAULT_APP_FONT;
        int size = AppearancePanel.normalizeAppFontSize(requestedSize);

        return new AppFontSelection(family, size);
    }

    public String normalizeCodeFamily(String requestedFamily, @NonNull Set<String> availableFamilies) {

        return availableFamilies.contains(requestedFamily)
                ? requestedFamily
                : AppearancePanel.DEFAULT_CODE_FONT;
    }

    public record AppFontSelection(String family, int size) {
    }
}
