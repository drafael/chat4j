package com.github.drafael.chat4j.settings;

import javax.swing.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RestartRequiredDialogTest {

    @Test
    @DisplayName("Restart prompt message uses a wrapping text component")
    void createMessage_whenMessageIsLong_wrapsWithoutHtmlClipping() {
        JTextArea message = RestartRequiredDialog.createMessage(
                "Chat WebView engine will switch from System WebView to Chromium Embedded Framework after you reopen Chat4J."
        );

        assertThat(message.getLineWrap()).isTrue();
        assertThat(message.getWrapStyleWord()).isTrue();
        assertThat(message.getPreferredSize().width).isLessThan(500);
        assertThat(message.getPreferredSize().height).isGreaterThan(message.getFontMetrics(message.getFont()).getHeight());
        assertThat(message.getText()).contains("Chromium Embedded Framework after you reopen Chat4J.");
    }
}
