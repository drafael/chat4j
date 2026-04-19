package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTitleDeriverTest {

    private final ConversationTitleDeriver subject = new ConversationTitleDeriver();

    @Test
    @DisplayName("Derive returns fallback title when first message is null")
    void derive_whenFirstMessageIsNull_returnsFallbackTitle() {
        String title = subject.derive(null);

        assertThat(title).isEqualTo("New chat");
    }

    @Test
    @DisplayName("Derive skips activated-skills directive and uses first user text")
    void derive_whenSkillDirectiveAppearsFirst_usesFirstNonDirectiveTextPart() {
        var message = new Message(
                Role.USER,
                List.of(new TextPart("Activated skills: brainstorm"), new TextPart("  Build API migration plan  ")),
                Instant.now()
        );

        String title = subject.derive(message);

        assertThat(title).isEqualTo("Build API migration plan");
    }

    @Test
    @DisplayName("Derive returns fallback title when extracted candidate is blank")
    void derive_whenExtractedTitleIsBlank_returnsFallbackTitle() {
        var message = new Message(Role.USER, "   ", Instant.now());

        String title = subject.derive(message);

        assertThat(title).isEqualTo("New chat");
    }

    @Test
    @DisplayName("Derive truncates long titles to 50 characters plus ellipsis")
    void derive_whenTitleExceedsLimit_truncatesToMaxLengthWithEllipsis() {
        String longTitle = "012345678901234567890123456789012345678901234567890123456789";
        var message = new Message(Role.USER, longTitle, Instant.now());

        String title = subject.derive(message);

        assertThat(title).isEqualTo("01234567890123456789012345678901234567890123456789...");
    }
}
