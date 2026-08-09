package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.ButtonGroup;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingUtilities;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ModelMenuSelectionSynchronizerTest {

    private final ModelMenuSelectionSynchronizer subject = new ModelMenuSelectionSynchronizer();

    @Test
    @DisplayName("Sync selection selects current item and deselects previous item")
    void syncSelection_whenSelectionChanges_updatesMenuItemsAndReturnsCurrentKey() throws Exception {
        SelectionResult result = callOnEdt(() -> {
            var previousItem = new JRadioButtonMenuItem("gpt-4o", true);
            var currentItem = new JRadioButtonMenuItem("gpt-4.1");
            var group = new ButtonGroup();
            group.add(previousItem);
            group.add(currentItem);
            Map<String, JRadioButtonMenuItem> itemsByKey = new LinkedHashMap<>();
            itemsByKey.put("OpenAI > gpt-4o", previousItem);
            itemsByKey.put("OpenAI > gpt-4.1", currentItem);

            String selectedKey = subject.syncSelection(
                    itemsByKey,
                    "OpenAI > gpt-4.1",
                    "OpenAI > gpt-4o",
                    false
            );
            return new SelectionResult(selectedKey, previousItem.isSelected(), currentItem.isSelected());
        });

        assertThat(result.previousSelected()).isFalse();
        assertThat(result.currentSelected()).isTrue();
        assertThat(result.selectedKey()).isEqualTo("OpenAI > gpt-4.1");
    }

    @Test
    @DisplayName("Sync selection keeps previous key unchanged when menu is dirty")
    void syncSelection_whenMenuIsDirty_returnsPreviousKeyWithoutMutatingSelection() throws Exception {
        SelectionResult result = callOnEdt(() -> {
            var selectedItem = new JRadioButtonMenuItem("gpt-4o", true);
            Map<String, JRadioButtonMenuItem> itemsByKey = Map.of("OpenAI > gpt-4o", selectedItem);

            String selectedKey = subject.syncSelection(
                    itemsByKey,
                    "OpenAI > gpt-4.1",
                    "OpenAI > gpt-4o",
                    true
            );
            return new SelectionResult(selectedKey, selectedItem.isSelected(), false);
        });

        assertThat(result.previousSelected()).isTrue();
        assertThat(result.selectedKey()).isEqualTo("OpenAI > gpt-4o");
    }

    @Test
    @DisplayName("Sync selection repairs radio state when the tracked key did not change")
    void syncSelection_whenTrackedSelectionUnchanged_restoresCurrentRadioItem() throws Exception {
        SelectionResult result = callOnEdt(() -> {
            var selectedItem = new JRadioButtonMenuItem("gpt-4.1");
            var rejectedItem = new JRadioButtonMenuItem("claude-sonnet", true);
            var group = new ButtonGroup();
            group.add(selectedItem);
            group.add(rejectedItem);
            Map<String, JRadioButtonMenuItem> itemsByKey = new LinkedHashMap<>();
            itemsByKey.put("OpenAI > gpt-4.1", selectedItem);
            itemsByKey.put("Anthropic > claude-sonnet", rejectedItem);

            String selectedKey = subject.syncSelection(
                    itemsByKey,
                    "OpenAI > gpt-4.1",
                    "OpenAI > gpt-4.1",
                    false
            );
            return new SelectionResult(selectedKey, rejectedItem.isSelected(), selectedItem.isSelected());
        });

        assertThat(result.previousSelected()).isFalse();
        assertThat(result.currentSelected()).isTrue();
        assertThat(result.selectedKey()).isEqualTo("OpenAI > gpt-4.1");
    }

    @Test
    @DisplayName("Sync selection clears grouped radio state when no model is selected")
    void syncSelection_whenSelectedModelMissing_clearsButtonGroupSelection() throws Exception {
        SelectionResult result = callOnEdt(() -> {
            var rejectedItem = new JRadioButtonMenuItem("claude-sonnet", true);
            var otherItem = new JRadioButtonMenuItem("gpt-4.1");
            var group = new ButtonGroup();
            group.add(rejectedItem);
            group.add(otherItem);
            Map<String, JRadioButtonMenuItem> itemsByKey = new LinkedHashMap<>();
            itemsByKey.put("Anthropic > claude-sonnet", rejectedItem);
            itemsByKey.put("OpenAI > gpt-4.1", otherItem);

            String selectedKey = subject.syncSelection(itemsByKey, null, null, false);
            return new SelectionResult(selectedKey, rejectedItem.isSelected(), otherItem.isSelected());
        });

        assertThat(result.previousSelected()).isFalse();
        assertThat(result.currentSelected()).isFalse();
        assertThat(result.selectedKey()).isNull();
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

    private record SelectionResult(String selectedKey, boolean previousSelected, boolean currentSelected) {
    }
}
