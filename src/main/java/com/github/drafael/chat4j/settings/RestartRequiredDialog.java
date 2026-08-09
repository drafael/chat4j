package com.github.drafael.chat4j.settings;

import java.awt.*;
import javax.swing.*;
import org.apache.commons.lang3.StringUtils;

import static com.github.drafael.chat4j.util.ModalDialogSupport.showOptionPane;

public final class RestartRequiredDialog {

    private static final int MESSAGE_COLUMNS = 34;

    private RestartRequiredDialog() {
    }

    public enum Choice {
        EXIT_NOW,
        LATER,
        CANCEL
    }

    public static Choice show(Component parent, String message) {
        Object[] options = {"Exit Now", "Later", "Cancel"};
        JOptionPane optionPane = new JOptionPane(
                createMessage(message),
                JOptionPane.INFORMATION_MESSAGE,
                JOptionPane.YES_NO_CANCEL_OPTION,
                null,
                options,
                options[0]
        );

        Object selectedValue = showOptionPane(parent, optionPane);
        if (options[0].equals(selectedValue)) {
            return Choice.EXIT_NOW;
        }
        if (options[1].equals(selectedValue)) {
            return Choice.LATER;
        }
        return Choice.CANCEL;
    }

    static JTextArea createMessage(String message) {
        JTextArea textArea = new JTextArea(StringUtils.defaultString(message));
        textArea.setColumns(MESSAGE_COLUMNS);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setOpaque(false);
        textArea.setBorder(null);

        Font messageFont = UIManager.getFont("OptionPane.messageFont");
        textArea.setFont(messageFont == null ? UIManager.getFont("Label.font") : messageFont);
        Color messageForeground = UIManager.getColor("OptionPane.messageForeground");
        textArea.setForeground(messageForeground == null ? UIManager.getColor("Label.foreground") : messageForeground);

        int preferredWidth = textArea.getFontMetrics(textArea.getFont()).charWidth('m') * MESSAGE_COLUMNS;
        textArea.setSize(new Dimension(preferredWidth, Short.MAX_VALUE));
        Dimension preferredSize = textArea.getPreferredSize();
        textArea.setPreferredSize(new Dimension(preferredWidth, preferredSize.height));
        return textArea;
    }
}
