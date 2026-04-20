package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelMenuStructureRebuildCoordinatorTest {

    @Test
    @DisplayName("Rebuild preserves state and skips action when models menu is absent")
    void rebuild_whenMenuMissing_preservesStateAndSkipsAction() {
        var actionCalls = new AtomicInteger();
        var subject = new ModelMenuStructureRebuildCoordinator((
                modelsMenu,
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                providers,
                onModelSelected
        ) -> actionCalls.incrementAndGet());

        ModelMenuStructureRebuildCoordinator.RebuildState state = subject.rebuild(
                null,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                List.of(),
                selected -> {
                },
                true,
                "provider/model"
        );

        assertThat(actionCalls.get()).isZero();
        assertThat(state.modelsMenuDirty()).isTrue();
        assertThat(state.lastMenuSelectedModelKey()).isEqualTo("provider/model");
    }

    @Test
    @DisplayName("Rebuild executes action and resets model-menu state when menu is present")
    void rebuild_whenMenuPresent_executesActionAndResetsState() {
        var actionCalls = new AtomicInteger();
        var capturedProviders = new ArrayList<>();
        var subject = new ModelMenuStructureRebuildCoordinator((
                modelsMenu,
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                providers,
                onModelSelected
        ) -> {
            actionCalls.incrementAndGet();
            capturedProviders.addAll(providers);
        });

        Map<String, JRadioButtonMenuItem> modelMenuItemsByKey = new LinkedHashMap<>();
        Map<String, JMenuItem> providerHeaderItemsByName = new LinkedHashMap<>();

        ModelMenuStructureRebuildCoordinator.RebuildState state = subject.rebuild(
                new JMenu("Model"),
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                List.of(),
                selected -> {
                },
                true,
                "old-key"
        );

        assertThat(actionCalls.get()).isEqualTo(1);
        assertThat(capturedProviders).isEmpty();
        assertThat(state.modelsMenuDirty()).isFalse();
        assertThat(state.lastMenuSelectedModelKey()).isNull();
    }

    @Test
    @DisplayName("Rebuild validates required arguments")
    void rebuild_whenArgumentMissing_throwsException() {
        var subject = new ModelMenuStructureRebuildCoordinator((
                modelsMenu,
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                providers,
                onModelSelected
        ) -> {
        });

        assertThatThrownBy(() -> subject.rebuild(
                new JMenu("Model"),
                null,
                new LinkedHashMap<>(),
                List.of(),
                selected -> {
                },
                true,
                "old-key"
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelMenuItemsByKey");

        assertThatThrownBy(() -> new ModelMenuStructureRebuildCoordinator(
                (ModelMenuStructureRebuildCoordinator.RebuildAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebuildAction must not be null");
    }
}
