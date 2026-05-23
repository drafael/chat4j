package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.RenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameRenderModeStateTest {

    @Test
    @DisplayName("Default render mode starts in preview")
    void defaults_whenConstructed_startWithPreviewMode() {
        var subject = new MainFrameRenderModeState();

        assertThat(subject.defaultRenderMode()).isEqualTo(RenderMode.PREVIEW);
    }

    @Test
    @DisplayName("Setter updates tracked default render mode")
    void setDefaultRenderMode_whenCalled_updatesStoredMode() {
        var subject = new MainFrameRenderModeState();

        subject.setDefaultRenderMode(RenderMode.MARKDOWN);

        assertThat(subject.defaultRenderMode()).isEqualTo(RenderMode.MARKDOWN);
    }
}
