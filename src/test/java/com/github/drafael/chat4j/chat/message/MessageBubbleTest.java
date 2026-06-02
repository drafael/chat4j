package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.chat.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageBubbleTest {

    @Test
    @DisplayName("User bubble renders SKILL and FALLBACK markers as badge labels")
    void setText_whenUserMessageContainsMarkers_rendersBadges() {
        MessageBubble subject = new MessageBubble(Role.USER);
        subject.setText("[SKILL] brainstorm\n[FALLBACK] Files use text references\nBuild me a plan");

        String html = readEditorText(subject);

        assertThat(html).contains("SKILL");
        assertThat(html).contains("ATTACHMENT");
        assertThat(html).contains("badge skill");
        assertThat(html).contains("badge fallback");
        assertThat(html).doesNotContain("[SKILL]");
        assertThat(html).doesNotContain("[FALLBACK]");
    }

    @Test
    @DisplayName("User bubble rerenders when render mode changes")
    void setRenderMode_whenUserMessageRendered_updatesContent() {
        MessageBubble subject = new MessageBubble(Role.USER);
        subject.setText("**bold**");

        subject.setRenderMode(RenderMode.MARKDOWN);
        String html = readEditorText(subject);

        assertThat(html).contains("**bold**");
        assertThat(html).doesNotContain("<b>bold</b>");
    }

    private String readEditorText(MessageBubble bubble) {
        return bubble.contentHtmlSnapshot();
    }
}
