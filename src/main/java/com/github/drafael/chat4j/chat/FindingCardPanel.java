package com.github.drafael.chat4j.chat;

import com.formdev.flatlaf.FlatClientProperties;
import com.github.drafael.chat4j.util.Fonts;
import org.apache.commons.lang3.StringUtils;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

public class FindingCardPanel extends JPanel {

    private static final int ARC = 18;

    private final JLabel severityLabel = new JLabel();
    private final JLabel titleLabel = new JLabel();
    private final JTextArea bodyText = new JTextArea();
    private final JButton fileReferenceButton = new JButton();
    private final JButton dismissButton = new JButton("Dismiss");
    private final JButton openFileButton = new JButton("Open file");
    private final JButton askFollowUpButton = new JButton("Ask follow-up");
    private final JButton applyFixButton = new JButton("Apply fix");

    public FindingCardPanel(Finding finding) {
        setLayout(new BorderLayout(0, 10));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        Finding normalized = normalize(finding);
        add(createHeader(normalized), BorderLayout.NORTH);
        add(createBody(normalized), BorderLayout.CENTER);
        add(createFooter(normalized), BorderLayout.SOUTH);

        setOnDismiss(this::dismissCard);
        setOnOpenFile(null);
        setOnAskFollowUp(null);
        setOnApplyFix(null);
    }

    public void setOnDismiss(Runnable action) {
        setButtonAction(dismissButton, action);
    }

    public void setOnOpenFile(Runnable action) {
        setButtonAction(openFileButton, action);
        setButtonAction(fileReferenceButton, action);
    }

    public void setOnAskFollowUp(Runnable action) {
        setButtonAction(askFollowUpButton, action);
    }

    public void setOnApplyFix(Runnable action) {
        setButtonAction(applyFixButton, action);
    }

    public String severity() {
        return severityLabel.getText();
    }

    public String title() {
        return titleLabel.getText();
    }

    private void dismissCard() {
        Container parent = getParent();
        if (parent == null) {
            setVisible(false);
            return;
        }
        parent.remove(this);
        parent.revalidate();
        parent.repaint();
    }

    private JPanel createHeader(Finding finding) {
        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);

        JPanel titleGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        titleGroup.setOpaque(false);

        severityLabel.setText(finding.severity());
        severityLabel.setOpaque(true);
        severityLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        severityLabel.setForeground(severityForeground(finding.severity()));
        severityLabel.setBackground(severityBackground(finding.severity()));
        Fonts.apply(severityLabel, Font.BOLD, Fonts.SIZE_SMALL);

        titleLabel.setText(finding.title());
        titleLabel.setToolTipText(finding.title());
        Fonts.apply(titleLabel, Font.BOLD, Fonts.SIZE_BODY_LARGE);

        titleGroup.add(severityLabel);
        titleGroup.add(titleLabel);
        header.add(titleGroup, BorderLayout.CENTER);

        configureToolbarButton(dismissButton);
        header.add(dismissButton, BorderLayout.EAST);
        return header;
    }

    private JPanel createBody(Finding finding) {
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);

        bodyText.setEditable(false);
        bodyText.setOpaque(false);
        bodyText.setLineWrap(true);
        bodyText.setWrapStyleWord(true);
        bodyText.setText(finding.body());
        bodyText.setBorder(null);
        Fonts.apply(bodyText, Font.PLAIN, Fonts.SIZE_BODY);
        body.add(bodyText, BorderLayout.CENTER);

        fileReferenceButton.setText(finding.fileReference());
        fileReferenceButton.setToolTipText(finding.fileReference());
        fileReferenceButton.setHorizontalAlignment(JButton.LEFT);
        fileReferenceButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        fileReferenceButton.putClientProperty("JButton.buttonType", "borderless");
        fileReferenceButton.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0");
        fileReferenceButton.setMargin(new Insets(0, 0, 0, 0));
        Fonts.apply(fileReferenceButton, Font.PLAIN, Fonts.SIZE_SMALL);
        body.add(fileReferenceButton, BorderLayout.SOUTH);
        return body;
    }

    private JPanel createFooter(Finding finding) {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        footer.setOpaque(false);

        configureToolbarButton(openFileButton);
        configureToolbarButton(askFollowUpButton);
        configureToolbarButton(applyFixButton);

        footer.add(openFileButton);
        footer.add(askFollowUpButton);
        footer.add(applyFixButton);
        return footer;
    }

    private void configureToolbarButton(JButton button) {
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:999");
        button.setFocusable(false);
        button.setMargin(new Insets(4, 10, 4, 10));
        Fonts.apply(button, Font.PLAIN, Fonts.SIZE_SMALL);
    }

    private void setButtonAction(JButton button, Runnable action) {
        for (var listener : button.getActionListeners()) {
            button.removeActionListener(listener);
        }
        button.setEnabled(action != null);
        if (action != null) {
            button.addActionListener(event -> action.run());
        }
    }

    private Finding normalize(Finding finding) {
        if (finding == null) {
            return new Finding("P?", "Finding", "", "");
        }
        return new Finding(
                StringUtils.defaultIfBlank(finding.severity(), "P?"),
                StringUtils.defaultIfBlank(finding.title(), "Finding"),
                StringUtils.defaultString(finding.body()),
                StringUtils.defaultIfBlank(finding.fileReference(), "No file reference")
        );
    }

    private Color severityForeground(String severity) {
        if (StringUtils.startsWithIgnoreCase(severity, "P1")) {
            return new Color(154, 52, 18);
        }
        if (StringUtils.startsWithIgnoreCase(severity, "P2")) {
            return new Color(55, 65, 81);
        }
        if (StringUtils.startsWithIgnoreCase(severity, "P3")) {
            return new Color(30, 64, 175);
        }
        return colorOrDefault(UIManager.getColor("Label.foreground"), new Color(60, 60, 60));
    }

    private Color severityBackground(String severity) {
        if (StringUtils.startsWithIgnoreCase(severity, "P1")) {
            return new Color(255, 237, 213);
        }
        if (StringUtils.startsWithIgnoreCase(severity, "P2")) {
            return new Color(229, 231, 235);
        }
        if (StringUtils.startsWithIgnoreCase(severity, "P3")) {
            return new Color(219, 234, 254);
        }
        return colorOrDefault(UIManager.getColor("Button.background"), new Color(238, 238, 238));
    }

    private Color cardBackground() {
        Color panel = UIManager.getColor("Panel.background");
        if (panel == null) {
            return new Color(250, 250, 250);
        }
        float[] hsb = Color.RGBtoHSB(panel.getRed(), panel.getGreen(), panel.getBlue(), null);
        boolean light = hsb[2] > 0.5f;
        return Color.getHSBColor(hsb[0], Math.max(0f, hsb[1] - 0.02f), Math.max(0f, Math.min(1f, hsb[2] + (light ? -0.02f : 0.08f))));
    }

    private Color cardBorder() {
        return colorOrDefault(UIManager.getColor("Component.borderColor"), new Color(210, 210, 210));
    }

    private Color colorOrDefault(Color color, Color fallback) {
        return color != null ? color : fallback;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(cardBackground());
        g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC));
        g2.setColor(cardBorder());
        g2.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC));
        g2.dispose();
        super.paintComponent(g);
    }

    public record Finding(String severity, String title, String body, String fileReference) {
    }
}
