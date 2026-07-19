package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.persistence.catalog.CatalogSnapshotStore;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;

/**
 * Startup-created services required to construct the main UI.
 */
public record AppServices(
    ConversationRepository conversationRepo,
    SettingsRepository settingsRepo,
    ProviderModelCacheService providerModelCacheService,
    ModelFavoritesService modelFavoritesService,
    StoragePaths storagePaths,
    CatalogSnapshotStore catalogSnapshots,
    ProviderRegistry providerRegistry,
    CopilotAuthResolver copilotAuthResolver,
    CodexAuthResolver codexAuthResolver,
    CopilotModelMetadataStore copilotModelMetadataStore
) {
}
