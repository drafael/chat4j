package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JOptionPane;

import static org.assertj.core.api.Assertions.assertThat;

class ClearChatConfirmationDialogTest {

    private final ClearChatConfirmationDialog subject = new ClearChatConfirmationDialog();

    @Test
    @DisplayName("Confirmation pane uses cancel-first clear chat options")
    void createOptionPane_whenBuilt_usesCancelFirstClearChatOptions() {
        JOptionPane pane = subject.createOptionPane();

        assertThat(pane.getMessageType()).isEqualTo(JOptionPane.WARNING_MESSAGE);
        assertThat(pane.getOptionType()).isEqualTo(JOptionPane.YES_NO_OPTION);
        assertThat(pane.getInitialValue()).isEqualTo(ClearChatConfirmationDialog.CANCEL_OPTION);
        assertThat(pane.getOptions()).containsExactly(
                ClearChatConfirmationDialog.CANCEL_OPTION,
                ClearChatConfirmationDialog.CONFIRM_OPTION
        );
    }
}
