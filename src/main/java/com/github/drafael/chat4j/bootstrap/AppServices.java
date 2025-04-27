package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import com.github.drafael.chat4j.storage.SettingsRepo;

/**
 * Startup-created services required to construct the main UI.
 */
public record AppServices(
    ConversationRepo conversationRepo,
    SettingsRepo settingsRepo,
    ProviderModelCacheService providerModelCacheService,
    ModelFavoritesService modelFavoritesService
) {
}
