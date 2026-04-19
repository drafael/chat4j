package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelMenuSelectionDispatchCoordinatorTest {

    @Test
    @DisplayName("Sync delegates to selection action and returns synced key")
    void sync_whenCalled_delegatesAndReturnsSyncedKey() {
        var capturedSelectedKey = new AtomicReference<String>();
        var capturedLastSelectedKey = new AtomicReference<String>();

        var subject = new ModelMenuSelectionDispatchCoordinator((
                modelMenuItemsByKey,
                selectedModelKey,
                lastMenuSelectedModelKey,
                modelsMenuDirty
        ) -> {
            capturedSelectedKey.set(selectedModelKey);
            capturedLastSelectedKey.set(lastMenuSelectedModelKey);
            return "OpenAI > gpt-4.1";
        });

        Map<String, JRadioButtonMenuItem> modelMenuItemsByKey = new LinkedHashMap<>();

        String syncedKey = subject.sync(
                modelMenuItemsByKey,
                "Anthropic > claude-3.7-sonnet",
                "OpenAI > gpt-4o",
                false
        );

        assertThat(capturedSelectedKey.get()).isEqualTo("Anthropic > claude-3.7-sonnet");
        assertThat(capturedLastSelectedKey.get()).isEqualTo("OpenAI > gpt-4o");
        assertThat(syncedKey).isEqualTo("OpenAI > gpt-4.1");
    }

    @Test
    @DisplayName("Sync validates required arguments and constructor dependency")
    void sync_whenArgumentMissing_throwsException() {
        var subject = new ModelMenuSelectionDispatchCoordinator((
                modelMenuItemsByKey,
                selectedModelKey,
                lastMenuSelectedModelKey,
                modelsMenuDirty
        ) -> selectedModelKey);

        assertThatThrownBy(() -> subject.sync(null, "m", "l", false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelMenuItemsByKey must not be null");

        assertThatThrownBy(() -> new ModelMenuSelectionDispatchCoordinator(
                (ModelMenuSelectionDispatchCoordinator.SelectionSyncAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("selectionSyncAction must not be null");
    }
}
