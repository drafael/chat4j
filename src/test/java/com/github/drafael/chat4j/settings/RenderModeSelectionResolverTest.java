package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderModeSelectionResolverTest {

    private final RenderModeSelectionResolver subject = new RenderModeSelectionResolver();

    @Test
    @DisplayName("Resolve returns global default mode")
    void resolve_whenDefaultModeProvided_returnsDefaultMode() {
        assertThat(subject.resolve(RenderMode.MARKDOWN)).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Resolve validates default mode")
    void resolve_whenDefaultModeMissing_throwsException() {
        assertThatThrownBy(() -> subject.resolve(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("defaultMode");
    }
}
