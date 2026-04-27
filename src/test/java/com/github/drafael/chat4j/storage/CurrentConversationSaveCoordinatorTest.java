package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class CurrentConversationSaveCoordinatorTest {

    @Test
    @DisplayName("Save skips persistence when history is empty")
    void save_whenHistoryEmpty_returnsSkippedResult() throws Exception {
        var createCalls = new AtomicInteger();
        var historyPersistCalls = new AtomicInteger();
        var modePersistCalls = new AtomicInteger();
        var agentSettingsPersistCalls = new AtomicInteger();
        var reasoningPersistCalls = new AtomicInteger();

        var subject = new CurrentConversationSaveCoordinator(
                new ConversationTitleDeriver(),
                (title, provider, model) -> {
                    createCalls.incrementAndGet();
                    return UUID.randomUUID();
                },
                (conversationId, history) -> historyPersistCalls.incrementAndGet(),
                (conversationId, mode) -> modePersistCalls.incrementAndGet(),
                (conversationId, agentModeEnabled, agentProjectRoot) -> agentSettingsPersistCalls.incrementAndGet(),
                (conversationId, reasoningLevel) -> reasoningPersistCalls.incrementAndGet()
        );

        UUID currentConversationId = UUID.randomUUID();
        var result = subject.save(
                currentConversationId,
                AssistantRenderMode.PREVIEW,
                emptyList(),
                "OpenAI > gpt-4.1",
                AssistantRenderMode.MARKDOWN,
                ReasoningLevel.HIGH,
                true,
                Path.of("/tmp/demo")
        );

        assertThat(result.saved()).isFalse();
        assertThat(result.conversationId()).isEqualTo(currentConversationId);
        assertThat(result.pendingUnsavedConversationRenderMode()).isEqualTo(AssistantRenderMode.PREVIEW);
        assertThat(result.createdConversation()).isFalse();
        assertThat(createCalls.get()).isZero();
        assertThat(historyPersistCalls.get()).isZero();
        assertThat(modePersistCalls.get()).isZero();
        assertThat(agentSettingsPersistCalls.get()).isZero();
        assertThat(reasoningPersistCalls.get()).isZero();
    }

    @Test
    @DisplayName("Save creates conversation and persists pending mode when conversation is new")
    void save_whenConversationIsNew_createsConversationAndPersistsPendingMode() throws Exception {
        var titleDeriver = new ConversationTitleDeriver() {
            @Override
            public String derive(Message firstMessage) {
                return "Derived Title";
            }
        };

        UUID createdConversationId = UUID.randomUUID();
        var createdTitle = new AtomicReference<String>();
        var createdProvider = new AtomicReference<String>();
        var createdModel = new AtomicReference<String>();
        var persistedHistoryConversationId = new AtomicReference<UUID>();
        var persistedHistory = new AtomicReference<List<Message>>();
        var persistedModeConversationId = new AtomicReference<UUID>();
        var persistedMode = new AtomicReference<AssistantRenderMode>();
        var persistedAgentConversationId = new AtomicReference<UUID>();
        var persistedAgentMode = new AtomicReference<Boolean>();
        var persistedAgentRoot = new AtomicReference<Path>();
        var persistedReasoningConversationId = new AtomicReference<UUID>();
        var persistedReasoningLevel = new AtomicReference<ReasoningLevel>();

        var subject = new CurrentConversationSaveCoordinator(
                titleDeriver,
                (title, provider, model) -> {
                    createdTitle.set(title);
                    createdProvider.set(provider);
                    createdModel.set(model);
                    return createdConversationId;
                },
                (conversationId, history) -> {
                    persistedHistoryConversationId.set(conversationId);
                    persistedHistory.set(history);
                },
                (conversationId, mode) -> {
                    persistedModeConversationId.set(conversationId);
                    persistedMode.set(mode);
                },
                (conversationId, agentModeEnabled, agentProjectRoot) -> {
                    persistedAgentConversationId.set(conversationId);
                    persistedAgentMode.set(agentModeEnabled);
                    persistedAgentRoot.set(agentProjectRoot);
                },
                (conversationId, reasoningLevel) -> {
                    persistedReasoningConversationId.set(conversationId);
                    persistedReasoningLevel.set(reasoningLevel);
                }
        );

        List<Message> history = List.of(Message.user("Hello"));
        Path projectRoot = Path.of("/tmp/chat4j-agent-root");

        var result = subject.save(
                null,
                AssistantRenderMode.MARKDOWN,
                history,
                "OpenAI > gpt-4.1",
                AssistantRenderMode.PREVIEW,
                ReasoningLevel.EXTRA_HIGH,
                true,
                projectRoot
        );

        assertThat(result.saved()).isTrue();
        assertThat(result.conversationId()).isEqualTo(createdConversationId);
        assertThat(result.pendingUnsavedConversationRenderMode()).isNull();
        assertThat(result.createdConversation()).isTrue();

        assertThat(createdTitle.get()).isEqualTo("Derived Title");
        assertThat(createdProvider.get()).isEqualTo("OpenAI");
        assertThat(createdModel.get()).isEqualTo("gpt-4.1");
        assertThat(persistedModeConversationId.get()).isEqualTo(createdConversationId);
        assertThat(persistedMode.get()).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(persistedAgentConversationId.get()).isEqualTo(createdConversationId);
        assertThat(persistedAgentMode.get()).isTrue();
        assertThat(persistedAgentRoot.get()).isEqualTo(projectRoot);
        assertThat(persistedReasoningConversationId.get()).isEqualTo(createdConversationId);
        assertThat(persistedReasoningLevel.get()).isEqualTo(ReasoningLevel.EXTRA_HIGH);
        assertThat(persistedHistoryConversationId.get()).isEqualTo(createdConversationId);
        assertThat(persistedHistory.get()).isEqualTo(history);
    }

    @Test
    @DisplayName("Save falls back to unknown provider/model when selected model key is invalid")
    void save_whenSelectedModelKeyInvalid_usesUnknownModelSelection() throws Exception {
        var provider = new AtomicReference<String>();
        var model = new AtomicReference<String>();

        var subject = new CurrentConversationSaveCoordinator(
                new ConversationTitleDeriver(),
                (title, createdProvider, createdModel) -> {
                    provider.set(createdProvider);
                    model.set(createdModel);
                    return UUID.randomUUID();
                },
                (conversationId, history) -> {
                },
                (conversationId, mode) -> {
                },
                (conversationId, agentModeEnabled, agentProjectRoot) -> {
                },
                (conversationId, reasoningLevel) -> {
                }
        );

        subject.save(
                null,
                null,
                List.of(Message.user("Hello")),
                "not-a-model-key",
                AssistantRenderMode.PREVIEW,
                ReasoningLevel.OFF,
                false,
                null
        );

        assertThat(provider.get()).isEqualTo("Unknown");
        assertThat(model.get()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("Save persists history only when conversation already exists")
    void save_whenConversationExists_persistsHistoryWithoutCreateOrModePersist() throws Exception {
        UUID existingConversationId = UUID.randomUUID();
        var createCalls = new AtomicInteger();
        var modePersistCalls = new AtomicInteger();
        var agentSettingsPersistCalls = new AtomicInteger();
        var reasoningPersistCalls = new AtomicInteger();
        var historyPersistConversationId = new AtomicReference<UUID>();

        var subject = new CurrentConversationSaveCoordinator(
                new ConversationTitleDeriver(),
                (title, provider, model) -> {
                    createCalls.incrementAndGet();
                    return UUID.randomUUID();
                },
                (conversationId, history) -> historyPersistConversationId.set(conversationId),
                (conversationId, mode) -> modePersistCalls.incrementAndGet(),
                (conversationId, agentModeEnabled, agentProjectRoot) -> agentSettingsPersistCalls.incrementAndGet(),
                (conversationId, reasoningLevel) -> reasoningPersistCalls.incrementAndGet()
        );

        var result = subject.save(
                existingConversationId,
                AssistantRenderMode.PREVIEW,
                List.of(Message.user("Hello"), Message.assistant("World")),
                "OpenAI > gpt-4.1",
                AssistantRenderMode.MARKDOWN,
                ReasoningLevel.MEDIUM,
                true,
                Path.of("/tmp/demo")
        );

        assertThat(result.saved()).isTrue();
        assertThat(result.conversationId()).isEqualTo(existingConversationId);
        assertThat(result.pendingUnsavedConversationRenderMode()).isEqualTo(AssistantRenderMode.PREVIEW);
        assertThat(result.createdConversation()).isFalse();
        assertThat(historyPersistConversationId.get()).isEqualTo(existingConversationId);
        assertThat(createCalls.get()).isZero();
        assertThat(modePersistCalls.get()).isZero();
        assertThat(agentSettingsPersistCalls.get()).isZero();
        assertThat(reasoningPersistCalls.get()).isZero();
    }
}
