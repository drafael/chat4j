package com.github.drafael.chat4j.chat.webview;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public record WebViewRuntimeStatus(
        @NonNull WebViewEngine configuredEngine,
        @NonNull WebViewEngine activeEngine,
        boolean swingWebViewAvailable,
        String swingWebViewMode,
        boolean jcefAvailable,
        String jcefMode,
        String fallbackReason
) {

    public static WebViewRuntimeStatus jEditorPaneDefault() {
        return new WebViewRuntimeStatus(
                WebViewEngine.JEDITOR_PANE,
                WebViewEngine.JEDITOR_PANE,
                false,
                "Not checked",
                false,
                "Not checked",
                ""
        );
    }

    public boolean hasFallback() {
        return configuredEngine != activeEngine && StringUtils.isNotBlank(fallbackReason);
    }

    @Override
    public String toString() {
        return "WebViewRuntimeStatus[configuredEngine=%s, activeEngine=%s, swingWebViewAvailable=%s, swingWebViewMode=%s, jcefAvailable=%s, jcefMode=%s, fallbackReason=<masked>]"
                .formatted(configuredEngine, activeEngine, swingWebViewAvailable, swingWebViewMode, jcefAvailable, jcefMode);
    }
}
