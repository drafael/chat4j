package com.github.drafael.chat4j.settings;

import lombok.NonNull;

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

    FontMenuStructureRebuildCoordinator(@NonNull RebuildAction rebuildAction) {
        this.rebuildAction = rebuildAction;
    }

    public RebuildState rebuild(
            JMenu fontMenu,
            @NonNull Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
            @NonNull Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
            @NonNull Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
            @NonNull Runnable onRestoreAppFont,
            @NonNull Runnable onIncreaseAppFontSize,
            @NonNull Runnable onDecreaseAppFontSize,
            @NonNull Consumer<String> onAppFontFamilySelected,
            @NonNull Consumer<String> onCodeFontFamilySelected,
            @NonNull IntConsumer onAppFontSizeSelected,
            boolean currentFontMenuBuilt,
            String currentLastMenuSelectedAppFontFamily,
            Integer currentLastMenuSelectedAppFontSize,
            String currentLastMenuSelectedCodeFontFamily
    ) {

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
