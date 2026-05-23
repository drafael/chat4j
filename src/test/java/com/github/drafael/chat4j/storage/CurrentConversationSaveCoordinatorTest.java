package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentConversationSaveCoordinatorTest {

    @Test
    @DisplayName("Save skips empty history")
    void save_whenHistoryEmpty_skipsPersistence() throws Exception {
        var subject = new CurrentConversationSaveCoordinator(
                new ConversationTitleDeriver(),
                (title, provider, model) -> UUID.randomUUID(),
                (conversationId, history) -> {},
                (conversationId, agentModeEnabled, agentProjectRoot) -> {},
                (conversationId, reasoningLevel) -> {}
        );
        UUID conversationId = UUID.randomUUID();

        var result = subject.save(conversationId, List.of(), "OpenAI:gpt-4o", ReasoningLevel.OFF, false, null);

        assertThat(result.saved()).isFalse();
        assertThat(result.conversationId()).isEqualTo(conversationId);
    }

    @Test
    @DisplayName("Save creates conversation and persists history without render mode")
    void save_whenNewConversation_createsConversationAndPersistsHistory() throws Exception {
        UUID createdId = UUID.randomUUID();
        var persistedHistory = new AtomicReference<List<Message>>();
        var agentSettingsPersisted = new AtomicBoolean(false);
        var reasoningPersisted = new AtomicBoolean(false);
        var subject = new CurrentConversationSaveCoordinator(
                new ConversationTitleDeriver(),
                (title, provider, model) -> createdId,
                (conversationId, history) -> persistedHistory.set(history),
                (conversationId, agentModeEnabled, agentProjectRoot) -> agentSettingsPersisted.set(true),
                (conversationId, reasoningLevel) -> reasoningPersisted.set(reasoningLevel == ReasoningLevel.OFF)
        );
        List<Message> history = List.of(Message.user("hello"));

        var result = subject.save(null, history, "OpenAI:gpt-4o", null, true, Path.of("/tmp"));

        assertThat(result.saved()).isTrue();
        assertThat(result.createdConversation()).isTrue();
        assertThat(result.conversationId()).isEqualTo(createdId);
        assertThat(persistedHistory.get()).isEqualTo(history);
        assertThat(agentSettingsPersisted).isTrue();
        assertThat(reasoningPersisted).isTrue();
    }

    @Test
    @DisplayName("Save existing conversation only persists history")
    void save_whenExistingConversation_persistsHistoryOnly() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var created = new AtomicBoolean(false);
        var persistedHistory = new AtomicReference<List<Message>>();
        var subject = new CurrentConversationSaveCoordinator(
                new ConversationTitleDeriver(),
                (title, provider, model) -> {
                    created.set(true);
                    return UUID.randomUUID();
                },
                (id, history) -> persistedHistory.set(history),
                (id, agentModeEnabled, agentProjectRoot) -> {},
                (id, reasoningLevel) -> {}
        );
        List<Message> history = List.of(Message.user("hello"));

        var result = subject.save(conversationId, history, "OpenAI:gpt-4o", ReasoningLevel.OFF, false, null);

        assertThat(result.saved()).isTrue();
        assertThat(result.createdConversation()).isFalse();
        assertThat(result.conversationId()).isEqualTo(conversationId);
        assertThat(created).isFalse();
        assertThat(persistedHistory.get()).isEqualTo(history);
    }

    @Test
    @DisplayName("Save validates history")
    void save_whenHistoryMissing_throwsException() {
        var subject = new CurrentConversationSaveCoordinator(
                new ConversationTitleDeriver(),
                (title, provider, model) -> UUID.randomUUID(),
                (conversationId, history) -> {},
                (conversationId, agentModeEnabled, agentProjectRoot) -> {},
                (conversationId, reasoningLevel) -> {}
        );

        assertThatThrownBy(() -> subject.save(null, null, null, null, false, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("history");
    }
}
