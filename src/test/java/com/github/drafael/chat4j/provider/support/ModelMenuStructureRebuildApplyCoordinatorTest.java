package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelMenuStructureRebuildApplyCoordinatorTest {

    @Test
    @DisplayName("Apply updates tracked model-menu state and returns applied state")
    void apply_whenCalled_updatesStateAndReturnsRebuildState() {
        var subject = new ModelMenuStructureRebuildApplyCoordinator();
        var rebuildState = new ModelMenuStructureRebuildCoordinator.RebuildState(false, "provider/model");

        var modelsMenuDirty = new AtomicReference<Boolean>();
        var lastMenuSelectedModelKey = new AtomicReference<String>();

        ModelMenuStructureRebuildCoordinator.RebuildState applied = subject.apply(
                rebuildState,
                modelsMenuDirty::set,
                lastMenuSelectedModelKey::set
        );

        assertThat(applied).isSameAs(rebuildState);
        assertThat(modelsMenuDirty.get()).isFalse();
        assertThat(lastMenuSelectedModelKey.get()).isEqualTo("provider/model");
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenRequiredArgumentMissing_throwsException() {
        var subject = new ModelMenuStructureRebuildApplyCoordinator();
        var rebuildState = new ModelMenuStructureRebuildCoordinator.RebuildState(false, null);

        assertThatThrownBy(() -> subject.apply(null, value -> {
        }, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebuildState");

        assertThatThrownBy(() -> subject.apply(rebuildState, null, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setModelsMenuDirty");

        assertThatThrownBy(() -> subject.apply(rebuildState, value -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setLastMenuSelectedModelKey");
    }
}
