package com.github.drafael.chat4j.web;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.List;

public class WebSearchAvailabilityResolver {

    public static final String NATIVE_OPTION_ID = "native";
    public static final String PERPLEXITY_OPTION_ID = "perplexity";

    public WebSearchAvailability resolve(
            ProviderRegistry.ProviderDef selectedProvider,
            String selectedModelId,
            List<ProviderRegistry.ProviderDef> availableProviders
    ) {
        return resolve(
                selectedProvider,
                selectedModelId,
                availableProviders,
                supportsNative(selectedProvider, selectedModelId)
        );
    }

    public WebSearchAvailability resolve(
            ProviderRegistry.ProviderDef selectedProvider,
            String selectedModelId,
            List<ProviderRegistry.ProviderDef> availableProviders,
            boolean nativeWebSearchSupported
    ) {
        List<WebSearchOption> options = new ArrayList<>();
        if (nativeWebSearchSupported) {
            options.add(new WebSearchOption(NATIVE_OPTION_ID, "Native", WebSearchMode.NATIVE, true));
        }

        if (!isSelectedProvider(selectedProvider, "Perplexity") && perplexityExternalAvailable(availableProviders)) {
            options.add(new WebSearchOption(PERPLEXITY_OPTION_ID, "Perplexity", WebSearchMode.EXTERNAL, true));
        }

        String defaultOptionId = options.stream()
                .filter(option -> Strings.CS.equals(option.id(), NATIVE_OPTION_ID))
                .findFirst()
                .or(() -> options.stream().filter(option -> Strings.CS.equals(option.id(), PERPLEXITY_OPTION_ID)).findFirst())
                .or(() -> options.stream().findFirst())
                .map(WebSearchOption::id)
                .orElse(null);
        return new WebSearchAvailability(options, defaultOptionId);
    }

    private boolean supportsNative(ProviderRegistry.ProviderDef selectedProvider, String selectedModelId) {
        if (selectedProvider == null || StringUtils.isBlank(selectedModelId)) {
            return false;
        }

        ProviderCapabilities capabilities = selectedProvider.capabilities();
        return ProviderCapabilityResolver.supportsNativeWebSearch(
                capabilities,
                selectedProvider.name(),
                selectedModelId
        );
    }

    private boolean perplexityExternalAvailable(List<ProviderRegistry.ProviderDef> availableProviders) {
        return availableProviders != null && availableProviders.stream()
                .anyMatch(provider -> Strings.CS.equals(provider.name(), "Perplexity"));
    }

    private boolean isSelectedProvider(ProviderRegistry.ProviderDef selectedProvider, String providerName) {
        return selectedProvider != null && Strings.CS.equals(selectedProvider.name(), providerName);
    }
}
