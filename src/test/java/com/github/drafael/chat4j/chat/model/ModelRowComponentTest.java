package com.github.drafael.chat4j.chat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRowComponentTest {

    @Test
    @DisplayName("Model row selects on mouse press so activation clicks are handled")
    void mousePressed_whenRowIsSelectable_selectsModel() throws Exception {
        var selectedProvider = new AtomicReference<String>();
        var selectedModel = new AtomicReference<String>();
        ModelRowComponent row = selectableRow(new ModelRowComponent.Listener() {
            @Override
            public void onSelect(String providerName, String modelId) {
                selectedProvider.set(providerName);
                selectedModel.set(modelId);
            }

            @Override
            public void onToggleFavorite(String providerName, String modelId) {
            }

            @Override
            public void onMouseEnter(String providerName, String modelId) {
            }
        });

        SwingUtilities.invokeAndWait(() -> fireMousePressed(row.panel()));

        assertThat(selectedProvider).hasValue("LM Studio");
        assertThat(selectedModel).hasValue("local-model");
    }

    @Test
    @DisplayName("Favorite star toggles only once on mouse press")
    void mousePressed_whenFavoriteStarPressed_togglesFavoriteOnce() throws Exception {
        var toggleCalls = new AtomicInteger();
        ModelRowComponent row = selectableRow(new ModelRowComponent.Listener() {
            @Override
            public void onSelect(String providerName, String modelId) {
            }

            @Override
            public void onToggleFavorite(String providerName, String modelId) {
                toggleCalls.incrementAndGet();
            }

            @Override
            public void onMouseEnter(String providerName, String modelId) {
            }
        });

        SwingUtilities.invokeAndWait(() -> fireMousePressed(favoriteLabel(row)));

        assertThat(toggleCalls).hasValue(1);
    }

    private static ModelRowComponent selectableRow(ModelRowComponent.Listener listener) {
        return new ModelRowComponent(
                "LM Studio",
                "local-model",
                "local-model",
                true,
                false,
                false,
                false,
                false,
                listener
        );
    }

    private static JLabel favoriteLabel(ModelRowComponent row) {
        for (Component component : row.panel().getComponents()) {
            if (component instanceof JPanel panel) {
                for (Component child : panel.getComponents()) {
                    if (child instanceof JLabel label && "Add to favorites".equals(label.getToolTipText())) {
                        return label;
                    }
                }
            }
        }
        throw new AssertionError("Favorite label not found");
    }

    private static void fireMousePressed(Component component) {
        MouseEvent event = new MouseEvent(
                component,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                1,
                false,
                MouseEvent.BUTTON1
        );
        for (var listener : component.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }
}
