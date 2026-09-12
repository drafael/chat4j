package com.github.drafael.chat4j.provider.support;

import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JMenu;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
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
                menuData,
                onModelSelected
        ) -> actionCalls.incrementAndGet());

        ModelMenuStructureRebuildCoordinator.RebuildState state = subject.rebuild(
                null,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                emptyMenuData(),
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
    void rebuild_whenMenuPresent_executesActionAndResetsState() throws Exception {
        var actionCalls = new AtomicInteger();
        var capturedMenuData = new AtomicReference<ProviderMenuDataResolver.ProviderMenuData>();
        var subject = new ModelMenuStructureRebuildCoordinator((
                modelsMenu,
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                menuData,
                onModelSelected
        ) -> {
            actionCalls.incrementAndGet();
            capturedMenuData.set(menuData);
        });
        var menuData = emptyMenuData();

        ModelMenuStructureRebuildCoordinator.RebuildState state = callOnEdt(() -> subject.rebuild(
                new JMenu("Model"),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                menuData,
                selected -> {
                },
                true,
                "old-key"
        ));

        assertThat(actionCalls.get()).isEqualTo(1);
        assertThat(capturedMenuData.get()).isSameAs(menuData);
        assertThat(state.modelsMenuDirty()).isFalse();
        assertThat(state.lastMenuSelectedModelKey()).isNull();
    }

    @Test
    @DisplayName("Rebuild validates required arguments")
    void rebuild_whenArgumentMissing_throwsException() throws Exception {
        var subject = new ModelMenuStructureRebuildCoordinator((
                modelsMenu,
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                menuData,
                onModelSelected
        ) -> {
        });

        assertThatThrownBy(() -> callOnEdt(() -> subject.rebuild(
                new JMenu("Model"),
                null,
                new LinkedHashMap<>(),
                emptyMenuData(),
                selected -> {
                },
                true,
                "old-key"
        )))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelMenuItemsByKey");

        assertThatThrownBy(() -> new ModelMenuStructureRebuildCoordinator(
                (ModelMenuStructureRebuildCoordinator.RebuildAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebuildAction");
    }

    private ProviderMenuDataResolver.ProviderMenuData emptyMenuData() {
        return new ProviderMenuDataResolver.ProviderMenuData(
                emptyList(),
                emptyMap(),
                emptyMap(),
                emptyList()
        );
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }
}
