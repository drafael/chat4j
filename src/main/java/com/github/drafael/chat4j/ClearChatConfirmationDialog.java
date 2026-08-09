package com.github.drafael.chat4j;

import com.github.drafael.chat4j.util.Fonts;
import lombok.NonNull;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.Objects;

import static com.github.drafael.chat4j.util.ModalDialogSupport.showOptionPane;

public class ClearChatConfirmationDialog {

    static final String CANCEL_OPTION = "Cancel";
    static final String CONFIRM_OPTION = "Yes";

    public boolean confirm(@NonNull Component parent) {
        return Objects.equals(showOptionPane(parent, createOptionPane()), CONFIRM_OPTION);
    }

    JOptionPane createOptionPane() {
        return new JOptionPane(
                createContent(),
                JOptionPane.WARNING_MESSAGE,
                JOptionPane.YES_NO_OPTION,
                null,
                new Object[]{CANCEL_OPTION, CONFIRM_OPTION},
                CANCEL_OPTION
        );
    }

    private JPanel createContent() {
        JLabel titleLabel = new JLabel("Clear Chat", SwingConstants.CENTER);
        Fonts.apply(titleLabel, Font.BOLD, Fonts.SIZE_BODY_LARGE);

        JLabel messageLabel = new JLabel(
                "<html><div style='text-align:center'>Are you sure you want to remove all<br>messages in the chat?</div></html>",
                SwingConstants.CENTER
        );

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder());
        content.add(titleLabel, BorderLayout.NORTH);
        content.add(messageLabel, BorderLayout.CENTER);
        return content;
    }
}
