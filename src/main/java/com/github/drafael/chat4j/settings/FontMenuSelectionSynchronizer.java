package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

import javax.swing.JRadioButtonMenuItem;
import java.util.Map;
import java.util.Objects;

public class FontMenuSelectionSynchronizer {

    public FontMenuSelectionState syncSelection(
            Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily,
            Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize,
            Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily,
            FontSettingsResolver.FontMenuSelection currentSelection,
            FontMenuSelectionState previousSelection,
            boolean fontMenuBuilt
    ) {
        Validate.notNull(appFontMenuItemsByFamily, "appFontMenuItemsByFamily must not be null");
        Validate.notNull(appFontSizeMenuItemsBySize, "appFontSizeMenuItemsBySize must not be null");
        Validate.notNull(codeFontMenuItemsByFamily, "codeFontMenuItemsByFamily must not be null");
        Validate.notNull(currentSelection, "currentSelection must not be null");

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
