package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.ProviderRuntimeSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NonNull;
import org.apache.commons.lang3.ObjectUtils;

import static java.util.Collections.emptyMap;

public class ProviderRuntimeSettingsResolver {

    private final SettingsRepository settingsRepo;

    public ProviderRuntimeSettingsResolver(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public Map<String, ProviderRegistry.ProviderRuntimeConfig> resolveAll(List<ProviderRegistry.ProviderDef> providers) {
        if (ObjectUtils.isEmpty(providers)) {
            return emptyMap();
        }

        Map<String, ProviderRegistry.ProviderRuntimeConfig> runtimeConfigByProvider = new LinkedHashMap<>();
        providers.forEach(provider -> runtimeConfigByProvider.put(provider.name(), resolve(provider)));
        return runtimeConfigByProvider;
    }

    public ProviderRegistry.ProviderRuntimeConfig resolve(@NonNull ProviderRegistry.ProviderDef providerDef) {

        ProviderRuntimeSettings settings = ProviderRuntimeSettings.forProvider(settingsRepo, providerDef.name());
        boolean enabled = settings.enabled(true);
        String baseUrl = settings.baseUrl(providerDef.baseUrl());

        return new ProviderRegistry.ProviderRuntimeConfig(enabled, baseUrl);
    }
}
