package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProviderRuntimeSettingsResolver {

    private final SettingsRepo settingsRepo;

    public ProviderRuntimeSettingsResolver(SettingsRepo settingsRepo) {
        this.settingsRepo = Validate.notNull(settingsRepo, "settingsRepo must not be null");
    }

    public Map<String, ProviderRegistry.ProviderRuntimeConfig> resolveAll(List<ProviderRegistry.ProviderDef> providers) {
        if (providers == null || providers.isEmpty()) {
            return Map.of();
        }

        Map<String, ProviderRegistry.ProviderRuntimeConfig> runtimeConfigByProvider = new LinkedHashMap<>();
        providers.forEach(provider -> runtimeConfigByProvider.put(provider.name(), resolve(provider)));
        return runtimeConfigByProvider;
    }

    public ProviderRegistry.ProviderRuntimeConfig resolve(ProviderRegistry.ProviderDef providerDef) {
        Validate.notNull(providerDef, "providerDef must not be null");

        boolean enabled = true;
        String baseUrl = providerDef.baseUrl();

        try {
            enabled = Boolean.parseBoolean(
                    settingsRepo.get("provider.%s.enabled".formatted(providerDef.name()), "true")
            );
        } catch (Exception e) {
            enabled = true;
        }

        try {
            baseUrl = settingsRepo.get("provider.%s.baseUrl".formatted(providerDef.name()), providerDef.baseUrl());
        } catch (Exception e) {
            baseUrl = providerDef.baseUrl();
        }

        if (StringUtils.isBlank(baseUrl)) {
            baseUrl = providerDef.baseUrl();
        }

        return new ProviderRegistry.ProviderRuntimeConfig(enabled, baseUrl);
    }
}
