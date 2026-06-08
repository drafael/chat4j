package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.chat.conversation.webview.jcef.JcefInitializationProgress;
import com.github.drafael.chat4j.util.Fonts;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;

final class JcefInitializationProgressDialog {

    private static final int DIALOG_WIDTH = 380;
    private static final int PROGRESS_HEIGHT = 18;

    private final JDialog dialog;
    private final JLabel messageLabel;
    private final JProgressBar progressBar;

    JcefInitializationProgressDialog() {
        dialog = new JDialog((Frame) null, "Preparing Chromium", true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setResizable(false);

        JLabel titleLabel = new JLabel("Preparing Chromium");
        titleLabel.setFont(Fonts.of(Font.BOLD, Fonts.SIZE_SUBTITLE));

        messageLabel = new JLabel("Starting Chromium…");
        progressBar = new JProgressBar(0, 100);
        progressBar.setIndeterminate(true);
        progressBar.setStringPainted(false);
        progressBar.setPreferredSize(new Dimension(DIALOG_WIDTH, PROGRESS_HEIGHT));

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(new EmptyBorder(22, 24, 22, 24));
        content.add(titleLabel, BorderLayout.NORTH);
        content.add(messageLabel, BorderLayout.CENTER);
        content.add(progressBar, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
    }

    void updateProgress(JcefInitializationProgress progress) {
        SwingUtilities.invokeLater(() -> applyProgress(progress));
    }

    void showDialog() {
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    void close() {
        SwingUtilities.invokeLater(() -> {
            dialog.setVisible(false);
            dialog.dispose();
        });
    }

    private void applyProgress(JcefInitializationProgress progress) {
        messageLabel.setText(progress.message());
        progressBar.setIndeterminate(!progress.determinate());
        progressBar.setValue(progress.percent());
    }
}
