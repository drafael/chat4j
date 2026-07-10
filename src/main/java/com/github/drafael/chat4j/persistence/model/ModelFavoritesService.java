package com.github.drafael.chat4j.persistence.model;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ModelFavoritesService {

    private final SettingsRepository settingsRepo;
    private final boolean persistenceEnabled;
    private final Set<ModelRef> favorites = ConcurrentHashMap.newKeySet();

    public ModelFavoritesService(SettingsRepository settingsRepo) {
        this(settingsRepo, true);
    }

    private ModelFavoritesService(SettingsRepository settingsRepo, boolean persistenceEnabled) {
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

        settingsRepo.findByPrefix(ModelFavoriteKeyCodec.FAVORITE_KEY_PREFIX).keySet().stream()
                .map(ModelFavoriteKeyCodec::parseFavoriteKey)
                .filter(Objects::nonNull)
                .forEach(favorites::add);
    }

    public boolean isFavorite(String providerName, String modelId) {
        ModelRef modelRef = ModelFavoriteKeyCodec.normalize(providerName, modelId);
        return modelRef != null && favorites.contains(modelRef);
    }

    public boolean setFavorite(String providerName, String modelId, boolean favorite) {
        ModelRef modelRef = ModelFavoriteKeyCodec.normalize(providerName, modelId);
        if (modelRef == null) {
            return false;
        }

        if (persistenceEnabled && settingsRepo != null) {
            String key = ModelFavoriteKeyCodec.toFavoriteKey(modelRef);
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

    public boolean toggleFavorite(String providerName, String modelId) {
        boolean shouldFavorite = !isFavorite(providerName, modelId);
        return setFavorite(providerName, modelId, shouldFavorite) ? shouldFavorite : false;
    }

    public Set<ModelRef> snapshot() {
        return Set.copyOf(favorites);
    }

    public record ModelRef(String providerName, String modelId) {
    }
}
