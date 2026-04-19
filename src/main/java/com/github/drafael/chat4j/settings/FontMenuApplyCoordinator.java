package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.util.WindowUiRefreshSupport;
import org.apache.commons.lang3.Validate;

import java.util.Set;

public class FontMenuApplyCoordinator {

    private final FontSelectionNormalizer fontSelectionNormalizer;
    private final FontPreviewApplier fontPreviewApplier;
    private final FontSettingsPersister fontSettingsPersister;
    private final WindowRefresher windowRefresher;

    public FontMenuApplyCoordinator(
            FontSelectionNormalizer fontSelectionNormalizer,
            FontPreviewApplier fontPreviewApplier,
            FontSettingsPersister fontSettingsPersister
    ) {
        this(
                fontSelectionNormalizer,
                fontPreviewApplier,
                fontSettingsPersister,
                WindowUiRefreshSupport::refreshAllWindows
        );
    }

    FontMenuApplyCoordinator(
            FontSelectionNormalizer fontSelectionNormalizer,
            FontPreviewApplier fontPreviewApplier,
            FontSettingsPersister fontSettingsPersister,
            WindowRefresher windowRefresher
    ) {
        this.fontSelectionNormalizer = Validate.notNull(
                fontSelectionNormalizer,
                "fontSelectionNormalizer must not be null"
        );
        this.fontPreviewApplier = Validate.notNull(fontPreviewApplier, "fontPreviewApplier must not be null");
        this.fontSettingsPersister = Validate.notNull(
                fontSettingsPersister,
                "fontSettingsPersister must not be null"
        );
        this.windowRefresher = Validate.notNull(windowRefresher, "windowRefresher must not be null");
    }

    public ApplyResult applyAppFontSelection(
            String appFontFamily,
            int appFontSize,
            Set<String> availableAppFontFamilies,
            Runnable onSyncFontMenuSelection
    ) {
        Validate.notNull(availableAppFontFamilies, "availableAppFontFamilies must not be null");
        Validate.notNull(onSyncFontMenuSelection, "onSyncFontMenuSelection must not be null");

        FontSelectionNormalizer.AppFontSelection appFontSelection = fontSelectionNormalizer.normalizeAppSelection(
                appFontFamily,
                appFontSize,
                availableAppFontFamilies
        );

        fontPreviewApplier.applyAppFont(appFontSelection.family(), appFontSelection.size());
        windowRefresher.refreshAllWindows();

        String errorMessage = null;
        try {
            fontSettingsPersister.persistAppFontSelection(appFontSelection.family(), appFontSelection.size());
        } catch (Exception e) {
            errorMessage = e.getMessage();
        }

        onSyncFontMenuSelection.run();
        return errorMessage == null
                ? ApplyResult.successResult()
                : ApplyResult.failureResult(errorMessage);
    }

    public ApplyResult applyCodeFontSelection(
            String requestedCodeFontFamily,
            Set<String> availableCodeFontFamilies,
            Runnable onSyncFontMenuSelection
    ) {
        Validate.notNull(availableCodeFontFamilies, "availableCodeFontFamilies must not be null");
        Validate.notNull(onSyncFontMenuSelection, "onSyncFontMenuSelection must not be null");

        String normalizedFamily = fontSelectionNormalizer.normalizeCodeFamily(
                requestedCodeFontFamily,
                availableCodeFontFamilies
        );

        fontPreviewApplier.applyCodeFont(normalizedFamily);
        windowRefresher.refreshAllWindows();

        String errorMessage = null;
        try {
            fontSettingsPersister.persistCodeFontFamily(normalizedFamily);
        } catch (Exception e) {
            errorMessage = e.getMessage();
        }

        onSyncFontMenuSelection.run();
        return errorMessage == null
                ? ApplyResult.successResult()
                : ApplyResult.failureResult(errorMessage);
    }

    public record ApplyResult(boolean success, String errorMessage) {

        static ApplyResult successResult() {
            return new ApplyResult(true, null);
        }

        static ApplyResult failureResult(String errorMessage) {
            return new ApplyResult(false, errorMessage);
        }
    }

    @FunctionalInterface
    interface WindowRefresher {
        void refreshAllWindows();
    }
}
