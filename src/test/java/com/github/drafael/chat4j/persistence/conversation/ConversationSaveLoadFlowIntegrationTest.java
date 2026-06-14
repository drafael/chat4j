package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.chat.NewChatCoordinator;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSaveLoadFlowIntegrationTest {

    @Test
    @DisplayName("New-chat flow saves conversation without render-mode state")
    void newChatFlow_whenStarted_savesCurrentConversationWithoutRenderMode() {
        var flowEvents = new ArrayList<String>();
        var currentConversationId = new AtomicReference<UUID>();

        new NewChatCoordinator().start(
                () -> flowEvents.add("save"),
                () -> {
                    flowEvents.add("clear-current");
                    currentConversationId.set(null);
                },
                () -> flowEvents.add("clear-active"),
                () -> flowEvents.add("clear-selection"),
                () -> flowEvents.add("clear-view"),
                () -> flowEvents.add("reset-runtime"),
                () -> flowEvents.add("focus")
        );

        assertThat(currentConversationId.get()).isNull();
        assertThat(flowEvents).containsExactly(
                "save",
                "clear-current",
                "clear-active",
                "clear-selection",
                "clear-view",
                "reset-runtime",
                "focus"
        );
    }

    @Test
    @DisplayName("Save/load flow does not apply render mode from conversations")
    void loadFlow_whenConversationLoaded_appliesMessagesAndModelOnly() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var loadedMessages = new AtomicReference<List<Message>>();
        var selectedModel = new AtomicReference<String>();
        var selectedConversation = new AtomicReference<UUID>();
        var saveCoordinator = new CurrentConversationSaveCoordinator(
                new ConversationTitleDeriver(),
                (title, provider, model) -> conversationId,
                (id, history) -> history.size(),
                (id, agentModeEnabled, agentProjectRoot) -> {},
                (id, reasoningLevel) -> {}
        );

        var saveResult = saveCoordinator.save(
                null,
                List.of(Message.user("hello")),
                "OpenAI:gpt-4o",
                ReasoningLevel.OFF,
                false,
                null
        );
        var loadApplyCoordinator = new ConversationLoadApplyCoordinator();
        loadApplyCoordinator.apply(
                ConversationLoadResultPlanner.LoadedConversationPlan.applyPlan(
                        saveResult.conversationId(),
                        List.of(Message.user("hello")),
                        1,
                        "OpenAI:gpt-4o"
                ),
                loadedMessages::set,
                (id, count) -> {},
                selectedModel::set,
                selectedConversation::set
        );

        assertThat(loadedMessages.get()).extracting(Message::content).containsExactly("hello");
        assertThat(selectedModel.get()).isEqualTo("OpenAI:gpt-4o");
        assertThat(selectedConversation.get()).isEqualTo(conversationId);
    }
}
