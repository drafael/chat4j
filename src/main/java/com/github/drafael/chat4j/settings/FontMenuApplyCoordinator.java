package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.util.WindowUiRefreshSupport;
import lombok.NonNull;

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
            @NonNull FontSelectionNormalizer fontSelectionNormalizer,
            @NonNull FontPreviewApplier fontPreviewApplier,
            @NonNull FontSettingsPersister fontSettingsPersister,
            @NonNull WindowRefresher windowRefresher
    ) {
        this.fontSelectionNormalizer = fontSelectionNormalizer;
        this.fontPreviewApplier = fontPreviewApplier;
        this.fontSettingsPersister = fontSettingsPersister;
        this.windowRefresher = windowRefresher;
    }

    public ApplyResult applyAppFontSelection(
            String appFontFamily,
            int appFontSize,
            @NonNull Set<String> availableAppFontFamilies,
            @NonNull Runnable onSyncFontMenuSelection
    ) {

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
            @NonNull Set<String> availableCodeFontFamilies,
            @NonNull Runnable onSyncFontMenuSelection
    ) {

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
