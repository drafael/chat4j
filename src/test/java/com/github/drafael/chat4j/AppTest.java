package com.github.drafael.chat4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppTest {

    private final String originalMetalProperty = System.getProperty("sun.java2d.metal");

    @AfterEach
    void restoreSystemProperties() {
        restoreProperty("sun.java2d.metal", originalMetalProperty);
    }

    @Test
    @DisplayName("macOS startup disables Java2D Metal when no override is provided")
    void configureNativeGraphicsPipeline_macOsWithoutOverride_disablesMetal() {
        System.clearProperty("sun.java2d.metal");

        App.configureNativeGraphicsPipeline(() -> true);

        assertThat(System.getProperty("sun.java2d.metal")).isEqualTo("false");
    }

    @Test
    @DisplayName("macOS startup preserves an explicit Java2D Metal override")
    void configureNativeGraphicsPipeline_macOsWithOverride_preservesOverride() {
        System.setProperty("sun.java2d.metal", "true");

        App.configureNativeGraphicsPipeline(() -> true);

        assertThat(System.getProperty("sun.java2d.metal")).isEqualTo("true");
    }

    @Test
    @DisplayName("non-macOS startup leaves Java2D Metal unset")
    void configureNativeGraphicsPipeline_nonMacOs_leavesMetalUnset() {
        System.clearProperty("sun.java2d.metal");

        App.configureNativeGraphicsPipeline(() -> false);

        assertThat(System.getProperty("sun.java2d.metal")).isNull();
    }

    private void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }

        System.setProperty(name, value);
    }
}
