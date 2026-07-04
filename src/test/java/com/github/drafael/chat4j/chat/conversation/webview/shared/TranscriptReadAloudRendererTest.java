package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptReadAloudRendererTest {

    @Test
    @DisplayName("Assistant transcript rows include Read aloud only when available")
    void renderEntriesHtml_readAloudAvailable_addsAssistantActionOnly() {
        var entries = List.of(
                ConversationEntry.message(Role.ASSISTANT, "Hello", 0),
                ConversationEntry.message(Role.USER, "Hi", 1)
        );
        var snapshot = TranscriptRenderSupport.snapshot(entries, RenderMode.PREVIEW, false, false, true);

        String html = new TranscriptEntryRenderer().renderEntriesHtml(snapshot);

        assertThat(html).containsOnlyOnce("data-action=\"read-aloud\"");
        assertThat(html).contains("<section class=\"row assistant\"");
    }

    @Test
    @DisplayName("Active Read aloud action renders as Stop")
    void renderEntriesHtml_readAloudActive_rendersStopAction() {
        var entries = List.of(ConversationEntry.message(Role.ASSISTANT, "Hello", 0));
        var snapshot = TranscriptRenderSupport.snapshot(entries, RenderMode.PREVIEW, false, false, true, 0);

        String html = new TranscriptEntryRenderer().renderEntriesHtml(snapshot);

        assertThat(html)
                .contains("title=\"Stop\"")
                .contains("data-read-aloud-active=\"true\"")
                .contains("icon player-stop");
    }

    @Test
    @DisplayName("Read aloud action is absent when TTS is unavailable")
    void renderEntriesHtml_readAloudUnavailable_omitsAction() {
        var entries = List.of(ConversationEntry.message(Role.ASSISTANT, "Hello", 0));
        var snapshot = TranscriptRenderSupport.snapshot(entries, RenderMode.PREVIEW, false, false, false);

        String html = new TranscriptEntryRenderer().renderEntriesHtml(snapshot);

        assertThat(html).doesNotContain("data-action=\"read-aloud\"");
    }
}
