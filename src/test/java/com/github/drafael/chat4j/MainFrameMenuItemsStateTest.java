package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameMenuItemsStateTest {

    @Test
    @DisplayName("Menu items state starts with empty maps")
    void defaults_whenConstructed_startWithEmptyMaps() {
        var subject = new MainFrameMenuItemsState();

        assertThat(subject.modelMenuItemsByKey()).isEmpty();
        assertThat(subject.providerHeaderItemsByName()).isEmpty();
        assertThat(subject.themeMenuItemsByName()).isEmpty();
        assertThat(subject.appFontMenuItemsByFamily()).isEmpty();
        assertThat(subject.appFontSizeMenuItemsBySize()).isEmpty();
        assertThat(subject.codeFontMenuItemsByFamily()).isEmpty();
    }

    @Test
    @DisplayName("Maps remain mutable and retain inserted menu item references")
    void maps_whenMutated_retainInsertedReferences() {
        var subject = new MainFrameMenuItemsState();
        var modelItem = new JRadioButtonMenuItem("OpenAI > gpt-4.1");
        var providerHeader = new JMenuItem("OpenAI");
        var themeItem = new JRadioButtonMenuItem("GitHub");
        var appFontItem = new JRadioButtonMenuItem("Inter");
        var appFontSizeItem = new JRadioButtonMenuItem("14");
        var codeFontItem = new JRadioButtonMenuItem("JetBrains Mono");

        subject.modelMenuItemsByKey().put("OpenAI > gpt-4.1", modelItem);
        subject.providerHeaderItemsByName().put("OpenAI", providerHeader);
        subject.themeMenuItemsByName().put("GitHub", themeItem);
        subject.appFontMenuItemsByFamily().put("Inter", appFontItem);
        subject.appFontSizeMenuItemsBySize().put(14, appFontSizeItem);
        subject.codeFontMenuItemsByFamily().put("JetBrains Mono", codeFontItem);

        assertThat(subject.modelMenuItemsByKey()).containsEntry("OpenAI > gpt-4.1", modelItem);
        assertThat(subject.providerHeaderItemsByName()).containsEntry("OpenAI", providerHeader);
        assertThat(subject.themeMenuItemsByName()).containsEntry("GitHub", themeItem);
        assertThat(subject.appFontMenuItemsByFamily()).containsEntry("Inter", appFontItem);
        assertThat(subject.appFontSizeMenuItemsBySize()).containsEntry(14, appFontSizeItem);
        assertThat(subject.codeFontMenuItemsByFamily()).containsEntry("JetBrains Mono", codeFontItem);
    }
}
