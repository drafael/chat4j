package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HelpMenuFactoryTest {

    private final HelpMenuFactory subject = new HelpMenuFactory();

    @Test
    @DisplayName("Create returns help menu with about item wired to callback")
    void create_whenCalled_returnsHelpMenuWithAboutAction() {
        AtomicInteger aboutCalls = new AtomicInteger();

        JMenu helpMenu = subject.create("Chat4J", aboutCalls::incrementAndGet);

        assertThat(helpMenu.getText()).isEqualTo("Help");
        assertThat(helpMenu.getItemCount()).isEqualTo(1);
        assertThat(helpMenu.getItem(0).getText()).isEqualTo("About Chat4J");

        helpMenu.getItem(0).doClick();
        assertThat(aboutCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Create rejects blank app title")
    void create_whenAppTitleBlank_throwsException() {
        assertThatThrownBy(() -> subject.create("  ", () -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appTitle must not be blank");
    }

    @Test
    @DisplayName("Create rejects null callback")
    void create_whenCallbackNull_throwsException() {
        assertThatThrownBy(() -> subject.create("Chat4J", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("onAbout");
    }
}
