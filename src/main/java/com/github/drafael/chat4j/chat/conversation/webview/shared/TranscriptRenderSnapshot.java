package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.render.Palette;
import com.github.drafael.chat4j.chat.render.RenderMode;

import java.util.List;

public record TranscriptRenderSnapshot(
        List<ConversationEntry> entries,
        RenderMode renderMode,
        boolean dark,
        boolean jumpButtonVisible,
        Palette palette,
        TranscriptChrome chrome,
        int codeFontSize,
        float fontScaleFactor
) {
    @Override
    public String toString() {
        return "TranscriptRenderSnapshot[entries=%d, renderMode=%s, dark=%s, jumpButtonVisible=%s, codeFontSize=%d, fontScaleFactor=%s]"
                .formatted(entries.size(), renderMode, dark, jumpButtonVisible, codeFontSize, fontScaleFactor);
    }
}
