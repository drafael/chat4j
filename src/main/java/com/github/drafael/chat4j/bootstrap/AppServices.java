package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;

/**
 * Startup-created services required to construct the main UI.
 */
public record AppServices(
    ConversationRepository conversationRepo,
    SettingsRepository settingsRepo,
    ProviderModelCacheService providerModelCacheService,
    ModelFavoritesService modelFavoritesService
) {
}
