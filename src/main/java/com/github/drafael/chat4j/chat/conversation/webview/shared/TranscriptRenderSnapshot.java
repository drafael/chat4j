package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.render.Palette;
import com.github.drafael.chat4j.chat.render.RenderMode;

import java.util.List;
import java.util.Set;
import lombok.Builder;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

@Builder
public record TranscriptRenderSnapshot(
        List<ConversationEntry> entries,
        RenderMode renderMode,
        boolean dark,
        boolean jumpButtonVisible,
        Palette palette,
        TranscriptChrome chrome,
        int codeFontSize,
        float fontScaleFactor,
        boolean readAloudAvailable,
        Set<Integer> readAloudMessageIndexes,
        int activeReadAloudMessageIndex
) {
    public TranscriptRenderSnapshot {
        entries = entries == null ? emptyList() : List.copyOf(entries);
        renderMode = renderMode == null ? RenderMode.PREVIEW : renderMode;
        readAloudMessageIndexes = readAloudMessageIndexes == null
                ? emptySet()
                : Set.copyOf(readAloudMessageIndexes);
    }

    public TranscriptRenderSnapshot(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean jumpButtonVisible,
            Palette palette,
            TranscriptChrome chrome,
            int codeFontSize,
            float fontScaleFactor
    ) {
        this(
                entries,
                renderMode,
                dark,
                jumpButtonVisible,
                palette,
                chrome,
                codeFontSize,
                fontScaleFactor,
                false,
                emptySet(),
                -1
        );
    }

    @Override
    public String toString() {
        return "TranscriptRenderSnapshot[entries=%d, renderMode=%s, dark=%s, jumpButtonVisible=%s, codeFontSize=%d, fontScaleFactor=%s, readAloudAvailable=%s, readAloudMessageIndexes=%d, activeReadAloudMessageIndex=%d]"
                .formatted(
                        entries.size(),
                        renderMode,
                        dark,
                        jumpButtonVisible,
                        codeFontSize,
                        fontScaleFactor,
                        readAloudAvailable,
                        readAloudMessageIndexes.size(),
                        activeReadAloudMessageIndex
                );
    }
}
