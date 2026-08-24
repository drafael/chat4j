package com.github.drafael.chat4j.chat.composer;

import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputBarFocusHandoffTest {

    @Test
    @DisplayName("Programmatic focus requests and composer clicks release native transcript focus")
    void requestInputFocus_whenProgrammaticOrClicked_runsConfiguredFocusHandoff() throws Exception {
        InputBar subject = callOnEdt(InputBar::new);
        AtomicInteger handoffs = new AtomicInteger();
        try {
            JTextArea textArea = callOnEdt(() -> textArea(subject));
            runOnEdt(() -> subject.setNativeFocusRelease(handoffs::incrementAndGet));

            runOnEdt(subject::requestInputFocus);
            runOnEdt(() -> textArea.dispatchEvent(new MouseEvent(
                    textArea,
                    MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(),
                    0,
                    4,
                    4,
                    1,
                    false
            )));
            runOnEdt(() -> {});

            assertThat(handoffs).hasValue(2);
        } finally {
            runOnEdt(subject::beginShutdown);
            runOnEdt(() -> {});
        }
    }

    private JTextArea textArea(InputBar subject) throws Exception {
        Field field = InputBar.class.getDeclaredField("textArea");
        field.setAccessible(true);
        return (JTextArea) field.get(subject);
    }

    private void runOnEdt(ThrowingAction action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
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

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
