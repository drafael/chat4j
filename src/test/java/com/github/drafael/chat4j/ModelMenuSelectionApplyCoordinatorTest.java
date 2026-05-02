package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelMenuSelectionApplyCoordinatorTest {

    @Test
    @DisplayName("Apply updates last selected model key and returns applied key")
    void apply_whenCalled_updatesStateAndReturnsValue() {
        var subject = new ModelMenuSelectionApplyCoordinator();
        var lastMenuSelectedModelKey = new AtomicReference<String>();

        String applied = subject.apply("OpenAI > gpt-4.1", lastMenuSelectedModelKey::set);

        assertThat(applied).isEqualTo("OpenAI > gpt-4.1");
        assertThat(lastMenuSelectedModelKey.get()).isEqualTo("OpenAI > gpt-4.1");
    }

    @Test
    @DisplayName("Apply validates required setter")
    void apply_whenSetterMissing_throwsException() {
        var subject = new ModelMenuSelectionApplyCoordinator();

        assertThatThrownBy(() -> subject.apply("OpenAI > gpt-4.1", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setLastMenuSelectedModelKey is marked non-null");
    }
}
