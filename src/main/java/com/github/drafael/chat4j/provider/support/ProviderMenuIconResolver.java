package com.github.drafael.chat4j.provider.support;


import lombok.NonNull;
import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;

public class ProviderMenuIconResolver implements ProviderMenuAvailabilityApplier.IconResolver {

    private static final int PROVIDER_MODEL_ICON_SIZE = 16;
    private static final int PROVIDER_HEADER_ICON_SIZE = 18;

    private final ProviderMenuIconTintResolver providerMenuIconTintResolver;
    private final Class<?> resourceAnchor;

    public ProviderMenuIconResolver(@NonNull ProviderMenuIconTintResolver providerMenuIconTintResolver, @NonNull Class<?> resourceAnchor) {
        this.providerMenuIconTintResolver = providerMenuIconTintResolver;
        this.resourceAnchor = resourceAnchor;
    }

    @Override
    public Icon resolveModelIcon(String providerName, JRadioButtonMenuItem item, boolean enabled) {
        return ProviderMenuIconRenderer.resolve(
                providerName,
                PROVIDER_MODEL_ICON_SIZE,
                providerMenuIconTintResolver.resolve(item, enabled),
                resourceAnchor
        );
    }

    @Override
    public Icon resolveHeaderIcon(String providerName, JMenuItem item, boolean enabled) {
        return ProviderMenuIconRenderer.resolve(
                providerName,
                PROVIDER_HEADER_ICON_SIZE,
                providerMenuIconTintResolver.resolve(item, enabled),
                resourceAnchor
        );
    }
}
