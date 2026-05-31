package com.github.drafael.chat4j.chat.message;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public record ChatWebViewRuntimeStatus(
        @NonNull ChatWebViewEngine configuredEngine,
        @NonNull ChatWebViewEngine activeEngine,
        boolean swingWebViewAvailable,
        String swingWebViewMode,
        String fallbackReason
) {

    public static ChatWebViewRuntimeStatus jEditorPaneDefault() {
        return new ChatWebViewRuntimeStatus(
                ChatWebViewEngine.JEDITOR_PANE,
                ChatWebViewEngine.JEDITOR_PANE,
                false,
                "Not checked",
                ""
        );
    }

    public boolean hasFallback() {
        return configuredEngine != activeEngine && StringUtils.isNotBlank(fallbackReason);
    }
}
