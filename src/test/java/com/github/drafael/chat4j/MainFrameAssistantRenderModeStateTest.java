package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameAssistantRenderModeStateTest {

    @Test
    @DisplayName("Default assistant render mode starts in preview")
    void defaults_whenConstructed_startWithPreviewMode() {
        var subject = new MainFrameAssistantRenderModeState();

        assertThat(subject.defaultAssistantRenderMode()).isEqualTo(AssistantRenderMode.PREVIEW);
    }

    @Test
    @DisplayName("Setter updates tracked default assistant render mode")
    void setDefaultAssistantRenderMode_whenCalled_updatesStoredMode() {
        var subject = new MainFrameAssistantRenderModeState();

        subject.setDefaultAssistantRenderMode(AssistantRenderMode.MARKDOWN);

        assertThat(subject.defaultAssistantRenderMode()).isEqualTo(AssistantRenderMode.MARKDOWN);
    }
}
