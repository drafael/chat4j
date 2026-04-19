package com.github.drafael.chat4j;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.util.Fonts;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import org.apache.commons.lang3.StringUtils;

public final class AboutDialog {

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private AboutDialog() {
    }

    public static void show(Window owner) {
        Info info = loadInfo();

        JDialog dialog = new JDialog(owner, "About %s".formatted(info.appName), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setUndecorated(true);
        dialog.setResizable(false);

        JPanel content = new JPanel(new BorderLayout(0, 16));
        content.setBorder(BorderFactory.createEmptyBorder(20, 28, 16, 28));
        dialog.setContentPane(content);

        JLabel logo = new JLabel(buildLogoIcon(96), SwingConstants.CENTER);
        logo.setHorizontalAlignment(SwingConstants.CENTER);
        content.add(logo, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(0, 0, 6, 0);

        JLabel title = new JLabel(info.appName, SwingConstants.CENTER);
        Fonts.apply(title, Font.BOLD, Fonts.SIZE_TITLE);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        center.add(title, gbc);

        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 12, 0);
        center.add(buildAuthorLink("Denys Rafael", "https://github.com/drafael"), gbc);

        gbc.gridwidth = 1;
        gbc.insets = new Insets(2, 0, 2, 8);
        gbc.anchor = GridBagConstraints.LINE_END;
        int row = 2;
        for (Map.Entry<String, String> e : info.rows.entrySet()) {
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            JLabel key = new JLabel("%s:".formatted(e.getKey()));
            center.add(key, gbc);

            gbc.gridx = 1;
            gbc.anchor = GridBagConstraints.LINE_START;
            gbc.weightx = 1;
            gbc.insets = new Insets(2, 0, 2, 0);
            center.add(new JLabel(e.getValue()), gbc);

            gbc.insets = new Insets(2, 0, 2, 8);
            gbc.anchor = GridBagConstraints.LINE_END;
            row++;
        }
        content.add(center, BorderLayout.CENTER);

        JButton okBtn = new JButton("OK");
        okBtn.addActionListener(ev -> dialog.dispose());

        JButton copyBtn = new JButton("Copy");
        copyBtn.addActionListener(ev -> {
            StringBuilder sb = new StringBuilder();
            sb.append(info.appName).append('\n');
            for (Map.Entry<String, String> e : info.rows.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(sb.toString()), null);
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        buttons.add(okBtn);
        buttons.add(copyBtn);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.getRootPane().setDefaultButton(okBtn);
        KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(escape, "close");
        dialog.getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dialog.dispose();
            }
        });

        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static JLabel buildAuthorLink(String text, String url) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setHorizontalTextPosition(SwingConstants.RIGHT);
        label.setIconTextGap(6);
        Color linkColor = UIManager.getColor("Component.linkColor");
        if (linkColor == null) {
            linkColor = new Color(0x2A7AE2);
        }
        label.setForeground(linkColor);
        Fonts.apply(label, Font.PLAIN, Fonts.SIZE_BODY);
        Icon icon = loadGitHubIcon(14, linkColor);
        if (icon != null) {
            label.setIcon(icon);
        }
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setToolTipText(url);
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(new URI(url));
                    }
                } catch (Exception ignored) {
                }
            }
        });
        return label;
    }

    private static Icon loadGitHubIcon(int size, Color color) {
        URL iconUrl = AboutDialog.class.getResource("/icons/github.svg");
        if (iconUrl == null) {
            return null;
        }
        FlatSVGIcon icon = new FlatSVGIcon(iconUrl).derive(size, size);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, c) -> new Color(
                color.getRed(), color.getGreen(), color.getBlue(), c.getAlpha())));
        return icon;
    }

    private static Info loadInfo() {
        Properties build = loadProperties("/build.properties");
        Properties git = loadProperties("/git.properties");

        String appName = build.getProperty("name", "Chat4J");
        String version = build.getProperty("version", "unknown");

        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Version", version);
        rows.put("Build date", formatBuildDate(build.getProperty("buildTime")));

        String commit = git.getProperty("git.commit.id.abbrev");
        if (StringUtils.isNotBlank(commit)) {
            String branch = git.getProperty("git.branch", "");
            String dirty = "true".equalsIgnoreCase(git.getProperty("git.dirty")) ? " (dirty)" : "";
            rows.put("Commit", "%s%s%s".formatted(commit, branch.isBlank() ? "" : " on %s".formatted(branch), dirty));
        }
        String commitTime = git.getProperty("git.commit.time");
        if (StringUtils.isNotBlank(commitTime)) {
            rows.put("Commit date", commitTime);
        }

        Runtime.Version rtv = Runtime.version();
        String vendor = System.getProperty("java.vendor", "");
        rows.put("Java", "%s%s".formatted(rtv, vendor.isBlank() ? "" : " (%s)".formatted(vendor)));
        rows.put("VM", "%s %s".formatted(System.getProperty("java.vm.name", ""), System.getProperty("java.vm.version", "")));
        rows.put("OS", "%s %s (%s)".formatted(
                System.getProperty("os.name", ""),
                System.getProperty("os.version", ""),
                System.getProperty("os.arch", "")
        ));

        return new Info(appName, rows);
    }

    private static String formatBuildDate(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "unknown";
        }
        try {
            return DISPLAY_DATE.format(Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDate());
        } catch (DateTimeParseException e) {
            try {
                return DISPLAY_DATE.format(LocalDate.parse(raw));
            } catch (DateTimeParseException ignored) {
                return raw;
            }
        }
    }

    private static Properties loadProperties(String path) {
        Properties props = new Properties();
        try (InputStream in = AboutDialog.class.getResourceAsStream(path)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
        }
        return props;
    }

    private static Icon buildLogoIcon(int size) {
        URL url = AboutDialog.class.getResource("/icons/icon.png");
        if (url == null) {
            return null;
        }
        BufferedImage source;
        try {
            source = ImageIO.read(url);
        } catch (IOException e) {
            return null;
        }
        if (source == null) {
            return null;
        }

        Color base = UIManager.getColor("Label.foreground");
        if (base == null) {
            base = new Color(60, 60, 60);
        }
        int fgRgb = base.getRGB() & 0xFFFFFF;
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage tinted = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = source.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a == 0) {
                    continue;
                }
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int luminance = (r * 299 + g * 587 + b * 114) / 1000;
                int newAlpha;
                if (luminance <= 80) {
                    newAlpha = a;
                } else if (luminance >= 180) {
                    continue;
                } else {
                    newAlpha = a * (180 - luminance) / 100;
                }
                tinted.setRGB(x, y, (newAlpha << 24) | fgRgb);
            }
        }

        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.drawImage(tinted, x, y, size, size, null);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    private record Info(String appName, Map<String, String> rows) {
    }
}
