package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.util.Fonts;

import java.awt.event.ActionEvent;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

public class InputBar extends JPanel {

    private final JTextArea textArea;
    private JScrollPane scrollPane;
    private JPanel generationIndicatorPanel;
    private JPanel generationInfoPanel;
    private JProgressBar generationProgressBar;
    private JLabel generationLabel;
    private JButton jumpToLatestButton;
    private JButton cancelGenerationButton;
    private final List<ActionListener> sendListeners = new ArrayList<>();
    private final List<ActionListener> jumpToLatestListeners = new ArrayList<>();
    private final List<ActionListener> cancelGenerationListeners = new ArrayList<>();
    private boolean sendOnEnter = true;

    public InputBar() {
        setLayout(new BorderLayout());

        textArea = new JTextArea(2, 40);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(Fonts.of(Font.PLAIN, 14));

        // FlatLaf native placeholder text
        textArea.putClientProperty("JTextField.placeholderText", "Enter a message here, press \u21B5 to send");

        textArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (sendOnEnter && !e.isShiftDown()) {
                        e.consume();
                        fireSend();
                    } else if (!sendOnEnter && (e.isControlDown() || e.isMetaDown())) {
                        e.consume();
                        fireSend();
                    }
                }
            }
        });

        // Auto-grow up to 5 lines
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { adjustHeight(); }
            public void removeUpdate(DocumentEvent e) { adjustHeight(); }
            public void changedUpdate(DocumentEvent e) { adjustHeight(); }
        });

        scrollPane = new JScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        generationIndicatorPanel = new JPanel(new BorderLayout());
        generationIndicatorPanel.setVisible(false);

        generationInfoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

        generationProgressBar = new JProgressBar();
        generationProgressBar.setIndeterminate(true);
        generationProgressBar.setPreferredSize(new Dimension(64, 8));
        generationProgressBar.setBorderPainted(false);
        generationInfoPanel.add(generationProgressBar);

        generationLabel = new JLabel("Generating response…");
        generationLabel.setFont(Fonts.of(Font.PLAIN, 12));
        generationInfoPanel.add(generationLabel);

        jumpToLatestButton = new JButton("Jump to latest");
        jumpToLatestButton.putClientProperty("JButton.buttonType", "borderless");
        jumpToLatestButton.setFont(Fonts.of(Font.PLAIN, 12));
        jumpToLatestButton.setFocusable(false);
        jumpToLatestButton.addActionListener(e -> fireJumpToLatest());
        generationInfoPanel.add(jumpToLatestButton);

        cancelGenerationButton = new JButton("Cancel");
        cancelGenerationButton.putClientProperty("JButton.buttonType", "borderless");
        cancelGenerationButton.setFont(Fonts.of(Font.PLAIN, 12));
        cancelGenerationButton.setFocusable(false);
        cancelGenerationButton.addActionListener(e -> fireCancelGeneration());

        generationIndicatorPanel.add(generationInfoPanel, BorderLayout.WEST);
        generationIndicatorPanel.add(cancelGenerationButton, BorderLayout.EAST);

        applyThemeStyles();
        add(generationIndicatorPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        applyThemeStyles();
    }

    public String getText() {
        return textArea.getText().trim();
    }

    public void clear() {
        textArea.setText("");
    }

    public void setEnabled(boolean enabled) {
        textArea.setEnabled(enabled);
    }

    public void addSendListener(ActionListener listener) {
        sendListeners.add(listener);
    }

    public void setSendOnEnter(boolean sendOnEnter) {
        this.sendOnEnter = sendOnEnter;
    }

    public void addJumpToLatestListener(ActionListener listener) {
        jumpToLatestListeners.add(listener);
    }

    public void addCancelGenerationListener(ActionListener listener) {
        cancelGenerationListeners.add(listener);
    }

    public void requestInputFocus() {
        textArea.requestFocusInWindow();
    }

    public void setGenerationIndicatorVisible(boolean visible) {
        Runnable update = () -> {
            generationIndicatorPanel.setVisible(visible);
            generationProgressBar.setIndeterminate(visible);
            revalidate();
            repaint();
        };

        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    public boolean isGenerationIndicatorVisible() {
        return generationIndicatorPanel.isVisible();
    }

    private void fireSend() {
        var event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "send");
        for (ActionListener l : sendListeners) {
            l.actionPerformed(event);
        }
    }

    private void fireJumpToLatest() {
        var event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "jumpToLatest");
        for (ActionListener l : jumpToLatestListeners) {
            l.actionPerformed(event);
        }
    }

    private void fireCancelGeneration() {
        var event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "cancelGeneration");
        for (ActionListener l : cancelGenerationListeners) {
            l.actionPerformed(event);
        }
    }

    private void adjustHeight() {
        int lineCount = Math.min(textArea.getLineCount(), 5);
        textArea.setRows(Math.max(2, lineCount));
        revalidate();
    }

    private void applyThemeStyles() {
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        UIManager.getColor("Separator.foreground")
                ),
                BorderFactory.createEmptyBorder(8, 16, 12, 16)
        ));

        if (scrollPane != null) {
            scrollPane.setBorder(BorderFactory.createLineBorder(
                    UIManager.getColor("Component.borderColor"), 1
            ));
        }

        if (generationIndicatorPanel != null) {
            generationIndicatorPanel.setOpaque(false);
        }

        if (generationInfoPanel != null) {
            generationInfoPanel.setOpaque(false);
        }

        if (generationLabel != null) {
            generationLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        }

        Color linkColor = UIManager.getColor("Component.linkColor");
        if (linkColor == null) {
            linkColor = UIManager.getColor("Component.accentColor");
        }
        if (linkColor == null) {
            linkColor = UIManager.getColor("Label.foreground");
        }

        if (jumpToLatestButton != null) {
            jumpToLatestButton.setForeground(linkColor);
        }

        if (cancelGenerationButton != null) {
            cancelGenerationButton.setForeground(linkColor);
        }
    }
}
