package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.Validate;

public class ProviderAvailabilityLabelFormatter {

    public String format(String providerName, boolean enabled) {
        Validate.notBlank(providerName, "providerName must not be blank");
        return enabled ? providerName : "%s (offline)".formatted(providerName);
    }
}
