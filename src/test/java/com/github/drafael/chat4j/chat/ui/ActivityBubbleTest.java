package com.github.drafael.chat4j.chat.ui;

import com.github.drafael.chat4j.chat.message.MessageBubble;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityBubbleTest {

    @Test
    @DisplayName("Failed tool cards use the semantic error title color")
    void setTitle_whenFailedStatus_usesErrorTitleColor() throws Exception {
        String key = "Component.error.focusedBorderColor";
        Object previous = callOnEdt(() -> UIManager.get(key));
        Color errorColor = new Color(210, 70, 70);
        try {
            Color actual = callOnEdt(() -> {
                UIManager.put(key, errorColor);
                ActivityBubble subject = new ActivityBubble();
                try {
                    subject.setTitle("✗ write file — denied");
                    return findComponents(subject, JLabel.class).stream()
                            .filter(label -> "✗ write file — denied".equals(label.getText()))
                            .findFirst()
                            .orElseThrow()
                            .getForeground();
                } finally {
                    subject.dispose();
                }
            });

            assertThat(actual).isEqualTo(errorColor);
        } finally {
            runOnEdt(() -> restoreUiDefault(key, previous));
        }
    }

    @Test
    @DisplayName("Streaming cards use the configured accent title color")
    void setStreaming_whenEnabled_usesAccentTitleColor() throws Exception {
        String key = "Component.accentColor";
        Object previous = callOnEdt(() -> UIManager.get(key));
        Color accent = new Color(80, 120, 240);
        try {
            Color actual = callOnEdt(() -> {
                UIManager.put(key, accent);
                ActivityBubble subject = new ActivityBubble();
                try {
                    subject.setStreaming(true);
                    return findComponents(subject, JLabel.class).stream()
                            .filter(label -> "Thinking".equals(label.getText()))
                            .findFirst()
                            .orElseThrow()
                            .getForeground();
                } finally {
                    subject.dispose();
                }
            });

            assertThat(actual).isEqualTo(accent);
        } finally {
            runOnEdt(() -> restoreUiDefault(key, previous));
        }
    }

    @Test
    @DisplayName("Activity actions expose keyboard focus and current collapse state")
    void constructor_whenActionsAreCreated_supportsKeyboardNavigation() throws Exception {
        callOnEdt(() -> {
            ActivityBubble subject = new ActivityBubble();
            try {
                List<JButton> buttons = findComponents(subject, JButton.class);
                JButton foldButton = buttons.stream()
                        .filter(button -> "Collapse thinking".equals(
                                button.getAccessibleContext().getAccessibleName()
                        ))
                        .findFirst()
                        .orElseThrow();
                JButton copyButton = buttons.stream()
                        .filter(button -> "Copy thinking".equals(
                                button.getAccessibleContext().getAccessibleName()
                        ))
                        .findFirst()
                        .orElseThrow();

                assertThat(foldButton.isFocusable()).isTrue();
                assertThat(copyButton.isFocusable()).isTrue();
                subject.setCollapsed(true);
                assertThat(foldButton.getAccessibleContext().getAccessibleName()).isEqualTo("Expand thinking");
                subject.setCollapsible(false);
                subject.dispatchEvent(new MouseEvent(
                        subject,
                        MouseEvent.MOUSE_EXITED,
                        System.currentTimeMillis(),
                        0,
                        0,
                        0,
                        0,
                        false
                ));
                assertThat(copyButton.isVisible()).isTrue();
                return null;
            } finally {
                subject.dispose();
            }
        });
    }

    @Test
    @DisplayName("Disposal releases the embedded message renderer")
    void dispose_whenCalled_disposesRenderedMessageView() throws Exception {
        boolean[] disposed = callOnEdt(() -> {
            ActivityBubble subject = new ActivityBubble();
            try {
                subject.setText("thinking");
                MessageBubble renderedBubble = findComponents(subject, MessageBubble.class).getFirst();
                subject.dispose();
                return new boolean[]{subject.isDisposed(), renderedBubble.isDisposed()};
            } finally {
                subject.dispose();
            }
        });

        assertThat(disposed).containsExactly(true, true);
    }

    private static <T extends Component> List<T> findComponents(Container root, Class<T> componentType) {
        List<T> matches = new ArrayList<>();
        collectComponents(root, componentType, matches);
        return matches;
    }

    private static <T extends Component> void collectComponents(
            Container root,
            Class<T> componentType,
            List<T> matches
    ) {
        for (Component component : root.getComponents()) {
            if (componentType.isInstance(component)) {
                matches.add(componentType.cast(component));
            }
            if (component instanceof Container child) {
                collectComponents(child, componentType, matches);
            }
        }
    }

    private static void restoreUiDefault(String key, Object value) {
        if (value == null) {
            UIManager.getDefaults().remove(key);
        } else {
            UIManager.put(key, value);
        }
    }

    private static void runOnEdt(ThrowingAction action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
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

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
