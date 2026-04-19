package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

public final class ModelSelectionCodec {

    private static final String DELIMITER = " > ";

    private ModelSelectionCodec() {
    }

    public static String format(String providerName, String modelId) {
        return providerName + DELIMITER + modelId;
    }

    public static Optional<ModelSelection> parse(String modelKey) {
        if (StringUtils.isBlank(modelKey)) {
            return Optional.empty();
        }

        int delimiterIndex = modelKey.indexOf(DELIMITER);
        if (delimiterIndex <= 0) {
            return Optional.empty();
        }

        int modelStartIndex = delimiterIndex + DELIMITER.length();
        if (modelStartIndex >= modelKey.length()) {
            return Optional.empty();
        }

        String providerName = modelKey.substring(0, delimiterIndex);
        String modelId = modelKey.substring(modelStartIndex);
        if (providerName.isBlank() || modelId.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new ModelSelection(providerName, modelId));
    }

    public record ModelSelection(String provider, String model) {
    }
}
