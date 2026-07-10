package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.settings.SettingsKeySlugs;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public final class ProviderRuntimeSettings {

    public static final String PROVIDER_PREFIX = "chat4j.provider.";
    public static final String PROVIDER_ENABLED_SUFFIX = ".enabled";
    public static final String PROVIDER_BASE_URL_SUFFIX = ".baseUrl";

    private final SettingsRepository settingsRepo;
    private final String providerName;

    private ProviderRuntimeSettings(@NonNull SettingsRepository settingsRepo, String providerName) {
        this.settingsRepo = settingsRepo;
        this.providerName = providerName;
    }

    public static ProviderRuntimeSettings forProvider(SettingsRepository settingsRepo, String providerName) {
        return new ProviderRuntimeSettings(settingsRepo, providerName);
    }

    public String enabledKey() {
        return "%s%s%s".formatted(PROVIDER_PREFIX, SettingsKeySlugs.providerSlug(providerName), PROVIDER_ENABLED_SUFFIX);
    }

    public String baseUrlKey() {
        return "%s%s%s".formatted(PROVIDER_PREFIX, SettingsKeySlugs.providerSlug(providerName), PROVIDER_BASE_URL_SUFFIX);
    }

    public boolean enabled(boolean defaultValue) {
        try {
            return Boolean.parseBoolean(settingsRepo.get(enabledKey(), String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public String baseUrl(String defaultValue) {
        try {
            String value = settingsRepo.get(baseUrlKey(), defaultValue);
            return StringUtils.isBlank(value) ? defaultValue : value;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
