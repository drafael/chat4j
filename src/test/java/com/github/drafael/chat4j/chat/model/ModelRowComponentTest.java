package com.github.drafael.chat4j.chat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRowComponentTest {

    @Test
    @DisplayName("Model row selects on mouse press so activation clicks are handled")
    void mousePressed_whenRowIsSelectable_selectsModel() throws Exception {
        var selectedProvider = new AtomicReference<String>();
        var selectedModel = new AtomicReference<String>();
        ModelRowComponent row = callOnEdt(() -> selectableRow(new ModelRowComponent.Listener() {
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
        }));

        SwingUtilities.invokeAndWait(() -> fireMousePressed(row.panel()));

        assertThat(selectedProvider).hasValue("LM Studio");
        assertThat(selectedModel).hasValue("local-model");
    }

    @Test
    @DisplayName("Non-primary mouse presses do not select models or toggle favorites")
    void mousePressed_whenButtonIsNotPrimary_ignoresActivation() throws Exception {
        var selectCalls = new AtomicInteger();
        var toggleCalls = new AtomicInteger();
        ModelRowComponent row = callOnEdt(() -> selectableRow(new ModelRowComponent.Listener() {
            @Override
            public void onSelect(String providerName, String modelId) {
                selectCalls.incrementAndGet();
            }

            @Override
            public void onToggleFavorite(String providerName, String modelId) {
                toggleCalls.incrementAndGet();
            }

            @Override
            public void onMouseEnter(String providerName, String modelId) {
            }
        }));

        SwingUtilities.invokeAndWait(() -> {
            fireMousePressed(row.panel(), MouseEvent.BUTTON3);
            fireMousePressed(favoriteLabel(row), MouseEvent.BUTTON3);
        });

        assertThat(selectCalls).hasValue(0);
        assertThat(toggleCalls).hasValue(0);
    }

    @Test
    @DisplayName("Disabled favorite stars keep the unavailable tooltip after state refreshes")
    void updateFavoriteState_whenRowIsNotSelectable_keepsUnavailableTooltip() throws Exception {
        ModelRowComponent row = callOnEdt(() -> new ModelRowComponent(
                "LM Studio",
                "local-model",
                false,
                false,
                false,
                false,
                false,
                noOpListener()
        ));

        String tooltip = callOnEdt(() -> {
            row.updateFavoriteState(true);
            return disabledFavoriteLabel(row).getToolTipText();
        });

        assertThat(tooltip).isEqualTo("LM Studio server is unavailable");
    }

    @Test
    @DisplayName("Favorite star toggles only once on mouse press")
    void mousePressed_whenFavoriteStarPressed_togglesFavoriteOnce() throws Exception {
        var toggleCalls = new AtomicInteger();
        ModelRowComponent row = callOnEdt(() -> selectableRow(new ModelRowComponent.Listener() {
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
        }));

        SwingUtilities.invokeAndWait(() -> fireMousePressed(favoriteLabel(row)));

        assertThat(toggleCalls).hasValue(1);
    }

    private static ModelRowComponent.Listener noOpListener() {
        return new ModelRowComponent.Listener() {
            @Override
            public void onSelect(String providerName, String modelId) {
            }

            @Override
            public void onToggleFavorite(String providerName, String modelId) {
            }

            @Override
            public void onMouseEnter(String providerName, String modelId) {
            }
        };
    }

    private static ModelRowComponent selectableRow(ModelRowComponent.Listener listener) {
        return new ModelRowComponent(
                "LM Studio",
                "local-model",
                true,
                false,
                false,
                false,
                false,
                listener
        );
    }

    private static JLabel disabledFavoriteLabel(ModelRowComponent row) {
        for (Component component : row.panel().getComponents()) {
            if (component instanceof JPanel panel) {
                for (Component child : panel.getComponents()) {
                    if (child instanceof JLabel label && !label.isEnabled()) {
                        return label;
                    }
                }
            }
        }
        throw new AssertionError("Disabled favorite label not found");
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

    private static <T> T callOnEdt(Callable<T> action) throws Exception {
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

    private static void fireMousePressed(Component component) {
        fireMousePressed(component, MouseEvent.BUTTON1);
    }

    private static void fireMousePressed(Component component, int button) {
        MouseEvent event = new MouseEvent(
                component,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                1,
                false,
                button
        );
        for (var listener : component.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }
}
