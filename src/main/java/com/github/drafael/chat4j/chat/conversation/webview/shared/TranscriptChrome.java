package com.github.drafael.chat4j.chat.conversation.webview.shared;

import java.awt.Color;

public record TranscriptChrome(
        Color panelBackground,
        String background,
        String bubbleBackground,
        String borderColor,
        String menuBackground,
        String buttonBackground,
        String buttonBorder,
        String sourceCitationBackground,
        String sourceCitationBorder,
        String sourceCitationText,
        String sourceCitationHoverBackground,
        String jumpRing,
        String jumpRingTrack,
        String hoverBackground,
        String hoverForeground,
        String iconColorValue,
        String scrollbarTrack,
        String scrollbarThumb,
        String scrollbarHoverThumb,
        int baseBodyFontSize,
        int bodyFontSize,
        int codeFontSize,
        int tableFontSize,
        int languageFontSize,
        String syntaxHighlightCss
) {
    @Override
    public String toString() {
        return "TranscriptChrome[panelBackground=%s, bodyFontSize=%d, codeFontSize=%d]"
                .formatted(panelBackground, bodyFontSize, codeFontSize);
    }
}
