package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicChatCompletionClientTest {

    @TempDir
    Path tempDir;

    private AnthropicChatCompletionClient subject;

    @BeforeEach
    void setUp() throws Exception {
        subject = new AnthropicChatCompletionClient(
                new ProviderAttachmentSupport(tempDir)
        );
    }

    @Test
    @DisplayName("Every system part is retained as an ordered Anthropic system text block")
    void systemBlocks_whenHistoryContainsMultipleSystemMessages_preservesAllPartsInOrder() {
        var firstAttachment = unavailableAttachment("first.txt");
        var secondAttachment = unavailableAttachment("second.txt");
        List<Message> history = List.of(
                new Message(Role.SYSTEM, List.of(
                        new TextPart("first text"),
                        new FilePart(firstAttachment)
                ), Instant.now()),
                new Message(Role.USER, "question", Instant.now()),
                new Message(Role.SYSTEM, List.of(
                        new FilePart(secondAttachment),
                        new TextPart("last text")
                ), Instant.now())
        );
        AttachmentProjectionPlan plan = AttachmentProjectionPlan.create(
                history,
                newAuthority(),
                AttachmentProjectionPlan.anthropic(true, true),
                () -> false
        );

        var blocks = subject.systemBlocks(plan);

        assertThat(blocks).extracting(block -> block.text()).containsExactly(
                "first text",
                "[File attached: first.txt]",
                "[File attached: second.txt]",
                "last text"
        );
    }

    @Test
    @DisplayName("Thinking budgets leave room for answer tokens at every supported reasoning level")
    void completionTokenLimit_whenReasoningIsEnabled_exceedsThinkingBudget() {
        assertThat(subject.completionTokenLimit(ReasoningLevel.HIGH, true)).isEqualTo(8192);
        assertThat(subject.completionTokenLimit(ReasoningLevel.EXTRA_HIGH, true)).isEqualTo(12288);
        assertThat(subject.completionTokenLimit(ReasoningLevel.EXTRA_HIGH, false)).isEqualTo(4096);
    }

    private AttachmentRef unavailableAttachment(String name) {
        return new AttachmentRef(null, "/unavailable", name, "text/plain", 1L, "sha");
    }

    private ProviderAttachmentSupport newAuthority() {
        try {
            return new ProviderAttachmentSupport(tempDir);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
