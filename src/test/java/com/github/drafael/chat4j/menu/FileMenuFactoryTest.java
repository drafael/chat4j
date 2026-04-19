package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileMenuFactoryTest {

    @Test
    @DisplayName("Create builds file menu with new chat item accelerator and callback")
    void create_whenCalled_buildsFileMenuWithNewChatItem() {
        var subject = new FileMenuFactory();
        var newChatCalls = new AtomicInteger();

        var fileMenu = subject.create(KeyEvent.CTRL_DOWN_MASK, newChatCalls::incrementAndGet);

        assertThat(fileMenu.getText()).isEqualTo("File");
        assertThat(fileMenu.getItemCount()).isEqualTo(1);
        assertThat(fileMenu.getItem(0).getText()).isEqualTo("New Chat");
        assertThat(fileMenu.getItem(0).getAccelerator())
                .isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK));

        fileMenu.getItem(0).doClick();
        assertThat(newChatCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Create validates callback")
    void create_whenCallbackMissing_throwsException() {
        var subject = new FileMenuFactory();

        assertThatThrownBy(() -> subject.create(KeyEvent.CTRL_DOWN_MASK, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("onNewChat must not be null");
    }
}
