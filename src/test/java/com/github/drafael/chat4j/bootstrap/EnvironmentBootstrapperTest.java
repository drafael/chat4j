package com.github.drafael.chat4j.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Collections.emptyMap;

class EnvironmentBootstrapperTest {

    @Test
    @DisplayName("Environment warning is not shown when launch is not from macOS jpackage")
    void shouldWarnUser_whenLaunchIsNotMacJpackage_returnsFalse() {
        boolean shouldWarn = EnvironmentBootstrapper.shouldWarnUser(false, emptyMap(), false);

        assertThat(shouldWarn).isFalse();
    }

    @Test
    @DisplayName("Environment warning is not shown when shell environment is loaded")
    void shouldWarnUser_whenShellEnvironmentIsLoaded_returnsFalse() {
        boolean shouldWarn = EnvironmentBootstrapper.shouldWarnUser(true, Map.of("OPENAI_API_KEY", "DUMMY_OPENAI_KEY_FOR_TESTS"), false);

        assertThat(shouldWarn).isFalse();
    }

    @Test
    @DisplayName("Environment warning is not shown when provider credentials are already available")
    void shouldWarnUser_whenProviderCredentialsAreAvailable_returnsFalse() {
        boolean shouldWarn = EnvironmentBootstrapper.shouldWarnUser(true, emptyMap(), true);

        assertThat(shouldWarn).isFalse();
    }

    @Test
    @DisplayName("Environment warning is shown when macOS jpackage launch has no shell env and no provider credentials")
    void shouldWarnUser_whenMacJpackageLaunchHasNoCredentials_returnsTrue() {
        boolean shouldWarn = EnvironmentBootstrapper.shouldWarnUser(true, emptyMap(), false);

        assertThat(shouldWarn).isTrue();
    }
}
