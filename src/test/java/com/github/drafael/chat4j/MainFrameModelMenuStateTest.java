package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameModelMenuStateTest {

    @Test
    @DisplayName("Default constructor starts with dirty menu and no selected model")
    void constructor_whenDefault_startsWithDirtyMenuAndNoSelection() {
        var subject = new MainFrameModelMenuState();

        assertThat(subject.modelsMenuDirty()).isTrue();
        assertThat(subject.lastMenuSelectedModelKey()).isNull();
    }

    @Test
    @DisplayName("Setters and dirty marker update tracked model-menu state")
    void stateMutators_whenCalled_updateTrackedState() {
        var subject = new MainFrameModelMenuState(false, "OpenAI > gpt-4o");

        subject.setLastMenuSelectedModelKey("Anthropic > claude-3.7-sonnet");
        subject.setModelsMenuDirty(false);
        subject.markModelsMenuDirty();

        assertThat(subject.lastMenuSelectedModelKey()).isEqualTo("Anthropic > claude-3.7-sonnet");
        assertThat(subject.modelsMenuDirty()).isTrue();
    }
}
