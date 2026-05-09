package com.github.drafael.chat4j.settings;


import lombok.NonNull;
import javax.swing.JRadioButtonMenuItem;
import java.util.Map;
import java.util.Objects;

public class FontMenuSelectionSynchronizer {

    public FontMenuSelectionState syncSelection(
            @NonNull Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
            @NonNull Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
            @NonNull Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
            @NonNull FontSettingsResolver.FontMenuSelection currentSelection,
            FontMenuSelectionState previousSelection,
            boolean fontMenuBuilt
    ) {

        FontMenuSelectionState effectivePreviousSelection = previousSelection == null
                ? new FontMenuSelectionState(null, null, null)
                : previousSelection;

        if (!fontMenuBuilt) {
            return effectivePreviousSelection;
        }

        boolean selectionUnchanged = Objects.equals(currentSelection.appFontFamily(), effectivePreviousSelection.appFontFamily())
                && Objects.equals(currentSelection.appFontSize(), effectivePreviousSelection.appFontSize())
                && Objects.equals(currentSelection.codeFontFamily(), effectivePreviousSelection.codeFontFamily());
        if (selectionUnchanged) {
            return effectivePreviousSelection;
        }

        if (effectivePreviousSelection.appFontFamily() != null) {
            JRadioButtonMenuItem previousAppFont = appFontMenuItemsByFamily.get(effectivePreviousSelection.appFontFamily());
            if (previousAppFont != null) {
                previousAppFont.setSelected(false);
            }
        }

        if (effectivePreviousSelection.appFontSize() != null) {
            JRadioButtonMenuItem previousAppFontSize = appFontSizeMenuItemsBySize.get(effectivePreviousSelection.appFontSize());
            if (previousAppFontSize != null) {
                previousAppFontSize.setSelected(false);
            }
        }

        if (effectivePreviousSelection.codeFontFamily() != null) {
            JRadioButtonMenuItem previousCodeFont = codeFontMenuItemsByFamily.get(effectivePreviousSelection.codeFontFamily());
            if (previousCodeFont != null) {
                previousCodeFont.setSelected(false);
            }
        }

        JRadioButtonMenuItem currentAppFont = appFontMenuItemsByFamily.get(currentSelection.appFontFamily());
        if (currentAppFont != null) {
            currentAppFont.setSelected(true);
        }

        JRadioButtonMenuItem currentAppFontSize = appFontSizeMenuItemsBySize.get(currentSelection.appFontSize());
        if (currentAppFontSize != null) {
            currentAppFontSize.setSelected(true);
        }

        JRadioButtonMenuItem currentCodeFont = codeFontMenuItemsByFamily.get(currentSelection.codeFontFamily());
        if (currentCodeFont != null) {
            currentCodeFont.setSelected(true);
        }

        return new FontMenuSelectionState(
                currentSelection.appFontFamily(),
                currentSelection.appFontSize(),
                currentSelection.codeFontFamily()
        );
    }

    public record FontMenuSelectionState(
            String appFontFamily,
            Integer appFontSize,
            String codeFontFamily
    ) {
    }
}
