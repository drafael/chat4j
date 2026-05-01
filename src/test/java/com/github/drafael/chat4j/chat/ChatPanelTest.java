package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.chat.agent.AgentOrchestrator;
import com.github.drafael.chat4j.chat.agent.AgentProviderAdapterFactory;
import com.github.drafael.chat4j.chat.agent.AgentTurnResult;
import com.github.drafael.chat4j.chat.agent.LocalToolRuntime;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ChatPanelTest {

    private ChatPanel subject;

    @BeforeEach
    void setUp() {
        subject = new ChatPanel();
        subject.getInputBar().setWebSearchLockedEnabled(false);
        subject.getInputBar().setWebSearchOptions(emptyList(), null);
        subject.getInputBar().setWebSearchEnabled(false);
    }

    @Test
    @DisplayName("Selecting a non-seed model still creates a runtime provider for the selected provider")
    void setSelectedModel_whenModelIsNotPartOfSeedModels_createsProviderForSelectedProvider() throws Exception {
        subject.setSelectedModel("Ollama > llama3.2:latest");

        Object currentProvider = readCurrentProvider(subject);

        assertThat(subject.getSelectedModel()).isEqualTo("Ollama > llama3.2:latest");
        assertThat(currentProvider).isNotNull();
        assertThat(currentProvider).isInstanceOf(ProviderService.class);
    }

    @Test
    @DisplayName("Selecting an unavailable provider clears any previously selected runtime provider")
    void setSelectedModel_whenProviderIsUnavailable_clearsPreviousRuntimeProvider() throws Exception {
        subject.setSelectedModel("Ollama > llama3.2:latest");
        assertThat(readCurrentProvider(subject)).isNotNull();

        subject.setSelectedModel("UnavailableProvider > some-model");

        assertThat(readCurrentProvider(subject)).isNull();
    }

    @Test
    @DisplayName("Thinking toggle is visible only for models with reasoning capability")
    void setSelectedModel_whenReasoningCapabilityChanges_updatesThinkingToggleVisibility() throws Exception {
        ProviderRegistry.ProviderDef reasoningProvider = new ProviderRegistry.ProviderDef(
                "OpenRouter",
                "OPENROUTER_API_KEY",
                null,
                List.of("claude-3.7-sonnet"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );
        ProviderRegistry.ProviderDef plainProvider = new ProviderRegistry.ProviderDef(
                "LocalTest",
                "LOCAL_TEST_API_KEY",
                null,
                List.of("basic-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );

        Map<String, ProviderRegistry.ProviderDef> customProviders = new LinkedHashMap<>();
        customProviders.put(reasoningProvider.name(), reasoningProvider);
        customProviders.put(plainProvider.name(), plainProvider);
        setField(subject, "providerMap", customProviders);

        JButton thinkingButton = readThinkingButton(subject.getInputBar());

        subject.setSelectedModel("OpenRouter > claude-3.7-sonnet");
        assertThat(thinkingButton.isVisible()).isTrue();

        subject.getInputBar().setThinkingEnabled(true);
        assertThat(subject.getInputBar().isThinkingEnabled()).isTrue();

        subject.setSelectedModel("LocalTest > basic-model");
        assertThat(thinkingButton.isVisible()).isFalse();
        assertThat(subject.getInputBar().isThinkingEnabled()).isFalse();
    }

    @Test
    @DisplayName("Switching conversations away and back restores previously selected reasoning level")
    void setSelectedModel_whenSwitchingAwayAndBack_restoresReasoningState() throws Exception {
        ProviderRegistry.ProviderDef reasoningProvider = new ProviderRegistry.ProviderDef(
                "OpenRouter",
                "OPENROUTER_API_KEY",
                null,
                List.of("claude-3.7-sonnet"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );
        ProviderRegistry.ProviderDef plainProvider = new ProviderRegistry.ProviderDef(
                "LocalTest",
                "LOCAL_TEST_API_KEY",
                null,
                List.of("basic-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );

        Map<String, ProviderRegistry.ProviderDef> customProviders = new LinkedHashMap<>();
        customProviders.put(reasoningProvider.name(), reasoningProvider);
        customProviders.put(plainProvider.name(), plainProvider);
        setField(subject, "providerMap", customProviders);

        JButton thinkingButton = readThinkingButton(subject.getInputBar());

        // Conversation A
        subject.setSelectedModel("OpenRouter > claude-3.7-sonnet");
        subject.getInputBar().setReasoningLevel(ReasoningLevel.EXTRA_HIGH);
        assertThat(subject.getInputBar().getReasoningLevel()).isEqualTo(ReasoningLevel.EXTRA_HIGH);
        assertThat(subject.getInputBar().isThinkingEnabled()).isTrue();

        // Switch to conversation B (non-reasoning model)
        subject.setSelectedModel("LocalTest > basic-model");
        assertThat(thinkingButton.isVisible()).isFalse();
        assertThat(subject.getInputBar().getReasoningLevel()).isEqualTo(ReasoningLevel.EXTRA_HIGH);
        assertThat(subject.getInputBar().isThinkingEnabled()).isFalse();

        // Switch back to conversation A
        subject.setSelectedModel("OpenRouter > claude-3.7-sonnet");
        assertThat(thinkingButton.isVisible()).isTrue();
        assertThat(subject.getInputBar().getReasoningLevel()).isEqualTo(ReasoningLevel.EXTRA_HIGH);
        assertThat(subject.getInputBar().isThinkingEnabled()).isTrue();
    }

    @Test
    @DisplayName("Agent toggle is visible only for models with tool capability")
    void setSelectedModel_whenToolCapabilityChanges_updatesAgentToggleVisibility() throws Exception {
        ProviderRegistry.ProviderDef toolProvider = new ProviderRegistry.ProviderDef(
                "OpenRouter",
                "OPENROUTER_API_KEY",
                null,
                List.of("gpt-5-mini"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );
        ProviderRegistry.ProviderDef plainProvider = new ProviderRegistry.ProviderDef(
                "LocalTest",
                "LOCAL_TEST_API_KEY",
                null,
                List.of("basic-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );

        Map<String, ProviderRegistry.ProviderDef> customProviders = new LinkedHashMap<>();
        customProviders.put(toolProvider.name(), toolProvider);
        customProviders.put(plainProvider.name(), plainProvider);
        setField(subject, "providerMap", customProviders);

        JToggleButton agentModeButton = readAgentModeButton(subject.getInputBar());

        subject.setSelectedModel("OpenRouter > gpt-5-mini");
        assertThat(agentModeButton.isVisible()).isTrue();

        subject.getInputBar().setAgentProjectRoot(Files.createTempDirectory("chat4j-agent-project"));
        subject.getInputBar().setAgentModeEnabled(true);
        assertThat(subject.getInputBar().isAgentModeEnabled()).isTrue();

        subject.setSelectedModel("LocalTest > basic-model");
        assertThat(agentModeButton.isVisible()).isFalse();
        assertThat(subject.getInputBar().isAgentModeEnabled()).isFalse();
    }

    @Test
    @DisplayName("Send is blocked when agent mode is enabled without a valid project folder")
    void onSend_whenAgentModeEnabledWithoutProjectFolder_showsValidationAndSkipsSend() throws Exception {
        setField(subject, "currentProvider", immediateProvider("pong"));
        subject.getInputBar().setAgentModeAvailable(true);

        Field enabledField = InputBar.class.getDeclaredField("agentModeEnabled");
        enabledField.setAccessible(true);
        enabledField.setBoolean(subject.getInputBar(), true);

        Field projectRootField = InputBar.class.getDeclaredField("agentProjectRoot");
        projectRootField.setAccessible(true);
        projectRootField.set(subject.getInputBar(), null);

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        JLabel validationLabel = readValidationLabel(subject.getInputBar());
        assertThat(validationLabel.isVisible()).isTrue();
        assertThat(validationLabel.getText()).contains("Select a valid project folder");
        assertThat(subject.getHistory()).isEmpty();
    }

    @Test
    @DisplayName("Agent mode routes send flow through orchestrator path")
    void onSend_whenAgentModeEnabled_routesThroughAgentOrchestrator() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-route");

        subject.getInputBar().setAgentModeAvailable(true);
        subject.getInputBar().setAgentProjectRoot(projectRoot);
        subject.getInputBar().setAgentModeEnabled(true);

        subject.setAgentOrchestratorForTests(new AgentOrchestrator(new AgentProviderAdapterFactory() {
            @Override
            public com.github.drafael.chat4j.chat.agent.AgentProviderAdapter create(
                    String providerName,
                    String modelId,
                    String baseUrl,
                    String apiKey,
                    ProviderService providerService,
                    String agentSystemPromptAppend
            ) {
                return (request, callbacks) -> {
                    callbacks.onToken().accept("agent-response");
                    return AgentTurnResult.complete();
                };
            }
        }, new LocalToolRuntime()));

        setField(subject, "selectedProviderName", "OpenAI");
        setField(subject, "selectedModelId", "gpt-5-mini");
        setField(subject, "currentProvider", new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled
            ) {
                throw new IllegalStateException("Non-agent provider path should not be called");
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        });

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        assertThat(subject.getHistory().get(1).content()).isEqualTo("agent-response");
    }

    @Test
    @DisplayName("Agent mode passes configured prompt addendum to orchestrator")
    void onSend_whenAgentPromptAddendumConfigured_passesAddendumToOrchestrator() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-route-addendum");
        AtomicReference<String> observedPromptAppend = new AtomicReference<>();

        subject.getInputBar().setAgentModeAvailable(true);
        subject.getInputBar().setAgentProjectRoot(projectRoot);
        subject.getInputBar().setAgentModeEnabled(true);
        subject.setAgentSystemPromptAppend("Always include key project files.");

        subject.setAgentOrchestratorForTests(new AgentOrchestrator(new AgentProviderAdapterFactory() {
            @Override
            public com.github.drafael.chat4j.chat.agent.AgentProviderAdapter create(
                    String providerName,
                    String modelId,
                    String baseUrl,
                    String apiKey,
                    ProviderService providerService,
                    String agentSystemPromptAppend
            ) {
                observedPromptAppend.set(agentSystemPromptAppend);
                return (request, callbacks) -> {
                    callbacks.onToken().accept("agent-response");
                    return AgentTurnResult.complete();
                };
            }
        }, new LocalToolRuntime()));

        setField(subject, "selectedProviderName", "OpenAI");
        setField(subject, "selectedModelId", "gpt-5-mini");
        setField(subject, "currentProvider", immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        assertThat(observedPromptAppend.get()).isEqualTo("Always include key project files.");
    }

    @Test
    @DisplayName("Cancelling an active stream invalidates the session and clears streaming state")
    void cancelStreaming_whenStreamIsActive_invalidatesSessionAndClearsStreamingState() throws Exception {
        setField(subject, "streaming", true);
        setField(subject, "activeStreamSessionId", 42L);

        subject.cancelStreaming();

        assertThat((boolean) readField(subject, "streaming")).isFalse();
        assertThat((long) readField(subject, "activeStreamSessionId")).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Send enters preparing state before background preparation completes")
    void onSend_whenPreparationIsInFlight_showsPreparingIndicatorAndDefersHistoryMutation() throws Exception {
        var started = new CountDownLatch(1);
        var releasePreparation = new CountDownLatch(1);

        subject.setSendPreparerForTests((composerState, providerSnapshot, isCancelled) -> {
            started.countDown();
            while (!releasePreparation.await(20, TimeUnit.MILLISECONDS)) {
                if (isCancelled.getAsBoolean()) {
                    throw new IllegalStateException("Cancelled");
                }
            }
            return Message.user(composerState.text());
        });

        setField(subject, "currentProvider", immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        assertThat(subject.getInputBar().isEnabled()).isFalse();
        assertThat(subject.getInputBar().isCancelGenerationVisible()).isTrue();
        assertThat(subject.getHistory()).isEmpty();

        releasePreparation.countDown();
        flushEdt();
    }

    @Test
    @DisplayName("Cancelling during preparing restores draft and clears busy state")
    void cancelStreaming_whenPreparing_restoresDraftAndClearsIndicator() throws Exception {
        var started = new CountDownLatch(1);
        var releasePreparation = new CountDownLatch(1);

        subject.setSendPreparerForTests((composerState, providerSnapshot, isCancelled) -> {
            started.countDown();
            while (!releasePreparation.await(20, TimeUnit.MILLISECONDS)) {
                if (isCancelled.getAsBoolean()) {
                    throw new IllegalStateException("Cancelled");
                }
            }
            return Message.user(composerState.text());
        });

        setField(subject, "currentProvider", immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        SwingUtilities.invokeAndWait(subject::cancelStreamingAndMarkCancelled);
        flushEdt();

        assertThat(subject.getInputBar().isEnabled()).isTrue();
        assertThat(subject.getInputBar().isCancelGenerationVisible()).isFalse();
        assertThat(subject.getHistory()).isEmpty();
        assertThat(textArea.getText()).isEqualTo("ping");

        releasePreparation.countDown();
        flushEdt();
    }

    @Test
    @DisplayName("Preparation failure keeps composer draft and shows inline error")
    void onSend_whenPreparationFails_keepsDraftAndShowsValidationError() throws Exception {
        subject.setSendPreparerForTests((composerState, providerSnapshot, isCancelled) -> {
            throw new IOException("Failed to stage attachment: boom");
        });

        setField(subject, "currentProvider", immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getInputBar().isEnabled()
                    && !subject.getInputBar().isCancelGenerationVisible()
                    && readValidationLabel(subject.getInputBar()).getText().contains("Failed to stage attachment");
        });

        assertThat(subject.getHistory()).isEmpty();
        assertThat(textArea.getText()).isEqualTo("ping");
        assertThat(readValidationLabel(subject.getInputBar()).getText()).contains("Failed to stage attachment");
    }

    @Test
    @DisplayName("Auto-scroll setting can be enabled and disabled at runtime")
    void setAutoScrollEnabled_whenCalled_updatesAutoScrollBehaviorFlag() {
        subject.setAutoScrollEnabled(false);
        assertThat(subject.isAutoScrollEnabled()).isFalse();

        subject.setAutoScrollEnabled(true);
        assertThat(subject.isAutoScrollEnabled()).isTrue();
    }

    @Test
    @DisplayName("Assistant render mode switch updates state and emits change callback")
    void setAssistantRenderMode_whenChanged_updatesStateAndNotifiesListener() {
        var capturedMode = new AtomicReference<AssistantRenderMode>();
        subject.setOnAssistantRenderModeChanged(capturedMode::set);

        subject.setAssistantRenderMode(AssistantRenderMode.MARKDOWN, true);

        assertThat(subject.getAssistantRenderMode()).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(capturedMode.get()).isEqualTo(AssistantRenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Clear chat button is visible only when chat history has messages")
    void loadHistoryAndClearChat_whenHistoryChanges_updatesClearChatButtonVisibility() throws Exception {
        flushEdt();
        assertThat(subject.getInputBar().isClearChatVisible()).isFalse();

        subject.loadHistory(List.of(Message.user("hello")));
        flushEdt();

        assertThat(subject.getInputBar().isClearChatVisible()).isTrue();

        subject.clearChatView();
        flushEdt();

        assertThat(subject.getInputBar().isClearChatVisible()).isFalse();
    }

    @Test
    @DisplayName("Bubble context menu clear item follows clear chat visibility and requests clear")
    void bubbleContextMenu_whenClearChatAvailabilityChanges_updatesItemVisibilityAndAction() throws Exception {
        var requested = new AtomicInteger();
        subject.setOnClearChatRequested(requested::incrementAndGet);
        subject.loadHistory(List.of(Message.user("hello")));
        flushEdt();

        MessageBubble bubble = findComponents((JPanel) readField(subject, "messagesPanel"), MessageBubble.class).getFirst();
        JPopupMenu popup = bubble.getEditorPane().getComponentPopupMenu();
        JMenuItem clearChatItem = findMenuItem(popup, "Clear Chat");

        notifyPopupWillBecomeVisible(popup);
        assertThat(clearChatItem.isVisible()).isTrue();

        SwingUtilities.invokeAndWait(clearChatItem::doClick);
        assertThat(requested).hasValue(1);

        subject.getInputBar().setEnabled(false);
        notifyPopupWillBecomeVisible(popup);

        assertThat(clearChatItem.isVisible()).isFalse();

        SwingUtilities.invokeAndWait(clearChatItem::doClick);
        assertThat(requested).hasValue(1);
    }

    @Test
    @DisplayName("Switching render mode rerenders loaded assistant bubbles")
    void setAssistantRenderMode_whenHistoryLoaded_updatesAssistantBubbleModes() throws Exception {
        subject.loadHistory(List.of(
                Message.user("hello"),
                Message.assistant("**bold**")
        ));

        subject.setAssistantRenderMode(AssistantRenderMode.MARKDOWN, true);

        @SuppressWarnings("unchecked")
        List<MessageBubble> assistantBubbles = (List<MessageBubble>) readField(subject, "assistantBubbles");
        assertThat(assistantBubbles).hasSize(1);
        assertThat(readBubbleRenderMode(assistantBubbles.getFirst())).isEqualTo(AssistantRenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Loading a user multimodal message renders attachment chips separately from the bubble text")
    void loadHistory_whenUserMessageHasAttachments_rendersChipsAndTextSeparately() throws Exception {
        AttachmentRef fileRef = new AttachmentRef(UUID.randomUUID(), "/tmp/demo.txt", "demo.txt", "text/plain", 128, "");
        AttachmentRef imageRef = new AttachmentRef(UUID.randomUUID(), "/tmp/image.png", "image.png", "image/png", 256, "");
        Message message = new Message(
                Role.USER,
                List.of(
                        new TextPart("Activated skills: brainstorm"),
                        new TextPart("hello there"),
                        new FilePart(fileRef),
                        new ImagePart(imageRef, null, null)
                ),
                Instant.now(),
                new MessageMeta(List.of("brainstorm"), List.of("fallback notice"), false, "")
        );

        subject.loadHistory(List.of(message));

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<FileAttachmentChip> chips = findComponents(messagesPanel, FileAttachmentChip.class);
        List<ImageAttachmentPreview> imagePreviews = findComponents(messagesPanel, ImageAttachmentPreview.class);
        List<MessageBubble> bubbles = findComponents(messagesPanel, MessageBubble.class);

        assertThat(chips).hasSize(1);
        assertThat(imagePreviews).hasSize(1);
        assertThat(bubbles).hasSize(1);
        assertThat(bubbles.getFirst().getFullText())
                .contains("[SKILL] brainstorm")
                .contains("[FALLBACK] fallback notice")
                .contains("hello there")
                .doesNotContain("[File attached:")
                .doesNotContain("[Image attached:");
    }

    @Test
    @DisplayName("Showing bubble action buttons keeps message spacing stable")
    void loadHistory_whenBubbleActionButtonsBecomeVisible_keepsWrapperHeightStable() throws Exception {
        subject.loadHistory(List.of(
                Message.user("hello"),
                Message.assistant("hi")
        ));
        flushEdt();

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        MessageBubble userBubble = findComponents(messagesPanel, MessageBubble.class).stream()
                .filter(bubble -> bubble.getRole() == Role.USER)
                .findFirst()
                .orElseThrow();
        Container hoverGroup = userBubble.getParent();
        List<JButton> buttons = findComponents(hoverGroup, JButton.class);

        Dimension before = hoverGroup.getPreferredSize();
        SwingUtilities.invokeAndWait(() -> buttons.forEach(button -> button.setVisible(true)));
        Dimension after = hoverGroup.getPreferredSize();

        assertThat(buttons).hasSize(2);
        assertThat(after.height).isEqualTo(before.height);
    }

    @Test
    @DisplayName("Completing a stream emits sidebar streaming state changes for the active conversation")
    void onSend_whenStreamCompletes_notifiesConversationStreamingChanges() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var events = new ArrayList<ChatPanel.ConversationStreamingEvent>();
        var callbacks = new CountDownLatch(2);

        subject.setActiveConversationId(conversationId);
        subject.setConversationIdSupplier(() -> conversationId);
        subject.setOnConversationStreamingChanged(event -> {
            synchronized (events) {
                events.add(event);
            }
            callbacks.countDown();
        });

        setField(subject, "currentProvider", immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        assertThat(callbacks.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        synchronized (events) {
            assertThat(events).containsExactly(
                    new ChatPanel.ConversationStreamingEvent(conversationId, true),
                    new ChatPanel.ConversationStreamingEvent(conversationId, false)
            );
        }
    }

    @Test
    @DisplayName("Completing a stream triggers save callback for both user and assistant messages")
    void onSend_whenStreamCompletes_notifiesMessageSubmittedForAssistantResponse() throws Exception {
        var callbackCount = new AtomicInteger();
        var callbacks = new CountDownLatch(2);
        subject.setOnMessageSubmitted(() -> {
            callbackCount.incrementAndGet();
            callbacks.countDown();
        });

        setField(subject, "currentProvider", new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled
            ) {
                onToken.accept("pong");
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        });

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        assertThat(callbacks.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        assertThat(callbackCount.get()).isEqualTo(2);
        assertThat(subject.getHistory()).hasSize(2);
        assertThat(subject.getHistory().get(1).role()).isEqualTo(Role.ASSISTANT);
        assertThat(subject.getHistory().get(1).content()).isEqualTo("pong");
    }

    @Test
    @DisplayName("Thinking bubble is created only when thinking tokens are emitted")
    void onSend_whenProviderDoesNotEmitThinking_doesNotRenderThinkingBubble() throws Exception {
        setField(subject, "currentProvider", immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        assertThat(findComponents(messagesPanel, ThinkingBubble.class)).isEmpty();
    }

    @Test
    @DisplayName("Loading history skips thinking bubbles when thinking text has no visible content")
    void loadHistory_whenAssistantThinkingIsInvisible_doesNotRenderThinkingBubble() throws Exception {
        List<Message> messages = List.of(
                Message.user("question"),
                new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("answer")),
                        Instant.now(),
                        new MessageMeta(emptyList(), emptyList(), false, "", "\u200B\u200C\u200D\uFEFF")
                )
        );

        SwingUtilities.invokeAndWait(() -> subject.loadHistory(messages));

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        assertThat(findComponents(messagesPanel, ThinkingBubble.class)).isEmpty();
    }

    @Test
    @DisplayName("Thinking bubbles loaded from history are collapsed by default")
    void loadHistory_whenAssistantThinkingExists_rendersCollapsedThinkingBubbleByDefault() throws Exception {
        List<Message> messages = List.of(
                Message.user("question"),
                new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("answer")),
                        Instant.now(),
                        new MessageMeta(emptyList(), emptyList(), false, "", "## Plan\n- Step 1\n- Step 2")
                )
        );

        SwingUtilities.invokeAndWait(() -> subject.loadHistory(messages));

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<ThinkingBubble> thinkingBubbles = findComponents(messagesPanel, ThinkingBubble.class);
        assertThat(thinkingBubbles).hasSize(1);
        assertThat(thinkingBubbles.getFirst().isCollapsed()).isTrue();
    }

    @Test
    @DisplayName("Consecutive assistant artifacts in history are merged into a single assistant message")
    void loadHistory_whenHistoryContainsConsecutiveAssistantArtifacts_mergesAssistantRun() throws Exception {
        List<Message> messages = List.of(
                Message.user("question"),
                new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("first answer")),
                        Instant.now(),
                        new MessageMeta(emptyList(), emptyList(), false, "", "first thinking")
                ),
                new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("")),
                        Instant.now(),
                        new MessageMeta(emptyList(), emptyList(), false, "", "artifact thinking")
                ),
                new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("final answer")),
                        Instant.now(),
                        new MessageMeta(emptyList(), emptyList(), false, "", "final thinking")
                )
        );

        SwingUtilities.invokeAndWait(() -> subject.loadHistory(messages));

        assertThat(subject.getHistory()).hasSize(2);
        Message assistant = subject.getHistory().get(1);
        assertThat(assistant.content()).isEqualTo("final answer");
        assertThat(assistant.meta().assistantThinking()).contains("first thinking");
        assertThat(assistant.meta().assistantThinking()).contains("artifact thinking");
        assertThat(assistant.meta().assistantThinking()).contains("final thinking");
    }

    @Test
    @DisplayName("Thinking bubble keeps rendered content visible in preview mode")
    void loadHistory_whenThinkingContainsMarkdown_rendersVisiblePreviewText() throws Exception {
        String thinking = "Here's a thinking process:\n\n1. **Step one**\n2. **Step two**";
        List<Message> messages = List.of(
                Message.user("question"),
                new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("answer")),
                        Instant.now(),
                        new MessageMeta(emptyList(), emptyList(), false, "", thinking)
                )
        );

        SwingUtilities.invokeAndWait(() -> {
            subject.loadHistory(messages);
            subject.setAssistantRenderMode(AssistantRenderMode.PREVIEW, true);
        });
        flushEdt();

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<ThinkingBubble> thinkingBubbles = findComponents(messagesPanel, ThinkingBubble.class);
        assertThat(thinkingBubbles).hasSize(1);

        List<JEditorPane> panes = findComponents(thinkingBubbles.getFirst(), JEditorPane.class);
        assertThat(panes).isNotEmpty();
        assertThat(panes.getFirst().getDocument().getLength()).isGreaterThan(1);
    }

    @Test
    @DisplayName("Thinking bubble renders without nested inner scroll containers")
    void onSend_whenProviderEmitsThinking_usesSingleRenderedPath() throws Exception {
        subject.getInputBar().setThinkingAvailable(true);
        subject.getInputBar().setThinkingEnabled(true);

        setField(subject, "currentProvider", new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled
            ) {
                onThinkingToken.accept("Thinking step one\n\n- detail");
                onToken.accept("final answer");
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        });

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("question"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<ThinkingBubble> thinkingBubbles = findComponents(messagesPanel, ThinkingBubble.class);
        assertThat(thinkingBubbles).hasSize(1);

        ThinkingBubble thinkingBubble = thinkingBubbles.getFirst();
        assertThat(findComponents(thinkingBubble, JScrollPane.class)).isEmpty();

        List<JEditorPane> panes = findComponents(thinkingBubble, JEditorPane.class);
        assertThat(panes).isNotEmpty();
        assertThat(panes.getFirst().getDocument().getLength()).isGreaterThan(5);
    }

    @Test
    @DisplayName("Thinking bubble copy button appears on hover")
    void loadHistory_whenThinkingBubbleHovered_showsCopyButton() throws Exception {
        List<Message> messages = List.of(
                Message.user("question"),
                new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("answer")),
                        Instant.now(),
                        new MessageMeta(emptyList(), emptyList(), false, "", "reasoning text")
                )
        );

        SwingUtilities.invokeAndWait(() -> subject.loadHistory(messages));

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        ThinkingBubble thinkingBubble = findComponents(messagesPanel, ThinkingBubble.class).getFirst();
        JButton copyButton = findComponents(thinkingBubble, JButton.class).stream()
                .filter(button -> "Copy thinking".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow();

        assertThat(copyButton.isVisible()).isFalse();

        MouseEvent hoverEvent = new MouseEvent(
                thinkingBubble,
                MouseEvent.MOUSE_ENTERED,
                System.currentTimeMillis(),
                0,
                2,
                2,
                0,
                false
        );
        for (var listener : thinkingBubble.getMouseListeners()) {
            listener.mouseEntered(hoverEvent);
        }

        assertThat(copyButton.isVisible()).isTrue();
    }

    @Test
    @DisplayName("Clicking thinking title toggles collapse state")
    void loadHistory_whenThinkingTitleClicked_togglesCollapsedState() throws Exception {
        List<Message> messages = List.of(
                Message.user("question"),
                new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("answer")),
                        Instant.now(),
                        new MessageMeta(emptyList(), emptyList(), false, "", "reasoning text")
                )
        );

        SwingUtilities.invokeAndWait(() -> subject.loadHistory(messages));

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        ThinkingBubble thinkingBubble = findComponents(messagesPanel, ThinkingBubble.class).getFirst();
        JLabel titleLabel = findComponents(thinkingBubble, JLabel.class).stream()
                .filter(label -> "Thinking".equals(label.getText()))
                .findFirst()
                .orElseThrow();

        assertThat(thinkingBubble.isCollapsed()).isTrue();

        MouseEvent clickEvent = new MouseEvent(
                titleLabel,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                2,
                2,
                1,
                false
        );
        for (var listener : titleLabel.getMouseListeners()) {
            listener.mouseClicked(clickEvent);
        }

        awaitCondition(1, TimeUnit.SECONDS, () -> {
            flushEdt();
            return !thinkingBubble.isCollapsed();
        });
        assertThat(thinkingBubble.isCollapsed()).isFalse();

        for (var listener : titleLabel.getMouseListeners()) {
            listener.mouseClicked(clickEvent);
        }

        awaitCondition(1, TimeUnit.SECONDS, () -> {
            flushEdt();
            return thinkingBubble.isCollapsed();
        });
        assertThat(thinkingBubble.isCollapsed()).isTrue();
    }

    @Test
    @DisplayName("Thinking tokens are ignored when thinking toggle is off")
    void onSend_whenThinkingToggleIsOff_ignoresThinkingTokens() throws Exception {
        setField(subject, "currentProvider", new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled
            ) {
                onThinkingToken.accept("internal reasoning");
                onToken.accept("final answer");
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        });

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("question"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        Message assistant = subject.getHistory().get(1);
        assertThat(assistant.content()).contains("final answer");
        assertThat(assistant.meta().assistantThinking()).isEmpty();

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        assertThat(findComponents(messagesPanel, ThinkingBubble.class)).isEmpty();
    }

    @Test
    @DisplayName("Thinking stream ignores non-visible control tokens and keeps visible text")
    void onSend_whenThinkingIncludesControlSequences_keepsVisibleThinkingText() throws Exception {
        subject.getInputBar().setThinkingAvailable(true);
        subject.getInputBar().setThinkingEnabled(true);

        setField(subject, "currentProvider", new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled
            ) {
                onThinkingToken.accept("First visible part");
                onThinkingToken.accept("\u001B[2J\u001B[H\u200B\u200C");
                onThinkingToken.accept(" and second visible part");
                onToken.accept("final answer");
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        });

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("question"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        Message assistant = subject.getHistory().get(1);
        assertThat(assistant.meta().assistantThinking()).contains("First visible part");
        assertThat(assistant.meta().assistantThinking()).contains("second visible part");
        assertThat(assistant.meta().assistantThinking()).doesNotContain("\u001B");

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<ThinkingBubble> thinkingBubbles = findComponents(messagesPanel, ThinkingBubble.class);
        assertThat(thinkingBubbles).hasSize(1);
        assertThat(thinkingBubbles.getFirst().getFullText()).contains("First visible part");
        assertThat(thinkingBubbles.getFirst().getFullText()).contains("second visible part");
    }

    @Test
    @DisplayName("Assistant response is persisted only once even when provider emits late error after complete")
    void onSend_whenProviderSignalsCompleteThenError_persistsAssistantOnce() throws Exception {
        setField(subject, "currentProvider", new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled
            ) {
                onThinkingToken.accept("analysis");
                onToken.accept("final");
                onComplete.run();
                onError.accept(new RuntimeException("late error"));
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        });

        subject.getInputBar().setThinkingAvailable(true);
        subject.getInputBar().setThinkingEnabled(true);
        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("question"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        long assistantCount = subject.getHistory().stream()
                .filter(message -> message.role() == Role.ASSISTANT)
                .count();
        assertThat(assistantCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Native thinking tokens are rendered and persisted separately from assistant answer text")
    void onSend_whenProviderEmitsThinking_persistsThinkingInAssistantMetaAndRendersThinkingBubble() throws Exception {
        subject.getInputBar().setThinkingAvailable(true);
        subject.getInputBar().setThinkingEnabled(true);

        setField(subject, "currentProvider", new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled
            ) {
                onThinkingToken.accept("We should compare enum features.");
                onToken.accept("Java enums are classes; TypeScript enums compile to JS objects.");
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        });

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("compare java and ts enums"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        Message assistant = subject.getHistory().get(1);
        assertThat(assistant.content()).contains("Java enums are classes");
        assertThat(assistant.meta().assistantThinking()).contains("compare enum features");

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<ThinkingBubble> thinkingBubbles = findComponents(messagesPanel, ThinkingBubble.class);
        assertThat(thinkingBubbles).hasSize(1);
        assertThat(thinkingBubbles.getFirst().getFullText()).contains("compare enum features");
    }

    @Test
    @DisplayName("Assistant persistence listener failure falls back to message-submitted callback")
    void onSend_whenAssistantPersistenceListenerFails_triggersMessageSubmittedFallback() throws Exception {
        var callbackCount = new AtomicInteger();
        var callbacks = new CountDownLatch(2);
        subject.setOnMessageSubmitted(() -> {
            callbackCount.incrementAndGet();
            callbacks.countDown();
        });

        subject.setOnAssistantMessageCompleted(event -> {
            if (event.message().role() == Role.ASSISTANT) {
                throw new IllegalStateException("boom");
            }
            return true;
        });

        setField(subject, "currentProvider", immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        assertThat(callbacks.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        assertThat(callbackCount.get()).isEqualTo(2);
        assertThat(subject.getHistory()).hasSize(2);
        assertThat(subject.getHistory().get(1).role()).isEqualTo(Role.ASSISTANT);
        assertThat(subject.getHistory().get(1).content()).isEqualTo("pong");
    }

    @Test
    @DisplayName("First send in unsaved chat keeps assistant stream bound after conversation is created")
    void onSend_whenUnsavedConversationGetsPersisted_streamRemainsVisibleAndPersistsAssistantMessage() throws Exception {
        var currentConversationId = new AtomicReference<UUID>();

        subject.setActiveConversationId(null);
        subject.setConversationIdSupplier(currentConversationId::get);
        subject.setOnMessageSubmitted(() -> {
            if (currentConversationId.get() == null && !subject.getHistory().isEmpty()) {
                UUID persistedConversationId = UUID.randomUUID();
                currentConversationId.set(persistedConversationId);
                subject.setActiveConversationId(persistedConversationId);
            }
        });

        setField(subject, "currentProvider", immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            List<Message> history = subject.getHistory();
            return history.size() == 2
                    && history.get(1).role() == Role.ASSISTANT
                    && "pong".equals(history.get(1).content());
        });

        assertThat(subject.getHistory()).hasSize(2);
        assertThat(subject.getHistory().get(0).content()).isEqualTo("ping");
        assertThat(subject.getHistory().get(1).content()).isEqualTo("pong");
        assertThat(subject.getInputBar().isEnabled()).isTrue();
        assertThat(subject.getInputBar().isCancelGenerationVisible()).isFalse();
    }

    @Test
    @DisplayName("Switching visible conversation while preparing keeps original send flow running in background")
    void onSend_whenConversationChangesDuringPreparing_continuesBackgroundFlowForOriginalConversation() throws Exception {
        var originalConversationId = UUID.randomUUID();
        var visibleConversationId = UUID.randomUUID();
        var preparationStarted = new CountDownLatch(1);
        var releasePreparation = new CountDownLatch(1);
        var persistedEvents = new ArrayList<ChatPanel.AssistantMessageEvent>();
        var completion = new CountDownLatch(2);

        subject.setActiveConversationId(originalConversationId);
        subject.setConversationIdSupplier(() -> originalConversationId);
        subject.setOnAssistantMessageCompleted(event -> {
            synchronized (persistedEvents) {
                persistedEvents.add(event);
            }
            completion.countDown();
            return true;
        });

        subject.setSendPreparerForTests((composerState, providerSnapshot, isCancelled) -> {
            preparationStarted.countDown();
            while (!releasePreparation.await(20, TimeUnit.MILLISECONDS)) {
                if (isCancelled.getAsBoolean()) {
                    throw new IllegalStateException("Cancelled");
                }
            }
            return Message.user(composerState.text());
        });

        setField(subject, "currentProvider", immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        assertThat(preparationStarted.await(2, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
            subject.setActiveConversationId(visibleConversationId);
            subject.loadHistory(List.of(Message.user("visible conversation")));
        });

        assertThat(subject.getInputBar().isEnabled()).isTrue();
        assertThat(subject.getInputBar().isCancelGenerationVisible()).isFalse();

        releasePreparation.countDown();
        assertThat(completion.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        synchronized (persistedEvents) {
            assertThat(persistedEvents).hasSize(2);
            assertThat(persistedEvents).allSatisfy(event ->
                    assertThat(event.conversationId()).isEqualTo(originalConversationId));
            assertThat(persistedEvents.stream().map(event -> event.message().role())).containsExactlyInAnyOrder(Role.USER, Role.ASSISTANT);
        }

        assertThat(subject.getHistory()).hasSize(1);
        assertThat(subject.getHistory().getFirst().content()).isEqualTo("visible conversation");
    }

    @Test
    @DisplayName("Switching conversations while streaming re-enables input in the newly visible chat")
    void setActiveConversationId_whenSwitchingAwayFromStreamingConversation_reEnablesInputBar() throws Exception {
        var originalConversationId = UUID.randomUUID();
        var visibleConversationId = UUID.randomUUID();
        var tokenDelivered = new CountDownLatch(1);
        var continueStream = new CountDownLatch(1);

        subject.setActiveConversationId(originalConversationId);
        subject.setConversationIdSupplier(() -> originalConversationId);

        setField(subject, "currentProvider", new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled
            ) {
                onToken.accept("pong");
                tokenDelivered.countDown();
                try {
                    continueStream.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        });

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        assertThat(tokenDelivered.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        assertThat(subject.getInputBar().isEnabled()).isFalse();

        SwingUtilities.invokeAndWait(() -> subject.setActiveConversationId(visibleConversationId));

        assertThat(subject.getInputBar().isEnabled()).isTrue();
        assertThat((boolean) readField(subject, "streaming")).isFalse();

        continueStream.countDown();
        flushEdt();
    }

    private static ProviderService immediateProvider(String responseText) {
        return new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled
            ) {
                onToken.accept(responseText);
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("test-model");
            }

            @Override
            public String name() {
                return "test";
            }

            @Override
            public String envVarName() {
                return "TEST_KEY";
            }
        };
    }

    private static JLabel readValidationLabel(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("validationLabel");
        field.setAccessible(true);
        return (JLabel) field.get(inputBar);
    }

    private static JButton readThinkingButton(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("thinkingButton");
        field.setAccessible(true);
        return (JButton) field.get(inputBar);
    }

    private static JToggleButton readAgentModeButton(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("agentModeButton");
        field.setAccessible(true);
        return (JToggleButton) field.get(inputBar);
    }

    private static Object readCurrentProvider(ChatPanel chatPanel) throws Exception {
        return readField(chatPanel, "currentProvider");
    }

    private static Object readField(ChatPanel chatPanel, String fieldName) throws Exception {
        Field field = ChatPanel.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(chatPanel);
    }

    private static void setField(ChatPanel chatPanel, String fieldName, Object value) throws Exception {
        Field field = ChatPanel.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(chatPanel, value);
    }

    private static AssistantRenderMode readBubbleRenderMode(MessageBubble bubble) throws Exception {
        Field field = MessageBubble.class.getDeclaredField("assistantRenderMode");
        field.setAccessible(true);
        return (AssistantRenderMode) field.get(bubble);
    }

    private static JTextArea readInputTextArea(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("textArea");
        field.setAccessible(true);
        return (JTextArea) field.get(inputBar);
    }

    private static void invokeOnSend(ChatPanel chatPanel) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod("onSend");
        method.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                method.invoke(chatPanel);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            // flush pending UI tasks
        });
    }

    private static void notifyPopupWillBecomeVisible(JPopupMenu popup) {
        PopupMenuEvent event = new PopupMenuEvent(popup);
        for (var listener : popup.getPopupMenuListeners()) {
            listener.popupMenuWillBecomeVisible(event);
        }
    }

    private static JMenuItem findMenuItem(JPopupMenu popup, String text) {
        for (Component component : popup.getComponents()) {
            if (component instanceof JMenuItem item && text.equals(item.getText())) {
                return item;
            }
        }
        throw new AssertionError("Menu item not found: %s".formatted(text));
    }

    private static void awaitCondition(long timeout, TimeUnit unit, CheckedBooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private static <T extends Component> List<T> findComponents(Container root, Class<T> componentType) {
        List<T> matches = new ArrayList<>();
        collectComponents(root, componentType, matches);
        return matches;
    }

    private static <T extends Component> void collectComponents(Container root, Class<T> componentType, List<T> matches) {
        for (Component component : root.getComponents()) {
            if (componentType.isInstance(component)) {
                matches.add(componentType.cast(component));
            }
            if (component instanceof Container child) {
                collectComponents(child, componentType, matches);
            }
        }
    }
}
