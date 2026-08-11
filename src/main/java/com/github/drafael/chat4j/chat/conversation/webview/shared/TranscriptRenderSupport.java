package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.render.CodeFontResolver;
import com.github.drafael.chat4j.chat.render.MarkdownPaletteResolver;
import com.github.drafael.chat4j.chat.render.Palette;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.util.Fonts;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static java.util.Collections.emptySet;

public final class TranscriptRenderSupport {

    private TranscriptRenderSupport() {
    }

    public static TranscriptRenderSnapshot snapshot(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean jumpButtonVisible
    ) {
        return snapshot(entries, renderMode, dark, jumpButtonVisible, false);
    }

    public static TranscriptRenderSnapshot snapshot(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean jumpButtonVisible,
            boolean readAloudAvailable
    ) {
        return snapshot(entries, renderMode, dark, jumpButtonVisible, readAloudAvailable, emptySet(), -1);
    }

    public static TranscriptRenderSnapshot snapshot(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean jumpButtonVisible,
            boolean readAloudAvailable,
            int activeReadAloudMessageIndex
    ) {
        return snapshot(
                entries,
                renderMode,
                dark,
                jumpButtonVisible,
                readAloudAvailable,
                emptySet(),
                activeReadAloudMessageIndex
        );
    }

    public static TranscriptRenderSnapshot snapshot(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean jumpButtonVisible,
            boolean readAloudAvailable,
            Set<Integer> readAloudMessageIndexes,
            int activeReadAloudMessageIndex
    ) {
        Palette palette = MarkdownPaletteResolver.resolve(dark);
        int codeFontSize = CodeFontResolver.resolveCodeFontSize();
        return TranscriptRenderSnapshot.builder()
                .entries(entries)
                .renderMode(renderMode == null ? RenderMode.PREVIEW : renderMode)
                .dark(dark)
                .jumpButtonVisible(jumpButtonVisible)
                .palette(palette)
                .chrome(TranscriptDocumentRenderer.documentChrome(dark, palette))
                .codeFontSize(codeFontSize)
                .fontScaleFactor(Fonts.scale(Fonts.SIZE_BODY) / (float) Fonts.SIZE_BODY)
                .readAloudAvailable(readAloudAvailable)
                .readAloudMessageIndexes(readAloudMessageIndexes)
                .activeReadAloudMessageIndex(activeReadAloudMessageIndex)
                .build();
    }

    public static <T> T withSnapshotFonts(TranscriptRenderSnapshot snapshot, Supplier<T> action) {
        return Fonts.withScaleFactor(
                snapshot.fontScaleFactor(),
                () -> CodeFontResolver.withResolvedCodeFontSize(snapshot.codeFontSize(), action)
        );
    }
}
