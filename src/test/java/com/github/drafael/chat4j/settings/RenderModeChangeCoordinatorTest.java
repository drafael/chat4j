package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderModeChangeCoordinatorTest {

    @Test
    @DisplayName("Apply persists global render mode")
    void apply_whenModeProvided_persistsMode() {
        var persistedMode = new AtomicReference<RenderMode>();
        var subject = new RenderModeChangeCoordinator(persistedMode::set);

        RenderModeChangeCoordinator.ApplyResult result = subject.apply(RenderMode.MARKDOWN);

        assertThat(result.handled()).isTrue();
        assertThat(persistedMode.get()).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Apply ignores null mode")
    void apply_whenModeMissing_ignoresChange() {
        var persistedMode = new AtomicReference<RenderMode>();
        var subject = new RenderModeChangeCoordinator(persistedMode::set);

        RenderModeChangeCoordinator.ApplyResult result = subject.apply(null);

        assertThat(result.handled()).isFalse();
        assertThat(persistedMode.get()).isNull();
    }

    @Test
    @DisplayName("Constructor validates persister")
    void constructor_whenPersisterMissing_throwsException() {
        assertThatThrownBy(() -> new RenderModeChangeCoordinator((RenderModeChangeCoordinator.ModePersister) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modePersister");
    }
}
