package com.github.drafael.chat4j.chat.message;

import me.friwi.jcefmaven.EnumProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JcefInitializationProgressTest {

    @Test
    @DisplayName("Download progress maps to determinate percentage")
    void from_whenDownloadProgressIsKnown_mapsToDeterminatePercentage() {
        JcefInitializationProgress result = JcefInitializationProgress.from(EnumProgress.DOWNLOADING, 42.4f);

        assertThat(result.stage()).isEqualTo("Downloading");
        assertThat(result.message()).isEqualTo("Downloading Chromium bundle…");
        assertThat(result.percent()).isEqualTo(42);
        assertThat(result.determinate()).isTrue();
    }

    @Test
    @DisplayName("Unknown progress maps to indeterminate state")
    void from_whenProgressHasNoEstimation_mapsToIndeterminateState() {
        JcefInitializationProgress result = JcefInitializationProgress.from(EnumProgress.EXTRACTING, EnumProgress.NO_ESTIMATION);

        assertThat(result.stage()).isEqualTo("Extracting");
        assertThat(result.message()).isEqualTo("Extracting Chromium bundle…");
        assertThat(result.percent()).isZero();
        assertThat(result.determinate()).isFalse();
    }

    @Test
    @DisplayName("Initialized progress maps to ready message")
    void from_whenInitialized_mapsToReadyMessage() {
        JcefInitializationProgress result = JcefInitializationProgress.from(EnumProgress.INITIALIZED, 100.0f);

        assertThat(result.stage()).isEqualTo("Ready");
        assertThat(result.message()).isEqualTo("Chromium ready");
        assertThat(result.percent()).isEqualTo(100);
        assertThat(result.determinate()).isTrue();
    }
}
