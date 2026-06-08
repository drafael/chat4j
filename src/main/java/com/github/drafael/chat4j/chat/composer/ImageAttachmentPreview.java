package com.github.drafael.chat4j.chat.composer;

import com.github.drafael.chat4j.provider.api.content.AttachmentRef;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public final class ImageAttachmentPreview extends JLabel {

    private static final int ARC = 12;
    private static final int MIN_WIDTH = 160;
    private static final int MAX_WIDTH = 420;
    private static final int MAX_HEIGHT = 360;
    private static final int DEFAULT_WIDTH = 320;
    private static final double WIDTH_RATIO = 1.0 / 3.0;

    private final BufferedImage source;
    private final File sourceFile;
    private int appliedTargetWidth = -1;
    private JScrollPane trackedScrollPane;
    private ComponentAdapter viewportListener;

    public ImageAttachmentPreview(AttachmentRef attachmentRef) {
        this.sourceFile = toFile(attachmentRef);
        this.source = loadImage(sourceFile);

        setOpaque(false);
        setHorizontalAlignment(SwingConstants.LEFT);
        setVerticalAlignment(SwingConstants.TOP);

        if (attachmentRef != null && !attachmentRef.originalName().isBlank()) {
            setToolTipText(attachmentRef.originalName());
        }

        if (sourceFile != null && source != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    openFile();
                }
            });
        }

        applyRender(DEFAULT_WIDTH);

        addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                trackScrollPane();
                refreshSizeFromViewport();
            }

            @Override
            public void ancestorRemoved(AncestorEvent event) {
                untrackScrollPane();
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {
            }
        });
    }

    private void trackScrollPane() {
        untrackScrollPane();
        Container ancestor = SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
        if (!(ancestor instanceof JScrollPane scrollPane) || scrollPane.getViewport() == null) {
            return;
        }
        trackedScrollPane = scrollPane;
        viewportListener = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                refreshSizeFromViewport();
            }
        };
        scrollPane.getViewport().addComponentListener(viewportListener);
    }

    private void untrackScrollPane() {
        if (trackedScrollPane != null && viewportListener != null) {
            trackedScrollPane.getViewport().removeComponentListener(viewportListener);
        }
        trackedScrollPane = null;
        viewportListener = null;
    }

    private void refreshSizeFromViewport() {
        int targetWidth = resolveTargetWidth();
        if (targetWidth == appliedTargetWidth) {
            return;
        }
        applyRender(targetWidth);
        revalidate();
        repaint();
    }

    private void applyRender(int targetWidth) {
        if (source == null) {
            setIcon(null);
            setPreferredSize(new Dimension(MIN_WIDTH, 120));
            appliedTargetWidth = targetWidth;
            return;
        }

        double scale = Math.min(1.0, (double) targetWidth / source.getWidth());
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        if (height > MAX_HEIGHT) {
            double shrink = (double) MAX_HEIGHT / height;
            width = Math.max(1, (int) Math.round(width * shrink));
            height = MAX_HEIGHT;
        }

        BufferedImage rounded = renderRounded(source, width, height, ARC);
        setIcon(new ImageIcon(rounded));
        Dimension size = new Dimension(width, height);
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(size);
        appliedTargetWidth = targetWidth;
    }

    private int resolveTargetWidth() {
        Container ancestor = SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
        int width = DEFAULT_WIDTH;
        if (ancestor instanceof JScrollPane scrollPane && scrollPane.getViewport() != null) {
            int viewportWidth = scrollPane.getViewport().getWidth();
            if (viewportWidth > 0) {
                width = (int) Math.round(viewportWidth * WIDTH_RATIO);
            }
        }
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
    }

    private static BufferedImage renderRounded(BufferedImage src, int width, int height, int arc) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.clip(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));
            g.drawImage(src, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private void openFile() {
        if (sourceFile == null || !sourceFile.exists()) {
            return;
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return;
        }
        try {
            Desktop.getDesktop().open(sourceFile);
        } catch (IOException ignored) {
        }
    }

    private static BufferedImage loadImage(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (IOException e) {
            return null;
        }
    }

    private static File toFile(AttachmentRef ref) {
        if (ref == null || ref.storagePath().isBlank()) {
            return null;
        }
        return new File(ref.storagePath());
    }
}
