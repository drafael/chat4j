package com.github.drafael.chat4j.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Rectangle;

import static org.assertj.core.api.Assertions.assertThat;

class ModalDialogSupportTest {

    @Test
    @DisplayName("Compact size grows width to owner-relative minimum")
    void compactSize_whenPackedWidthIsSmall_growsToMinimumWidth() {
        Dimension result = ModalDialogSupport.compactSize(
                new Dimension(100, 120),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result.width).isEqualTo(200);
    }

    @Test
    @DisplayName("Compact size caps width to owner-relative maximum")
    void compactSize_whenPackedWidthIsLarge_capsToMaximumWidth() {
        Dimension result = ModalDialogSupport.compactSize(
                new Dimension(500, 120),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result.width).isEqualTo(300);
    }

    @Test
    @DisplayName("Compact size applies soft minimum height")
    void compactSize_whenPackedHeightIsSmall_appliesSoftMinimumHeight() {
        Dimension result = ModalDialogSupport.compactSize(
                new Dimension(240, 80),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result.height).isEqualTo(140);
    }

    @Test
    @DisplayName("Compact size caps height to owner-relative maximum")
    void compactSize_whenPackedHeightIsLarge_capsToMaximumHeight() {
        Dimension result = ModalDialogSupport.compactSize(
                new Dimension(240, 500),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result.height).isEqualTo(240);
    }

    @Test
    @DisplayName("Compact size uses sane fallback dimensions for tiny owner bounds")
    void compactSize_whenOwnerBoundsAreTiny_usesFallbackDimensions() {
        Dimension result = ModalDialogSupport.compactSize(
                new Dimension(20, 20),
                new Rectangle(0, 0, 20, 20)
        );

        assertThat(result.width).isEqualTo(128);
        assertThat(result.height).isEqualTo(140);
    }
}
