package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.Validate;

import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;

public class ProviderMenuIconResolver implements ProviderMenuAvailabilityApplier.IconResolver {

    private static final int PROVIDER_MODEL_ICON_SIZE = 16;
    private static final int PROVIDER_HEADER_ICON_SIZE = 18;

    private final ProviderMenuIconTintResolver providerMenuIconTintResolver;
    private final Class<?> resourceAnchor;

    public ProviderMenuIconResolver(ProviderMenuIconTintResolver providerMenuIconTintResolver, Class<?> resourceAnchor) {
        this.providerMenuIconTintResolver = Validate.notNull(
                providerMenuIconTintResolver,
                "providerMenuIconTintResolver must not be null"
        );
        this.resourceAnchor = Validate.notNull(resourceAnchor, "resourceAnchor must not be null");
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
