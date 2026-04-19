package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelMenuSelectionSynchronizerTest {

    private final ModelMenuSelectionSynchronizer subject = new ModelMenuSelectionSynchronizer();

    @Test
    @DisplayName("Sync selection selects current item and deselects previous item")
    void syncSelection_whenSelectionChanges_updatesMenuItemsAndReturnsCurrentKey() {
        var previousItem = new JRadioButtonMenuItem("gpt-4o");
        previousItem.setSelected(true);
        var currentItem = new JRadioButtonMenuItem("gpt-4.1");
        Map<String, JRadioButtonMenuItem> itemsByKey = new LinkedHashMap<>();
        itemsByKey.put("OpenAI > gpt-4o", previousItem);
        itemsByKey.put("OpenAI > gpt-4.1", currentItem);

        String last = subject.syncSelection(
                itemsByKey,
                "OpenAI > gpt-4.1",
                "OpenAI > gpt-4o",
                false
        );

        assertThat(previousItem.isSelected()).isFalse();
        assertThat(currentItem.isSelected()).isTrue();
        assertThat(last).isEqualTo("OpenAI > gpt-4.1");
    }

    @Test
    @DisplayName("Sync selection keeps previous key unchanged when menu is dirty")
    void syncSelection_whenMenuIsDirty_returnsPreviousKeyWithoutMutatingSelection() {
        var selectedItem = new JRadioButtonMenuItem("gpt-4o");
        selectedItem.setSelected(true);
        Map<String, JRadioButtonMenuItem> itemsByKey = Map.of("OpenAI > gpt-4o", selectedItem);

        String last = subject.syncSelection(
                itemsByKey,
                "OpenAI > gpt-4.1",
                "OpenAI > gpt-4o",
                true
        );

        assertThat(selectedItem.isSelected()).isTrue();
        assertThat(last).isEqualTo("OpenAI > gpt-4o");
    }

    @Test
    @DisplayName("Sync selection keeps current key when selection did not change")
    void syncSelection_whenSelectionIsUnchanged_returnsExistingKey() {
        var selectedItem = new JRadioButtonMenuItem("gpt-4.1");
        selectedItem.setSelected(true);
        Map<String, JRadioButtonMenuItem> itemsByKey = Map.of("OpenAI > gpt-4.1", selectedItem);

        String last = subject.syncSelection(
                itemsByKey,
                "OpenAI > gpt-4.1",
                "OpenAI > gpt-4.1",
                false
        );

        assertThat(selectedItem.isSelected()).isTrue();
        assertThat(last).isEqualTo("OpenAI > gpt-4.1");
    }
}
