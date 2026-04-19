package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class FontMenuStructureRebuildCoordinator {

    private final RebuildAction rebuildAction;

    public FontMenuStructureRebuildCoordinator(FontMenuStructureRebuilder fontMenuStructureRebuilder) {
        this(fontMenuStructureRebuilder::rebuild);
    }

    FontMenuStructureRebuildCoordinator(RebuildAction rebuildAction) {
        this.rebuildAction = Validate.notNull(rebuildAction, "rebuildAction must not be null");
    }

    public RebuildState rebuild(
            JMenu fontMenu,
            Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
            Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
            Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
            Runnable onRestoreAppFont,
            Runnable onIncreaseAppFontSize,
            Runnable onDecreaseAppFontSize,
            Consumer<String> onAppFontFamilySelected,
            Consumer<String> onCodeFontFamilySelected,
            IntConsumer onAppFontSizeSelected,
            boolean currentFontMenuBuilt,
            String currentLastMenuSelectedAppFontFamily,
            Integer currentLastMenuSelectedAppFontSize,
            String currentLastMenuSelectedCodeFontFamily
    ) {
        Validate.notNull(appFontMenuItemsByFamily, "appFontMenuItemsByFamily must not be null");
        Validate.notNull(appFontSizeMenuItemsBySize, "appFontSizeMenuItemsBySize must not be null");
        Validate.notNull(codeFontMenuItemsByFamily, "codeFontMenuItemsByFamily must not be null");
        Validate.notNull(onRestoreAppFont, "onRestoreAppFont must not be null");
        Validate.notNull(onIncreaseAppFontSize, "onIncreaseAppFontSize must not be null");
        Validate.notNull(onDecreaseAppFontSize, "onDecreaseAppFontSize must not be null");
        Validate.notNull(onAppFontFamilySelected, "onAppFontFamilySelected must not be null");
        Validate.notNull(onCodeFontFamilySelected, "onCodeFontFamilySelected must not be null");
        Validate.notNull(onAppFontSizeSelected, "onAppFontSizeSelected must not be null");

        if (fontMenu == null) {
            return new RebuildState(
                    currentFontMenuBuilt,
                    currentLastMenuSelectedAppFontFamily,
                    currentLastMenuSelectedAppFontSize,
                    currentLastMenuSelectedCodeFontFamily
            );
        }

        rebuildAction.rebuild(
                fontMenu,
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                onRestoreAppFont,
                onIncreaseAppFontSize,
                onDecreaseAppFontSize,
                onAppFontFamilySelected,
                onCodeFontFamilySelected,
                onAppFontSizeSelected
        );
        return new RebuildState(true, null, null, null);
    }

    public record RebuildState(
            boolean fontMenuBuilt,
            String lastMenuSelectedAppFontFamily,
            Integer lastMenuSelectedAppFontSize,
            String lastMenuSelectedCodeFontFamily
    ) {
    }

    @FunctionalInterface
    interface RebuildAction {
        void rebuild(
                JMenu fontMenu,
                Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
                Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
                Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
                Runnable onRestoreAppFont,
                Runnable onIncreaseAppFontSize,
                Runnable onDecreaseAppFontSize,
                Consumer<String> onAppFontFamilySelected,
                Consumer<String> onCodeFontFamilySelected,
                IntConsumer onAppFontSizeSelected
        );
    }
}
