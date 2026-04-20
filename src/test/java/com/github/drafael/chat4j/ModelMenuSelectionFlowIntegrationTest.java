package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ModelMenuSelectionFlowIntegrationTest {

    @Test
    @DisplayName("Selected-model change flow syncs menu checks and updates tracked selection")
    void onSelectedModelChanged_whenMenuReady_syncsSelectionAndUpdatesLastTrackedValue() {
        var previousModelItem = new JRadioButtonMenuItem("OpenAI > gpt-4.1", true);
        var currentModelItem = new JRadioButtonMenuItem("Anthropic > claude-sonnet");
        Map<String, JRadioButtonMenuItem> modelMenuItemsByKey = new LinkedHashMap<>();
        modelMenuItemsByKey.put("OpenAI > gpt-4.1", previousModelItem);
        modelMenuItemsByKey.put("Anthropic > claude-sonnet", currentModelItem);

        var dispatchCoordinator = new ModelMenuSelectionDispatchCoordinator(new ModelMenuSelectionSynchronizer());
        var applyCoordinator = new ModelMenuSelectionApplyCoordinator();
        var changeCoordinator = new ModelMenuSelectionChangeCoordinator();

        var lastSelectedModelKey = new AtomicReference<>("OpenAI > gpt-4.1");
        String selectedModelKey = "Anthropic > claude-sonnet";

        boolean synced = changeCoordinator.onSelectedModelChanged(
                new JMenu("Models"),
                false,
                () -> {
                    String syncedSelection = dispatchCoordinator.sync(
                            modelMenuItemsByKey,
                            selectedModelKey,
                            lastSelectedModelKey.get(),
                            false
                    );
                    applyCoordinator.apply(syncedSelection, lastSelectedModelKey::set);
                }
        );

        assertThat(synced).isTrue();
        assertThat(lastSelectedModelKey.get()).isEqualTo("Anthropic > claude-sonnet");
        assertThat(previousModelItem.isSelected()).isFalse();
        assertThat(currentModelItem.isSelected()).isTrue();
    }
}
