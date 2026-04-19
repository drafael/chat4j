package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MenuSectionHeaderFactoryTest {

    private final MenuSectionHeaderFactory subject = new MenuSectionHeaderFactory();

    @Test
    @DisplayName("Create returns disabled menu header with provided text")
    void create_whenCalled_returnsDisabledHeader() {
        JMenuItem header = subject.create("Section");

        assertThat(header.getText()).isEqualTo("Section");
        assertThat(header.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Add to appends created header to menu")
    void addTo_whenCalled_addsHeaderToMenu() {
        JMenu menu = new JMenu("Root");

        subject.addTo(menu, "Section");

        assertThat(menu.getItemCount()).isEqualTo(1);
        assertThat(menu.getItem(0).getText()).isEqualTo("Section");
        assertThat(menu.getItem(0).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Create rejects blank text")
    void create_whenTextBlank_throwsException() {
        assertThatThrownBy(() -> subject.create("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text must not be blank");
    }
}
