package com.github.drafael.chat4j.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.nCopies;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ModalDialogSupportTest {

    @Test
    @DisplayName("Adaptive size grows width to the owner-relative minimum")
    void adaptiveDialogSize_whenPackedWidthIsSmall_growsToMinimumWidth() {
        Dimension result = ModalDialogSupport.adaptiveDialogSize(
                new Dimension(100, 120),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result).isEqualTo(new Dimension(300, 120));
    }

    @Test
    @DisplayName("Adaptive size preserves a natural width within owner-relative bounds")
    void adaptiveDialogSize_whenPackedWidthIsWithinBounds_preservesWidth() {
        Dimension result = ModalDialogSupport.adaptiveDialogSize(
                new Dimension(500, 120),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result).isEqualTo(new Dimension(500, 120));
    }

    @Test
    @DisplayName("Adaptive size caps width to the owner-relative maximum")
    void adaptiveDialogSize_whenPackedWidthIsLarge_capsToMaximumWidth() {
        Dimension result = ModalDialogSupport.adaptiveDialogSize(
                new Dimension(900, 120),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result).isEqualTo(new Dimension(750, 120));
    }

    @Test
    @DisplayName("Adaptive size preserves the natural packed height")
    void adaptiveDialogSize_whenPackedHeightIsLarge_preservesNaturalHeight() {
        Dimension result = ModalDialogSupport.adaptiveDialogSize(
                new Dimension(500, 600),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result).isEqualTo(new Dimension(500, 600));
    }

    @Test
    @DisplayName("Adaptive size uses the actual dimensions of a small owner")
    void adaptiveDialogSize_whenOwnerBoundsAreSmall_usesActualDimensions() {
        Dimension result = ModalDialogSupport.adaptiveDialogSize(
                new Dimension(20, 20),
                new Rectangle(0, 0, 200, 100)
        );

        assertThat(result).isEqualTo(new Dimension(60, 20));
    }

    @Test
    @DisplayName("Adaptive size uses fallback dimensions for unusable owner bounds")
    void adaptiveDialogSize_whenOwnerBoundsAreUnusable_usesFallbackDimensions() {
        Dimension result = ModalDialogSupport.adaptiveDialogSize(
                new Dimension(20, 20),
                new Rectangle(0, 0, 0, 0)
        );

        assertThat(result).isEqualTo(new Dimension(192, 20));
    }

    @Test
    @DisplayName("Adaptive dialogs preserve a larger minimum content size")
    void readableAdaptiveSize_whenContentHasLargerMinimum_preservesMinimumSize() {
        Dimension result = ModalDialogSupport.readableAdaptiveSize(
                new Dimension(900, 600),
                new Dimension(800, 450),
                new Rectangle(0, 0, 1000, 800)
        );

        assertThat(result).isEqualTo(new Dimension(800, 600));
    }

    @Test
    @DisplayName("A missing parent produces an ownerless modal")
    void dialogOwner_whenParentIsNull_returnsNull() {
        assertThat(ModalDialogSupport.dialogOwner(null)).isNull();
    }

    @Test
    @DisplayName("Short plain messages remain non-wrapping without a scroll pane")
    void adaptiveMessage_whenShortPlainTextFits_returnsNonWrappingTextArea() throws Exception {
        MessageSnapshot snapshot = callOnEdt(() -> messageSnapshot(
                ModalDialogSupport.adaptiveMessage(
                        "Delete all chats?",
                        new Rectangle(0, 0, 1000, 800)
                )
        ));

        assertThat(snapshot.containerType()).isEqualTo(JTextArea.class);
        assertThat(snapshot.text()).isEqualTo("Delete all chats?");
        assertThat(snapshot.lineWrap()).isFalse();
        assertThat(snapshot.wrapStyleWord()).isFalse();
        assertThat(snapshot.editable()).isFalse();
        assertThat(snapshot.lineCount()).isOne();
    }

    @Test
    @DisplayName("A multilingual delete confirmation remains on one line when space is available")
    void adaptiveMessage_whenMultilingualMessageFits_returnsSingleLineTextArea() throws Exception {
        String message = "Видалити \"Як правильно налаштувати приватний доступ до сервера\"?";

        MessageSnapshot snapshot = callOnEdt(() -> messageSnapshot(
                ModalDialogSupport.adaptiveMessage(
                        message,
                        new Rectangle(0, 0, 1600, 900)
                )
        ));

        assertThat(snapshot.containerType()).isEqualTo(JTextArea.class);
        assertThat(snapshot.text()).isEqualTo(message);
        assertThat(snapshot.lineWrap()).isFalse();
        assertThat(snapshot.lineCount()).isOne();
    }

    @Test
    @DisplayName("Explicit plain-text line breaks remain intact")
    void adaptiveMessage_whenTextContainsExplicitLineBreak_preservesLines() throws Exception {
        MessageSnapshot snapshot = callOnEdt(() -> messageSnapshot(
                ModalDialogSupport.adaptiveMessage(
                        "First line\nSecond line",
                        new Rectangle(0, 0, 1000, 800)
                )
        ));

        assertThat(snapshot.containerType()).isEqualTo(JTextArea.class);
        assertThat(snapshot.text()).isEqualTo("First line\nSecond line");
        assertThat(snapshot.lineWrap()).isFalse();
        assertThat(snapshot.lineCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Tall plain messages scroll without wrapping explicit short lines")
    void adaptiveMessage_whenPlainTextHasManyShortLines_scrollsWithoutWrapping() throws Exception {
        String message = String.join("\n", nCopies(50, "Short line"));

        MessageSnapshot snapshot = callOnEdt(() -> messageSnapshot(
                ModalDialogSupport.adaptiveMessage(
                        message,
                        new Rectangle(0, 0, 1000, 800)
                )
        ));

        assertThat(snapshot.containerType()).isEqualTo(JScrollPane.class);
        assertThat(snapshot.text()).isEqualTo(message);
        assertThat(snapshot.lineWrap()).isFalse();
        assertThat(snapshot.wrapStyleWord()).isFalse();
        assertThat(snapshot.lineCount()).isEqualTo(50);
        assertThat(snapshot.preferredSize().height).isLessThanOrEqualTo(280);
        assertThat(snapshot.messagePreferredWidth()).isLessThanOrEqualTo(snapshot.viewportWidth());
    }

    @Test
    @DisplayName("Long plain messages wrap within a bounded transparent viewport")
    void adaptiveMessage_whenPlainTextIsLong_wrapsWithinBoundedViewport() throws Exception {
        MessageSnapshot snapshot = callOnEdt(() -> messageSnapshot(
                ModalDialogSupport.adaptiveMessage(
                        "A long message ".repeat(100),
                        new Rectangle(0, 0, 1000, 800)
                )
        ));

        assertThat(snapshot.containerType()).isEqualTo(JScrollPane.class);
        assertThat(snapshot.text()).isEqualTo("A long message ".repeat(100));
        assertThat(snapshot.lineWrap()).isTrue();
        assertThat(snapshot.wrapStyleWord()).isTrue();
        assertThat(snapshot.editable()).isFalse();
        assertThat(snapshot.preferredSize().width).isEqualTo(590);
        assertThat(snapshot.preferredSize().height).isLessThanOrEqualTo(280);
    }

    @Test
    @DisplayName("HTML messages remain unchanged")
    void adaptiveMessage_whenMessageIsHtml_returnsOriginalMessage() throws Exception {
        String message = "<html>First line<br>Second line</html>";

        Object result = callOnEdt(() -> ModalDialogSupport.adaptiveMessage(
                message,
                new Rectangle(0, 0, 1000, 800)
        ));

        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("Component messages remain unchanged")
    void adaptiveMessage_whenMessageIsComponent_returnsOriginalComponent() throws Exception {
        JPanel message = callOnEdt(JPanel::new);

        Object result = callOnEdt(() -> ModalDialogSupport.adaptiveMessage(
                message,
                new Rectangle(0, 0, 1000, 800)
        ));

        assertThat(result).isSameAs(message);
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

    private MessageSnapshot messageSnapshot(Object message) {
        if (message instanceof JScrollPane scrollPane) {
            var textArea = (JTextArea) scrollPane.getViewport().getView();
            scrollPane.setSize(scrollPane.getPreferredSize());
            scrollPane.doLayout();
            return new MessageSnapshot(
                    JScrollPane.class,
                    textArea.getText(),
                    textArea.getLineWrap(),
                    textArea.getWrapStyleWord(),
                    textArea.isEditable(),
                    textArea.getLineCount(),
                    scrollPane.getPreferredSize(),
                    textArea.getPreferredSize().width,
                    scrollPane.getViewport().getExtentSize().width
            );
        }

        var textArea = (JTextArea) message;
        int preferredWidth = textArea.getPreferredSize().width;
        return new MessageSnapshot(
                JTextArea.class,
                textArea.getText(),
                textArea.getLineWrap(),
                textArea.getWrapStyleWord(),
                textArea.isEditable(),
                textArea.getLineCount(),
                textArea.getPreferredSize(),
                preferredWidth,
                preferredWidth
        );
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
            Class<?> containerType,
            String text,
            boolean lineWrap,
            boolean wrapStyleWord,
            boolean editable,
            int lineCount,
            Dimension preferredSize,
            int messagePreferredWidth,
            int viewportWidth
    ) {
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
