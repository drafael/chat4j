package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.menu.MenuSectionHeaderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FontMenuStructureRebuilderTest {

    @Test
    @DisplayName("Rebuild clears stale entries, builds menu sections, and wires callbacks")
    void rebuild_whenCalled_rebuildsMenuAndWiresActions() {
        var subject = new FontMenuStructureRebuilder(
                new MenuSectionHeaderFactory(),
                new StubFontOptionsProvider(),
                () -> InputEvent.CTRL_DOWN_MASK
        );

        JMenu fontMenu = new JMenu("Font");
        fontMenu.add(new JMenuItem("stale"));

        Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily = new LinkedHashMap<>();
        appFontMenuItemsByFamily.put("stale", new JRadioButtonMenuItem("stale"));

        Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize = new LinkedHashMap<>();
        appFontSizeMenuItemsBySize.put(999, new JRadioButtonMenuItem("999"));

        Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily = new LinkedHashMap<>();
        codeFontMenuItemsByFamily.put("stale", new JRadioButtonMenuItem("stale"));

        AtomicInteger restoreCalls = new AtomicInteger();
        AtomicInteger increaseCalls = new AtomicInteger();
        AtomicInteger decreaseCalls = new AtomicInteger();
        AtomicReference<String> selectedAppFamily = new AtomicReference<>();
        AtomicReference<String> selectedCodeFamily = new AtomicReference<>();
        AtomicReference<Integer> selectedAppSize = new AtomicReference<>();

        subject.rebuild(
                fontMenu,
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                restoreCalls::incrementAndGet,
                increaseCalls::incrementAndGet,
                decreaseCalls::incrementAndGet,
                selectedAppFamily::set,
                selectedCodeFamily::set,
                selectedAppSize::set
        );

        assertThat(fontMenu.getMenuComponentCount()).isEqualTo(14);
        assertThat(((JMenuItem) fontMenu.getMenuComponent(0)).getText()).isEqualTo("Restore Font");
        assertThat(((JMenuItem) fontMenu.getMenuComponent(1)).getText()).isEqualTo("Increase Font Size");
        assertThat(((JMenuItem) fontMenu.getMenuComponent(2)).getText()).isEqualTo("Decrease Font Size");
        assertThat(fontMenu.getMenuComponent(3)).isInstanceOf(JSeparator.class);
        assertThat(((JMenuItem) fontMenu.getMenuComponent(4)).getText()).isEqualTo("UI Font Family");
        assertThat(fontMenu.getMenuComponent(7)).isInstanceOf(JSeparator.class);
        assertThat(((JMenuItem) fontMenu.getMenuComponent(8)).getText()).isEqualTo("Code Font Family (Monospaced)");
        assertThat(fontMenu.getMenuComponent(10)).isInstanceOf(JSeparator.class);
        assertThat(((JMenuItem) fontMenu.getMenuComponent(11)).getText()).isEqualTo("UI Font Size");

        assertThat(((JMenuItem) fontMenu.getMenuComponent(0)).getAccelerator())
                .isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        assertThat(((JMenuItem) fontMenu.getMenuComponent(1)).getAccelerator())
                .isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        assertThat(((JMenuItem) fontMenu.getMenuComponent(2)).getAccelerator())
                .isEqualTo(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));

        assertThat(appFontMenuItemsByFamily).containsOnlyKeys("System Default", "Inter");
        assertThat(codeFontMenuItemsByFamily).containsOnlyKeys("Monospaced");
        assertThat(appFontSizeMenuItemsBySize).containsOnlyKeys(13, 14);

        ((JMenuItem) fontMenu.getMenuComponent(0)).doClick();
        ((JMenuItem) fontMenu.getMenuComponent(1)).doClick();
        ((JMenuItem) fontMenu.getMenuComponent(2)).doClick();
        appFontMenuItemsByFamily.get("Inter").doClick();
        codeFontMenuItemsByFamily.get("Monospaced").doClick();
        appFontSizeMenuItemsBySize.get(14).doClick();

        assertThat(restoreCalls.get()).isEqualTo(1);
        assertThat(increaseCalls.get()).isEqualTo(1);
        assertThat(decreaseCalls.get()).isEqualTo(1);
        assertThat(selectedAppFamily.get()).isEqualTo("Inter");
        assertThat(selectedCodeFamily.get()).isEqualTo("Monospaced");
        assertThat(selectedAppSize.get()).isEqualTo(14);
    }

    private static class StubFontOptionsProvider implements FontMenuStructureRebuilder.FontOptionsProvider {

        @Override
        public String[] appFontOptions() {
            return new String[]{"System Default", "Inter"};
        }

        @Override
        public String[] codeFontOptions() {
            return new String[]{"Monospaced"};
        }

        @Override
        public int[] appFontSizeOptions() {
            return new int[]{13, 14};
        }
    }
}
