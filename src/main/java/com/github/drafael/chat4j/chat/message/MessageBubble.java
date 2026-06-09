package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.chat.content.JEditorPaneMessageContentView;
import com.github.drafael.chat4j.chat.content.MessageContentView;
import com.github.drafael.chat4j.chat.content.MessageContentViewProvider;
import com.github.drafael.chat4j.chat.content.MessageHtmlRenderer;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import javax.swing.*;

public class MessageBubble extends JPanel implements ChatMessageView {

    private static final int ARC = 14;

    private final Role role;
    private RenderMode renderMode = RenderMode.PREVIEW;
    private final StringBuilder fullText = new StringBuilder();
    private final MessageHtmlRenderer messageHtmlRenderer;
    private final MessageContentView contentView;
    private int maxContentWidth = Integer.MAX_VALUE;

    public MessageBubble(Role role) {
        this(role, JEditorPaneMessageContentView::new, new MessageHtmlRenderer());
    }

    MessageBubble(Role role, MessageContentViewProvider contentViewProvider, MessageHtmlRenderer messageHtmlRenderer) {
        this.role = role;
        this.messageHtmlRenderer = messageHtmlRenderer;
        setLayout(new BorderLayout());
        setOpaque(false);

        if (role == Role.USER) {
            setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        } else {
            setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        }

        contentView = contentViewProvider.create(role, () -> maxContentWidth);
        add(contentView.component(), BorderLayout.CENTER);
    }

    @Override
    public JComponent component() {
        return this;
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

    @Override
    public void appendText(String token) {
        fullText.append(token);
        renderContent();
    }

    @Override
    public void setText(String text) {
        fullText.setLength(0);
        fullText.append(text);
        renderContent();
    }

    @Override
    public void setMaxContentWidth(int maxContentWidth) {
        int normalized = maxContentWidth <= 0 ? Integer.MAX_VALUE : maxContentWidth;
        if (this.maxContentWidth == normalized) {
            return;
        }
        this.maxContentWidth = normalized;
        contentView.invalidateLayout();
        revalidate();
        repaint();
    }

    @Override
    public void setRenderMode(RenderMode renderMode) {
        if (renderMode == null) {
            return;
        }

        if (this.renderMode != renderMode) {
            this.renderMode = renderMode;
            renderContent();
        }
    }

    private void renderContent() {
        String text = fullText.toString();
        boolean isDark = detectDarkMode();
        contentView.setHtml(messageHtmlRenderer.render(role, renderMode, text, isDark));

        revalidate();
        contentView.component().repaint();
    }

    @Override
    public String getFullText() {
        return fullText.toString();
    }

    @Override
    public String contentHtmlSnapshot() {
        return contentView.htmlSnapshot();
    }

    @Override
    public String contentTextSnapshot() {
        return contentView.textSnapshot();
    }

    @Override
    public void setContextMenu(JPopupMenu popupMenu) {
        contentView.setContextMenu(popupMenu);
    }

    @Override
    public void installKeyBinding(KeyStroke keyStroke, String actionName, Action action) {
        contentView.installKeyBinding(keyStroke, actionName, action);
    }

    @Override
    public void selectAllContent() {
        contentView.selectAll();
    }

    @Override
    public boolean hasContentSelection() {
        return contentView.hasSelection();
    }

    @Override
    public void copySelectedContent() {
        contentView.copySelection();
    }

    @Override
    public void requestContentFocus() {
        contentView.requestContentFocus();
    }

    @Override
    public void dispose() {
        contentView.dispose();
    }

    @Override
    public boolean isDisposed() {
        return contentView.isDisposed();
    }

    @Override
    public Role getRole() {
        return role;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (contentView != null) {
            renderContent();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        // Let BorderLayout compute preferred size based on content view
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
