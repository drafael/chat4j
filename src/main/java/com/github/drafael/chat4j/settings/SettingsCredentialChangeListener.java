package com.github.drafael.chat4j.settings;

@FunctionalInterface
public interface SettingsCredentialChangeListener {

    SettingsCredentialChangeListener NO_OP = change -> {
    };

    void credentialChanged(String canonicalTokenId);

    default void credentialChanging(String canonicalTokenId) {
    }

    default void credentialChangeCompleted(String canonicalTokenId) {
    }

    default void allCredentialsChanging() {
    }

    default void allCredentialsChangeCompleted() {
    }

    default void providerAuthChanging(String providerName) {
    }

    default void providerAuthChanged(String providerName) {
    }

    default void providerAuthChangeCompleted(String providerName) {
    }
}
