package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.chat.NewChatCoordinator;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSaveLoadFlowIntegrationTest {

    @Test
    @DisplayName("New chat selects the newly created sidebar item after the first user message is saved")
    void newChat_whenFirstUserMessageIsSaved_createsAndSelectsSidebarConversation() {
        UUID createdConversationId = UUID.randomUUID();
        var currentConversationId = new AtomicReference<>(UUID.randomUUID());
        var pendingRenderMode = new AtomicReference<>(AssistantRenderMode.MARKDOWN);
        var activeConversationId = new AtomicReference<>(UUID.randomUUID());
        var selectedSidebarConversations = new ArrayList<UUID>();
        var sidebarRefreshCalls = new AtomicInteger();
        var saveFailures = new ArrayList<Exception>();
        var flowEvents = new ArrayList<String>();
        var history = List.of(Message.user("hello from a new chat"));
        var selectedModelKey = "OpenAI > gpt-4.1";
        var subject = new NewChatCoordinator();

        var saveCoordinator = new CurrentConversationSaveCoordinator(
                new ConversationTitleDeriver(),
                (title, provider, model) -> {
                    flowEvents.add("save-create-conversation");
                    return createdConversationId;
                },
                (conversationId, loadedHistory) -> flowEvents.add("save-persist-history"),
                (conversationId, mode) -> flowEvents.add("save-persist-mode"),
                (conversationId, agentModeEnabled, agentProjectRoot) -> flowEvents.add("save-persist-agent-settings"),
                (conversationId, reasoningLevel) -> flowEvents.add("save-persist-reasoning-level")
        );
        var saveDispatchCoordinator = new CurrentConversationSaveDispatchCoordinator();
        var saveUiApplyCoordinator = new CurrentConversationSaveUiApplyCoordinator();

        Consumer<Boolean> saveCurrentConversation = selectCreatedConversation -> {
            flowEvents.add("save-dispatch:%s".formatted(selectCreatedConversation));
            saveDispatchCoordinator.save(
                    currentConversationId.get(),
                    pendingRenderMode.get(),
                    history,
                    selectedModelKey,
                    AssistantRenderMode.PREVIEW,
                    ReasoningLevel.HIGH,
                    true,
                    Path.of("/tmp/demo"),
                    saveCoordinator::save,
                    saveResult -> saveUiApplyCoordinator.apply(
                            saveResult,
                            currentConversationId::set,
                            pendingRenderMode::set,
                            activeConversationId::set,
                            sidebarRefreshCalls::incrementAndGet,
                            selectedSidebarConversations::add,
                            selectCreatedConversation
                    ),
                    saveFailures::add
            );
        };

        subject.start(
                () -> flowEvents.add("new-chat-save"),
                () -> {
                    flowEvents.add("clear-current");
                    currentConversationId.set(null);
                },
                () -> {
                    flowEvents.add("clear-pending");
                    pendingRenderMode.set(null);
                },
                () -> {
                    flowEvents.add("clear-active");
                    activeConversationId.set(null);
                },
                () -> flowEvents.add("clear-sidebar-selection"),
                () -> flowEvents.add("clear-chat-view"),
                () -> flowEvents.add("reset-agent-state"),
                AssistantRenderMode.PREVIEW,
                (mode, userInitiated) -> flowEvents.add("apply-render-mode:%s:%s".formatted(mode, userInitiated)),
                () -> flowEvents.add("focus")
        );

        assertThat(currentConversationId.get()).isNull();
        assertThat(pendingRenderMode.get()).isNull();
        assertThat(activeConversationId.get()).isNull();
        assertThat(selectedSidebarConversations).isEmpty();

        saveCurrentConversation.accept(true);

        assertThat(saveFailures).isEmpty();
        assertThat(currentConversationId.get()).isEqualTo(createdConversationId);
        assertThat(activeConversationId.get()).isEqualTo(createdConversationId);
        assertThat(sidebarRefreshCalls.get()).isEqualTo(1);
        assertThat(selectedSidebarConversations).containsExactly(createdConversationId);
        assertThat(flowEvents).containsSubsequence(
                "new-chat-save",
                "clear-current",
                "clear-pending",
                "clear-active",
                "clear-sidebar-selection",
                "clear-chat-view",
                "reset-agent-state",
                "apply-render-mode:PREVIEW:true",
                "focus",
                "save-dispatch:true",
                "save-create-conversation",
                "save-persist-mode",
                "save-persist-agent-settings",
                "save-persist-reasoning-level",
                "save-persist-history"
        );
    }

    @Test
    @DisplayName("Switching conversations saves unsaved history without reselecting the saved sidebar item")
    void start_whenSwitchingConversation_savesCurrentConversationBeforeDispatchingLoad() {
        UUID createdConversationId = UUID.randomUUID();
        UUID targetConversationId = UUID.randomUUID();
        var currentConversationId = new AtomicReference<UUID>();
        var pendingRenderMode = new AtomicReference<>(AssistantRenderMode.MARKDOWN);
        var activeConversationId = new AtomicReference<UUID>();
        var selectedSidebarConversations = new ArrayList<UUID>();
        var sidebarRefreshCalls = new AtomicInteger();
        var saveFailures = new ArrayList<Exception>();
        var flowEvents = new ArrayList<String>();
        var persistedConversationIds = new ArrayList<UUID>();
        var persistedModes = new ArrayList<AssistantRenderMode>();
        var selectedModelKey = "OpenAI > gpt-4.1";
        var history = List.of(Message.user("hello"));

        var saveCoordinator = new CurrentConversationSaveCoordinator(
                new ConversationTitleDeriver(),
                (title, provider, model) -> {
                    flowEvents.add("save-create-conversation");
                    return createdConversationId;
                },
                (conversationId, loadedHistory) -> {
                    flowEvents.add("save-persist-history");
                    persistedConversationIds.add(conversationId);
                },
                (conversationId, mode) -> {
                    flowEvents.add("save-persist-mode");
                    persistedModes.add(mode);
                },
                (conversationId, agentModeEnabled, agentProjectRoot) -> flowEvents.add("save-persist-agent-settings"),
                (conversationId, reasoningLevel) -> flowEvents.add("save-persist-reasoning-level")
        );

        var saveDispatchCoordinator = new CurrentConversationSaveDispatchCoordinator();
        var saveUiApplyCoordinator = new CurrentConversationSaveUiApplyCoordinator();
        Runnable saveCurrentConversation = () -> {
            flowEvents.add("save-dispatch");
            saveDispatchCoordinator.save(
                    currentConversationId.get(),
                    pendingRenderMode.get(),
                    history,
                    selectedModelKey,
                    AssistantRenderMode.PREVIEW,
                    ReasoningLevel.LOW,
                    false,
                    null,
                    saveCoordinator::save,
                    saveResult -> saveUiApplyCoordinator.apply(
                            saveResult,
                            value -> {
                                flowEvents.add("save-ui-set-current");
                                currentConversationId.set(value);
                            },
                            pendingRenderMode::set,
                            activeConversationId::set,
                            sidebarRefreshCalls::incrementAndGet,
                            selectedSidebarConversations::add,
                            false
                    ),
                    saveFailures::add
            );
        };

        var dispatchCoordinator = new RecordingConversationLoadDispatchCoordinator();
        var loadStartCoordinator = new ConversationLoadStartCoordinator(dispatchCoordinator);

        long requestId = loadStartCoordinator.start(
                targetConversationId,
                saveCurrentConversation,
                value -> {
                    flowEvents.add("load-set-current");
                    currentConversationId.set(value);
                },
                () -> {
                    flowEvents.add("load-clear-pending");
                    pendingRenderMode.set(null);
                },
                value -> {
                    flowEvents.add("load-set-active");
                    activeConversationId.set(value);
                },
                (conversationId, listener) -> 7L,
                (loadedRequestId, loadedConversationId, records, conversation) -> {
                },
                (failedRequestId, failedConversationId, error) -> {
                }
        );

        assertThat(requestId).isEqualTo(88L);
        assertThat(saveFailures).isEmpty();
        assertThat(persistedConversationIds).containsExactly(createdConversationId);
        assertThat(persistedModes).containsExactly(AssistantRenderMode.MARKDOWN);
        assertThat(sidebarRefreshCalls.get()).isEqualTo(1);
        assertThat(selectedSidebarConversations).isEmpty();

        assertThat(currentConversationId.get()).isEqualTo(targetConversationId);
        assertThat(activeConversationId.get()).isEqualTo(targetConversationId);
        assertThat(pendingRenderMode.get()).isNull();

        assertThat(flowEvents).containsSubsequence(
                "save-dispatch",
                "save-create-conversation",
                "save-persist-mode",
                "save-persist-agent-settings",
                "save-persist-reasoning-level",
                "save-persist-history",
                "save-ui-set-current",
                "load-set-current",
                "load-clear-pending",
                "load-set-active"
        );

        assertThat(dispatchCoordinator.lastConversationId).isEqualTo(targetConversationId);
        assertThat(dispatchCoordinator.dispatchCalls.get()).isEqualTo(1);
    }

    private static class RecordingConversationLoadDispatchCoordinator extends ConversationLoadDispatchCoordinator {

        private final AtomicInteger dispatchCalls = new AtomicInteger();
        private UUID lastConversationId;

        private RecordingConversationLoadDispatchCoordinator() {
            super(action -> action.run());
        }

        @Override
        public long dispatch(
                UUID conversationId,
                AsyncLoader asyncLoader,
                LoadedHandler onLoaded,
                FailureHandler onFailure
        ) {
            dispatchCalls.incrementAndGet();
            lastConversationId = conversationId;
            return 88L;
        }
    }
}
