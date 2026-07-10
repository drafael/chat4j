package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.tts.provider.TextToSpeechProvider;
import com.github.drafael.chat4j.chat.conversation.ConversationAttachment;
import com.github.drafael.chat4j.chat.composer.FileAttachmentChip;
import com.github.drafael.chat4j.chat.composer.ImageAttachmentPreview;
import com.github.drafael.chat4j.chat.ui.JumpToLatestButton;
import com.github.drafael.chat4j.chat.composer.InputBar;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.chat.ui.ActivityBubble;
import com.github.drafael.chat4j.chat.agent.AgentOrchestrator;
import com.github.drafael.chat4j.chat.message.ChatMessageViewFactory;
import com.github.drafael.chat4j.chat.message.MessageBubble;
import com.github.drafael.chat4j.chat.conversation.webview.system.SystemWebView;
import com.github.drafael.chat4j.chat.webview.WebViewEngine;
import com.github.drafael.chat4j.chat.agent.AgentProviderAdapter;
import com.github.drafael.chat4j.chat.agent.AgentProviderAdapterFactory;
import com.github.drafael.chat4j.chat.agent.AgentTurnResult;
import com.github.drafael.chat4j.chat.agent.LocalToolRuntime;
import com.github.drafael.chat4j.chat.agent.ToolInvocationRequest;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AgentToolActivityMeta;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.tts.audio.AudioPlaybackService;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.TextToSpeechProviderRegistry;
import com.github.drafael.chat4j.tts.TextToSpeechService;
import com.github.drafael.chat4j.tts.TextToSpeechSettings;
import com.github.drafael.chat4j.web.WebSearchMode;
import com.github.drafael.chat4j.web.WebSearchOption;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
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
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import static org.assertj.core.api.Assertions.tuple;

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
    @DisplayName("Activity bubble uses status title color for failed tool cards")
    void setTitle_whenFailedStatus_usesErrorTitleColor() throws Exception {
        ActivityBubble bubble = new ActivityBubble();
        Color errorColor = new Color(210, 70, 70);
        UIManager.put("Component.error.focusedBorderColor", errorColor);

        SwingUtilities.invokeAndWait(() -> bubble.setTitle("✗ write file — denied"));

        JLabel titleLabel = findComponents(bubble, JLabel.class).stream()
                .filter(label -> "✗ write file — denied".equals(label.getText()))
                .findFirst()
                .orElseThrow();
        assertThat(titleLabel.getForeground()).isEqualTo(errorColor);
    }

    @Test
    @DisplayName("Activity bubble uses accent title color while streaming")
    void setStreaming_whenEnabled_usesAccentTitleColor() throws Exception {
        ActivityBubble bubble = new ActivityBubble();
        Color accent = new Color(80, 120, 240);
        UIManager.put("Component.accentColor", accent);

        SwingUtilities.invokeAndWait(() -> bubble.setStreaming(true));

        JLabel titleLabel = findComponents(bubble, JLabel.class).stream()
                .filter(label -> "Thinking".equals(label.getText()))
                .findFirst()
                .orElseThrow();
        assertThat(titleLabel.getForeground()).isEqualTo(accent);
    }

    @Test
    @DisplayName("Activity bubble disposes its embedded message renderer")
    void dispose_whenCalled_disposesRenderedMessageView() throws Exception {
        ActivityBubble bubble = new ActivityBubble();

        SwingUtilities.invokeAndWait(() -> {
            bubble.setText("thinking");
            bubble.dispose();
        });

        MessageBubble renderedBubble = findComponents(bubble, MessageBubble.class).getFirst();
        assertThat(bubble.isDisposed()).isTrue();
        assertThat(renderedBubble.isDisposed()).isTrue();
    }

    @Test
    @DisplayName("Visible streaming listener reports active generation lifecycle")
    void onSend_whenStreamingVisible_notifiesVisibleStreamingChanges() throws Exception {
        var releaseStream = new CountDownLatch(1);
        var observedStates = new ArrayList<Boolean>();
        subject.setOnVisibleStreamingChanged(observedStates::add);
        setField(subject, "selectedProviderName", "OpenAI");
        setField(subject, "selectedModelId", "gpt-5-mini");
        setCurrentProvider(subject, new ProviderService() {
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
                try {
                    releaseStream.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                onToken.accept("pong");
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("gpt-5-mini");
            }

            @Override
            public String name() {
                return "OpenAI";
            }

            @Override
            public String envVarName() {
                return "OPENAI_API_KEY";
            }
        });

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> observedStates.contains(true));
        releaseStream.countDown();
        awaitCondition(2, TimeUnit.SECONDS, () -> observedStates.contains(false));

        assertThat(observedStates).containsSubsequence(true, false);
    }

    @Test
    @DisplayName("Composer is centered and constrained inside workspace")
    void layout_whenWidePanel_constrainsComposerWidth() throws Exception {
        subject.setSize(1400, 900);
        subject.doLayout();
        flushEdt();

        assertThat(subject.getInputBar().getWidth()).isLessThanOrEqualTo(920);
    }

    @Test
    @DisplayName("Chat panel exposes icon render mode buttons for the title bar")
    void constructor_whenCreated_restoresRenderModeButtonsOnly() {
        List<JToggleButton> renderModeButtons = findComponents(subject.getRenderTogglePanel(), JToggleButton.class);

        assertThat(renderModeButtons.stream().map(AbstractButton::getText))
                .containsOnly("");
        assertThat(renderModeButtons.stream().map(AbstractButton::getToolTipText))
                .contains("Preview rendered markdown", "Show raw markdown")
                .doesNotContain("Project");
        assertThat(renderModeButtons.stream()
                .filter(button -> Strings.CS.equals(button.getToolTipText(), "Preview rendered markdown"))
                .findFirst()
                .orElseThrow()
                .isSelected()).isTrue();
        assertThat(findComponents(subject, JButton.class).stream().map(JButton::getToolTipText))
                .doesNotContain("More conversation actions");
        assertThat(findComponents(subject, JLabel.class).stream().map(JLabel::getText))
                .doesNotContain("New chat");
    }

    @Test
    @DisplayName("Search the web suggestion enables web search toggle")
    void searchTheWebSuggestion_whenClicked_enablesWebSearch() throws Exception {
        AtomicReference<Boolean> notified = new AtomicReference<>();
        subject.getInputBar().addWebSearchEnabledListener(notified::set);
        subject.getInputBar().setWebSearchOptions(
                List.of(new WebSearchOption("native", "Native", WebSearchMode.NATIVE, true)),
                "native"
        );

        JButton searchSuggestion = findComponents(subject, JButton.class).stream()
                .filter(button -> Strings.CS.equals(button.getText(), "Search the web"))
                .findFirst()
                .orElseThrow();

        SwingUtilities.invokeAndWait(searchSuggestion::doClick);

        assertThat(subject.getInputBar().getRawText()).isEqualTo("Search the web");
        assertThat(subject.getInputBar().isWebSearchEnabled()).isTrue();
        assertThat(notified.get()).isTrue();
    }

    @Test
    @DisplayName("Prompt quick action buttons invoke command center prompt actions")
    void promptQuickActionButton_whenClicked_invokesActionWithoutReplacingInput() throws Exception {
        AtomicInteger invoked = new AtomicInteger();

        subject.setPromptQuickActions(List.of(new ChatPanel.PromptQuickAction("Summarize", invoked::incrementAndGet)));

        JButton summarizeButton = findComponents(subject, JButton.class).stream()
                .filter(button -> Strings.CS.equals(button.getText(), "Summarize"))
                .findFirst()
                .orElseThrow();

        SwingUtilities.invokeAndWait(summarizeButton::doClick);

        assertThat(invoked).hasValue(1);
        assertThat(subject.getInputBar().getRawText()).isEmpty();
    }

    @Test
    @DisplayName("Loaded assistant findings remain normal chat content")
    void loadHistory_whenAssistantContainsFindings_rendersAsAssistantMessage() throws Exception {
        subject.loadHistory(List.of(
                Message.user("Review codebase"),
                Message.assistant("""
                        Findings

                        P1 Agent bash escapes selected root
                        Agent Mode documents bash as running within selected folder.
                        LocalToolRuntime.java:218-233
                        """)
        ));

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<MessageBubble> assistantBubbles = findComponents(messagesPanel, MessageBubble.class).stream()
                .filter(bubble -> bubble.getRole() == Role.ASSISTANT)
                .toList();

        assertThat(assistantBubbles).hasSize(1);
        assertThat(findComponents(messagesPanel, JLabel.class).stream().map(JLabel::getText))
                .doesNotContain("1 finding");
        assertThat(subject.getHistory()).extracting(Message::content)
                .contains("Findings\n\nP1 Agent bash escapes selected root\nAgent Mode documents bash as running within selected folder.\nLocalToolRuntime.java:218-233\n");
    }

    @Test
    @DisplayName("Selecting a non-seed model still creates a runtime provider for the selected provider")
    void setSelectedModel_whenModelIsNotPartOfSeedModels_createsProviderForSelectedProvider() throws Exception {
        subject.setSelectedModel("Ollama > llama3.2:latest");

        awaitProviderResolved(subject);

        Object currentProvider = readCurrentProvider(subject);
        assertThat(subject.getSelectedModel()).isEqualTo("Ollama > llama3.2:latest");
        assertThat(currentProvider).isNotNull();
        assertThat(currentProvider).isInstanceOf(ProviderService.class);
    }

    @Test
    @DisplayName("Selecting an unavailable provider clears any previously selected runtime provider")
    void setSelectedModel_whenProviderIsUnavailable_clearsPreviousRuntimeProvider() throws Exception {
        subject.setSelectedModel("Ollama > llama3.2:latest");
        awaitProviderResolved(subject);
        assertThat(readCurrentProvider(subject)).isNotNull();

        subject.setSelectedModel("UnavailableProvider > some-model");

        assertThat(readCurrentProvider(subject)).isNull();
    }

    @Test
    @DisplayName("Selecting a model does not block the UI while provider creation is still running")
    void setSelectedModel_whenProviderFactoryBlocks_returnsBeforeProviderIsReady() throws Exception {
        var factoryStarted = new CountDownLatch(1);
        var releaseFactory = new CountDownLatch(1);
        ProviderRegistry.ProviderDef provider = new ProviderRegistry.ProviderDef(
                "SlowProvider",
                null,
                null,
                List.of("slow-model"),
                ProviderCapabilities.chatAndModels(),
                model -> {
                    factoryStarted.countDown();
                    try {
                        releaseFactory.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return immediateProvider("ok");
                },
                List::of
        );
        setField(subject, "providerMap", Map.of(provider.name(), provider));

        SwingUtilities.invokeAndWait(() -> subject.setSelectedModel("SlowProvider > slow-model"));

        assertThat(factoryStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(readCurrentProvider(subject)).isNull();
        assertThat((boolean) readField(subject, "currentProviderResolving")).isTrue();

        releaseFactory.countDown();
        awaitProviderResolved(subject);
    }

    @Test
    @DisplayName("Selecting an LM Studio model keeps the UI responsive while local probes run")
    void setSelectedModel_whenLmStudioModelSelected_runsLocalProbesOffEdt() throws Exception {
        var requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        try {
            var provider = new ProviderRegistry.ProviderDef(
                    "LM Studio",
                    null,
                    "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort()),
                    List.of("local-model"),
                    ProviderCapabilities.chatAndModels(),
                    model -> immediateProvider("ok"),
                    List::of
            );
            setField(subject, "providerMap", Map.of(provider.name(), provider));

            SwingUtilities.invokeAndWait(() -> subject.setSelectedModel("LM Studio > local-model"));

            awaitCondition(2, TimeUnit.SECONDS, () -> requestCount.get() > 0);
        } finally {
            server.stop(0);
        }
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
        setCurrentProvider(subject, immediateProvider("pong"));
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
            public AgentProviderAdapter create(
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
        setCurrentProvider(subject, new ProviderService() {
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
    @DisplayName("Agent mode renders tool bubbles before the final answer when tools run first")
    void onSend_whenAgentModeUsesToolsBeforeAnswer_rendersSeparateToolBubblesBeforeAssistantAnswer() throws Exception {
        Path projectRoot = Files.createTempDirectory("chat4j-agent-tools-bubble");
        Files.writeString(projectRoot.resolve("note.txt"), "hello tool");
        AtomicInteger turns = new AtomicInteger();

        subject.getInputBar().setAgentModeAvailable(true);
        subject.getInputBar().setAgentProjectRoot(projectRoot);
        subject.getInputBar().setAgentModeEnabled(true);

        subject.setAgentOrchestratorForTests(new AgentOrchestrator(new AgentProviderAdapterFactory() {
            @Override
            public AgentProviderAdapter create(
                    String providerName,
                    String modelId,
                    String baseUrl,
                    String apiKey,
                    ProviderService providerService,
                    String agentSystemPromptAppend
            ) {
                return (request, callbacks) -> {
                    if (turns.incrementAndGet() == 1) {
                        return AgentTurnResult.continueWithTools(List.of(
                                new ToolInvocationRequest(
                                        "list-root",
                                        "ls",
                                        "{\"path\":\".\"}"
                                ),
                                new ToolInvocationRequest(
                                        "read-note",
                                        "read",
                                        "{\"path\":\"note.txt\"}"
                                )
                        ));
                    }

                    callbacks.onToken().accept("agent-response");
                    return AgentTurnResult.complete();
                };
            }
        }, new LocalToolRuntime()));

        setField(subject, "selectedProviderName", "OpenAI");
        setField(subject, "selectedModelId", "gpt-5-mini");
        setCurrentProvider(subject, immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<ActivityBubble> toolBubbles = findComponents(messagesPanel, ActivityBubble.class).stream()
                .filter(bubble -> thinkingBubbleTitle(bubble).startsWith("✓ "))
                .toList();

        assertThat(toolBubbles).hasSize(2);
        assertThat(thinkingBubbleTitle(toolBubbles.getFirst())).isEqualTo("✓ ls .");
        assertThat(thinkingBubbleTitle(toolBubbles.get(1))).isEqualTo("✓ read note.txt");
        assertThat(hasVisibleCollapseToggle(toolBubbles.getFirst())).isFalse();
        assertThat(hasVisibleCollapseToggle(toolBubbles.get(1))).isFalse();
        assertThat(messageRowIndex(messagesPanel, toolBubbles.getFirst()))
                .isLessThan(messageRowIndex(messagesPanel, assistantBubble(messagesPanel)));
        assertThat(messageRowIndex(messagesPanel, toolBubbles.get(1)))
                .isLessThan(messageRowIndex(messagesPanel, assistantBubble(messagesPanel)));

        Message assistantMessage = subject.getHistory().get(1);
        assertThat(assistantMessage.meta().agentToolActivities())
                .extracting(AgentToolActivityMeta::toolName)
                .containsExactly("ls", "read");
        assertThat(assistantMessage.meta().agentToolActivities())
                .extracting(AgentToolActivityMeta::status)
                .containsExactly("SUCCEEDED", "SUCCEEDED");
    }

    @Test
    @DisplayName("Loading history restores persisted agent tool invocation bubbles")
    void loadHistory_whenAssistantHasAgentToolActivities_restoresToolBubbles() throws Exception {
        Message assistantMessage = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("done")),
                Instant.now(),
                new MessageMeta(
                        emptyList(),
                        emptyList(),
                        false,
                        "",
                        "",
                        "",
                        List.of(
                                new AgentToolActivityMeta("read-note", "read", "SUCCEEDED", "path=note.txt", ""),
                                new AgentToolActivityMeta("grep-error", "grep", "FAILED", "path=., query=todo", "no matches")
                        )
                )
        );

        SwingUtilities.invokeAndWait(() -> subject.loadHistory(List.of(Message.user("run tools"), assistantMessage)));

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<ActivityBubble> toolBubbles = findComponents(messagesPanel, ActivityBubble.class).stream()
                .filter(bubble -> !hasVisibleCollapseToggle(bubble))
                .toList();

        assertThat(toolBubbles).hasSize(2);
        assertThat(thinkingBubbleTitle(toolBubbles.getFirst())).isEqualTo("✓ read note.txt");
        assertThat(thinkingBubbleTitle(toolBubbles.get(1))).isEqualTo("✗ grep . todo — no matches");
        assertThat(messageRowIndex(messagesPanel, toolBubbles.getFirst()))
                .isLessThan(messageRowIndex(messagesPanel, assistantBubble(messagesPanel)));
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
            public AgentProviderAdapter create(
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
        setCurrentProvider(subject, immediateProvider("pong"));

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
    @DisplayName("Conversation loading blocks sends until history is applied")
    void onSend_whenConversationIsLoading_doesNotStartSend() throws Exception {
        var providerCalls = new AtomicInteger();
        setCurrentProvider(subject, new ProviderService() {
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
                providerCalls.incrementAndGet();
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
        SwingUtilities.invokeAndWait(() -> {
            textArea.setText("ping");
            subject.setConversationLoading(true);
        });

        invokeOnSend(subject);
        flushEdt();

        assertThat(providerCalls).hasValue(0);
        assertThat(subject.getHistory()).isEmpty();
        assertThat(subject.getInputBar().isEnabled()).isFalse();

        SwingUtilities.invokeAndWait(() -> subject.setConversationLoading(false));
        assertThat(subject.getInputBar().isEnabled()).isTrue();
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
    @DisplayName("Visible cancel falls back to legacy provider cancellation when no session handle exists")
    void cancelStreaming_whenLegacyProviderHasNoSessionHandle_callsProviderCancel() throws Exception {
        var streamStarted = new CountDownLatch(1);
        var releaseStream = new CountDownLatch(1);
        var providerCancels = new AtomicInteger();

        setCurrentProvider(subject, new ProviderService() {
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
                streamStarted.countDown();
                try {
                    releaseStream.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void cancelActiveRequest() {
                providerCancels.incrementAndGet();
                releaseStream.countDown();
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

        assertThat(streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
        subject.cancelStreaming();
        flushEdt();

        assertThat(providerCancels).hasValue(1);
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

        setCurrentProvider(subject, immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        assertThat(subject.getInputBar().isEnabled()).isFalse();
        assertThat(subject.getInputBar().isCancelGenerationVisible()).isTrue();
        assertThat(subject.getHistory()).isEmpty();

        releasePreparation.countDown();
        awaitCondition(5, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2 && subject.getInputBar().isEnabled();
        });
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

        setCurrentProvider(subject, immediateProvider("pong"));

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

        setCurrentProvider(subject, immediateProvider("pong"));

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
    @DisplayName("Jump to latest remains visible when scroll is not at conversation end")
    void setAutoScrollEnabled_whenScrollNotAtBottom_showsJumpToLatestButton() throws Exception {
        setField(subject, "atBottom", false);

        subject.setAutoScrollEnabled(true);

        JComponent jumpToLatestOverlay = (JComponent) readField(subject, "jumpToLatestOverlay");
        assertThat(jumpToLatestOverlay.isVisible()).isTrue();
    }

    @Test
    @DisplayName("Jump to latest stays hidden when streaming at conversation end")
    void setAutoScrollEnabled_whenStreamingAtBottom_keepsJumpToLatestHidden() throws Exception {
        setField(subject, "atBottom", true);
        setField(subject, "streaming", true);

        Method method = ChatPanel.class.getDeclaredMethod("refreshJumpOverlay");
        method.setAccessible(true);
        method.invoke(subject);

        JComponent jumpToLatestOverlay = (JComponent) readField(subject, "jumpToLatestOverlay");
        assertThat(jumpToLatestOverlay.isVisible()).isFalse();
    }

    @Test
    @DisplayName("Jump to latest stops animating when streaming ends away from bottom")
    void updateGenerationIndicator_whenStreamingEndsAwayFromBottom_stopsJumpAnimation() throws Exception {
        setField(subject, "atBottom", false);
        JumpToLatestButton jumpToLatestOverlay = (JumpToLatestButton) readField(subject, "jumpToLatestOverlay");
        SwingUtilities.invokeAndWait(() -> {
            jumpToLatestOverlay.setVisible(true);
            jumpToLatestOverlay.setStreaming(true);
        });

        Method method = ChatPanel.class.getDeclaredMethod("updateGenerationIndicator");
        method.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            try {
                method.invoke(subject);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(jumpToLatestOverlay.isVisible()).isTrue();
        assertThat(jumpToLatestOverlay.isStreaming()).isFalse();
    }

    @Test
    @DisplayName("Render mode switch updates state and emits change callback")
    void setRenderMode_whenChanged_updatesStateAndNotifiesListener() {
        var capturedMode = new AtomicReference<RenderMode>();
        subject.setOnRenderModeChanged(capturedMode::set);

        subject.setRenderMode(RenderMode.MARKDOWN, true);

        assertThat(subject.getRenderMode()).isEqualTo(RenderMode.MARKDOWN);
        assertThat(capturedMode.get()).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Render mode buttons use icons instead of visible text")
    void renderModeToggle_whenCreated_usesIconButtons() throws Exception {
        JToggleButton previewToggle = (JToggleButton) readField(subject, "previewToggle");
        JToggleButton markdownToggle = (JToggleButton) readField(subject, "markdownToggle");

        assertThat(previewToggle.getText()).isEmpty();
        assertThat(markdownToggle.getText()).isEmpty();
        assertThat(previewToggle.getIcon()).isNotNull();
        assertThat(markdownToggle.getIcon()).isNotNull();
        assertThat(previewToggle.getAccessibleContext().getAccessibleName()).isEqualTo(RenderMode.PREVIEW.displayName());
        assertThat(markdownToggle.getAccessibleContext().getAccessibleName()).isEqualTo(RenderMode.MARKDOWN.displayName());
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
        JPopupMenu popup = contentPopupMenu(bubble);
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
    @DisplayName("Switching render mode rerenders loaded user and assistant bubbles")
    void setRenderMode_whenHistoryLoaded_updatesMessageBubbleModes() throws Exception {
        subject.loadHistory(List.of(
                Message.user("**user**"),
                Message.assistant("**assistant**")
        ));

        subject.setRenderMode(RenderMode.MARKDOWN, true);

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<MessageBubble> bubbles = findComponents(messagesPanel, MessageBubble.class).stream()
                .filter(bubble -> !hasAncestor(bubble, ActivityBubble.class))
                .toList();
        assertThat(bubbles).hasSize(2);
        assertThat(bubbles).allSatisfy(bubble -> assertThat(readBubbleRenderMode(bubble)).isEqualTo(RenderMode.MARKDOWN));
        assertThat(bubbles.stream().map(MessageBubble::contentHtmlSnapshot)).allSatisfy(html -> assertThat(html).contains("**"));
    }

    @Test
    @DisplayName("Loading another conversation keeps the selected render-mode toggle")
    void loadHistory_whenRenderModeSelected_preservesRenderModeToggle() throws Exception {
        subject.setRenderMode(RenderMode.MARKDOWN, true);

        subject.loadHistory(List.of(Message.user("hello from another chat")));
        flushEdt();

        assertThat(subject.getRenderMode()).isEqualTo(RenderMode.MARKDOWN);
        assertThat(((JToggleButton) readField(subject, "markdownToggle")).isSelected()).isTrue();
        assertThat(((JToggleButton) readField(subject, "previewToggle")).isSelected()).isFalse();
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
    @DisplayName("Transcript attachment metadata is populated from the backing user message")
    void conversationAttachments_whenUserMessageHasAttachments_returnsWebViewMetadata() throws Exception {
        AttachmentRef fileRef = new AttachmentRef(UUID.randomUUID(), "/tmp/demo.txt", "demo.txt", "text/plain", 128, "");
        AttachmentRef imageRef = new AttachmentRef(UUID.randomUUID(), "/tmp/image.png", "image.png", "image/png", 256, "");
        Message message = new Message(
                Role.USER,
                List.of(
                        new TextPart("hello there"),
                        new FilePart(fileRef),
                        new ImagePart(imageRef, null, null)
                ),
                Instant.now()
        );

        subject.loadHistory(List.of(message));
        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        Component wrapper = Arrays.stream(messagesPanel.getComponents())
                .filter(component -> !"filler".equals(component.getName()))
                .findFirst()
                .orElseThrow();

        List<ConversationAttachment> attachments = invokeConversationAttachments(subject, wrapper);

        assertThat(attachments)
                .extracting(
                        ConversationAttachment::originalName,
                        ConversationAttachment::mimeType,
                        ConversationAttachment::sizeBytes,
                        ConversationAttachment::image
                )
                .containsExactly(
                        tuple("demo.txt", "text/plain", 128L, false),
                        tuple("image.png", "image/png", 256L, true)
                );
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

        assertThat(buttons).hasSize(3);
        assertThat(after.height).isEqualTo(before.height);
    }

    @Test
    @DisplayName("Read aloud action button invokes Text to Speech service")
    void readAloudButton_whenClicked_invokesTextToSpeechService() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService();
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        subject.loadHistory(List.of(Message.assistant("assistant answer")));
        flushEdt();

        JButton readAloudButton = findComponents(subject, JButton.class).stream()
                .filter(button -> "Read aloud".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow();

        SwingUtilities.invokeAndWait(readAloudButton::doClick);
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isEqualTo("assistant answer");
        assertThat(findComponents(subject, JButton.class).stream().map(JButton::getToolTipText))
                .contains("Stop");
    }

    @Test
    @DisplayName("Read aloud web transcript action uses message indexes without duplicate action bars")
    void handleWebTranscriptAction_whenReadAloudAfterUserMessage_invokesTextToSpeechService() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService();
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        subject.loadHistory(List.of(
                Message.user("question"),
                Message.assistant("assistant answer")
        ));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        method.invoke(subject, "read-aloud", 1, "");
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isEqualTo("assistant answer");
    }

    @Test
    @DisplayName("Read aloud web transcript action uses stored message text for valid indexes")
    void handleWebTranscriptAction_whenReadAloudIndexIsValid_usesStoredMessageText() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService();
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        subject.loadHistory(List.of(Message.assistant("stored assistant answer")));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        method.invoke(subject, "read-aloud", 0, "browser rendered answer");
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isEqualTo("stored assistant answer");
    }

    @Test
    @DisplayName("Read aloud web transcript action can use browser-provided text when index is unavailable")
    void handleWebTranscriptAction_whenReadAloudIndexUnavailable_usesTextPayload() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService();
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        method.invoke(subject, "read-aloud", -1, "assistant answer");
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isEqualTo("assistant answer");
    }

    @Test
    @DisplayName("Read aloud web transcript action uses bubble history index when activity-only assistant messages are skipped")
    void handleWebTranscriptAction_whenActivityOnlyAssistantIsSkipped_usesBubbleHistoryIndex() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService();
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        Message activityOnlyAssistant = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("")),
                Instant.now(),
                new MessageMeta(emptyList(), emptyList(), false, "", "thinking", "searching")
        );
        subject.loadHistory(List.of(
                Message.user("question"),
                activityOnlyAssistant,
                Message.assistant("assistant answer")
        ));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        method.invoke(subject, "read-aloud", 2, "assistant answer");
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isEqualTo("assistant answer");
        assertThat(textToSpeechService.requestedKey()).isEqualTo("web:1");
    }

    @Test
    @DisplayName("Read aloud web transcript action resolves visible indexes shifted by skipped activity messages")
    void handleWebTranscriptAction_whenVisibleIndexIsShiftedByActivity_resolvesAssistantBubbleIndex() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService();
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        Message activityOnlyAssistant = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("")),
                Instant.now(),
                new MessageMeta(emptyList(), emptyList(), false, "", "thinking", "searching")
        );
        subject.loadHistory(List.of(
                Message.user("question"),
                activityOnlyAssistant,
                Message.assistant("assistant answer")
        ));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        method.invoke(subject, "read-aloud", 1, "assistant answer");
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isEqualTo("assistant answer");
        assertThat(textToSpeechService.requestedKey()).isEqualTo("web:1");
    }

    @Test
    @DisplayName("Web transcript actions use visible message indexes when web search activity is present")
    void handleWebTranscriptAction_whenWebSearchActivityPresent_usesVisibleMessageIndex() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService();
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        Message assistantMessage = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("assistant answer")),
                Instant.now(),
                new MessageMeta(emptyList(), emptyList(), false, "", "", "**Searched**\n- java copy message")
        );
        subject.loadHistory(List.of(Message.user("question"), assistantMessage));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        method.invoke(subject, "read-aloud", 1, "");
        flushEdt();

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        assertThat(findComponents(messagesPanel, ActivityBubble.class)).hasSize(1);
        assertThat(textToSpeechService.requestedText()).isEqualTo("assistant answer");
        assertThat(textToSpeechService.requestedKey()).isEqualTo("web:1");
    }

    @Test
    @DisplayName("Regenerating recent assistant response uses stored message indexes")
    void regenerateRecentResponse_whenRecentBubbleIsAssistant_usesStoredMessageIndex() throws Exception {
        subject.loadHistory(List.of(
                Message.user("question"),
                Message.assistant("old answer")
        ));
        setCurrentProvider(subject, immediateProvider("new answer"));
        flushEdt();

        assertThat(subject.canRegenerateRecentResponse()).isTrue();

        subject.regenerateRecentResponse();
        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            List<Message> history = subject.getHistory();
            return history.size() == 2 && "new answer".equals(history.getLast().content());
        });

        assertThat(subject.getHistory())
                .extracting(Message::content)
                .containsExactly("question", "new answer");
    }

    @Test
    @DisplayName("Regenerating recent response uses history indexes when activity-only assistant entries are hidden")
    void regenerateRecentResponse_whenActivityOnlyAssistantEntryIsHidden_usesHistoryMessageIndex() throws Exception {
        Message activityOnlyAssistant = new Message(
                Role.ASSISTANT,
                emptyList(),
                Instant.now(),
                new MessageMeta(emptyList(), emptyList(), false, "", "", "**Searched**\n- earlier search")
        );
        subject.loadHistory(List.of(
                Message.user("first question"),
                activityOnlyAssistant,
                Message.user("second question")
        ));
        setCurrentProvider(subject, immediateProvider("second answer"));
        flushEdt();

        assertThat(subject.canRegenerateRecentResponse()).isTrue();

        subject.regenerateRecentResponse();
        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            List<Message> history = subject.getHistory();
            return history.size() == 4 && "second answer".equals(history.getLast().content());
        });

        assertThat(subject.getHistory())
                .extracting(Message::content)
                .containsExactly("first question", "", "second question", "second answer");
    }

    @Test
    @DisplayName("Edit user message save only updates history and preserves later assistant response")
    void editUserMessage_whenSaveOnly_updatesHistoryWithoutTruncating() throws Exception {
        var submitted = new AtomicInteger();
        subject.setOnMessageSubmitted(submitted::incrementAndGet);
        subject.loadHistory(List.of(
                Message.user("old question"),
                Message.assistant("old answer")
        ));
        flushEdt();

        JButton editButton = findComponents(subject, JButton.class).stream()
                .filter(button -> "Edit message".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow();
        SwingUtilities.invokeAndWait(editButton::doClick);
        JTextArea textArea = readInputTextArea(subject.getInputBar());
        assertThat(textArea.getText()).isEqualTo("old question");

        SwingUtilities.invokeAndWait(() -> textArea.setText("updated question"));
        JButton saveOnlyButton = findComponents(subject, JButton.class).stream()
                .filter(button -> "Save only".equals(button.getText()))
                .findFirst()
                .orElseThrow();
        SwingUtilities.invokeAndWait(saveOnlyButton::doClick);

        assertThat(subject.getHistory()).extracting(Message::content)
                .containsExactly("updated question", "old answer");
        assertThat(submitted).hasValue(1);
    }

    @Test
    @DisplayName("Cancelling user message edit restores composer draft and leaves history unchanged")
    void editUserMessage_whenCancelled_restoresDraftAndKeepsHistory() throws Exception {
        subject.loadHistory(List.of(Message.user("old question")));
        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("draft text"));
        flushEdt();

        JButton editButton = findComponents(subject, JButton.class).stream()
                .filter(button -> "Edit message".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow();
        SwingUtilities.invokeAndWait(editButton::doClick);
        SwingUtilities.invokeAndWait(() -> textArea.setText("changed edit"));

        JButton cancelButton = findComponents(subject, JButton.class).stream()
                .filter(button -> "Cancel editing".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow();
        SwingUtilities.invokeAndWait(cancelButton::doClick);

        assertThat(subject.getHistory()).extracting(Message::content).containsExactly("old question");
        assertThat(textArea.getText()).isEqualTo("draft text");
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

        setCurrentProvider(subject, immediateProvider("pong"));

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

        setCurrentProvider(subject, new ProviderService() {
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
    void onSend_whenProviderDoesNotEmitThinking_doesNotRenderActivityBubble() throws Exception {
        setCurrentProvider(subject, immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        assertThat(findComponents(messagesPanel, ActivityBubble.class)).isEmpty();
    }

    @Test
    @DisplayName("Loading history skips thinking bubbles when thinking text has no visible content")
    void loadHistory_whenAssistantThinkingIsInvisible_doesNotRenderActivityBubble() throws Exception {
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
        assertThat(findComponents(messagesPanel, ActivityBubble.class)).isEmpty();
    }

    @Test
    @DisplayName("Thinking bubbles loaded from history are collapsed by default")
    void loadHistory_whenAssistantThinkingExists_rendersCollapsedActivityBubbleByDefault() throws Exception {
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
        List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
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
            subject.setRenderMode(RenderMode.PREVIEW, true);
        });
        flushEdt();

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
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

        setCurrentProvider(subject, new ProviderService() {
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
        List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
        assertThat(thinkingBubbles).hasSize(1);

        ActivityBubble thinkingBubble = thinkingBubbles.getFirst();
        assertThat(findComponents(thinkingBubble, JScrollPane.class)).isEmpty();

        List<JEditorPane> panes = findComponents(thinkingBubble, JEditorPane.class);
        assertThat(panes).isNotEmpty();
        assertThat(panes.getFirst().getDocument().getLength()).isGreaterThan(5);
    }

    @Test
    @DisplayName("Thinking bubble copy button appears on hover")
    void loadHistory_whenActivityBubbleHovered_showsCopyButton() throws Exception {
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
        ActivityBubble thinkingBubble = findComponents(messagesPanel, ActivityBubble.class).getFirst();
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
        ActivityBubble thinkingBubble = findComponents(messagesPanel, ActivityBubble.class).getFirst();
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
        setCurrentProvider(subject, new ProviderService() {
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

        // Allow headroom for asynchronous send/completion dispatch on slower CI.
        awaitCondition(5, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        Message assistant = subject.getHistory().get(1);
        assertThat(assistant.content()).contains("final answer");
        assertThat(assistant.meta().assistantThinking()).isEmpty();

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        assertThat(findComponents(messagesPanel, ActivityBubble.class)).isEmpty();
    }

    @Test
    @DisplayName("Thinking stream ignores non-visible control tokens and keeps visible text")
    void onSend_whenThinkingIncludesControlSequences_keepsVisibleThinkingText() throws Exception {
        subject.getInputBar().setThinkingAvailable(true);
        subject.getInputBar().setThinkingEnabled(true);

        setCurrentProvider(subject, new ProviderService() {
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
        List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
        assertThat(thinkingBubbles).hasSize(1);
        assertThat(thinkingBubbles.getFirst().getFullText()).contains("First visible part");
        assertThat(thinkingBubbles.getFirst().getFullText()).contains("second visible part");
    }

    @Test
    @DisplayName("Assistant response is persisted only once even when provider emits late error after complete")
    void onSend_whenProviderSignalsCompleteThenError_persistsAssistantOnce() throws Exception {
        setCurrentProvider(subject, new ProviderService() {
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

        awaitCondition(5, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        long assistantCount = subject.getHistory().stream()
                .filter(message -> message.role() == Role.ASSISTANT)
                .count();
        assertThat(assistantCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Think tags emitted in answer tokens are rendered as thinking for any provider")
    void onSend_whenProviderEmitsThinkTagsInAnswerTokens_extractsThinkingModelAgnostically() throws Exception {
        subject.getInputBar().setThinkingAvailable(true);
        subject.getInputBar().setThinkingEnabled(true);

        setCurrentProvider(subject, new ProviderService() {
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
                onToken.accept("<thi");
                onToken.accept("nk>hidden reasoning</thi");
                onToken.accept("nk>visible answer");
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

        awaitCondition(5, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        Message assistant = subject.getHistory().get(1);
        assertThat(assistant.content()).isEqualTo("visible answer");
        assertThat(assistant.meta().assistantThinking()).contains("hidden reasoning");

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
        assertThat(thinkingBubbles).hasSize(1);
        assertThat(thinkingBubbles.getFirst().getFullText()).contains("hidden reasoning");
        assertThat(messageRowIndex(messagesPanel, thinkingBubbles.getFirst()))
                .isLessThan(messageRowIndex(messagesPanel, assistantBubble(messagesPanel)));
    }

    @Test
    @DisplayName("Think tags in answer tokens render as thinking even when reasoning is disabled")
    void onSend_whenReasoningDisabledAndProviderEmitsThinkTags_rendersActivityBubble() throws Exception {
        subject.getInputBar().setThinkingAvailable(false);
        subject.getInputBar().setThinkingEnabled(false);
        setCurrentProvider(subject, immediateProvider("<think>hidden reasoning</think>visible answer"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("question"));
        invokeOnSend(subject);

        awaitCondition(5, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2;
        });

        Message assistant = subject.getHistory().get(1);
        assertThat(assistant.content()).isEqualTo("visible answer");
        assertThat(assistant.meta().assistantThinking()).contains("hidden reasoning");

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
        assertThat(thinkingBubbles).hasSize(1);
        assertThat(thinkingBubbles.getFirst().getFullText()).contains("hidden reasoning");
        assertThat(messageRowIndex(messagesPanel, thinkingBubbles.getFirst()))
                .isLessThan(messageRowIndex(messagesPanel, assistantBubble(messagesPanel)));
    }

    @Test
    @DisplayName("Native thinking tokens are rendered and persisted separately from assistant answer text")
    void onSend_whenProviderEmitsThinking_persistsThinkingInAssistantMetaAndRendersActivityBubble() throws Exception {
        subject.getInputBar().setThinkingAvailable(true);
        subject.getInputBar().setThinkingEnabled(true);

        setCurrentProvider(subject, new ProviderService() {
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
        List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
        assertThat(thinkingBubbles).hasSize(1);
        assertThat(thinkingBubbles.getFirst().getFullText()).contains("compare enum features");
        assertThat(messageRowIndex(messagesPanel, thinkingBubbles.getFirst()))
                .isLessThan(messageRowIndex(messagesPanel, assistantBubble(messagesPanel)));
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

        setCurrentProvider(subject, immediateProvider("pong"));

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

        setCurrentProvider(subject, immediateProvider("pong"));

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

        setCurrentProvider(subject, immediateProvider("pong"));

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
    @DisplayName("Loading conversation history recovers pending assistant for the loaded conversation")
    void loadConversationHistory_whenPendingAssistantExists_recoversForLoadedConversation() throws Exception {
        UUID loadedConversationId = UUID.randomUUID();
        UUID otherConversationId = UUID.randomUUID();
        Message recoveredAssistant = Message.assistant("sonar result");
        @SuppressWarnings("unchecked")
        Map<UUID, Message> pendingRecoveries = (Map<UUID, Message>) readField(subject, "pendingCompletedAssistantRecoveries");
        pendingRecoveries.put(loadedConversationId, recoveredAssistant);

        SwingUtilities.invokeAndWait(() -> {
            subject.setActiveConversationId(otherConversationId);
            subject.loadConversationHistory(loadedConversationId, List.of(Message.user("question")));
        });

        assertThat(subject.getHistory()).extracting(Message::content)
                .containsExactly("question", "sonar result");
        assertThat(readField(subject, "activeConversationId")).isEqualTo(loadedConversationId);
        assertThat(pendingRecoveries).doesNotContainKey(loadedConversationId);
    }

    @Test
    @DisplayName("Failed hidden assistant persistence queues recovery for the original conversation")
    void persistAssistantResponse_whenHiddenPersistenceFails_queuesPendingRecovery() throws Exception {
        UUID originalConversationId = UUID.randomUUID();
        UUID visibleConversationId = UUID.randomUUID();
        AtomicInteger persistCalls = new AtomicInteger();
        subject.setActiveConversationId(visibleConversationId);
        subject.setOnAssistantMessageCompleted(event -> {
            persistCalls.incrementAndGet();
            return false;
        });
        StreamingSession session = new StreamingSession(1L, originalConversationId, null);
        session.response.append("background sonar result");

        invokePersistAssistantResponse(subject, session, null, true);

        @SuppressWarnings("unchecked")
        Map<UUID, Message> pendingRecoveries = (Map<UUID, Message>) readField(subject, "pendingCompletedAssistantRecoveries");
        assertThat(persistCalls).hasValue(1);
        assertThat(pendingRecoveries).containsKey(originalConversationId);
        assertThat(subject.getHistory()).isEmpty();

        SwingUtilities.invokeAndWait(() -> {
            subject.setActiveConversationId(originalConversationId);
            subject.loadConversationHistory(originalConversationId, List.of(Message.user("question")));
        });

        assertThat(subject.getHistory()).extracting(Message::content)
                .containsExactly("question", "background sonar result");
        assertThat(pendingRecoveries).doesNotContainKey(originalConversationId);
    }

    @Test
    @DisplayName("Reloading a streaming conversation reattaches the full in-flight assistant text")
    void loadHistory_whenConversationIsStillStreaming_restoresBufferedAssistantText() throws Exception {
        var originalConversationId = UUID.randomUUID();
        var visibleConversationId = UUID.randomUUID();
        var firstTokenDelivered = new CountDownLatch(1);
        var releaseSecondToken = new CountDownLatch(1);

        subject.setActiveConversationId(originalConversationId);
        subject.setConversationIdSupplier(() -> originalConversationId);

        setCurrentProvider(subject, new ProviderService() {
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
                onToken.accept("first ");
                firstTokenDelivered.countDown();
                try {
                    releaseSecondToken.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                onToken.accept("second");
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

        assertThat(firstTokenDelivered.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        SwingUtilities.invokeAndWait(() -> {
            subject.setActiveConversationId(visibleConversationId);
            subject.loadHistory(List.of(Message.user("other chat")));
            subject.setActiveConversationId(originalConversationId);
            subject.loadHistory(List.of(Message.user("ping")));
        });

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        assertThat(assistantBubble(messagesPanel).getFullText()).isEqualTo("first ");

        releaseSecondToken.countDown();
        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2
                    && "first second".equals(subject.getHistory().get(1).content())
                    && "first second".equals(assistantBubble(messagesPanel).getFullText());
        });
    }

    @Test
    @DisplayName("Reloading a streaming conversation during Ollama thinking restores buffered thinking text")
    void loadHistory_whenStreamingThinkingOnly_restoresBufferedThinkingActivity() throws Exception {
        var originalConversationId = UUID.randomUUID();
        var visibleConversationId = UUID.randomUUID();
        var thinkingDelivered = new CountDownLatch(1);
        var releaseAnswer = new CountDownLatch(1);

        subject.setActiveConversationId(originalConversationId);
        subject.setConversationIdSupplier(() -> originalConversationId);

        setCurrentProvider(subject, new ProviderService() {
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
                onToken.accept("<think>hidden reasoning");
                thinkingDelivered.countDown();
                try {
                    releaseAnswer.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                onToken.accept("</think>visible answer");
                onComplete.run();
            }

            @Override
            public List<String> availableModels() {
                return List.of("ollama-model");
            }

            @Override
            public String name() {
                return "Ollama";
            }

            @Override
            public String envVarName() {
                return "OLLAMA_API_KEY";
            }
        });

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        assertThat(thinkingDelivered.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        SwingUtilities.invokeAndWait(() -> {
            subject.setActiveConversationId(visibleConversationId);
            subject.loadHistory(List.of(Message.user("other chat")));
            subject.setActiveConversationId(originalConversationId);
            subject.loadHistory(List.of(Message.user("ping")));
        });

        JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
        ActivityBubble thinkingBubble = findComponents(messagesPanel, ActivityBubble.class).stream()
                .filter(bubble -> bubble.getFullText().contains("hidden reasoning"))
                .findFirst()
                .orElseThrow();
        assertThat(thinkingBubble.isVisible()).isTrue();

        releaseAnswer.countDown();
        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return subject.getHistory().size() == 2
                    && "visible answer".equals(subject.getHistory().get(1).content())
                    && assistantBubble(messagesPanel).getFullText().equals("visible answer");
        });
    }

    @Test
    @DisplayName("Loading stale history after background stream completion keeps completed assistant response")
    void loadHistory_whenBackgroundStreamCompletedAfterRecordsLoaded_keepsAssistantResponse() throws Exception {
        var originalConversationId = UUID.randomUUID();
        var visibleConversationId = UUID.randomUUID();
        var firstTokenDelivered = new CountDownLatch(1);
        var releaseCompletion = new CountDownLatch(1);
        var persistedAssistant = new CountDownLatch(1);

        subject.setActiveConversationId(originalConversationId);
        subject.setConversationIdSupplier(() -> originalConversationId);
        subject.setOnAssistantMessageCompleted(event -> {
            if (event.message().role() == Role.ASSISTANT) {
                persistedAssistant.countDown();
            }
            return true;
        });

        setCurrentProvider(subject, new ProviderService() {
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
                onToken.accept("saved answer");
                firstTokenDelivered.countDown();
                try {
                    releaseCompletion.await(2, TimeUnit.SECONDS);
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

        assertThat(firstTokenDelivered.await(2, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
            subject.setActiveConversationId(visibleConversationId);
            subject.loadHistory(List.of(Message.user("other chat")));
        });

        releaseCompletion.countDown();
        assertThat(persistedAssistant.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        SwingUtilities.invokeAndWait(() -> {
            subject.setActiveConversationId(originalConversationId);
            subject.loadHistory(List.of(Message.user("ping")));
        });

        assertThat(subject.getHistory()).hasSize(2);
        assertThat(subject.getHistory().get(1).role()).isEqualTo(Role.ASSISTANT);
        assertThat(subject.getHistory().get(1).content()).isEqualTo("saved answer");

        SwingUtilities.invokeAndWait(() -> subject.loadHistory(List.of(Message.user("ping"))));

        assertThat(subject.getHistory()).hasSize(1);
        assertThat(subject.getHistory().getFirst().content()).isEqualTo("ping");
    }

    @Test
    @DisplayName("Switching conversations keeps the original stream running in the background")
    void setActiveConversationId_whenSwitchingAwayFromStreamingConversation_keepsOriginalStreamRunning() throws Exception {
        var originalConversationId = UUID.randomUUID();
        var visibleConversationId = UUID.randomUUID();
        var tokenDelivered = new CountDownLatch(1);
        var releaseCompletion = new CountDownLatch(1);
        var persistedAssistant = new CountDownLatch(1);
        var cancellationObserved = new AtomicReference<Boolean>();

        subject.setActiveConversationId(originalConversationId);
        subject.setConversationIdSupplier(() -> originalConversationId);
        subject.setOnAssistantMessageCompleted(event -> {
            if (event.message().role() == Role.ASSISTANT) {
                persistedAssistant.countDown();
            }
            return true;
        });

        setCurrentProvider(subject, new ProviderService() {
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
                onToken.accept("background answer");
                tokenDelivered.countDown();
                try {
                    releaseCompletion.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                cancellationObserved.set(isCancelled.getAsBoolean());
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
        SwingUtilities.invokeAndWait(() -> {
            subject.setActiveConversationId(visibleConversationId);
            subject.loadHistory(List.of(Message.user("other chat")));
        });

        releaseCompletion.countDown();
        assertThat(persistedAssistant.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        assertThat(cancellationObserved.get()).isFalse();
        assertThat(subject.getHistory()).extracting(Message::content).containsExactly("other chat");

        SwingUtilities.invokeAndWait(() -> {
            subject.setActiveConversationId(originalConversationId);
            subject.loadHistory(List.of(Message.user("ping")));
        });

        assertThat(subject.getHistory()).extracting(Message::content)
                .containsExactly("ping", "background answer");
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

        setCurrentProvider(subject, new ProviderService() {
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

    private static void setCurrentProvider(ChatPanel chatPanel, ProviderService provider) throws Exception {
        setField(chatPanel, "currentProvider", provider);
        // Direct provider injection represents a ready provider; constructor-started async model resolution may still be running.
        setField(chatPanel, "currentProviderResolving", false);
    }

    private static void awaitProviderResolved(ChatPanel chatPanel) throws Exception {
        awaitCondition(2, TimeUnit.SECONDS, () -> readCurrentProvider(chatPanel) != null
                && !(boolean) readField(chatPanel, "currentProviderResolving"));
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

    private static void invokePersistAssistantResponse(
            ChatPanel chatPanel,
            StreamingSession session,
            SendJob sendJob,
            boolean allowBlankContent
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod(
                "persistAssistantResponse",
                StreamingSession.class,
                SendJob.class,
                boolean.class
        );
        method.setAccessible(true);
        method.invoke(chatPanel, session, sendJob, allowBlankContent);
    }

    @SuppressWarnings("unchecked")
    private static List<ConversationAttachment> invokeConversationAttachments(
            ChatPanel chatPanel,
            Component component
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod("conversationAttachments", Component.class);
        method.setAccessible(true);
        return (List<ConversationAttachment>) method.invoke(chatPanel, component);
    }

    private static RenderMode readBubbleRenderMode(MessageBubble bubble) throws Exception {
        Field field = MessageBubble.class.getDeclaredField("renderMode");
        field.setAccessible(true);
        return (RenderMode) field.get(bubble);
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

    private static String thinkingBubbleTitle(ActivityBubble bubble) {
        return findComponents(bubble, JLabel.class).stream()
                .map(JLabel::getText)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }

    private static boolean hasVisibleCollapseToggle(ActivityBubble bubble) {
        return findComponents(bubble, JButton.class).stream()
                .filter(Component::isVisible)
                .map(AbstractButton::getText)
                .anyMatch(text -> Strings.CS.equalsAny(text, "▸", "▾"));
    }

    private static MessageBubble assistantBubble(JPanel messagesPanel) {
        return findComponents(messagesPanel, MessageBubble.class).stream()
                .filter(bubble -> bubble.getRole() == Role.ASSISTANT)
                .filter(bubble -> !hasAncestor(bubble, ActivityBubble.class))
                .findFirst()
                .orElseThrow();
    }

    private static boolean hasAncestor(Component component, Class<? extends Component> ancestorType) {
        Component current = component.getParent();
        while (current != null) {
            if (ancestorType.isInstance(current)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static int messageRowIndex(JPanel messagesPanel, Component component) {
        Component row = component;
        while (row != null && row.getParent() != messagesPanel) {
            row = row.getParent();
        }
        assertThat(row).isNotNull();

        GridBagLayout layout = (GridBagLayout) messagesPanel.getLayout();
        return layout.getConstraints(row).gridy;
    }

    private static ChatPanel chatPanelWithTextToSpeech(TextToSpeechService textToSpeechService) {
        return new ChatPanel(
                ProviderModelCacheService.createDefault(),
                ModelFavoritesService.createInMemory(),
                new ChatMessageViewFactory(),
                WebViewEngine.JEDITOR_PANE,
                textToSpeechService
        );
    }

    private static JPopupMenu contentPopupMenu(MessageBubble bubble) {
        return findComponents(bubble, JComponent.class).stream()
                .map(JComponent::getComponentPopupMenu)
                .filter(popup -> popup != null)
                .findFirst()
                .orElseThrow();
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

    private static final class RecordingTextToSpeechService extends TextToSpeechService {
        private String requestedText = "";
        private String requestedKey = "";
        private String activeMessageKey = "";

        private RecordingTextToSpeechService() throws IOException {
            super(
                    new TextToSpeechSettings(
                            new SettingsRepository(Files.createTempFile("chat4j-tts-chat-panel", ".properties")),
                            new TextToSpeechProviderRegistry(emptyList())
                    ),
                    new AudioPlaybackService() {
                        @Override
                        public void play(TextToSpeechAudio audio) {
                        }

                        @Override
                        public void stop() {
                        }
                    }
            );
        }

        @Override
        public boolean isReadAloudAvailable() {
            return true;
        }

        @Override
        public boolean isReadAloudActive(String messageKey) {
            return activeMessageKey.equals(messageKey);
        }

        @Override
        public void readAloud(String messageKey, String text, Consumer<String> errorHandler) {
            requestedKey = messageKey;
            requestedText = text;
        }

        @Override
        public void readAloud(String messageKey, String text, Consumer<String> errorHandler, Consumer<String> statusHandler) {
            requestedKey = messageKey;
            requestedText = text;
        }

        @Override
        public void readAloud(
                String messageKey,
                String text,
                Consumer<String> errorHandler,
                Consumer<String> statusHandler,
                Runnable stateChangeHandler
        ) {
            requestedKey = messageKey;
            requestedText = text;
            activeMessageKey = activeMessageKey.equals(messageKey) ? "" : messageKey;
            if (stateChangeHandler != null) {
                stateChangeHandler.run();
            }
        }

        @Override
        public void stop() {
            activeMessageKey = "";
        }

        private String requestedText() {
            return requestedText;
        }

        private String requestedKey() {
            return requestedKey;
        }
    }
}
