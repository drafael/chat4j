package com.github.drafael.chat4j.settings;

@FunctionalInterface
public interface SettingsCredentialChangeListener {

    SettingsCredentialChangeListener NO_OP = change -> {
    };

    void credentialChanged(String canonicalTokenId);

    default void providerAuthChanged(String providerName) {
    }
}
