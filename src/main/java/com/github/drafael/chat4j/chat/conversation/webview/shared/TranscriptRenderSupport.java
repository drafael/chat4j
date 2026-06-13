package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.render.CodeFontResolver;
import com.github.drafael.chat4j.chat.render.MarkdownPaletteResolver;
import com.github.drafael.chat4j.chat.render.Palette;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.util.Fonts;

import java.util.List;
import java.util.function.Supplier;

public final class TranscriptRenderSupport {

    private TranscriptRenderSupport() {
    }

    public static TranscriptRenderSnapshot snapshot(
            List<ConversationEntry> entries,
            RenderMode renderMode,
            boolean dark,
            boolean jumpButtonVisible
    ) {
        Palette palette = MarkdownPaletteResolver.resolve(dark);
        int codeFontSize = CodeFontResolver.resolveCodeFontSize();
        return new TranscriptRenderSnapshot(
                entries,
                renderMode == null ? RenderMode.PREVIEW : renderMode,
                dark,
                jumpButtonVisible,
                palette,
                TranscriptDocumentRenderer.documentChrome(dark, palette),
                codeFontSize,
                Fonts.scale(Fonts.SIZE_BODY) / (float) Fonts.SIZE_BODY
        );
    }

    public static <T> T withSnapshotFonts(TranscriptRenderSnapshot snapshot, Supplier<T> action) {
        return Fonts.withScaleFactor(
                snapshot.fontScaleFactor(),
                () -> CodeFontResolver.withResolvedCodeFontSize(snapshot.codeFontSize(), action)
        );
    }
}
