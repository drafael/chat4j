package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.util.Fonts;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.net.URI;

public class MessageBubble extends JPanel {

    private static final int ARC = 16;

    private final Role role;
    private AssistantRenderMode assistantRenderMode = AssistantRenderMode.PREVIEW;
    private final StringBuilder fullText = new StringBuilder();
    private final JEditorPane editorPane;

    public MessageBubble(Role role) {
        this.role = role;
        setLayout(new BorderLayout());
        setOpaque(false);

        if (role == Role.USER) {
            setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        } else {
            setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        }

        // JEditorPane that tracks its parent's width for word wrapping
        editorPane = new JEditorPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }

            @Override
            public Dimension getPreferredSize() {
                // Force the editor to lay out at the parent's current width
                // so that HTML content wraps correctly inside BoxLayout
                Container parent = getParent();
                if (parent != null && parent.getWidth() > 0) {
                    setSize(parent.getWidth(), Short.MAX_VALUE);
                }
                return super.getPreferredSize();
            }
        };
        editorPane.setEditable(false);
        editorPane.setOpaque(false);
        editorPane.setContentType("text/html");

        HTMLEditorKit kit = new HTMLEditorKit();
        editorPane.setEditorKit(kit);
        editorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        if (editorPane.getCaret() instanceof DefaultCaret caret) {
            caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }

        configureHyperlinkSupport();

        add(editorPane, BorderLayout.CENTER);
    }

    private void configureHyperlinkSupport() {
        editorPane.addHyperlinkListener(event -> {
            if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
                return;
            }

            String link = event.getURL() != null ? event.getURL().toExternalForm() : event.getDescription();
            if (link == null || link.isBlank()) {
                return;
            }

            openExternalLink(link);
        });
    }

    private void openExternalLink(String link) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            return;
        }

        try {
            Desktop.getDesktop().browse(URI.create(link));
        } catch (Exception e) {
            // Ignore link open failures to keep chat interaction uninterrupted.
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (role == Role.USER) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getUserBubbleColor());
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), ARC, ARC));
            g2.dispose();
        }
        super.paintComponent(g);
    }

    private Color getUserBubbleColor() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) {
            return detectDarkMode() ? new Color(58, 58, 60) : new Color(239, 239, 241);
        }

        float[] hsb = Color.RGBtoHSB(bg.getRed(), bg.getGreen(), bg.getBlue(), null);
        boolean isDark = hsb[2] <= 0.5f;
        float brightness = clamp(hsb[2] + (isDark ? 0.10f : -0.04f));
        float saturation = clamp(hsb[1] + (isDark ? -0.02f : 0.02f));
        return Color.getHSBColor(hsb[0], saturation, brightness);
    }

    private boolean detectDarkMode() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg != null) {
            float[] hsb = Color.RGBtoHSB(bg.getRed(), bg.getGreen(), bg.getBlue(), null);
            return hsb[2] <= 0.5f;
        }
        return false;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    public void appendText(String token) {
        fullText.append(token);
        renderContent();
    }

    public void setText(String text) {
        fullText.setLength(0);
        fullText.append(text);
        renderContent();
    }

    public void setAssistantRenderMode(AssistantRenderMode assistantRenderMode) {
        if (assistantRenderMode == null || role != Role.ASSISTANT) {
            return;
        }

        if (this.assistantRenderMode != assistantRenderMode) {
            this.assistantRenderMode = assistantRenderMode;
            renderContent();
        }
    }

    private void renderContent() {
        String text = fullText.toString();
        boolean isDark = detectDarkMode();
        Palette palette = MarkdownPaletteResolver.resolve(isDark);

        if (role == Role.USER) {
            editorPane.setText(toUserHtml(text, palette, isDark));
        } else if (assistantRenderMode == AssistantRenderMode.MARKDOWN) {
            editorPane.setText(toEscapedHtml(text, palette, true));
        } else {
            editorPane.setText(MarkdownRenderer.toHtml(text, isDark));
        }

        revalidate();
        editorPane.repaint();
    }

    private String toEscapedHtml(String text, Palette palette, boolean monospaced) {
        String escaped = escapeHtml(text).replace("\n", "<br>");

        String fontFamily = monospaced ? palette.monoFontFamily() : palette.baseFontFamily();
        int bodyFontSize = Fonts.scale(Fonts.SIZE_SMALL);
        return "<html><head><style>"
                + "body { font-family: " + fontFamily + "; "
                + "font-size: " + bodyFontSize + "px; line-height: 1.4; color: " + palette.textColor() + "; margin: 0; padding: 0; }"
                + "</style></head><body>" + escaped + "</body></html>";
    }

    private String toUserHtml(String text, Palette palette, boolean isDark) {
        String badgeBackground = isDark ? "#2d4f8f" : "#dbeafe";
        String badgeText = isDark ? "#dbeafe" : "#1e3a8a";
        String fallbackBackground = isDark ? "#5c3a16" : "#ffedd5";
        String fallbackText = isDark ? "#fed7aa" : "#9a3412";

        String body = text.lines()
                .map(line -> toUserLineHtml(line, badgeBackground, badgeText, fallbackBackground, fallbackText))
                .reduce((left, right) -> left + right)
                .orElse("");

        int bodyFontSize = Fonts.scale(Fonts.SIZE_SMALL);
        int badgeFontSize = Fonts.scale(Fonts.SIZE_BADGE);
        return "<html><head><style>"
                + "body { font-family: " + palette.baseFontFamily() + "; font-size: " + bodyFontSize + "px; line-height: 1.45; color: " + palette.textColor() + "; margin: 0; padding: 0; }"
                + ".line { margin: 0 0 3px 0; }"
                + ".badge { display: inline-block; border-radius: 999px; padding: 1px 6px; font-size: " + badgeFontSize + "px; font-weight: 700; letter-spacing: 0.04em; margin-right: 6px; }"
                + ".skill { background: " + badgeBackground + "; color: " + badgeText + "; }"
                + ".fallback { background: " + fallbackBackground + "; color: " + fallbackText + "; }"
                + "</style></head><body>" + body + "</body></html>";
    }

    private String toUserLineHtml(String line,
                                  String skillBadgeBackground,
                                  String skillBadgeText,
                                  String fallbackBadgeBackground,
                                  String fallbackBadgeText
    ) {
        if (line == null || line.isBlank()) {
            return "<div class='line'>&nbsp;</div>";
        }

        if (line.startsWith("[SKILL] ")) {
            return "<div class='line'><span class='badge skill' style='background: " + skillBadgeBackground + "; color: " + skillBadgeText + ";'>SKILL</span>"
                    + escapeHtml(line.substring(8).trim()) + "</div>";
        }

        if (line.startsWith("[FALLBACK] ")) {
            return "<div class='line'><span class='badge fallback' style='background: " + fallbackBadgeBackground + "; color: " + fallbackBadgeText + ";'>FALLBACK</span>"
                    + escapeHtml(line.substring(11).trim()) + "</div>";
        }

        return "<div class='line'>" + escapeHtml(line) + "</div>";
    }

    private String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public String getFullText() {
        return fullText.toString();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (editorPane != null) {
            renderContent();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        // Let BorderLayout compute preferred size based on editorPane
        return super.getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension pref = getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, pref.height);
    }

    @Override
    public Dimension getMinimumSize() {
        // Prevent the bubble from demanding more than 0 width,
        // so BoxLayout never forces a horizontal scrollbar
        return new Dimension(0, super.getMinimumSize().height);
    }
}
