package com.github.drafael.chat4j.storage;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ModelFavoritesService {

    private static final String FAVORITE_KEY_PREFIX = SettingsKeys.MODEL_FAVORITE_PREFIX;
    private static final String KEY_DELIMITER = SettingsKeys.MODEL_FAVORITE_DELIMITER;

    private final SettingsRepo settingsRepo;
    private final boolean persistenceEnabled;
    private final Set<ModelRef> favorites = ConcurrentHashMap.newKeySet();

    public ModelFavoritesService(SettingsRepo settingsRepo) {
        this(settingsRepo, true);
    }

    private ModelFavoritesService(SettingsRepo settingsRepo, boolean persistenceEnabled) {
        this.settingsRepo = settingsRepo;
        this.persistenceEnabled = persistenceEnabled;
    }

    public static ModelFavoritesService createInMemory() {
        return new ModelFavoritesService(null, false);
    }

    public void primeFromSettings() {
        favorites.clear();
        if (!persistenceEnabled || settingsRepo == null) {
            return;
        }

        try {
            settingsRepo.findByPrefix(FAVORITE_KEY_PREFIX).keySet().stream()
                    .map(ModelFavoritesService::parseFavoriteKey)
                    .filter(Objects::nonNull)
                    .forEach(favorites::add);
        } catch (SQLException e) {
            favorites.clear();
        }
    }

    public boolean isFavorite(String providerName, String modelId) {
        ModelRef modelRef = normalize(providerName, modelId);
        return modelRef != null && favorites.contains(modelRef);
    }

    public boolean setFavorite(String providerName, String modelId, boolean favorite) throws SQLException {
        ModelRef modelRef = normalize(providerName, modelId);
        if (modelRef == null) {
            return false;
        }

        if (persistenceEnabled && settingsRepo != null) {
            String key = toFavoriteKey(modelRef);
            if (favorite) {
                settingsRepo.put(key, "true");
            } else {
                settingsRepo.remove(key);
            }
        }

        if (favorite) {
            favorites.add(modelRef);
        } else {
            favorites.remove(modelRef);
        }

        return true;
    }

    public boolean toggleFavorite(String providerName, String modelId) throws SQLException {
        boolean shouldFavorite = !isFavorite(providerName, modelId);
        return setFavorite(providerName, modelId, shouldFavorite) ? shouldFavorite : false;
    }

    public Set<ModelRef> snapshot() {
        return Set.copyOf(favorites);
    }

    private static String toFavoriteKey(ModelRef modelRef) {
        return FAVORITE_KEY_PREFIX
                + modelRef.providerName()
                + KEY_DELIMITER
                + encode(modelRef.modelId());
    }

    private static ModelRef parseFavoriteKey(String key) {
        if (key == null || !key.startsWith(FAVORITE_KEY_PREFIX)) {
            return null;
        }

        String raw = key.substring(FAVORITE_KEY_PREFIX.length());
        String[] parts = raw.split(KEY_DELIMITER, 2);
        if (parts.length != 2) {
            return null;
        }

        return normalize(decode(parts[0]), decode(parts[1]));
    }

    private static ModelRef normalize(String providerName, String modelId) {
        if (providerName == null || modelId == null) {
            return null;
        }

        String normalizedProvider = SettingsKeys.providerSlug(providerName);
        String normalizedModel = modelId.trim();
        if (normalizedProvider.isEmpty() || normalizedModel.isEmpty()) {
            return null;
        }

        return new ModelRef(normalizedProvider, normalizedModel);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    public record ModelRef(String providerName, String modelId) {
    }
}
