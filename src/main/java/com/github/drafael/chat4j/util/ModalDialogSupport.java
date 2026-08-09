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

    private static final double MIN_WIDTH_RATIO = 0.20;
    private static final double MAX_WIDTH_RATIO = 0.30;
    private static final double MAX_HEIGHT_RATIO = 0.30;
    private static final int FALLBACK_OWNER_WIDTH = 640;
    private static final int FALLBACK_OWNER_HEIGHT = 480;
    private static final int SOFT_MIN_HEIGHT = 140;
    private static final int MESSAGE_COLUMNS = 18;

    private ModalDialogSupport() {
    }

    public static void prepareCompactModal(JDialog dialog, Component parent) {
        Window owner = resolveMainOwner(parent, dialog);
        Rectangle ownerBounds = owner == null ? fallbackScreenBounds() : owner.getBounds();

        dialog.pack();
        dialog.setSize(readableCompactSize(
                dialog.getSize(),
                dialog.getContentPane().getMinimumSize(),
                ownerBounds
        ));
        dialog.setLocationRelativeTo(owner == null ? parent : owner);
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

    static Dimension readableCompactSize(
            Dimension packedSize,
            Dimension minimumContentSize,
            Rectangle ownerBounds
    ) {
        Dimension compactSize = compactSize(packedSize, ownerBounds);
        Dimension safeMinimumContentSize = minimumContentSize == null ? new Dimension(0, 0) : minimumContentSize;
        return new Dimension(
                max(compactSize.width, safeMinimumContentSize.width),
                max(compactSize.height, safeMinimumContentSize.height)
        );
    }

    static Dimension compactSize(Dimension packedSize, Rectangle ownerBounds) {
        Dimension safePackedSize = packedSize == null ? new Dimension(0, 0) : packedSize;
        Rectangle safeOwnerBounds = ownerBounds == null
                ? new Rectangle(0, 0, FALLBACK_OWNER_WIDTH, FALLBACK_OWNER_HEIGHT)
                : ownerBounds;

        int ownerWidth = max(safeOwnerBounds.width, FALLBACK_OWNER_WIDTH);
        int ownerHeight = max(safeOwnerBounds.height, FALLBACK_OWNER_HEIGHT);
        int maxWidth = max(1, (int) round(ownerWidth * MAX_WIDTH_RATIO));
        int minWidth = min(maxWidth, max(1, (int) round(ownerWidth * MIN_WIDTH_RATIO)));
        int maxHeight = max(SOFT_MIN_HEIGHT, (int) round(ownerHeight * MAX_HEIGHT_RATIO));

        int targetWidth = clamp(safePackedSize.width, minWidth, maxWidth);
        int targetHeight = min(max(safePackedSize.height, SOFT_MIN_HEIGHT), maxHeight);
        return new Dimension(targetWidth, targetHeight);
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
        Object message = optionPane.getMessage();
        Object compactMessage = compactMessage(message);
        if (compactMessage != message) {
            optionPane.setMessage(compactMessage);
        }

        Window owner = dialogOwner(parent);
        var dialog = new JDialog(owner, Dialog.ModalityType.APPLICATION_MODAL);
        configureTitlelessOptionPaneDialog(dialog, optionPane);
        prepareCompactModal(dialog, parent);
        try {
            dialog.setVisible(true);
            return optionPane.getValue();
        } finally {
            dialog.dispose();
        }
    }

    static Object compactMessage(Object message) {
        if (!(message instanceof String text) || Strings.CI.startsWith(text, "<html>")) {
            return message;
        }

        var textArea = new JTextArea(text);
        textArea.setColumns(MESSAGE_COLUMNS);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setOpaque(false);
        textArea.setBorder(null);
        Font messageFont = UIManager.getFont("OptionPane.messageFont");
        textArea.setFont(messageFont == null ? UIManager.getFont("Label.font") : messageFont);
        textArea.setForeground(UIManager.getColor("OptionPane.messageForeground"));

        int preferredWidth = textArea.getFontMetrics(textArea.getFont()).charWidth('m') * MESSAGE_COLUMNS;
        textArea.setSize(new Dimension(preferredWidth, Short.MAX_VALUE));
        int preferredHeight = min(textArea.getPreferredSize().height, SOFT_MIN_HEIGHT);

        var scrollPane = new JScrollPane(
                textArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        );
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

    static Window dialogOwner(Component parent) {
        if (parent instanceof Window window) {
            return window;
        }
        return parent == null ? null : SwingUtilities.getWindowAncestor(parent);
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

    private static Rectangle fallbackScreenBounds() {
        try {
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            return new Rectangle(0, 0, screenSize.width, screenSize.height);
        } catch (HeadlessException e) {
            return new Rectangle(0, 0, FALLBACK_OWNER_WIDTH, FALLBACK_OWNER_HEIGHT);
        }
    }
}
