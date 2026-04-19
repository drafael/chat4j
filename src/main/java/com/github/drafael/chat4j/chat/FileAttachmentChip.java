package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.util.Fonts;

import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import org.apache.commons.lang3.StringUtils;

final class FileAttachmentChip extends JPanel {

    private static final int ARC = 10;
    private static final String DIALOG_TITLE = "Open Attachment";

    FileAttachmentChip(AttachmentRef attachmentRef) {
        setOpaque(false);
        setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 8));

        File file = toFile(attachmentRef);
        if (file != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(displayName(attachmentRef));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    openFile(file);
                }
            });
        }

        Icon fileIcon = file == null
                ? null
                : FileSystemView.getFileSystemView().getSystemIcon(file);
        if (fileIcon == null) {
            fileIcon = UIManager.getIcon("FileView.fileIcon");
        }

        String label = ellipsize(displayName(attachmentRef), 22);
        if (attachmentRef.sizeBytes() > 0) {
            label = "%s  %s".formatted(label, formatSize(attachmentRef.sizeBytes()));
        }

        JLabel nameLabel = new JLabel(label, fileIcon, SwingConstants.LEADING);
        Fonts.apply(nameLabel, Font.PLAIN, Fonts.SIZE_COMPACT);
        nameLabel.setForeground(UIManager.getColor("Label.foreground"));
        nameLabel.setIconTextGap(5);
        add(nameLabel);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(resolveBackground());
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        g2.dispose();
        super.paintComponent(g);
    }

    @Override
    protected void paintBorder(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(resolveBorderColor());
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        g2.dispose();
    }

    private static Color resolveBackground() {
        Color bg = UIManager.getColor("TextField.background");
        if (bg == null) {
            bg = UIManager.getColor("Panel.background");
        }
        return bg != null ? bg : new Color(90, 90, 90);
    }

    private static Color resolveBorderColor() {
        Color border = UIManager.getColor("TextField.borderColor");
        if (border == null) {
            border = UIManager.getColor("Component.borderColor");
        }
        if (border == null) {
            border = UIManager.getColor("Separator.foreground");
        }
        return border != null ? border : new Color(150, 150, 150);
    }

    private static String displayName(AttachmentRef attachmentRef) {
        if (attachmentRef == null) {
            return "";
        }
        if (!attachmentRef.originalName().isBlank()) {
            return attachmentRef.originalName();
        }

        File file = toFile(attachmentRef);
        return file == null ? "" : file.getName();
    }

    private static File toFile(AttachmentRef attachmentRef) {
        if (attachmentRef == null || StringUtils.isBlank(attachmentRef.storagePath())) {
            return null;
        }

        return new File(attachmentRef.storagePath());
    }

    private static String ellipsize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : "%s\u2026".formatted(trimmed.substring(0, maxLength - 1));
    }

    private static String formatSize(long bytes) {
        if (bytes < 1_000) {
            return "%d B".formatted(bytes);
        }
        if (bytes < 1_000_000) {
            return "%.1f kB".formatted(bytes / 1_000.0);
        }
        if (bytes < 1_000_000_000) {
            return "%.1f MB".formatted(bytes / 1_000_000.0);
        }
        return "%.1f GB".formatted(bytes / 1_000_000_000.0);
    }

    private void openFile(File file) {
        if (file == null || !file.exists()) {
            showOpenError("Attachment file is not available on disk.");
            return;
        }

        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            showOpenError("Opening attachments is not supported on this system.");
            return;
        }

        try {
            Desktop.getDesktop().open(file);
        } catch (IOException e) {
            showOpenError("Unable to open attachment: %s".formatted(file.getName()));
        }
    }

    private void showOpenError(String message) {
        JOptionPane.showMessageDialog(this, message, DIALOG_TITLE, JOptionPane.WARNING_MESSAGE);
    }
}
