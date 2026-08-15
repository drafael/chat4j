package com.github.drafael.chat4j.util;

import lombok.NonNull;
import org.apache.commons.lang3.Strings;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.round;

public final class ModalDialogSupport {

    private static final double MIN_WIDTH_RATIO = 0.30;
    private static final double MAX_WIDTH_RATIO = 0.75;
    private static final double MAX_HEIGHT_RATIO = 0.50;
    private static final int FALLBACK_OWNER_WIDTH = 640;
    private static final int FALLBACK_OWNER_HEIGHT = 480;
    private static final int OPTION_PANE_HORIZONTAL_RESERVE = 160;
    private static final int OPTION_PANE_VERTICAL_RESERVE = 120;

    private ModalDialogSupport() {
    }

    public static void showMessageDialog(Component parent, Object message, int messageType) {
        callOnEventDispatchThread(() -> showOptionPaneOnEventDispatchThread(
                parent,
                new JOptionPane(message, messageType, JOptionPane.DEFAULT_OPTION)
        ));
    }

    public static int showConfirmDialog(Component parent, Object message, int optionType, int messageType) {
        Object value = callOnEventDispatchThread(() -> showOptionPaneOnEventDispatchThread(
                parent,
                new JOptionPane(message, messageType, optionType)
        ));
        return value instanceof Integer selectedValue ? selectedValue : JOptionPane.CLOSED_OPTION;
    }

    public static Object showOptionPane(Component parent, @NonNull JOptionPane optionPane) {
        return callOnEventDispatchThread(() -> showOptionPaneOnEventDispatchThread(parent, optionPane));
    }

    static Dimension readableAdaptiveSize(
            Dimension packedSize,
            Dimension minimumContentSize,
            Rectangle ownerBounds
    ) {
        Dimension adaptiveSize = adaptiveDialogSize(packedSize, ownerBounds);
        Dimension safeMinimumContentSize = minimumContentSize == null ? new Dimension(0, 0) : minimumContentSize;
        return new Dimension(
                max(adaptiveSize.width, safeMinimumContentSize.width),
                max(adaptiveSize.height, safeMinimumContentSize.height)
        );
    }

    static Dimension adaptiveDialogSize(Dimension packedSize, Rectangle ownerBounds) {
        Dimension safePackedSize = packedSize == null ? new Dimension(0, 0) : packedSize;
        Rectangle safeOwnerBounds = usableOwnerBounds(ownerBounds);

        int minWidth = max(1, (int) round(safeOwnerBounds.width * MIN_WIDTH_RATIO));
        int maxWidth = max(minWidth, (int) round(safeOwnerBounds.width * MAX_WIDTH_RATIO));

        int targetWidth = clamp(safePackedSize.width, minWidth, maxWidth);
        int targetHeight = max(safePackedSize.height, 1);
        return new Dimension(targetWidth, targetHeight);
    }

    static Object adaptiveMessage(Object message, Rectangle ownerBounds) {
        if (!(message instanceof String text) || Strings.CI.startsWith(text, "<html>")) {
            return message;
        }

        Rectangle safeOwnerBounds = usableOwnerBounds(ownerBounds);
        int maxDialogWidth = max(1, (int) round(safeOwnerBounds.width * MAX_WIDTH_RATIO));
        int maxDialogHeight = max(1, (int) round(safeOwnerBounds.height * MAX_HEIGHT_RATIO));
        int maxMessageWidth = max(1, maxDialogWidth - OPTION_PANE_HORIZONTAL_RESERVE);
        int maxMessageHeight = max(1, maxDialogHeight - OPTION_PANE_VERTICAL_RESERVE);

        JTextArea textArea = createMessageTextArea(text);
        Dimension naturalSize = textArea.getPreferredSize();
        boolean exceedsWidth = naturalSize.width > maxMessageWidth;
        boolean exceedsHeight = naturalSize.height > maxMessageHeight;
        if (!exceedsWidth && !exceedsHeight) {
            return textArea;
        }

        var scrollPane = new JScrollPane(
                textArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        );
        int verticalScrollBarWidth = exceedsHeight
                ? scrollPane.getVerticalScrollBar().getPreferredSize().width
                : 0;
        int maxViewportWidth = max(1, maxMessageWidth - verticalScrollBarWidth);
        boolean requiresWrapping = naturalSize.width > maxViewportWidth;
        int preferredWidth = requiresWrapping
                ? maxMessageWidth
                : min(maxMessageWidth, naturalSize.width + verticalScrollBarWidth);
        if (requiresWrapping) {
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setSize(new Dimension(maxViewportWidth, Short.MAX_VALUE));
        }
        int preferredHeight = min(textArea.getPreferredSize().height, maxMessageHeight);

        scrollPane.setPreferredSize(new Dimension(preferredWidth, preferredHeight));
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setFocusable(false);
        return scrollPane;
    }

    public static void configureTitlelessDialog(@NonNull JDialog dialog) {
        dialog.setUndecorated(true);
    }

    static void configureTitlelessOptionPaneDialog(JDialog dialog, JOptionPane optionPane) {
        configureTitlelessDialog(dialog);
        dialog.setContentPane(optionPane);
        optionPane.addPropertyChangeListener(event -> {
            if (dialog.isVisible()
                    && event.getSource() == optionPane
                    && JOptionPane.VALUE_PROPERTY.equals(event.getPropertyName())) {
                dialog.setVisible(false);
            }
        });
    }

    static Window dialogOwner(Component parent) {
        if (parent instanceof Window window) {
            return window;
        }
        return parent == null ? null : SwingUtilities.getWindowAncestor(parent);
    }

    private static JTextArea createMessageTextArea(String text) {
        var textArea = new JTextArea(text);
        textArea.setLineWrap(false);
        textArea.setWrapStyleWord(false);
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setOpaque(false);
        textArea.setBorder(null);

        Font messageFont = UIManager.getFont("OptionPane.messageFont");
        Font fallbackFont = UIManager.getFont("Label.font");
        if (messageFont != null || fallbackFont != null) {
            textArea.setFont(messageFont == null ? fallbackFont : messageFont);
        }

        Color messageForeground = UIManager.getColor("OptionPane.messageForeground");
        if (messageForeground != null) {
            textArea.setForeground(messageForeground);
        }
        return textArea;
    }

    private static Object callOnEventDispatchThread(Supplier<Object> action) {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.get();
        }

        var result = new AtomicReference<>();
        var failure = new AtomicReference<Throwable>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(action.get());
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while showing a modal dialog", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Could not show a modal dialog", e.getCause());
        }
        rethrowDialogFailure(failure.get());
        return result.get();
    }

    private static Object showOptionPaneOnEventDispatchThread(Component parent, JOptionPane optionPane) {
        Window owner = dialogOwner(parent);
        var dialog = new JDialog(owner, Dialog.ModalityType.APPLICATION_MODAL);
        Window mainOwner = resolveMainOwner(parent, dialog);
        Rectangle ownerBounds = mainOwner == null ? fallbackScreenBounds() : usableOwnerBounds(mainOwner.getBounds());

        Object message = optionPane.getMessage();
        Object adaptiveMessage = adaptiveMessage(message, ownerBounds);
        if (adaptiveMessage != message) {
            optionPane.setMessage(adaptiveMessage);
        }

        configureTitlelessOptionPaneDialog(dialog, optionPane);
        prepareModal(dialog, mainOwner == null ? parent : mainOwner, ownerBounds);
        try {
            dialog.setVisible(true);
            return optionPane.getValue();
        } finally {
            dialog.dispose();
        }
    }

    private static void prepareModal(JDialog dialog, Component locationParent, Rectangle ownerBounds) {
        dialog.pack();
        dialog.setSize(readableAdaptiveSize(
                dialog.getSize(),
                dialog.getContentPane().getMinimumSize(),
                ownerBounds
        ));
        dialog.setLocationRelativeTo(locationParent);
    }

    private static void rethrowDialogFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Could not show a modal dialog", failure);
        }
    }

    private static int clamp(int value, int minValue, int maxValue) {
        return min(max(value, minValue), maxValue);
    }

    private static Window resolveMainOwner(Component parent, JDialog dialog) {
        Window parentOwner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        Window resolvedParentOwner = resolveMainOwner(parentOwner);
        if (resolvedParentOwner != null) {
            return resolvedParentOwner;
        }

        Window dialogOwner = dialog == null ? null : dialog.getOwner();
        Window resolvedDialogOwner = resolveMainOwner(dialogOwner);
        return resolvedDialogOwner == null ? dialogOwner : resolvedDialogOwner;
    }

    private static Window resolveMainOwner(Window window) {
        if (window == null) {
            return null;
        }
        if (window instanceof Frame) {
            return window;
        }

        Window resolvedOwner = resolveMainOwner(window.getOwner());
        return resolvedOwner == null ? window : resolvedOwner;
    }

    private static Rectangle usableOwnerBounds(Rectangle ownerBounds) {
        if (ownerBounds == null || ownerBounds.width <= 0 || ownerBounds.height <= 0) {
            return new Rectangle(0, 0, FALLBACK_OWNER_WIDTH, FALLBACK_OWNER_HEIGHT);
        }
        return ownerBounds;
    }

    private static Rectangle fallbackScreenBounds() {
        try {
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            return usableOwnerBounds(new Rectangle(0, 0, screenSize.width, screenSize.height));
        } catch (HeadlessException e) {
            return new Rectangle(0, 0, FALLBACK_OWNER_WIDTH, FALLBACK_OWNER_HEIGHT);
        }
    }
}
