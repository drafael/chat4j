package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class MessageBubbleTest {

    @Test
    @DisplayName("User bubble renders SKILL and FALLBACK markers as badge labels")
    void setText_whenUserMessageContainsMarkers_rendersBadges() throws Exception {
        MessageBubble subject = new MessageBubble(Role.USER);
        subject.setText("[SKILL] brainstorm\n[FALLBACK] Files use text references\nBuild me a plan");

        String html = readEditorText(subject);

        assertThat(html).contains("SKILL");
        assertThat(html).contains("FALLBACK");
        assertThat(html).contains("badge skill");
        assertThat(html).contains("badge fallback");
        assertThat(html).doesNotContain("[SKILL]");
        assertThat(html).doesNotContain("[FALLBACK]");
    }

    @Test
    @DisplayName("External link allowlist accepts http/https/mailto and rejects unsafe schemes")
    void isAllowedExternalLink_whenSchemeValidationRuns_allowsSafeSchemesOnly() {
        assertThat(MessageBubble.isAllowedExternalLink("https://example.com/path")).isTrue();
        assertThat(MessageBubble.isAllowedExternalLink("http://example.com")).isTrue();
        assertThat(MessageBubble.isAllowedExternalLink("mailto:security@example.com")).isTrue();

        assertThat(MessageBubble.isAllowedExternalLink("javascript:alert(1)")).isFalse();
        assertThat(MessageBubble.isAllowedExternalLink("file:///etc/passwd")).isFalse();
        assertThat(MessageBubble.isAllowedExternalLink("data:text/html;base64,PHNjcmlwdD4=")).isFalse();
    }

    private String readEditorText(MessageBubble bubble) throws Exception {
        Field field = MessageBubble.class.getDeclaredField("editorPane");
        field.setAccessible(true);
        JEditorPane editorPane = (JEditorPane) field.get(bubble);
        return editorPane.getText();
    }
}
