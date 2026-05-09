package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.menu.MenuSectionHeaderFactory;
import lombok.NonNull;

import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class FontMenuStructureRebuilder {

    private static final String RESTORE_FONT = "Restore Font";
    private static final String INCREASE_FONT_SIZE = "Increase Font Size";
    private static final String DECREASE_FONT_SIZE = "Decrease Font Size";
    private static final String UI_FONT_FAMILY = "UI Font Family";
    private static final String CODE_FONT_FAMILY = "Code Font Family (Monospaced)";
    private static final String UI_FONT_SIZE = "UI Font Size";

    private final MenuSectionHeaderFactory menuSectionHeaderFactory;
    private final FontOptionsProvider fontOptionsProvider;
    private final ShortcutMaskResolver shortcutMaskResolver;

    public FontMenuStructureRebuilder(MenuSectionHeaderFactory menuSectionHeaderFactory) {
        this(
                menuSectionHeaderFactory,
                new AppearancePanelFontOptionsProvider(),
                () -> Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
        );
    }

    FontMenuStructureRebuilder(
            @NonNull MenuSectionHeaderFactory menuSectionHeaderFactory,
            @NonNull FontOptionsProvider fontOptionsProvider,
            @NonNull ShortcutMaskResolver shortcutMaskResolver
    ) {
        this.menuSectionHeaderFactory = menuSectionHeaderFactory;
        this.fontOptionsProvider = fontOptionsProvider;
        this.shortcutMaskResolver = shortcutMaskResolver;
    }

    public void rebuild(
            @NonNull JMenu fontMenu,
            @NonNull Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
            @NonNull Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
            @NonNull Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
            @NonNull Runnable onRestoreAppFont,
            @NonNull Runnable onIncreaseAppFontSize,
            @NonNull Runnable onDecreaseAppFontSize,
            @NonNull Consumer<String> onAppFontFamilySelected,
            @NonNull Consumer<String> onCodeFontFamilySelected,
            @NonNull IntConsumer onAppFontSizeSelected
    ) {

        fontMenu.removeAll();
        appFontMenuItemsByFamily.clear();
        appFontSizeMenuItemsBySize.clear();
        codeFontMenuItemsByFamily.clear();

        int menuShortcut = shortcutMaskResolver.resolveShortcutMask();

        JMenuItem restoreFontItem = new JMenuItem(RESTORE_FONT);
        restoreFontItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0, menuShortcut | InputEvent.ALT_DOWN_MASK));
        restoreFontItem.addActionListener(e -> onRestoreAppFont.run());
        fontMenu.add(restoreFontItem);

        JMenuItem increaseFontItem = new JMenuItem(INCREASE_FONT_SIZE);
        increaseFontItem.setAccelerator(
                KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, menuShortcut | InputEvent.ALT_DOWN_MASK)
        );
        increaseFontItem.addActionListener(e -> onIncreaseAppFontSize.run());
        fontMenu.add(increaseFontItem);

        JMenuItem decreaseFontItem = new JMenuItem(DECREASE_FONT_SIZE);
        decreaseFontItem.setAccelerator(
                KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menuShortcut | InputEvent.ALT_DOWN_MASK)
        );
        decreaseFontItem.addActionListener(e -> onDecreaseAppFontSize.run());
        fontMenu.add(decreaseFontItem);

        fontMenu.addSeparator();

        menuSectionHeaderFactory.addTo(fontMenu, UI_FONT_FAMILY);
        ButtonGroup appFontGroup = new ButtonGroup();
        for (String fontFamily : fontOptionsProvider.appFontOptions()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(fontFamily);
            item.addActionListener(e -> onAppFontFamilySelected.accept(fontFamily));
            appFontGroup.add(item);
            fontMenu.add(item);
            appFontMenuItemsByFamily.put(fontFamily, item);
        }

        fontMenu.addSeparator();

        menuSectionHeaderFactory.addTo(fontMenu, CODE_FONT_FAMILY);
        ButtonGroup codeFontGroup = new ButtonGroup();
        for (String fontFamily : fontOptionsProvider.codeFontOptions()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(fontFamily);
            item.addActionListener(e -> onCodeFontFamilySelected.accept(fontFamily));
            codeFontGroup.add(item);
            fontMenu.add(item);
            codeFontMenuItemsByFamily.put(fontFamily, item);
        }

        fontMenu.addSeparator();

        menuSectionHeaderFactory.addTo(fontMenu, UI_FONT_SIZE);
        ButtonGroup appFontSizeGroup = new ButtonGroup();
        for (int fontSize : fontOptionsProvider.appFontSizeOptions()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(String.valueOf(fontSize));
            item.addActionListener(e -> onAppFontSizeSelected.accept(fontSize));
            appFontSizeGroup.add(item);
            fontMenu.add(item);
            appFontSizeMenuItemsBySize.put(fontSize, item);
        }
    }

    interface FontOptionsProvider {
        String[] appFontOptions();

        String[] codeFontOptions();

        int[] appFontSizeOptions();
    }

    private static class AppearancePanelFontOptionsProvider implements FontOptionsProvider {

        @Override
        public String[] appFontOptions() {
            return AppearancePanel.appFontOptions();
        }

        @Override
        public String[] codeFontOptions() {
            return AppearancePanel.codeFontOptions();
        }

        @Override
        public int[] appFontSizeOptions() {
            return AppearancePanel.appFontSizeOptions();
        }
    }

    @FunctionalInterface
    interface ShortcutMaskResolver {
        int resolveShortcutMask();
    }
}
