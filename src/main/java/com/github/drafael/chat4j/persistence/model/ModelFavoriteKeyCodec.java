package com.github.drafael.chat4j.persistence.model;

import com.github.drafael.chat4j.persistence.settings.SettingsKeySlugs;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class ModelFavoriteKeyCodec {

    static final String FAVORITE_KEY_PREFIX = "chat4j.models.favorite.";
    private static final String KEY_DELIMITER = "::";

    private ModelFavoriteKeyCodec() {
    }

    static String toFavoriteKey(ModelFavoritesService.ModelRef modelRef) {
        if (modelRef == null) {
            throw new IllegalArgumentException("model favorite reference should not be null");
        }

        ModelFavoritesService.ModelRef normalized = normalize(modelRef.providerName(), modelRef.modelId());
        if (normalized == null) {
            throw new IllegalArgumentException("model favorite reference should include a provider and model");
        }

        return FAVORITE_KEY_PREFIX
                + normalized.providerName()
                + KEY_DELIMITER
                + encode(normalized.modelId());
    }

    static ModelFavoritesService.ModelRef parseFavoriteKey(String key) {
        if (key == null || !key.startsWith(FAVORITE_KEY_PREFIX)) {
            return null;
        }

        String raw = key.substring(FAVORITE_KEY_PREFIX.length());
        int delimiterIndex = raw.indexOf(KEY_DELIMITER);
        if (delimiterIndex < 0) {
            return null;
        }

        String provider = raw.substring(0, delimiterIndex);
        String model = raw.substring(delimiterIndex + KEY_DELIMITER.length());
        return normalize(decode(provider), decode(model));
    }

    static ModelFavoritesService.ModelRef normalize(String providerName, String modelId) {
        if (providerName == null || modelId == null) {
            return null;
        }

        String normalizedProvider = SettingsKeySlugs.providerSlug(providerName);
        String normalizedModel = modelId.trim();
        if (normalizedModel.isEmpty()) {
            return null;
        }

        return new ModelFavoritesService.ModelRef(normalizedProvider, normalizedModel);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
