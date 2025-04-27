package com.github.drafael.chat4j.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentBootstrapperTest {

    @Test
    @DisplayName("Environment warning is not shown when launch is not from macOS jpackage")
    void shouldWarnUser_whenLaunchIsNotMacJpackage_returnsFalse() {
        boolean shouldWarn = EnvironmentBootstrapper.shouldWarnUser(false, Map.of(), false);

        assertThat(shouldWarn).isFalse();
    }

    @Test
    @DisplayName("Environment warning is not shown when shell environment is loaded")
    void shouldWarnUser_whenShellEnvironmentIsLoaded_returnsFalse() {
        boolean shouldWarn = EnvironmentBootstrapper.shouldWarnUser(true, Map.of("OPENAI_API_KEY", "sk-test"), false);

        assertThat(shouldWarn).isFalse();
    }

    @Test
    @DisplayName("Environment warning is not shown when provider credentials are already available")
    void shouldWarnUser_whenProviderCredentialsAreAvailable_returnsFalse() {
        boolean shouldWarn = EnvironmentBootstrapper.shouldWarnUser(true, Map.of(), true);

        assertThat(shouldWarn).isFalse();
    }

    @Test
    @DisplayName("Environment warning is shown when macOS jpackage launch has no shell env and no provider credentials")
    void shouldWarnUser_whenMacJpackageLaunchHasNoCredentials_returnsTrue() {
        boolean shouldWarn = EnvironmentBootstrapper.shouldWarnUser(true, Map.of(), false);

        assertThat(shouldWarn).isTrue();
    }
}
