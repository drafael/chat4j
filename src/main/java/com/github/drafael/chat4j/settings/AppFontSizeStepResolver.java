package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import java.util.stream.IntStream;

public class AppFontSizeStepResolver {

    public int resolveAdjustedSize(int[] sizeOptions, int currentSize, boolean increase) {
        Validate.notNull(sizeOptions, "sizeOptions must not be null");
        Validate.isTrue(sizeOptions.length > 0, "sizeOptions must not be empty");

        int normalizedCurrentSize = AppearancePanel.normalizeAppFontSize(currentSize);
        return increase
                ? IntStream.of(sizeOptions)
                .filter(size -> size > normalizedCurrentSize)
                .findFirst()
                .orElse(normalizedCurrentSize)
                : IntStream.range(0, sizeOptions.length)
                .map(index -> sizeOptions[sizeOptions.length - 1 - index])
                .filter(size -> size < normalizedCurrentSize)
                .findFirst()
                .orElse(normalizedCurrentSize);
    }
}
