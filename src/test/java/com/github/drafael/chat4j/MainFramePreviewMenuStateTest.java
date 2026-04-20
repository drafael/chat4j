package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;

import static org.assertj.core.api.Assertions.assertThat;

class MainFramePreviewMenuStateTest {

    @Test
    @DisplayName("Default state starts without toggle item and sync flag disabled")
    void defaults_whenConstructed_startWithExpectedValues() {
        var subject = new MainFramePreviewMenuState();

        assertThat(subject.togglePreviewMenuItem()).isNull();
        assertThat(subject.syncingPreviewMenuSelection()).isFalse();
    }

    @Test
    @DisplayName("Setters update preview toggle item and sync flag")
    void setters_whenCalled_updateTrackedState() {
        var subject = new MainFramePreviewMenuState();
        var menuItem = new JCheckBoxMenuItem("Preview");

        subject.setTogglePreviewMenuItem(menuItem);
        subject.setSyncingPreviewMenuSelection(true);

        assertThat(subject.togglePreviewMenuItem()).isSameAs(menuItem);
        assertThat(subject.syncingPreviewMenuSelection()).isTrue();
    }
}
