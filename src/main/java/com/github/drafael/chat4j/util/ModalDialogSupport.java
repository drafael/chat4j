package com.github.drafael.chat4j.util;

import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;

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

    private ModalDialogSupport() {
    }

    public static void prepareCompactModal(JDialog dialog, Component parent) {
        Window owner = resolveMainOwner(parent, dialog);
        Rectangle ownerBounds = owner == null ? fallbackScreenBounds() : owner.getBounds();

        dialog.pack();
        dialog.setSize(compactSize(dialog.getSize(), ownerBounds));
        dialog.setLocationRelativeTo(owner == null ? parent : owner);
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
