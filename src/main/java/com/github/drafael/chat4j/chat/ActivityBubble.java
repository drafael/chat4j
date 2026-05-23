package com.github.drafael.chat4j.chat;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.chat.message.ChatMessageView;
import com.github.drafael.chat4j.chat.message.ChatMessageViewFactory;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.util.Fonts;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.regex.Pattern;

public class ActivityBubble extends JPanel {

    private static final int ARC = 18;
    private static final int COLLAPSE_DEBOUNCE_MS = 90;
    private static final Insets BUBBLE_INSETS = new Insets(4, 6, 4, 6);
    private static final int COPY_BUTTON_SIZE = 20;
    private static final int COPY_ICON_SIZE = 13;
    private static final int TOGGLE_BUTTON_SIZE = 18;
    private static final Pattern HTML_STYLE_BLOCK_PATTERN = Pattern.compile("(?is)<style.*?</style>");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("(?s)<[^>]+>");

    private final StringBuilder fullText = new StringBuilder();
    private final JButton foldButton;
    private final JLabel titleLabel;
    private final JButton copyButton;
    private final JPanel copyButtonSlot;
    private final JPanel contentPanel;
    private final ChatMessageViewFactory messageViewFactory = new ChatMessageViewFactory();
    private final ChatMessageView renderedBubble;
    private final Timer collapseDebounceTimer;

    private Boolean pendingCollapsedState;
    private boolean copyButtonHovered;
    private boolean copyButtonCheckFeedbackActive;
    private Timer copyButtonCheckFeedbackTimer;
    private Color copyButtonBaseColor = new Color(120, 120, 120);
    private Color copyButtonHoverColor = new Color(150, 150, 150);
    private StatusTone statusTone = StatusTone.DEFAULT;

    private RenderMode renderMode = RenderMode.PREVIEW;
    private boolean collapsed;
    private boolean collapsible = true;
    private boolean streaming;
    private boolean renderValidationScheduled;
    private boolean disposed;

    public ActivityBubble() {
        this("Thinking", false);
    }

    public ActivityBubble(boolean defaultCollapsed) {
        this("Thinking", defaultCollapsed);
    }

    public ActivityBubble(String title, boolean defaultCollapsed) {
        setLayout(new BorderLayout(0, 4));
        setOpaque(false);
        setDoubleBuffered(true);
        setBorder(BorderFactory.createEmptyBorder(
                BUBBLE_INSETS.top,
                BUBBLE_INSETS.left,
                BUBBLE_INSETS.bottom,
                BUBBLE_INSETS.right
        ));

        foldButton = new JButton();
        foldButton.putClientProperty("JButton.buttonType", "toolBarButton");
        foldButton.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:999");
        foldButton.setMargin(new Insets(0, 0, 0, 0));
        foldButton.setFocusable(false);
        Dimension toggleButtonSize = new Dimension(TOGGLE_BUTTON_SIZE, TOGGLE_BUTTON_SIZE);
        foldButton.setPreferredSize(toggleButtonSize);
        foldButton.setMinimumSize(toggleButtonSize);
        foldButton.setMaximumSize(toggleButtonSize);
        foldButton.addActionListener(e -> toggleCollapsedDebounced());

        String titleText = StringUtils.defaultIfBlank(title, "Thinking");
        statusTone = resolveStatusTone(titleText);
        titleLabel = new JLabel(titleText);
        Fonts.apply(titleLabel, Font.PLAIN, Fonts.SIZE_BODY);
        titleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        titleLabel.setToolTipText("Toggle %s".formatted(titleText.toLowerCase()));
        titleLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggleCollapsedDebounced();
            }
        });

        copyButton = new JButton();
        copyButton.putClientProperty("JButton.buttonType", "borderless");
        copyButton.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:999");
        copyButton.setMargin(new Insets(0, 0, 0, 0));
        copyButton.setFocusable(false);
        copyButton.setBorderPainted(false);
        copyButton.setContentAreaFilled(false);
        copyButton.setOpaque(false);
        copyButton.setToolTipText("Copy %s".formatted(titleText.toLowerCase()));
        copyButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                setCopyButtonHighlighted(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setCopyButtonHighlighted(false);
            }
        });
        Dimension copyButtonDimension = new Dimension(COPY_BUTTON_SIZE, COPY_BUTTON_SIZE);
        copyButton.setPreferredSize(copyButtonDimension);
        copyButton.setMinimumSize(copyButtonDimension);
        copyButton.setMaximumSize(copyButtonDimension);
        copyButton.setVisible(false);
        copyButton.addActionListener(e -> copyThinkingToClipboard());

        copyButtonSlot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        copyButtonSlot.setOpaque(false);
        copyButtonSlot.setPreferredSize(copyButtonDimension);
        copyButtonSlot.setMinimumSize(copyButtonDimension);
        copyButtonSlot.setMaximumSize(copyButtonDimension);
        copyButtonSlot.add(copyButton);

        JPanel headerStart = new JPanel(new GridBagLayout());
        headerStart.setOpaque(false);
        GridBagConstraints foldConstraints = new GridBagConstraints();
        foldConstraints.gridx = 0;
        foldConstraints.gridy = 0;
        foldConstraints.anchor = GridBagConstraints.CENTER;
        foldConstraints.insets = new Insets(0, 0, 0, 4);
        headerStart.add(foldButton, foldConstraints);

        GridBagConstraints titleConstraints = new GridBagConstraints();
        titleConstraints.gridx = 1;
        titleConstraints.gridy = 0;
        titleConstraints.anchor = GridBagConstraints.CENTER;
        headerStart.add(titleLabel, titleConstraints);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(headerStart, BorderLayout.WEST);
        header.add(copyButtonSlot, BorderLayout.EAST);

        renderedBubble = messageViewFactory.create(Role.ASSISTANT);
        renderedBubble.component().setBorder(BorderFactory.createEmptyBorder());
        renderedBubble.component().setOpaque(false);
        renderedBubble.component().setDoubleBuffered(true);
        renderedBubble.setRenderMode(renderMode);

        contentPanel = new JPanel(new BorderLayout());
        contentPanel.setOpaque(false);
        contentPanel.setDoubleBuffered(true);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(0, TOGGLE_BUTTON_SIZE + 4, 0, 0));
        contentPanel.add(renderedBubble.component(), BorderLayout.CENTER);

        add(header, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);

        collapseDebounceTimer = new Timer(COLLAPSE_DEBOUNCE_MS, e -> {
            if (pendingCollapsedState != null) {
                applyCollapsedState(pendingCollapsedState);
                pendingCollapsedState = null;
            }
        });
        collapseDebounceTimer.setRepeats(false);

        copyButtonCheckFeedbackTimer = new Timer(900, event -> {
            copyButtonCheckFeedbackActive = false;
            updateCopyButtonIconForState();
        });
        copyButtonCheckFeedbackTimer.setRepeats(false);

        installHoverListener();

        updateColors();
        this.collapsed = !defaultCollapsed;
        setCollapsed(defaultCollapsed);
    }

    public void appendText(String token) {
        if (StringUtils.isEmpty(token)) {
            return;
        }

        fullText.append(token);
        refreshContent();
    }

    public void setText(String text) {
        fullText.setLength(0);
        if (text != null) {
            fullText.append(text);
        }
        refreshContent();
    }

    public String getFullText() {
        return fullText.toString();
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setTitle(String title) {
        String titleText = StringUtils.defaultIfBlank(title, "Thinking");
        statusTone = resolveStatusTone(titleText);
        titleLabel.setText(titleText);
        titleLabel.setToolTipText(collapsible ? "Toggle %s".formatted(titleText.toLowerCase()) : null);
        copyButton.setToolTipText("Copy %s".formatted(titleText.toLowerCase()));
        updateColors();
    }

    public void setCollapsible(boolean collapsible) {
        if (this.collapsible == collapsible) {
            return;
        }

        this.collapsible = collapsible;
        foldButton.setVisible(collapsible);
        titleLabel.setCursor(collapsible
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
        titleLabel.setToolTipText(collapsible ? "Toggle %s".formatted(titleLabel.getText().toLowerCase()) : null);
        if (!collapsible) {
            applyCollapsedState(true);
        }
    }

    public void setRenderMode(RenderMode renderMode) {
        if (renderMode == null || this.renderMode == renderMode) {
            return;
        }

        this.renderMode = renderMode;
        refreshContent();
    }

    public void setCollapsed(boolean collapsed) {
        pendingCollapsedState = null;
        if (collapseDebounceTimer.isRunning()) {
            collapseDebounceTimer.stop();
        }
        applyCollapsedState(collapsed);
    }

    public void dispose() {
        if (disposed) {
            return;
        }

        disposed = true;
        pendingCollapsedState = null;
        renderValidationScheduled = false;
        if (collapseDebounceTimer.isRunning()) {
            collapseDebounceTimer.stop();
        }
        if (copyButtonCheckFeedbackTimer.isRunning()) {
            copyButtonCheckFeedbackTimer.stop();
        }
        renderedBubble.dispose();
    }

    public boolean isDisposed() {
        return disposed;
    }

    private void toggleCollapsedDebounced() {
        if (!collapsible) {
            return;
        }

        boolean baseState = pendingCollapsedState != null ? pendingCollapsedState : collapsed;
        requestCollapsed(!baseState);
    }

    private void requestCollapsed(boolean collapsed) {
        pendingCollapsedState = collapsed;
        collapseDebounceTimer.restart();
    }

    private void applyCollapsedState(boolean collapsed) {
        boolean effectiveCollapsed = !collapsible || collapsed;
        if (this.collapsed == effectiveCollapsed) {
            return;
        }

        this.collapsed = effectiveCollapsed;
        contentPanel.setVisible(!effectiveCollapsed);
        foldButton.setText(effectiveCollapsed ? "▸" : "▾");
        foldButton.setVisible(collapsible);

        setBorder(BorderFactory.createEmptyBorder(
                BUBBLE_INSETS.top,
                BUBBLE_INSETS.left,
                BUBBLE_INSETS.bottom,
                BUBBLE_INSETS.right
        ));

        // Avoid forcing a full re-layout/re-render cycle here to reduce chat flicker.
        // setVisible() already triggers the needed invalidation/paint for contentPanel.
        repaint();
    }

    public void setStreaming(boolean streaming) {
        if (this.streaming == streaming) {
            return;
        }

        this.streaming = streaming;
        updateColors();
        refreshContent();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (titleLabel != null) {
            setBorder(BorderFactory.createEmptyBorder(
                    BUBBLE_INSETS.top,
                    BUBBLE_INSETS.left,
                    BUBBLE_INSETS.bottom,
                    BUBBLE_INSETS.right
            ));
            updateColors();
            refreshContent();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(getBackground());
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

    private void refreshContent() {
        if (disposed) {
            return;
        }

        renderedBubble.setRenderMode(resolveRenderMode());
        renderedBubble.setText(fullText.toString());

        if (!streaming) {
            scheduleRenderValidation();
        }

        revalidate();
        repaint();
    }

    private RenderMode resolveRenderMode() {
        if (streaming) {
            return RenderMode.MARKDOWN;
        }
        return renderMode;
    }

    private void scheduleRenderValidation() {
        if (disposed || renderValidationScheduled || renderMode != RenderMode.PREVIEW || streaming) {
            return;
        }

        renderValidationScheduled = true;
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
            renderValidationScheduled = false;
            if (disposed || streaming || renderMode != RenderMode.PREVIEW || !hasVisibleThinkingText(fullText.toString())) {
                return;
            }

            String visible = renderedBubble.contentTextSnapshot();
            String html = renderedBubble.contentHtmlSnapshot();
            if (StringUtils.isBlank(visible) && previewLooksBlank(html)) {
                renderAsMarkdownFallback();
            }
        }));
    }

    private void renderAsMarkdownFallback() {
        renderedBubble.setRenderMode(RenderMode.MARKDOWN);
        renderedBubble.setText(fullText.toString());
    }

    private boolean previewLooksBlank(String html) {
        if (StringUtils.isBlank(html)) {
            return true;
        }

        String noStyle = HTML_STYLE_BLOCK_PATTERN.matcher(html).replaceAll("");
        String noTags = HTML_TAG_PATTERN.matcher(noStyle).replaceAll("");
        String normalized = noTags
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
        return normalized.isEmpty();
    }

    private boolean hasVisibleThinkingText(String text) {
        return StringUtils.isNotBlank(text == null ? "" : text.replaceAll("\\p{Cf}", ""));
    }

    private void updateColors() {
        Color panelBackground = UIManager.getColor("Panel.background");
        if (panelBackground == null) {
            panelBackground = new Color(35, 37, 43);
        }

        boolean dark = detectDark(panelBackground);
        setBackground(resolveSecondaryBackground(panelBackground));

        Color muted = UIManager.getColor("Label.disabledForeground");
        if (muted == null) {
            muted = new Color(130, 130, 130);
        }
        Color titleColor = streaming ? resolveStreamingTitleColor(muted) : resolveTitleColor(muted);

        titleLabel.setForeground(titleColor);
        foldButton.setForeground(titleColor);

        Color iconColor = blend(muted, panelBackground, 0.25f);
        Color accent = UIManager.getColor("Component.accentColor");
        if (accent == null) {
            accent = UIManager.getColor("Button.focusedBorderColor");
        }
        if (accent == null) {
            accent = muted;
        }

        copyButtonBaseColor = streaming ? titleColor : iconColor;
        float hoverBlend = dark ? 0.86f : 0.80f;
        Color hoverCandidate = blend(iconColor, accent, hoverBlend);
        if (colorDistance(hoverCandidate, getBackground()) <= colorDistance(iconColor, getBackground()) + 14f) {
            hoverCandidate = blend(muted, dark ? Color.WHITE : Color.BLACK, 0.68f);
        }
        copyButtonHoverColor = hoverCandidate;

        setCopyButtonHighlighted(copyButtonHovered);
    }

    private Color resolveTitleColor(Color fallback) {
        return switch (statusTone) {
            case SUCCESS -> colorOrDefault(UIManager.getColor("Actions.Green"), new Color(60, 150, 95));
            case FAILURE -> colorOrDefault(UIManager.getColor("Component.error.focusedBorderColor"), new Color(190, 58, 58));
            case SKIPPED -> colorOrDefault(UIManager.getColor("Label.disabledForeground"), fallback);
            case RUNNING, DEFAULT -> fallback;
        };
    }

    private Color resolveStreamingTitleColor(Color fallback) {
        Color accent = UIManager.getColor("Component.accentColor");
        if (accent == null) {
            accent = UIManager.getColor("ProgressBar.foreground");
        }
        return accent != null ? accent : fallback;
    }

    private Color colorOrDefault(Color color, Color fallback) {
        return color != null ? color : fallback;
    }

    private StatusTone resolveStatusTone(String title) {
        String normalized = StringUtils.trimToEmpty(title);
        if (normalized.startsWith("✓")) {
            return StatusTone.SUCCESS;
        }
        if (normalized.startsWith("✗")) {
            return StatusTone.FAILURE;
        }
        if (normalized.startsWith("↷")) {
            return StatusTone.SKIPPED;
        }
        if (normalized.startsWith("•")) {
            return StatusTone.RUNNING;
        }
        return StatusTone.DEFAULT;
    }

    private enum StatusTone {
        DEFAULT,
        RUNNING,
        SUCCESS,
        FAILURE,
        SKIPPED
    }

    private void installHoverListener() {
        MouseAdapter hoverListener = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                setCopyButtonVisible(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!isShowing()) {
                    setCopyButtonVisible(false);
                    return;
                }

                Point screenPoint = new Point(e.getXOnScreen(), e.getYOnScreen());
                SwingUtilities.convertPointFromScreen(screenPoint, ActivityBubble.this);
                if (!contains(screenPoint)) {
                    setCopyButtonVisible(false);
                }
            }
        };

        addMouseListenerRecursively(this, hoverListener);
    }

    private void addMouseListenerRecursively(Component component, MouseAdapter listener) {
        component.addMouseListener(listener);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                addMouseListenerRecursively(child, listener);
            }
        }
    }

    private void setCopyButtonVisible(boolean visible) {
        if (copyButton.isVisible() == visible) {
            return;
        }
        copyButton.setVisible(visible);
        if (!visible) {
            setCopyButtonHighlighted(false);
        } else {
            updateCopyButtonIconForState();
        }
        copyButton.repaint();
    }

    private void setCopyButtonHighlighted(boolean highlighted) {
        copyButtonHovered = highlighted;
        updateCopyButtonIconForState();
        copyButton.repaint();
    }

    private void copyThinkingToClipboard() {
        if (StringUtils.isBlank(fullText.toString())) {
            return;
        }

        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(fullText.toString()), null);
        copyButtonCheckFeedbackActive = true;
        updateCopyButtonIconForState();
        copyButtonCheckFeedbackTimer.restart();
    }

    private void updateCopyButtonIconForState() {
        Color iconColor = resolveCopyButtonIconColor();
        copyButton.setForeground(iconColor);

        String iconPath = copyButtonCheckFeedbackActive ? "/icons/chat/check.svg" : "/icons/input/copy.svg";
        Icon icon = loadTintedIcon(iconPath, iconColor, COPY_ICON_SIZE);
        if (icon != null) {
            copyButton.setIcon(icon);
        }
    }

    private Color resolveCopyButtonIconColor() {
        if (copyButtonHovered && copyButton.isVisible()) {
            return copyButtonHoverColor;
        }
        return copyButtonBaseColor;
    }

    private Icon loadTintedIcon(String path, Color tint, int size) {
        URL iconUrl = ActivityBubble.class.getResource(path);
        if (iconUrl == null) {
            return null;
        }

        FlatSVGIcon icon = new FlatSVGIcon(iconUrl).derive(size, size);
        Color resolvedTint = tint == null ? new Color(90, 90, 90) : tint;
        icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> new Color(
                resolvedTint.getRed(),
                resolvedTint.getGreen(),
                resolvedTint.getBlue(),
                color.getAlpha()
        )));
        return icon.hasFound() ? icon : null;
    }

    private Color resolveSecondaryBackground(Color panelBackground) {
        Color foreground = UIManager.getColor("Label.foreground");
        if (foreground == null) {
            foreground = detectDark(panelBackground) ? Color.WHITE : Color.BLACK;
        }

        return blend(panelBackground, foreground, detectDark(panelBackground) ? 0.06f : 0.03f);
    }

    private Color resolveBorderColor() {
        Color foreground = UIManager.getColor("Label.disabledForeground");
        if (foreground == null) {
            foreground = Color.GRAY;
        }

        return new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 85);
    }

    private boolean detectDark(Color color) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        return hsb[2] <= 0.5f;
    }

    private Color blend(Color base, Color overlay, float ratio) {
        float clamped = Math.max(0f, Math.min(1f, ratio));
        float baseWeight = 1f - clamped;
        return new Color(
                Math.round(base.getRed() * baseWeight + overlay.getRed() * clamped),
                Math.round(base.getGreen() * baseWeight + overlay.getGreen() * clamped),
                Math.round(base.getBlue() * baseWeight + overlay.getBlue() * clamped)
        );
    }

    private float colorDistance(Color a, Color b) {
        float dr = a.getRed() - b.getRed();
        float dg = a.getGreen() - b.getGreen();
        float db = a.getBlue() - b.getBlue();
        return (float) Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
