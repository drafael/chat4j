package com.github.drafael.chat4j.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ModalDialogSupportTest {

    @Test
    @DisplayName("Compact size grows width to owner-relative minimum")
    void compactSize_whenPackedWidthIsSmall_growsToMinimumWidth() {
        Dimension result = ModalDialogSupport.compactSize(
                new Dimension(100, 120),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result.width).isEqualTo(200);
    }

    @Test
    @DisplayName("Compact size caps width to owner-relative maximum")
    void compactSize_whenPackedWidthIsLarge_capsToMaximumWidth() {
        Dimension result = ModalDialogSupport.compactSize(
                new Dimension(500, 120),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result.width).isEqualTo(300);
    }

    @Test
    @DisplayName("Compact size applies soft minimum height")
    void compactSize_whenPackedHeightIsSmall_appliesSoftMinimumHeight() {
        Dimension result = ModalDialogSupport.compactSize(
                new Dimension(240, 80),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result.height).isEqualTo(140);
    }

    @Test
    @DisplayName("Compact size caps height to owner-relative maximum")
    void compactSize_whenPackedHeightIsLarge_capsToMaximumHeight() {
        Dimension result = ModalDialogSupport.compactSize(
                new Dimension(240, 500),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result.height).isEqualTo(240);
    }

    @Test
    @DisplayName("Compact size uses sane fallback dimensions for tiny owner bounds")
    void compactSize_whenOwnerBoundsAreTiny_usesFallbackDimensions() {
        Dimension result = ModalDialogSupport.compactSize(
                new Dimension(20, 20),
                new Rectangle(0, 0, 20, 20)
        );

        assertThat(result.width).isEqualTo(128);
        assertThat(result.height).isEqualTo(140);
    }

    @Test
    @DisplayName("Compact dialogs preserve the minimum content size")
    void readableCompactSize_whenContentHasLargerMinimum_preservesMinimumSize() {
        Dimension result = ModalDialogSupport.readableCompactSize(
                new Dimension(547, 278),
                new Dimension(547, 278),
                new Rectangle(0, 0, 600, 600)
        );

        assertThat(result).isEqualTo(new Dimension(547, 278));
    }

    @Test
    @DisplayName("A missing parent produces an ownerless modal")
    void dialogOwner_whenParentIsNull_returnsNull() {
        assertThat(ModalDialogSupport.dialogOwner(null)).isNull();
    }

    @Test
    @DisplayName("Plain messages wrap within a bounded transparent viewport")
    void compactMessage_whenPlainText_wrapsWithinBoundedViewport() throws Exception {
        MessageSnapshot snapshot = callOnEdt(() -> {
            var scrollPane = (JScrollPane) ModalDialogSupport.compactMessage("A long message ".repeat(40));
            var textArea = (JTextArea) scrollPane.getViewport().getView();
            return new MessageSnapshot(
                    textArea.getText(),
                    textArea.getLineWrap(),
                    textArea.getWrapStyleWord(),
                    textArea.isEditable(),
                    scrollPane.getPreferredSize().height
            );
        });

        assertThat(snapshot.text()).isEqualTo("A long message ".repeat(40));
        assertThat(snapshot.lineWrap()).isTrue();
        assertThat(snapshot.wrapStyleWord()).isTrue();
        assertThat(snapshot.editable()).isFalse();
        assertThat(snapshot.preferredHeight()).isLessThanOrEqualTo(140);
    }

    @Test
    @DisplayName("Option pane dialogs are undecorated and have no title")
    void configureTitlelessOptionPaneDialog_whenConfigured_isUndecoratedWithoutTitle() throws Exception {
        JDialog dialog = callOnEdt(() -> mock(JDialog.class));
        JOptionPane optionPane = callOnEdt(() -> new JOptionPane("Message"));

        runOnEdt(() -> ModalDialogSupport.configureTitlelessOptionPaneDialog(dialog, optionPane));

        verify(dialog).setUndecorated(true);
        verify(dialog).setContentPane(optionPane);
        verify(dialog, never()).setTitle(anyString());
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

    private record MessageSnapshot(
            String text,
            boolean lineWrap,
            boolean wrapStyleWord,
            boolean editable,
            int preferredHeight
    ) {
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
