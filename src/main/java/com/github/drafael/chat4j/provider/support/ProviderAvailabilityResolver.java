package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toMap;

@Slf4j
public class ProviderAvailabilityResolver {

    private static final Set<String> LOCAL_HEALTH_GATED_PROVIDERS = Set.of("LM Studio", "Ollama");

    private final SettingsRepo settingsRepo;
    private final LocalServiceHealthProbe localServiceHealthProbe;

    public ProviderAvailabilityResolver(SettingsRepo settingsRepo) {
        this(settingsRepo, new LocalServiceHealthProbe() {
            @Override
            public boolean isReachable(String baseUrl) {
                return LocalServiceHealth.isReachable(baseUrl);
            }

            @Override
            public boolean isReachableNonBlocking(String baseUrl) {
                return LocalServiceHealth.isReachableNonBlocking(baseUrl);
            }
        });
    }

    ProviderAvailabilityResolver(@NonNull SettingsRepo settingsRepo, @NonNull LocalServiceHealthProbe localServiceHealthProbe) {
        this.settingsRepo = settingsRepo;
        this.localServiceHealthProbe = localServiceHealthProbe;
    }

    public boolean isModelSelectionEnabled(@NonNull ProviderRegistry.ProviderDef providerDef) {

        if (!LOCAL_HEALTH_GATED_PROVIDERS.contains(providerDef.name())) {
            return true;
        }

        return localServiceHealthProbe.isReachableNonBlocking(providerDef.baseUrl());
    }

    public Map<String, Boolean> resolveMenuAvailability(@NonNull List<ProviderRegistry.ProviderDef> providers) {

        Map<String, String> defaultBaseUrlByProvider = providers.stream()
                .collect(toMap(
                        ProviderRegistry.ProviderDef::name,
                        ProviderRegistry.ProviderDef::baseUrl,
                        (existing, replacement) -> replacement,
                        LinkedHashMap::new
                ));

        Map<String, Boolean> providerEnabledByName = new LinkedHashMap<>();
        LOCAL_HEALTH_GATED_PROVIDERS.forEach(providerName -> {
            String defaultBaseUrl = defaultBaseUrlByProvider.get(providerName);
            String configuredBaseUrl = readConfiguredProviderBaseUrl(providerName, defaultBaseUrl);
            providerEnabledByName.put(providerName, localServiceHealthProbe.isReachable(configuredBaseUrl));
        });

        return providerEnabledByName;
    }

    private String readConfiguredProviderBaseUrl(String providerName, String defaultBaseUrl) {
        try {
            String value = settingsRepo.get(SettingsKeys.providerBaseUrlKey(providerName), defaultBaseUrl);
            return StringUtils.isBlank(value) ? defaultBaseUrl : value;
        } catch (Exception e) {
            log.warn("Failed to resolve configured base URL for {}: {}", providerName, ExceptionUtils.getMessage(e));
            return defaultBaseUrl;
        }
    }

    interface LocalServiceHealthProbe {
        boolean isReachable(String baseUrl);

        boolean isReachableNonBlocking(String baseUrl);
    }
}
