package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.support.BlockingHttpClientTransport;
import com.github.drafael.chat4j.chat.conversation.ConversationAttachment;
import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.conversation.ConversationEntryKind;
import com.github.drafael.chat4j.chat.composer.AttachmentStager;
import com.github.drafael.chat4j.chat.composer.ComposerAttachment;
import com.github.drafael.chat4j.chat.composer.FileAttachmentChip;
import com.github.drafael.chat4j.chat.composer.ImageAttachmentPreview;
import com.github.drafael.chat4j.chat.ui.JumpToLatestButton;
import com.github.drafael.chat4j.chat.composer.InputBar;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.chat.ui.ActivityBubble;
import com.github.drafael.chat4j.chat.agent.AgentOrchestrator;
import com.github.drafael.chat4j.chat.agent.AgentToolActivity;
import com.github.drafael.chat4j.chat.message.ChatMessageViewFactory;
import com.github.drafael.chat4j.chat.message.MessageBubble;
import com.github.drafael.chat4j.chat.model.ModelSelectorPopup;
import com.github.drafael.chat4j.chat.webview.WebViewEngine;
import com.github.drafael.chat4j.chat.conversation.webview.system.SystemWebView;
import com.github.drafael.chat4j.chat.agent.AgentProviderAdapter;
import com.github.drafael.chat4j.chat.agent.AgentProviderAdapterFactory;
import com.github.drafael.chat4j.chat.agent.AgentTurnResult;
import com.github.drafael.chat4j.chat.agent.LocalToolRuntime;
import com.github.drafael.chat4j.chat.agent.McpApprovalHandler;
import com.github.drafael.chat4j.chat.agent.ToolInvocationRequest;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.AgentToolActivityMeta;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.api.content.WebSearchSource;
import com.github.drafael.chat4j.mcp.McpRunProvider;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.conversation.ConversationHistoryEntry;
import com.github.drafael.chat4j.persistence.conversation.ConversationPersistenceIndeterminateException;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCache;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.CredentialMutationListener;
import com.github.drafael.chat4j.provider.support.CredentialMutationService;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.NativeWebSearchOutcome;
import com.github.drafael.chat4j.stt.SpeechToTextService;
import com.github.drafael.chat4j.tts.audio.AudioPlaybackService;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.TextToSpeechProviderRegistry;
import com.github.drafael.chat4j.tts.TextToSpeechService;
import com.github.drafael.chat4j.tts.TextToSpeechSettings;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.sql.SQLException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptReadAloudToken.create;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatPanelTest {

    @TempDir
    private Path tempDir;

    private final List<CompletableFuture<?>> controlledFutures = new ArrayList<>();
    private final List<TextToSpeechService> testTextToSpeechServices = new ArrayList<>();
    private ChatPanel subject;
    private ProviderRegistry providerRegistry;
    private CopilotAuthResolver copilotAuthResolver;
    private CopilotModelMetadataStore copilotModelMetadataStore;
    private CodexAuthResolver codexAuthResolver;
    private CredentialResolver credentialResolver;
    private CredentialMutationService credentialMutationService;
    private StoragePaths storagePaths;
    private ProviderAttachmentSupport attachmentSupport;

    @BeforeEach
    void setUp() throws Exception {
        copilotAuthResolver = new CopilotAuthResolver(
                tempDir.resolve("copilot-home"),
                emptyMap(),
                new BlockingHttpClientTransport(HttpClient.newHttpClient())
        );
        codexAuthResolver = new CodexAuthResolver(
                tempDir.resolve("codex-home"),
                emptyMap(),
                new BlockingHttpClientTransport(HttpClient.newHttpClient())
        );
        ApiTokenVault tokenVault = new ApiTokenVault(
                StoragePaths.ofConfigHome(tempDir.resolve("credentials"))
        );
        credentialResolver = new CredentialResolver(tokenVault, emptyMap(), emptyMap());
        credentialMutationService = new CredentialMutationService(tokenVault, credentialResolver);
        storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("chat-panel"));
        Path attachmentRoot = Files.createDirectories(storagePaths.attachmentsDirectory());
        attachmentSupport = new ProviderAttachmentSupport(attachmentRoot);
        copilotModelMetadataStore = new CopilotModelMetadataStore(tempDir.resolve("provider-metadata"));
        providerRegistry = new ProviderRegistry(
                copilotAuthResolver,
                codexAuthResolver,
                copilotModelMetadataStore,
                credentialResolver,
                emptyMap(),
                attachmentSupport
        );
        providerRegistry.applyRuntimeConfig(Map.of(
                "GitHub Copilot", new ProviderRegistry.ProviderRuntimeConfig(false, null),
                "OpenAI Codex", new ProviderRegistry.ProviderRuntimeConfig(false, null)
        ));
        ProviderModelCacheService cacheService = modelCacheService(tempDir.resolve("subject-cache"));
        runOnEdt(() -> {
            subject = newChatPanel(cacheService, ModelFavoritesService.createInMemory());
            initializeProviderModels(subject);
            subject.getInputBar().setWebSearchPresentation(false, false, false);
            subject.setOnDurableUserMessageSubmitted(event ->
                    CompletableFuture.completedFuture(event.conversationId())
            );
            subject.setOnDurableAssistantMessageCompleted(event -> CompletableFuture.completedFuture(null));
            subject.setOnDurableHistoryMutation(event -> CompletableFuture.completedFuture(null));
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        controlledFutures.forEach(future -> future.cancel(true));
        runOnEdt(() -> {});
        if (subject != null) {
            disposePanel(subject);
        }
        testTextToSpeechServices.forEach(service -> service.disposeAsync().join());
        credentialMutationService.closeSecrets();
    }

    @Test
    @DisplayName("Cached Copilot Responses evidence controls Web Search availability without provider creation")
    void setSelectedModel_whenCopilotResponsesEvidenceIsCached_exposesOptionalWebSearch() throws Exception {
        long generation = copilotModelMetadataStore.currentGeneration();
        assertThat(copilotModelMetadataStore.updateIfGenerationCurrent(
                generation,
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata(
                        "gpt-5.4-mini",
                        List.of("/chat/completions", "/responses")
                ))
        )).isTrue();
        var provider = new ProviderRegistry.ProviderDef(
                "GitHub Copilot",
                null,
                "https://api.githubcopilot.com",
                "https://api.githubcopilot.com",
                List.of("gpt-5.4-mini"),
                ProviderCapabilities.chatAndModels(),
                model -> {
                    throw new AssertionError("Provider creation should not occur during capability resolution");
                },
                List::of
        );

        try {
            runOnEdt(() -> {
                setField(subject, "providerMap", Map.of(provider.name(), provider));
                setField(subject, "installedProviderScope", 1L);
                subject.setSelectedModel("GitHub Copilot > gpt-5.4-mini");
            });

            assertThat(callOnEdt(() -> subject.getInputBar().isWebSearchAvailable())).isTrue();

            subject.invalidateSelectedProviderCapabilityEvidence(Set.of("GitHub Copilot"));

            assertThat(callOnEdt(() -> subject.getInputBar().isWebSearchAvailable())).isFalse();
            assertThat(callOnEdt(() -> readField(subject, "nativeWebSearchOutcome")))
                    .isEqualTo(NativeWebSearchOutcome.PENDING);
            assertThat(copilotModelMetadataStore.clear()).isTrue();
        } finally {
            runOnEdt(() -> {
                setField(subject, "providerMap", emptyMap());
                setField(subject, "installedProviderScope", -1L);
            });
        }
    }

    @Test
    @DisplayName("Codestral selection does not expose unsupported Mistral Web Search")
    void setSelectedModel_whenMistralModelRejectsBuiltinConnectors_hidesWebSearch() throws Exception {
        var provider = new ProviderRegistry.ProviderDef(
                "Mistral",
                "MISTRAL_API_KEY",
                "https://api.mistral.ai/v1",
                "https://api.mistral.ai/v1",
                List.of("codestral-latest"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );

        try {
            runOnEdt(() -> {
                setField(subject, "providerMap", Map.of(provider.name(), provider));
                setField(subject, "installedProviderScope", 1L);
                subject.setSelectedModel("Mistral > codestral-latest");
            });

            assertThat(callOnEdt(() -> subject.getInputBar().isWebSearchAvailable())).isFalse();
            assertThat(callOnEdt(() -> readField(subject, "nativeWebSearchOutcome")))
                    .isEqualTo(NativeWebSearchOutcome.UNSUPPORTED);
        } finally {
            runOnEdt(() -> {
                setField(subject, "providerMap", emptyMap());
                setField(subject, "installedProviderScope", -1L);
            });
        }
    }

    @Test
    @DisplayName("Gemini latest aliases do not expose unsupported Google Web Search")
    void setSelectedModel_whenGoogleModelUsesLatestAlias_hidesWebSearch() throws Exception {
        var provider = new ProviderRegistry.ProviderDef(
                "Google AI",
                "GEMINI_API_KEY",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                List.of("gemini-2.5-flash-latest"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );

        try {
            runOnEdt(() -> {
                setField(subject, "providerMap", Map.of(provider.name(), provider));
                setField(subject, "installedProviderScope", 1L);
                subject.setSelectedModel("Google AI > gemini-2.5-flash-latest");
            });

            assertThat(callOnEdt(() -> subject.getInputBar().isWebSearchAvailable())).isFalse();
            assertThat(callOnEdt(() -> readField(subject, "nativeWebSearchOutcome")))
                    .isEqualTo(NativeWebSearchOutcome.UNSUPPORTED);
        } finally {
            runOnEdt(() -> {
                setField(subject, "providerMap", emptyMap());
                setField(subject, "installedProviderScope", -1L);
            });
        }
    }

    @Test
    @DisplayName("Copilot admission rejects revoked Responses evidence before provider creation")
    void admitProvider_whenCopilotResponsesEvidenceWasRevoked_rejectsBeforeProviderCreation() throws Exception {
        assertThat(copilotModelMetadataStore.updateIfGenerationCurrent(
                copilotModelMetadataStore.currentGeneration(),
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("gpt-5.4-mini", List.of("/responses")))
        )).isTrue();
        var providerCreations = new AtomicInteger();
        var provider = new ProviderRegistry.ProviderDef(
                "GitHub Copilot",
                null,
                "https://api.githubcopilot.com",
                "https://api.githubcopilot.com",
                List.of("gpt-5.4-mini"),
                ProviderCapabilities.chatAndModels(),
                model -> {
                    providerCreations.incrementAndGet();
                    return immediateProvider("unexpected");
                },
                List::of
        );
        var sendJob = new SendJob(
                1L,
                UUID.randomUUID(),
                new SendRuntimeSnapshot(provider, "gpt-5.4-mini", NativeWebSearchOutcome.OPTIONAL),
                List.of(Message.user("Search")),
                ReasoningLevel.OFF,
                true,
                false,
                null,
                ""
        );
        assertThat(copilotModelMetadataStore.clear()).isTrue();

        assertThatThrownBy(() -> invokeAdmitProvider(subject, sendJob))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessage("Native Web Search is no longer available for the selected provider configuration.");
        assertThat(providerCreations).hasValue(0);
    }

    @Test
    @DisplayName("Copilot admission rejects Responses evidence revoked during provider creation")
    void admitProvider_whenCopilotEvidenceChangesDuringCreation_rejectsProvider() throws Exception {
        assertThat(copilotModelMetadataStore.updateIfGenerationCurrent(
                copilotModelMetadataStore.currentGeneration(),
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("gpt-5.4-mini", List.of("/responses")))
        )).isTrue();
        var providerCreations = new AtomicInteger();
        var provider = new ProviderRegistry.ProviderDef(
                "GitHub Copilot",
                null,
                "https://api.githubcopilot.com",
                "https://api.githubcopilot.com",
                List.of("gpt-5.4-mini"),
                ProviderCapabilities.chatAndModels(),
                model -> {
                    providerCreations.incrementAndGet();
                    copilotModelMetadataStore.clear();
                    return immediateProvider("unexpected");
                },
                List::of
        );
        var sendJob = new SendJob(
                1L,
                UUID.randomUUID(),
                new SendRuntimeSnapshot(provider, "gpt-5.4-mini", NativeWebSearchOutcome.OPTIONAL),
                List.of(Message.user("Search")),
                ReasoningLevel.OFF,
                true,
                false,
                null,
                ""
        );

        assertThatThrownBy(() -> invokeAdmitProvider(subject, sendJob))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessage("Native Web Search is no longer available for the selected provider configuration.");
        assertThat(providerCreations).hasValue(1);
        assertThat(sendJob.provider).isNull();
        assertThat(sendJob.providerAdmitted).isFalse();
    }

    @Test
    @DisplayName("An admitted Copilot continuation retains its original Responses evidence")
    void admitProvider_whenCopilotContinuationWasAlreadyAdmitted_retainsCapturedEvidence() throws Exception {
        assertThat(copilotModelMetadataStore.updateIfGenerationCurrent(
                copilotModelMetadataStore.currentGeneration(),
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("gpt-5.4-mini", List.of("/responses")))
        )).isTrue();
        var providerCreations = new AtomicInteger();
        var provider = new ProviderRegistry.ProviderDef(
                "GitHub Copilot",
                null,
                "https://api.githubcopilot.com",
                "https://api.githubcopilot.com",
                List.of("gpt-5.4-mini"),
                ProviderCapabilities.chatAndModels(),
                model -> {
                    providerCreations.incrementAndGet();
                    return immediateProvider("ok");
                },
                List::of
        );
        var original = new SendJob(
                1L,
                UUID.randomUUID(),
                new SendRuntimeSnapshot(provider, "gpt-5.4-mini", NativeWebSearchOutcome.OPTIONAL),
                List.of(Message.user("Search")),
                ReasoningLevel.OFF,
                true,
                false,
                null,
                ""
        );
        invokeAdmitProvider(subject, original);
        SendJob continuation = SendJob.admittedContinuation(2L, original);
        assertThat(copilotModelMetadataStore.clear()).isTrue();

        invokeAdmitProvider(subject, continuation);

        assertThat(providerCreations).hasValue(2);
        assertThat(continuation.providerAdmitted).isTrue();
    }

    @Test
    @DisplayName("Shutdown invalidates provider refreshes and rejects new refresh work")
    void beginShutdown_whenProviderRefreshCouldArrive_invalidatesAndRejectsRefresh() throws Exception {
        UUID conversationId = UUID.randomUUID();
        runOnEdt(() -> subject.setActiveConversationId(conversationId));
        long historyRevision = (long) readField(subject, "historyRevision");
        long openActionGeneration = ((AtomicLong) readField(subject, "openActionUiGeneration")).get();
        long before = ((AtomicLong) readField(subject, "providerRefreshCounter")).get();
        long readAloudBefore = ((AtomicLong) readField(subject, "readAloudUiGeneration")).get();
        long speechToTextBefore = ((AtomicLong) readField(subject, "speechToTextUiGeneration")).get();
        assertThat(callOnEdt(() -> subject.isOpenActionUiCurrent(
                historyRevision,
                conversationId,
                openActionGeneration
        ))).isTrue();

        runOnEdt(subject::beginShutdown);
        long afterShutdown = ((AtomicLong) readField(subject, "providerRefreshCounter")).get();
        runOnEdt(subject::refreshProviders);

        assertThat(afterShutdown).isGreaterThan(before);
        assertThat(((AtomicLong) readField(subject, "providerRefreshCounter")).get()).isEqualTo(afterShutdown);
        assertThat(((AtomicLong) readField(subject, "readAloudUiGeneration")).get()).isGreaterThan(readAloudBefore);
        assertThat(((AtomicLong) readField(subject, "speechToTextUiGeneration")).get()).isGreaterThan(speechToTextBefore);
        assertThat(callOnEdt(() -> subject.getInputBar().isEnabled())).isFalse();
        assertThat(callOnEdt(() -> subject.isOpenActionUiCurrent(
                historyRevision,
                conversationId,
                openActionGeneration
        ))).isFalse();
    }

    @Test
    @DisplayName("Diagram open errors do not expose unexpected filesystem details")
    void diagramOpenError_whenFailureContainsPath_returnsSafeMessage() {
        assertThat(subject.diagramOpenError(new IOException("/private/tmp/chat4j-mermaid-secret.html")))
                .isEqualTo("Unable to open diagram.");
        assertThat(subject.diagramOpenError(new IOException("Diagram is too large.")))
                .isEqualTo("Diagram is too large.");
    }

    @Test
    @DisplayName("File open work runs off the EDT without blocking Swing delivery")
    void runOpenActionInBackground_whenOperationBlocks_keepsEdtResponsive() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var operationStarted = new CountDownLatch(1);
        var releaseOperation = new CountDownLatch(1);
        var operationCompleted = new CountDownLatch(1);
        var operationRanOnEdt = new AtomicBoolean(true);
        runOnEdt(() -> subject.setActiveConversationId(conversationId));
        long expectedHistoryRevision = (long) readField(subject, "historyRevision");

        try {
            runOnEdt(() -> subject.runOpenActionInBackground(
                    expectedHistoryRevision,
                    conversationId,
                    "chat4j-open-action-test",
                    () -> {
                        operationRanOnEdt.set(SwingUtilities.isEventDispatchThread());
                        operationStarted.countDown();
                        try {
                            awaitLatch(releaseOperation);
                        } finally {
                            operationCompleted.countDown();
                        }
                    },
                    Exception::getMessage
            ));
            assertThat(operationStarted.await(2, TimeUnit.SECONDS)).isTrue();

            var edtResponsive = new AtomicBoolean();
            runOnEdt(() -> edtResponsive.set(true));

            assertThat(edtResponsive).isTrue();
            assertThat(operationRanOnEdt).isFalse();
        } finally {
            releaseOperation.countDown();
            assertThat(operationCompleted.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> {});
        }
    }

    @Test
    @DisplayName("Visible streaming listener reports active generation lifecycle")
    void onSend_whenStreamingVisible_notifiesVisibleStreamingChanges() throws Exception {
        var releaseStream = new CountDownLatch(1);
        var observedStates = new CopyOnWriteArrayList<Boolean>();
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
    @DisplayName("Codex search activity waits for an observed provider query")
    void prepareNativeWebSearchActivity_whenProviderIsCodex_doesNotClaimSearchBeforeEvent() throws Exception {
        UUID conversationId = UUID.randomUUID();
        StreamingSession session = new StreamingSession(1L, conversationId, null);
        SendJob sendJob = webSearchSendJob(
                1L,
                conversationId,
                "OpenAI Codex",
                "gpt-5.4-mini",
                "https://api.openai.com/v1"
        );
        List<Message> history = List.of(Message.user("latest Java release"));

        List<Message> result = callOnEdt(() -> invokePrepareNativeWebSearchActivity(subject, sendJob, session, history));

        assertThat(result).isSameAs(history);
        assertThat(session.consultedSourceMode).isTrue();
        assertThat(session.webSearchQueries).isEmpty();
        assertThat(session.webSearchActivity).isEmpty();

        invokeHandleAssistantWebSearchQuery(subject, session, "Java 26 release date");
        invokeHandleAssistantWebSearchQuery(subject, session, "OpenJDK 26 release notes");

        assertThat(session.webSearchQueries).containsExactly("Java 26 release date", "OpenJDK 26 release notes");
        assertThat(session.webSearchActivity).hasToString("""
                **Searched**
                - Java 26 release date
                - OpenJDK 26 release notes
                """.trim());
    }

    @Test
    @DisplayName("Mistral search does not claim the user prompt as an observed query")
    void prepareNativeWebSearchActivity_whenProviderIsMistral_keepsUnobservedActivityEmpty() throws Exception {
        UUID conversationId = UUID.randomUUID();
        StreamingSession session = new StreamingSession(2L, conversationId, null);
        SendJob sendJob = webSearchSendJob(
                2L,
                conversationId,
                "Mistral",
                "mistral-small-latest",
                "https://api.mistral.ai/v1"
        );
        List<Message> history = List.of(Message.user("latest Mistral release"));

        List<Message> result = callOnEdt(() -> invokePrepareNativeWebSearchActivity(subject, sendJob, session, history));

        assertThat(result).isSameAs(history);
        assertThat(session.consultedSourceMode).isFalse();
        assertThat(session.webSearchQueries).isEmpty();
        assertThat(session.webSearchActivity).isEmpty();
    }

    @Test
    @DisplayName("Native search activity does not claim an unobserved user-prompt query")
    void prepareNativeWebSearchActivity_whenProviderDoesNotReportQuery_keepsActivityEmpty() throws Exception {
        UUID conversationId = UUID.randomUUID();
        StreamingSession session = new StreamingSession(3L, conversationId, null);
        SendJob sendJob = webSearchSendJob(
                3L,
                conversationId,
                "OpenAI",
                "gpt-5",
                "https://api.openai.com/v1"
        );
        List<Message> history = List.of(Message.user("latest OpenAI release"));

        List<Message> result = callOnEdt(() -> invokePrepareNativeWebSearchActivity(subject, sendJob, session, history));

        assertThat(result).isSameAs(history);
        assertThat(session.consultedSourceMode).isFalse();
        assertThat(session.webSearchQueries).isEmpty();
        assertThat(session.webSearchActivity).isEmpty();
    }

    @Test
    @DisplayName("Codex activity remains absent when no provider search event is observed")
    void finalizeConsultedSourceActivity_whenCodexDoesNotSearch_keepsActivityEmpty() throws Exception {
        StreamingSession session = new StreamingSession(2L, UUID.randomUUID(), null);
        invokeInitializeConsultedSourceActivity(subject, session);

        invokeFinalizeConsultedSourceActivity(subject, session);

        assertThat(session.consultedSourceMode).isTrue();
        assertThat(session.webSearchActivity).isEmpty();
    }

    @Test
    @DisplayName("DeepSeek activity does not claim the prompt as a provider-observed search")
    void prepareNativeWebSearchActivity_whenDeepSeekReturnsNoEvidence_keepsActivityEmpty() throws Exception {
        UUID conversationId = UUID.randomUUID();
        StreamingSession session = new StreamingSession(1L, conversationId, null);
        List<Message> history = List.of(Message.user("latest DeepSeek models"));

        invokePrepareNativeWebSearchActivity(
                subject,
                deepSeekWebSearchSendJob(conversationId),
                session,
                history
        );
        invokeFinalizeConsultedSourceActivity(subject, session);

        assertThat(session.consultedSourceMode).isTrue();
        assertThat(session.webSearchQueries).isEmpty();
        assertThat(session.webSearchActivity).isEmpty();
    }

    @Test
    @DisplayName("Consulted-source initialization rejects cancelled, terminal, and shutdown sessions")
    void initializeConsultedSourceActivity_whenSessionCannotAcceptSources_doesNotMutateState() throws Exception {
        StreamingSession cancelled = new StreamingSession(2L, UUID.randomUUID(), null);
        cancelled.cancelled.set(true);
        StreamingSession terminal = new StreamingSession(3L, UUID.randomUUID(), null);
        terminal.terminalCallbackStarted.set(true);
        StreamingSession shutdown = new StreamingSession(4L, UUID.randomUUID(), null);

        invokeInitializeConsultedSourceActivity(subject, cancelled);
        invokeInitializeConsultedSourceActivity(subject, terminal);
        setField(subject, "shutdownInProgress", true);
        try {
            invokeInitializeConsultedSourceActivity(subject, shutdown);
        } finally {
            setField(subject, "shutdownInProgress", false);
        }

        assertThat(List.of(cancelled, terminal, shutdown)).allSatisfy(session -> {
            assertThat(session.consultedSourceMode).isFalse();
            assertThat(session.webSearchQueries).isEmpty();
            assertThat(session.webSearchSources).isEmpty();
            assertThat(session.webSearchActivity).isEmpty();
        });
    }

    @Test
    @DisplayName("Consulted-search admission rejects cancelled, terminal, and shutdown sessions")
    void handleAssistantWebSearchEvidence_whenSessionCannotAcceptEvidence_doesNotMutateSnapshot() throws Exception {
        StreamingSession cancelled = initializedConsultedSourceSession(5L, "cancelled query");
        cancelled.cancelled.set(true);
        StreamingSession terminal = initializedConsultedSourceSession(6L, "terminal query");
        terminal.terminalCallbackStarted.set(true);
        StreamingSession shutdown = initializedConsultedSourceSession(7L, "shutdown query");

        invokeHandleAssistantWebSearchQuery(subject, cancelled, "late cancelled query");
        invokeHandleAssistantWebSearchQuery(subject, terminal, "late terminal query");
        invokeHandleAssistantWebSearchSource(subject, cancelled, new WebSearchSource("Docs", "https://cancelled.example"));
        invokeHandleAssistantWebSearchSource(subject, terminal, new WebSearchSource("Docs", "https://terminal.example"));
        setField(subject, "shutdownInProgress", true);
        try {
            invokeHandleAssistantWebSearchQuery(subject, shutdown, "late shutdown query");
            invokeHandleAssistantWebSearchSource(subject, shutdown, new WebSearchSource("Docs", "https://shutdown.example"));
        } finally {
            setField(subject, "shutdownInProgress", false);
        }

        assertThat(List.of(cancelled, terminal, shutdown)).allSatisfy(session -> {
            assertThat(session.webSearchQueries).hasSize(1);
            assertThat(session.webSearchSources).isEmpty();
            assertThat(session.webSearchActivity)
                    .hasToString("**Searched**\n- %s".formatted(session.webSearchQueries.getFirst()));
        });
    }

    @Test
    @DisplayName("A terminal transition holding the persistence lock wins over a waiting source callback")
    void handleAssistantWebSearchSource_whenTerminalTransitionWinsLock_rejectsLateSource() throws Exception {
        StreamingSession session = initializedConsultedSourceSession(8L, "ordered query");
        Object terminalLock = readField(subject, "terminalPersistenceLock");
        CountDownLatch callbackReady = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

        Thread callback;
        synchronized (terminalLock) {
            callback = Thread.startVirtualThread(() -> {
                callbackReady.countDown();
                try {
                    invokeHandleAssistantWebSearchSource(
                            subject,
                            session,
                            new WebSearchSource("Late", "https://late.example")
                    );
                } catch (Throwable t) {
                    callbackFailure.set(t);
                } finally {
                    callbackFinished.countDown();
                }
            });
            assertThat(callbackReady.await(2, TimeUnit.SECONDS)).isTrue();
            session.terminalCallbackStarted.set(true);
        }

        assertThat(callbackFinished.await(2, TimeUnit.SECONDS)).isTrue();
        callback.join();
        assertThat(callbackFailure.get()).isNull();
        assertThat(session.webSearchSources).isEmpty();
        assertThat(session.webSearchActivity).hasToString("**Searched**\n- ordered query");
    }

    @Test
    @DisplayName("A cancellation holding the persistence lock rejects a waiting token callback")
    void handleAssistantToken_whenCancellationWinsLock_rejectsLateToken() throws Exception {
        UUID conversationId = UUID.randomUUID();
        StreamingSession session = new StreamingSession(9L, conversationId, null);
        SendJob sendJob = webSearchSendJob(
                9L,
                conversationId,
                "OpenAI",
                "gpt-5",
                "https://api.openai.com/v1"
        );
        Object terminalLock = readField(subject, "terminalPersistenceLock");
        CountDownLatch callbackReady = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

        Thread callback;
        synchronized (terminalLock) {
            callback = Thread.startVirtualThread(() -> {
                callbackReady.countDown();
                try {
                    invokeHandleAssistantToken(subject, session, sendJob, "late token");
                } catch (Throwable t) {
                    callbackFailure.set(t);
                } finally {
                    callbackFinished.countDown();
                }
            });
            assertThat(callbackReady.await(2, TimeUnit.SECONDS)).isTrue();
            session.cancelled.set(true);
        }

        assertThat(callbackFinished.await(2, TimeUnit.SECONDS)).isTrue();
        callback.join();
        assertThat(callbackFailure.get()).isNull();
        assertThat(session.response).isEmpty();
        assertThat(session.responseParts).isEmpty();
    }

    @Test
    @DisplayName("DeepSeek consulted sources remain structured and do not scrape answer links")
    void prepareAssistantResponse_whenConsultedSourceMode_doesNotScrapeAnswerLinks() throws Exception {
        UUID conversationId = UUID.randomUUID();
        StreamingSession session = new StreamingSession(1L, conversationId, null);
        appendAssistantResponse(session, "Answer with [model link](https://answer.example/link)");
        invokeInitializeConsultedSourceActivity(subject, session);
        invokeHandleAssistantWebSearchSource(subject, session, new WebSearchSource("Docs", "https://docs.example/source"));
        invokeFinalizeConsultedSourceActivity(subject, session);

        Object prepared = invokePrepareAssistantResponse(subject, session, deepSeekWebSearchSendJob(conversationId));
        ConversationHistoryEntry entry = preparedAssistantEntry(prepared);

        assertThat(entry.message().content()).contains("model link").doesNotContain("Sources:");
        assertThat(entry.message().meta().assistantWebSearch())
                .contains("**Sources consulted**")
                .contains("https://docs.example/source")
                .doesNotContain("**Searched**", "https://answer.example/link");
        assertThat(entry.message().meta().citations()).isEmpty();
    }


    @Test
    @DisplayName("Composer is centered and constrained inside workspace")
    void layout_whenWidePanel_constrainsComposerWidth() throws Exception {
        runOnEdt(() -> {
            subject.setSize(1400, 900);
            subject.doLayout();
        });

        assertThat(callOnEdt(() -> subject.getInputBar().getWidth())).isLessThanOrEqualTo(920);
    }

    @Test
    @DisplayName("Chat panel exposes icon render mode buttons for the title bar")
    void constructor_whenCreated_restoresRenderModeButtonsOnly() throws Exception {
        runOnEdt(() -> {
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
        });
    }


    @Test
    @DisplayName("Prompt quick action buttons invoke command center prompt actions")
    void promptQuickActionButton_whenClicked_invokesActionWithoutReplacingInput() throws Exception {
        AtomicInteger invoked = new AtomicInteger();

        JButton summarizeButton = callOnEdt(() -> {
            subject.setPromptQuickActions(List.of(new ChatPanel.PromptQuickAction("Summarize", invoked::incrementAndGet)));
            return findComponents(subject, JButton.class).stream()
                    .filter(button -> Strings.CS.equals(button.getText(), "Summarize"))
                    .findFirst()
                    .orElseThrow();
        });

        runOnEdt(summarizeButton::doClick);

        assertThat(invoked).hasValue(1);
        assertThat(callOnEdt(() -> subject.getInputBar().getRawText())).isEmpty();
    }

    @Test
    @DisplayName("Loaded assistant findings remain normal chat content")
    void loadHistory_whenAssistantContainsFindings_rendersAsAssistantMessage() throws Exception {
        List<MessageBubble> assistantBubbles = callOnEdt(() -> {
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
            return findComponents(messagesPanel, MessageBubble.class).stream()
                    .filter(bubble -> bubble.getRole() == Role.ASSISTANT)
                    .toList();
        });

        assertThat(assistantBubbles).hasSize(1);
        assertThat(callOnEdt(() -> findComponents(
                (JPanel) readField(subject, "messagesPanel"),
                JLabel.class
        ).stream().map(JLabel::getText).toList())).doesNotContain("1 finding");
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .contains("Findings\n\nP1 Agent bash escapes selected root\nAgent Mode documents bash as running within selected folder.\nLocalToolRuntime.java:218-233\n");
    }

    @Test
    @DisplayName("Refreshing providers after cache invalidation drops stale selected model")
    void refreshProviders_whenSelectedProviderCacheInvalidated_selectsSeedModel() throws Exception {
        Path cacheHome = tempDir.resolve("model-cache-invalidation");
        ProviderModelCacheService cacheService = modelCacheService(cacheHome);
        var provider = providerDef(null);
        var panelRef = new AtomicReference<ChatPanel>();
        runOnEdt(() -> panelRef.set(newChatPanel(cacheService, ModelFavoritesService.createInMemory())));
        ChatPanel panel = panelRef.get();
        try {
            invokePrepareProviderModels(panel, List.of(provider), cacheService.nextScopeVersion());
            updateModels(cacheService, "OpenAI", "", List.of("old-account-model"));
            cacheService.invalidate("OpenAI");
            runOnEdt(() -> {
                setField(panel, "selectedProviderName", "OpenAI");
                setField(panel, "selectedModelId", "old-account-model");
                invokeApplyProviderModels(panel, List.of(provider));
                assertThat(panel.getSelectedModel()).isEqualTo("OpenAI > seed-model");
            });
        } finally {
            disposePanel(panel);
        }
    }

    @Test
    @DisplayName("A scope change during provider selection rejects models from the previous endpoint")
    void applyProviderModels_whenScopeChangesDuringSelectionCheck_rejectsStaleModels() throws Exception {
        var cacheService = new BlockingModelCacheService(tempDir.resolve("model-cache-scope-race"));
        var provider = providerDef("https://old.example.com/v1");
        cacheService.synchronizeScope(provider.name(), provider.baseUrl(), cacheService.nextScopeVersion());
        updateModels(cacheService, provider.name(), provider.baseUrl(), List.of("old-endpoint-model"));

        var panelRef = new AtomicReference<ChatPanel>();
        runOnEdt(() -> panelRef.set(newChatPanel(cacheService, ModelFavoritesService.createInMemory())));
        ChatPanel panel = panelRef.get();
        var completion = new CountDownLatch(1);
        var callbackError = new AtomicReference<Throwable>();
        try {
            runOnEdt(() -> {
                setField(panel, "selectedProviderName", provider.name());
                setField(panel, "selectedModelId", "old-endpoint-model");
            });
            cacheService.blockNextLookup();
            long scopeVersion = cacheService.nextScopeVersion();
            SwingUtilities.invokeLater(() -> {
                try {
                    invokeApplyProviderModels(panel, List.of(provider), scopeVersion);
                } catch (Throwable t) {
                    callbackError.set(t);
                } finally {
                    completion.countDown();
                }
            });

            assertThat(cacheService.awaitLookupStarted()).isTrue();
            cacheService.synchronizeScope(
                    provider.name(),
                    "https://new.example.com/v1",
                    cacheService.nextScopeVersion()
            );
            cacheService.releaseLookup();
            assertThat(completion.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(callbackError.get()).isNull();
            assertThat(callOnEdt(panel::getSelectedModel)).isEqualTo("OpenAI > seed-model");
        } finally {
            cacheService.releaseLookup();
            completion.await(2, TimeUnit.SECONDS);
            disposePanel(panel);
        }
    }

    @Test
    @DisplayName("Re-enabling a provider after its base URL changed invalidates models from the previous endpoint")
    void applyProviderModels_whenProviderReenabledWithChangedBaseUrl_invalidatesProviderCache() throws Exception {
        Path cacheHome = tempDir.resolve("model-cache-base-url");
        ProviderModelCacheService cacheService = modelCacheService(cacheHome);
        var panelRef = new AtomicReference<ChatPanel>();
        runOnEdt(() -> panelRef.set(newChatPanel(cacheService, ModelFavoritesService.createInMemory())));
        ChatPanel panel = panelRef.get();
        try {
            var previousProvider = providerDef("https://old.example.com/v1");
            var updatedProvider = providerDef("https://new.example.com/v1");
            invokePrepareProviderModels(panel, List.of(previousProvider), cacheService.nextScopeVersion());
            runOnEdt(() -> invokeApplyProviderModels(panel, List.of(previousProvider)));
            updateModels(cacheService, "OpenAI", previousProvider.baseUrl(), List.of("old-endpoint-model"));

            runOnEdt(() -> invokeApplyProviderModels(panel, emptyList()));
            invokePrepareProviderModels(panel, List.of(updatedProvider), cacheService.nextScopeVersion());
            runOnEdt(() -> invokeApplyProviderModels(panel, List.of(updatedProvider)));

            assertThat(cacheService.isInvalidated("OpenAI")).isTrue();
            assertThat(cacheService.shouldRefresh("OpenAI")).isTrue();
        } finally {
            disposePanel(panel);
        }
    }

    @Test
    @DisplayName("Re-enabling a provider with the same base URL preserves its model catalog")
    void applyProviderModels_whenProviderReenabledWithSameBaseUrl_preservesProviderCache() throws Exception {
        Path cacheHome = tempDir.resolve("model-cache-enable-toggle");
        ProviderModelCacheService cacheService = modelCacheService(cacheHome);
        var provider = providerDef("https://same.example.com/v1");
        var panelRef = new AtomicReference<ChatPanel>();
        runOnEdt(() -> panelRef.set(newChatPanel(cacheService, ModelFavoritesService.createInMemory())));
        ChatPanel panel = panelRef.get();
        try {
            invokePrepareProviderModels(panel, List.of(provider), cacheService.nextScopeVersion());
            runOnEdt(() -> invokeApplyProviderModels(panel, List.of(provider)));
            updateModels(cacheService, "OpenAI", provider.baseUrl(), List.of("same-endpoint-model"));

            runOnEdt(() -> invokeApplyProviderModels(panel, emptyList()));
            invokePrepareProviderModels(panel, List.of(provider), cacheService.nextScopeVersion());
            runOnEdt(() -> invokeApplyProviderModels(panel, List.of(provider)));

            assertThat(cacheService.isInvalidated("OpenAI")).isFalse();
            assertThat(cacheService.getModels("OpenAI")).contains("same-endpoint-model");
        } finally {
            disposePanel(panel);
        }
    }

    @Test
    @DisplayName("Provider refresh notifies model menus after a base URL change")
    void applyProviderModels_whenBaseUrlChanges_notifiesModelCatalogListener() throws Exception {
        ProviderModelCacheService cacheService = modelCacheService(tempDir.resolve("model-cache-menu-refresh"));
        var previousProvider = providerDef("https://old.example.com/v1");
        var updatedProvider = providerDef("https://new.example.com/v1");
        var catalogChanges = new AtomicInteger();
        var panelRef = new AtomicReference<ChatPanel>();
        runOnEdt(() -> panelRef.set(newChatPanel(cacheService, ModelFavoritesService.createInMemory())));
        ChatPanel panel = panelRef.get();
        try {
            invokePrepareProviderModels(panel, List.of(previousProvider), cacheService.nextScopeVersion());
            runOnEdt(() -> invokeApplyProviderModels(panel, List.of(previousProvider)));
            runOnEdt(() -> panel.setOnModelCatalogChanged(catalogChanges::incrementAndGet));

            invokePrepareProviderModels(panel, List.of(updatedProvider), cacheService.nextScopeVersion());
            runOnEdt(() -> invokeApplyProviderModels(panel, List.of(updatedProvider)));

            assertThat(catalogChanges).hasValue(1);
        } finally {
            disposePanel(panel);
        }
    }

    @Test
    @DisplayName("Loading an invalidated non-seed model selection falls back to seed model")
    void setSelectedModel_whenProviderCacheInvalidated_usesSeedModel() throws Exception {
        Path cacheHome = tempDir.resolve("model-cache-load");
        ProviderModelCacheService cacheService = modelCacheService(cacheHome);
        var provider = providerDef(null);
        var panelRef = new AtomicReference<ChatPanel>();
        runOnEdt(() -> panelRef.set(newChatPanel(cacheService, ModelFavoritesService.createInMemory())));
        ChatPanel panel = panelRef.get();
        try {
            invokePrepareProviderModels(panel, List.of(provider), cacheService.nextScopeVersion());
            updateModels(cacheService, "OpenAI", "", List.of("old-account-model"));
            cacheService.invalidate("OpenAI");
            runOnEdt(() -> {
                setField(panel, "providerMap", Map.of(provider.name(), provider));
                panel.setSelectedModel("OpenAI > old-account-model");
                assertThat(panel.getSelectedModel()).isEqualTo("OpenAI > seed-model");
            });
        } finally {
            disposePanel(panel);
        }
    }

    @Test
    @DisplayName("User selection rejects an invalidated stale model instead of substituting a seed model")
    void resolveUserSelectableModel_whenProviderCacheInvalidated_rejectsStaleModel() throws Exception {
        Path cacheHome = tempDir.resolve("model-cache-user-selection");
        ProviderModelCacheService cacheService = modelCacheService(cacheHome);
        var provider = providerDef(null);
        var panelRef = new AtomicReference<ChatPanel>();
        runOnEdt(() -> panelRef.set(newChatPanel(cacheService, ModelFavoritesService.createInMemory())));
        ChatPanel panel = panelRef.get();
        try {
            invokePrepareProviderModels(panel, List.of(provider), cacheService.nextScopeVersion());
            updateModels(cacheService, "OpenAI", "", List.of("old-account-model"));
            cacheService.invalidate("OpenAI");
            runOnEdt(() -> setField(panel, "providerMap", Map.of(provider.name(), provider)));

            assertThat(callOnEdt(() -> panel.resolveUserSelectableModel("OpenAI > old-account-model"))).isNull();
            assertThat(callOnEdt(() -> panel.resolveUserSelectableModel("OpenAI > seed-model")))
                    .isEqualTo("OpenAI > seed-model");
        } finally {
            disposePanel(panel);
        }
    }

    @Test
    @DisplayName("User selection accepts visible seed models after an empty catalog refresh")
    void resolveUserSelectableModel_whenUsableCacheEmpty_acceptsSeedModel() throws Exception {
        Path cacheHome = tempDir.resolve("model-cache-empty-user-catalog");
        ProviderModelCacheService cacheService = modelCacheService(cacheHome);
        var provider = providerDef(null);
        var panelRef = new AtomicReference<ChatPanel>();
        runOnEdt(() -> panelRef.set(newChatPanel(cacheService, ModelFavoritesService.createInMemory())));
        ChatPanel panel = panelRef.get();
        try {
            invokePrepareProviderModels(panel, List.of(provider), cacheService.nextScopeVersion());
            updateModels(cacheService, "OpenAI", "", emptyList());
            runOnEdt(() -> setField(panel, "providerMap", Map.of(provider.name(), provider)));

            assertThat(callOnEdt(() -> panel.resolveUserSelectableModel("OpenAI > seed-model")))
                    .isEqualTo("OpenAI > seed-model");
        } finally {
            disposePanel(panel);
        }
    }

    @Test
    @DisplayName("User selection rejects providers outside the available provider map")
    void resolveUserSelectableModel_whenProviderUnavailable_rejectsSelection() throws Exception {
        assertThat(callOnEdt(() -> subject.resolveUserSelectableModel("UnavailableProvider > some-model"))).isNull();
    }

    @Test
    @DisplayName("Loading an invalidated non-seed model selection without seed models is ignored")
    void setSelectedModel_whenProviderCacheInvalidatedAndNoSeedModels_doesNotSelectStaleModel() throws Exception {
        Path cacheHome = tempDir.resolve("model-cache-empty-seed");
        ProviderModelCacheService cacheService = modelCacheService(cacheHome);
        var provider = new ProviderRegistry.ProviderDef(
                "EmptySeed",
                "EMPTY_SEED_API_KEY",
                null,
                null,
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                () -> emptyList()
        );
        var panelRef = new AtomicReference<ChatPanel>();
        runOnEdt(() -> panelRef.set(newChatPanel(cacheService, ModelFavoritesService.createInMemory())));
        ChatPanel panel = panelRef.get();
        try {
            invokePrepareProviderModels(panel, List.of(provider), cacheService.nextScopeVersion());
            updateModels(cacheService, "EmptySeed", "", List.of("old-account-model"));
            cacheService.invalidate("EmptySeed");
            runOnEdt(() -> {
                setField(panel, "providerMap", Map.of(provider.name(), provider));
                setField(panel, "selectedProviderName", null);
                setField(panel, "selectedModelId", null);
                panel.setSelectedModel("EmptySeed > old-account-model");
                assertThat(panel.getSelectedModel()).isNull();
            });
        } finally {
            disposePanel(panel);
        }
    }

    @Test
    @DisplayName("Selecting a non-seed model retains configuration without creating a provider")
    void setSelectedModel_whenModelIsNotPartOfSeedModels_retainsSelectionWithoutCreatingProvider() throws Exception {
        var factoryCalls = new AtomicInteger();
        ProviderRegistry.ProviderDef provider = new ProviderRegistry.ProviderDef(
                "Ollama",
                null,
                "http://localhost:11434/v1",
                "http://localhost:11434/v1",
                List.of("llama3.2:latest"),
                ProviderCapabilities.chatAndModels(),
                model -> {
                    factoryCalls.incrementAndGet();
                    return immediateProvider("ok");
                },
                List::of
        );

        runOnEdt(() -> {
            setField(subject, "providerMap", Map.of(provider.name(), provider));
            subject.setSelectedModel("Ollama > llama3.2:latest");
        });

        assertThat(callOnEdt(subject::getSelectedModel)).isEqualTo("Ollama > llama3.2:latest");
        assertThat(factoryCalls).hasValue(0);
    }

    @Test
    @DisplayName("Popup model selection dispatches user intent without directly changing the selected model")
    void requestModelSelection_whenListenerConfigured_dispatchesWithoutApplying() throws Exception {
        var requestedModel = new AtomicReference<String>();
        String originalModel = callOnEdt(subject::getSelectedModel);
        Method requestSelection = ChatPanel.class.getDeclaredMethod(
                "requestModelSelection",
                String.class,
                String.class
        );
        requestSelection.setAccessible(true);

        runOnEdt(() -> {
            subject.setOnModelSelectionRequested(requestedModel::set);
            requestSelection.invoke(subject, "OpenAI", "gpt-requested");
        });

        assertThat(requestedModel).hasValue("OpenAI > gpt-requested");
        assertThat(callOnEdt(subject::getSelectedModel)).isEqualTo(originalModel);
    }

    @Test
    @DisplayName("Direct model restoration does not dispatch user-selection intent")
    void setSelectedModel_whenCalledDirectly_doesNotDispatchUserIntent() throws Exception {
        var requestedModel = new AtomicReference<String>();

        runOnEdt(() -> {
            subject.setOnModelSelectionRequested(requestedModel::set);
            subject.setSelectedModel("Ollama > llama3.2:latest");
        });

        assertThat(callOnEdt(subject::getSelectedModel)).isEqualTo("Ollama > llama3.2:latest");
        assertThat(requestedModel.get()).isNull();
    }

    @Test
    @DisplayName("The next send resolves a GUI token saved after model selection")
    void onSend_whenGuiTokenChangesAfterSelection_usesLatestToken() throws Exception {
        var admittedToken = new AtomicReference<String>();
        var factoryCalls = new AtomicInteger();
        ProviderRegistry.ProviderDef provider = new ProviderRegistry.ProviderDef(
                "OpenAI",
                "OPENAI_API_KEY",
                "https://api.openai.com/v1",
                "https://api.openai.com/v1",
                List.of("gpt-test"),
                ProviderCapabilities.chatAndModels(),
                model -> {
                    factoryCalls.incrementAndGet();
                    String token = credentialResolver.resolveRequiredApiKey("OPENAI_API_KEY", null);
                    admittedToken.set(token);
                    return providerWithApiKey(token);
                },
                List::of
        );
        runOnEdt(() -> {
            setField(subject, "providerMap", Map.of(provider.name(), provider));
            setField(subject, "installedProviderScope", 1L);
            subject.setSelectedModel("OpenAI > gpt-test");
            subject.getInputBar().setText("hello");
        });
        assertThat(factoryCalls).hasValue(0);
        credentialMutationService.saveTokenOverride(
                "OPENAI_API_KEY",
                "latest-gui-token".toCharArray(),
                CredentialMutationListener.NO_OP
        );

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> admittedToken.get() != null);
        flushEdt();

        assertThat(admittedToken).hasValue("latest-gui-token");
        assertThat(factoryCalls).hasValue(1);
    }

    @Test
    @DisplayName("Request settlement clears provider and credential references")
    void finishSendJob_whenStreamSettles_clearsRequestCredentialReferences() throws Exception {
        var streamStarted = new CountDownLatch(1);
        var releaseStream = new CountDownLatch(1);
        ProviderService requestProvider = new ProviderService() {
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
                    releaseStream.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                onComplete.run();
            }


            @Override
            public String apiKey() {
                return "request-secret";
            }
        };
        ProviderRegistry.ProviderDef provider = new ProviderRegistry.ProviderDef(
                "OpenAI",
                "OPENAI_API_KEY",
                "https://api.openai.com/v1",
                "https://api.openai.com/v1",
                List.of("gpt-test"),
                ProviderCapabilities.chatAndModels(),
                model -> requestProvider,
                List::of
        );
        runOnEdt(() -> {
            setField(subject, "providerMap", Map.of(provider.name(), provider));
            setField(subject, "installedProviderScope", 1L);
            subject.setSelectedModel("OpenAI > gpt-test");
            subject.getInputBar().setText("hello");
        });

        SendJob sendJob;
        StreamingSession session;
        try {
            invokeOnSend(subject);
            assertThat(streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
            sendJob = callOnEdt(() -> ((Map<Long, SendJob>) readField(subject, "activeSendJobs"))
                    .values().iterator().next());
            session = callOnEdt(() -> ((Map<Long, StreamingSession>) readField(subject, "activeSessions"))
                    .values().iterator().next());
            assertThat(sendJob.apiKey).isEqualTo("request-secret");
            assertThat(sendJob.provider).isSameAs(requestProvider);
            assertThat(session.provider).isSameAs(requestProvider);
        } finally {
            releaseStream.countDown();
        }
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()
                        && ((Map<?, ?>) readField(subject, "activeSessions")).isEmpty()));
        flushEdt();

        assertThat(sendJob.apiKey).isNull();
        assertThat(sendJob.provider).isNull();
        assertThat(session.provider).isNull();
    }

    @Test
    @DisplayName("Loading a known OAuth provider before discovery preserves its model selection")
    void setSelectedModel_whenKnownProviderIsStillBeingDiscovered_preservesSelection() throws Exception {
        runOnEdt(() -> {
            setField(subject, "providerMap", emptyMap());
            subject.setSelectedModel("GitHub Copilot > claude-sonnet-4.6");

            assertThat(subject.getSelectedModel()).isEqualTo("GitHub Copilot > claude-sonnet-4.6");
            assertThat(subject.getModelSelectorButton().getProviderName()).isEqualTo("GitHub Copilot");
            assertThat(subject.getModelSelectorButton().getModelName()).isEqualTo("claude-sonnet-4.6");
            assertThat(subject.getInputBar().isSendable()).isFalse();
        });
    }

    @Test
    @DisplayName("Selecting an unavailable provider clears runtime and composer readiness")
    void setSelectedModel_whenProviderIsUnavailable_clearsRuntimeAndComposerReadiness() throws Exception {
        runOnEdt(() -> {
            subject.getInputBar().setText("ready to send");
            subject.setSelectedModel("Ollama > llama3.2:latest");
        });
        assertThat(callOnEdt(() -> subject.getInputBar().isSendable())).isTrue();

        runOnEdt(() -> subject.setSelectedModel("UnavailableProvider > some-model"));

        assertThat(callOnEdt(subject::getSelectedModel)).isNull();
        assertThat(callOnEdt(() -> subject.getInputBar().isSendable())).isFalse();
    }

    @Test
    @DisplayName("Providers discovered by the model popup can be selected immediately")
    void updateProviderModelsFromPopup_whenProviderAppearsLater_allowsProviderSelection() throws Exception {
        ProviderRegistry.ProviderDef provider = new ProviderRegistry.ProviderDef(
                "LateProvider",
                null,
                "https://example.invalid/v1",
                "https://example.invalid/v1",
                List.of("late-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );

        runOnEdt(() -> {
            invokeUpdateProviderModelsFromPopup(subject, List.of(provider));
            subject.setSelectedModel("LateProvider > late-model");
        });
        runOnEdt(() -> assertThat(subject.getSelectedModel()).isEqualTo("LateProvider > late-model"));
    }

    @Test
    @DisplayName("Older provider snapshots cannot overwrite a newer popup provider list")
    void updateProviderModelsFromPopup_whenOlderSnapshotArrivesLater_keepsNewerProviders() throws Exception {
        ProviderRegistry.ProviderDef newerProvider = new ProviderRegistry.ProviderDef(
                "NewerProvider",
                null,
                null,
                null,
                List.of("newer-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );
        ProviderRegistry.ProviderDef staleProvider = new ProviderRegistry.ProviderDef(
                "StaleProvider",
                null,
                null,
                null,
                List.of("stale-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("stale"),
                List::of
        );

        ProviderModelCacheService cacheService = (ProviderModelCacheService) readField(subject, "modelCacheService");
        long staleScopeVersion = cacheService.nextScopeVersion();
        long newerScopeVersion = cacheService.nextScopeVersion();
        runOnEdt(() -> {
            assertThat(invokeUpdateProviderModelsFromPopup(subject, List.of(staleProvider), staleScopeVersion)).isFalse();
            Map<?, ?> providersAfterSupersededUpdate = (Map<?, ?>) readField(subject, "providerMap");
            assertThat(providersAfterSupersededUpdate.containsKey(staleProvider.name())).isFalse();

            assertThat(invokeUpdateProviderModelsFromPopup(subject, List.of(newerProvider), newerScopeVersion)).isTrue();
            assertThat(invokeUpdateProviderModelsFromPopup(subject, List.of(staleProvider), staleScopeVersion)).isFalse();
            subject.setSelectedModel("NewerProvider > newer-model");
        });
        runOnEdt(() -> assertThat(subject.getSelectedModel()).isEqualTo("NewerProvider > newer-model"));
    }

    @Test
    @DisplayName("Popup provider updates clear selections invalidated by an endpoint change")
    void updateProviderModelsFromPopup_whenSelectedProviderIsInvalidated_clearsSelection() throws Exception {
        var selectionChanges = new AtomicInteger();
        var catalogChanges = new AtomicInteger();
        ProviderRegistry.ProviderDef provider = new ProviderRegistry.ProviderDef(
                "ChangedProvider",
                null,
                "https://new.example.invalid/v1",
                "https://new.example.invalid/v1",
                List.of("changed-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );
        runOnEdt(() -> {
            subject.setOnSelectedModelChanged(selectionChanges::incrementAndGet);
            subject.setOnModelCatalogChanged(catalogChanges::incrementAndGet);
            invokeUpdateProviderModelsFromPopup(subject, List.of(provider));
            subject.setSelectedModel("ChangedProvider > changed-model");
        });
        selectionChanges.set(0);
        catalogChanges.set(0);
        ProviderModelCacheService cacheService = (ProviderModelCacheService) readField(subject, "modelCacheService");

        cacheService.invalidate(provider.name());
        runOnEdt(() -> invokeUpdateProviderModelsFromPopup(subject, List.of(provider)));

        runOnEdt(() -> {
            assertThat(subject.getSelectedModel()).isNull();
            assertThat(selectionChanges).hasValue(1);
            assertThat(catalogChanges).hasValue(1);
        });
    }

    @Test
    @DisplayName("Selecting a model does not create a request provider")
    void setSelectedModel_whenProviderFactoryWouldBlock_doesNotInvokeFactory() throws Exception {
        var factoryStarted = new CountDownLatch(1);
        var releaseFactory = new CountDownLatch(1);
        ProviderRegistry.ProviderDef provider = new ProviderRegistry.ProviderDef(
                "SlowProvider",
                null,
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
        runOnEdt(() -> {
            setField(subject, "providerMap", Map.of(provider.name(), provider));
            subject.setSelectedModel("SlowProvider > slow-model");
        });

        assertThat(factoryStarted.await(100, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(callOnEdt(subject::getSelectedModel)).isEqualTo("SlowProvider > slow-model");
        releaseFactory.countDown();
    }



    @Test
    @DisplayName("Thinking toggle is visible only for models with reasoning capability")
    void setSelectedModel_whenReasoningCapabilityChanges_updatesThinkingToggleVisibility() throws Exception {
        ProviderRegistry.ProviderDef reasoningProvider = new ProviderRegistry.ProviderDef(
                "OpenRouter",
                "OPENROUTER_API_KEY",
                null,
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
                null,
                List.of("basic-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );

        Map<String, ProviderRegistry.ProviderDef> customProviders = new LinkedHashMap<>();
        customProviders.put(reasoningProvider.name(), reasoningProvider);
        customProviders.put(plainProvider.name(), plainProvider);
        runOnEdt(() -> {
            setField(subject, "providerMap", customProviders);
            JButton thinkingButton = readThinkingButton(subject.getInputBar());

            subject.setSelectedModel("OpenRouter > claude-3.7-sonnet");
            assertThat(thinkingButton.isVisible()).isTrue();

            subject.getInputBar().setThinkingEnabled(true);
            assertThat(subject.getInputBar().isThinkingEnabled()).isTrue();

            subject.setSelectedModel("LocalTest > basic-model");
            assertThat(thinkingButton.isVisible()).isFalse();
            assertThat(subject.getInputBar().isThinkingEnabled()).isFalse();
        });
    }

    @Test
    @DisplayName("Codex reasoning selection follows the selected model's advertised levels")
    void setSelectedModel_whenCodexModelChanges_clampsAndRestoresReasoningLevel() throws Exception {
        ProviderRegistry.ProviderDef codexProvider = new ProviderRegistry.ProviderDef(
                "OpenAI Codex",
                "CODEX_ACCESS_TOKEN",
                "https://chatgpt.com/backend-api/codex",
                "https://chatgpt.com/backend-api/codex",
                List.of("gpt-5.5", "gpt-5.6-sol", "gpt-5.6-luna"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );

        runOnEdt(() -> {
            setField(subject, "providerMap", Map.of(codexProvider.name(), codexProvider));
            subject.setSelectedModel("OpenAI Codex > gpt-5.6-sol");
            subject.getInputBar().setReasoningLevel(ReasoningLevel.ULTRA);

            assertThat(subject.getInputBar().getEffectiveReasoningLevel()).isEqualTo(ReasoningLevel.ULTRA);

            subject.setSelectedModel("OpenAI Codex > gpt-5.6-luna");

            assertThat(subject.getInputBar().getReasoningLevel()).isEqualTo(ReasoningLevel.ULTRA);
            assertThat(subject.getInputBar().getEffectiveReasoningLevel()).isEqualTo(ReasoningLevel.MAX);

            subject.setSelectedModel("OpenAI Codex > gpt-5.5");

            assertThat(subject.getInputBar().getEffectiveReasoningLevel()).isEqualTo(ReasoningLevel.EXTRA_HIGH);

            subject.setSelectedModel("OpenAI Codex > gpt-5.6-sol");

            assertThat(subject.getInputBar().getEffectiveReasoningLevel()).isEqualTo(ReasoningLevel.ULTRA);
        });
    }

    @Test
    @DisplayName("Switching conversations away and back restores previously selected reasoning level")
    void setSelectedModel_whenSwitchingAwayAndBack_restoresReasoningState() throws Exception {
        ProviderRegistry.ProviderDef reasoningProvider = new ProviderRegistry.ProviderDef(
                "OpenRouter",
                "OPENROUTER_API_KEY",
                null,
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
                null,
                List.of("basic-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );

        Map<String, ProviderRegistry.ProviderDef> customProviders = new LinkedHashMap<>();
        customProviders.put(reasoningProvider.name(), reasoningProvider);
        customProviders.put(plainProvider.name(), plainProvider);
        runOnEdt(() -> {
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
        });
    }

    @Test
    @DisplayName("Agent toggle is visible only for models with tool capability")
    void setSelectedModel_whenToolCapabilityChanges_updatesAgentToggleVisibility() throws Exception {
        ProviderRegistry.ProviderDef toolProvider = new ProviderRegistry.ProviderDef(
                "OpenRouter",
                "OPENROUTER_API_KEY",
                null,
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
                null,
                List.of("basic-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );

        Map<String, ProviderRegistry.ProviderDef> customProviders = new LinkedHashMap<>();
        customProviders.put(toolProvider.name(), toolProvider);
        customProviders.put(plainProvider.name(), plainProvider);
        Path projectRoot = Files.createDirectories(tempDir.resolve("agent-project"));
        runOnEdt(() -> {
            setField(subject, "providerMap", customProviders);
            JToggleButton agentModeButton = readAgentModeButton(subject.getInputBar());

            subject.setSelectedModel("OpenRouter > gpt-5-mini");
            assertThat(agentModeButton.isVisible()).isTrue();

            subject.getInputBar().setAgentProjectRoot(projectRoot);
            subject.getInputBar().setAgentModeEnabled(true);
            assertThat(subject.getInputBar().isAgentModeEnabled()).isTrue();

            subject.setSelectedModel("LocalTest > basic-model");
            assertThat(agentModeButton.isVisible()).isFalse();
            assertThat(subject.getInputBar().isAgentModeEnabled()).isFalse();
        });
    }

    @Test
    @DisplayName("Persisted Together selections reject unknown and wrong-case model IDs before provider models load")
    void setSelectedModel_whenTogetherModelsAreNotLoaded_rejectsUnreviewedIds() throws Exception {
        runOnEdt(() -> setField(subject, "providerMap", emptyMap()));

        runOnEdt(() -> subject.setSelectedModel("Together > qwen/qwen3.5-9b"));
        assertThat(callOnEdt(subject::getSelectedModel)).isNull();

        runOnEdt(() -> subject.setSelectedModel("Together >  Qwen/Qwen3.5-9B "));
        assertThat(callOnEdt(subject::getSelectedModel)).isEqualTo("Together > Qwen/Qwen3.5-9B");
    }

    @Test
    @DisplayName("Together model application replaces a removed selection while popup refresh only clears it")
    void providerModelsChanged_whenTogetherSelectionIsAbsent_appliesPathSpecificFallback() throws Exception {
        String baseUrl = "https://api.together.ai/v1";
        String availableModel = "Qwen/Qwen3.5-9B";
        String removedModel = "MiniMaxAI/MiniMax-M3";
        var provider = new ProviderRegistry.ProviderDef(
                "Together",
                "TOGETHER_API_KEY",
                baseUrl,
                baseUrl,
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("answer"),
                List::of
        );
        ProviderModelCacheService cacheService = (ProviderModelCacheService) readField(subject, "modelCacheService");
        long applyScope = cacheService.nextScopeVersion();
        cacheService.synchronizeScope("Together", baseUrl, applyScope);
        ProviderModelCacheService.RefreshAttempt attempt = cacheService.tryBeginRefreshIfNeeded(
                "Together",
                baseUrl,
                Duration.ZERO
        ).orElseThrow();
        assertThat(cacheService.update(attempt, List.of(availableModel))).isTrue();
        runOnEdt(() -> {
            setField(subject, "selectedProviderName", "Together");
            setField(subject, "selectedModelId", removedModel);
        });

        invokeApplyProviderModels(subject, List.of(provider), applyScope);
        assertThat(callOnEdt(subject::getSelectedModel)).isEqualTo("Together > " + availableModel);

        runOnEdt(() -> {
            setField(subject, "selectedProviderName", "Together");
            setField(subject, "selectedModelId", removedModel);
        });
        assertThat(invokeUpdateProviderModelsFromPopup(subject, List.of(provider), applyScope)).isTrue();

        assertThat(callOnEdt(subject::getSelectedModel)).isNull();
    }

    @Test
    @DisplayName("Together admission rejects a reviewed model absent from the current usable model list before provider creation")
    void onSend_whenTogetherModelIsNotCurrentlyUsable_rejectsBeforeProviderCreation() throws Exception {
        String baseUrl = "https://api.together.ai/v1";
        String availableModel = "Qwen/Qwen3.5-9B";
        String unavailableModel = "MiniMaxAI/MiniMax-M3";
        var providerCalls = new AtomicInteger();
        var provider = new ProviderRegistry.ProviderDef(
                "Together",
                "TOGETHER_API_KEY",
                baseUrl,
                baseUrl,
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                model -> {
                    providerCalls.incrementAndGet();
                    return immediateProvider("answer");
                },
                List::of
        );
        ProviderModelCacheService cacheService = (ProviderModelCacheService) readField(subject, "modelCacheService");
        long scope = cacheService.nextScopeVersion();
        cacheService.synchronizeScope("Together", baseUrl, scope);
        ProviderModelCacheService.RefreshAttempt attempt = cacheService.tryBeginRefreshIfNeeded(
                "Together",
                baseUrl,
                Duration.ZERO
        ).orElseThrow();
        assertThat(cacheService.update(attempt, List.of(availableModel))).isTrue();
        runOnEdt(() -> {
            setField(subject, "providerMap", Map.of("Together", provider));
            setField(subject, "selectedProviderName", "Together");
            setField(subject, "selectedModelId", unavailableModel);
            setField(subject, "installedProviderScope", scope);
            subject.getInputBar().setText("message");
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()
        ));

        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("current model list");
    }

    @Test
    @DisplayName("Together admission rejects a reviewed model when the current catalog is truly empty")
    void onSend_whenTogetherCatalogIsTrulyEmpty_rejectsBeforeProviderCreation() throws Exception {
        String providerName = "Together";
        String modelId = "Qwen/Qwen3.5-9B";
        String baseUrl = "https://api.together.ai/v1";
        var providerCalls = new AtomicInteger();
        var provider = new ProviderRegistry.ProviderDef(
                providerName,
                "TOGETHER_API_KEY",
                baseUrl,
                baseUrl,
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                ignored -> {
                    providerCalls.incrementAndGet();
                    return immediateProvider("answer");
                },
                List::of
        );
        ProviderModelCacheService cacheService = (ProviderModelCacheService) readField(subject, "modelCacheService");
        long scope = cacheService.nextScopeVersion();
        cacheService.synchronizeScope(providerName, baseUrl, scope);
        assertThat(cacheService.findUsableModels(providerName, baseUrl)).contains(emptyList());
        runOnEdt(() -> {
            setField(subject, "providerMap", Map.of(providerName, provider));
            setField(subject, "selectedProviderName", providerName);
            setField(subject, "selectedModelId", modelId);
            setField(subject, "installedProviderScope", scope);
            subject.getInputBar().setText("message");
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()
        ));

        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("current model list");
    }

    @Test
    @DisplayName("Together admission accepts a snapshot-bounded fallback after an empty refresh")
    void onSend_whenTogetherRefreshIsEmpty_admitsRetainedReviewedFallback() throws Exception {
        String providerName = "Together";
        String modelId = "Qwen/Qwen3.5-9B";
        String baseUrl = "https://api.together.ai/v1";
        var providerCalls = new AtomicInteger();
        var provider = new ProviderRegistry.ProviderDef(
                providerName,
                "TOGETHER_API_KEY",
                baseUrl,
                baseUrl,
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                ignored -> providerReturning("answer", providerCalls),
                List::of
        );
        ProviderModelCacheService cacheService = (ProviderModelCacheService) readField(subject, "modelCacheService");
        long scope = cacheService.nextScopeVersion();
        cacheService.synchronizeScope(providerName, baseUrl, scope);
        ProviderModelCacheService.RefreshAttempt initial = cacheService.tryBeginRefreshIfNeeded(
                providerName,
                baseUrl,
                Duration.ZERO
        ).orElseThrow();
        assertThat(cacheService.update(initial, List.of(modelId, "unreviewed/model"))).isTrue();
        cacheService.invalidate(providerName);
        ProviderModelCacheService.RefreshAttempt emptyRefresh = cacheService.tryBeginRefreshIfNeeded(
                providerName,
                baseUrl,
                Duration.ZERO
        ).orElseThrow();
        assertThat(cacheService.update(emptyRefresh, emptyList())).isFalse();
        assertThat(cacheService.findUsableModels(providerName, baseUrl)).contains(List.of(modelId));
        runOnEdt(() -> {
            setField(subject, "providerMap", Map.of(providerName, provider));
            setField(subject, "selectedProviderName", providerName);
            setField(subject, "selectedModelId", modelId);
            setField(subject, "installedProviderScope", scope);
            subject.getInputBar().setText("message");
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> providerCalls.get() == 1);

        assertThat(providerCalls).hasValue(1);
    }

    @Test
    @DisplayName("Custom Together endpoints present allowlisted models as text only on initial selection")
    void setSelectedModel_whenTogetherUsesCustomBaseUrl_keepsReasoningAndAgentModeUnavailable() throws Exception {
        var provider = new ProviderRegistry.ProviderDef(
                "Together",
                "TOGETHER_API_KEY",
                "https://proxy.example/v1",
                "https://api.together.ai/v1",
                List.of("Qwen/Qwen3.5-9B"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("answer"),
                List::of
        );
        runOnEdt(() -> {
            setField(subject, "providerMap", Map.of("Together", provider));
            subject.setSelectedModel("Together > Qwen/Qwen3.5-9B");
        });

        assertThat(callOnEdt(() -> readThinkingButton(subject.getInputBar()).isVisible())).isFalse();
        assertThat(callOnEdt(() -> readAgentModeButton(subject.getInputBar()).isVisible())).isFalse();
    }

    @Test
    @DisplayName("Removing the panel invalidates provider send authority")
    void removeNotify_whenProviderWasInstalled_blocksSendUntilRefresh() throws Exception {
        setCurrentProvider(subject, immediateProvider("answer"));
        runOnEdt(() -> {
            subject.getInputBar().setText("message");
            subject.removeNotify();
            subject.getInputBar().setEnabled(true);
        });

        invokeOnSend(subject);

        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "activeSendJobs"))).isEmpty();
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("Provider configuration is still loading");
    }

    @Test
    @DisplayName("Provider refresh immediately revokes the installed send runtime")
    void refreshProviders_whenProviderWasInstalled_blocksSendUntilRefreshCompletes() throws Exception {
        setCurrentProvider(subject, immediateProvider("answer"));
        runOnEdt(() -> {
            subject.refreshProviders();
            subject.getInputBar().setEnabled(true);
            subject.getInputBar().setText("message");
        });

        invokeOnSend(subject);

        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "activeSendJobs"))).isEmpty();
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .satisfiesAnyOf(
                        message -> assertThat(message).contains("Provider configuration is still loading"),
                        message -> assertThat(message).contains("Select a model/provider")
                );
    }

    @Test
    @DisplayName("Credential invalidation blocks ordinary sends until settlement completes")
    void invalidateSelectedProviderCapabilityEvidence_whenCredentialsAreChanging_blocksSend() throws Exception {
        var providerCalls = new AtomicInteger();
        setCurrentProvider(subject, providerReturning("answer", providerCalls));
        String providerName = callOnEdt(() -> (String) readField(subject, "selectedProviderName"));
        runOnEdt(() -> {
            subject.invalidateSelectedProviderCapabilityEvidence(Set.of(providerName));
            subject.getInputBar().setText("message");
        });

        invokeOnSend(subject);

        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "activeSendJobs"))).isEmpty();
        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("credentials are still updating");
    }

    @Test
    @DisplayName("One settlement does not unblock another credential change for the same provider")
    void settleSelectedProviderCredentialChange_whenChangesOverlap_keepsProviderBlocked() throws Exception {
        var providerCalls = new AtomicInteger();
        setCurrentProvider(subject, providerReturning("answer", providerCalls));
        String providerName = callOnEdt(() -> (String) readField(subject, "selectedProviderName"));
        runOnEdt(() -> {
            subject.invalidateSelectedProviderCapabilityEvidence(Set.of(providerName));
            subject.invalidateSelectedProviderCapabilityEvidence(Set.of(providerName));
            subject.settleSelectedProviderCredentialChange(Set.of(providerName));
            subject.getInputBar().setText("message");
        });

        invokeOnSend(subject);

        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(() -> ((Map<?, ?>) readField(subject, "credentialChangesPending")).get(providerName)))
                .isEqualTo(1);
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("credentials are still updating");
    }

    @Test
    @DisplayName("Credential completion clears pending state without refreshing during shutdown")
    void settleSelectedProviderCredentialChange_whenShutdownStarted_clearsPendingState() throws Exception {
        setCurrentProvider(subject, immediateProvider("answer"));
        String providerName = callOnEdt(() -> (String) readField(subject, "selectedProviderName"));
        runOnEdt(() -> {
            subject.invalidateSelectedProviderCapabilityEvidence(Set.of(providerName));
            subject.beginShutdown();
            subject.settleSelectedProviderCredentialChange(Set.of(providerName));
        });

        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "credentialChangesPending"))).isEmpty();
    }

    @Test
    @DisplayName("Credential completion starts an authoritative full provider refresh")
    void settleSelectedProviderCredentialChange_whenFinalChangeCompletes_refreshesAllProviders() throws Exception {
        setCurrentProvider(subject, immediateProvider("answer"));
        String providerName = callOnEdt(() -> (String) readField(subject, "selectedProviderName"));
        ProviderRegistry.ProviderDef selectedProvider = callOnEdt(() ->
                ((Map<String, ProviderRegistry.ProviderDef>) readField(subject, "providerMap")).get(providerName)
        );
        ProviderRegistry.ProviderDef otherProvider = new ProviderRegistry.ProviderDef(
                "Together",
                "TOGETHER_API_KEY",
                "https://api.together.ai/v1",
                "https://api.together.ai/v1",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                ignored -> immediateProvider("other"),
                List::of
        );
        ProviderRegistry registry = mock(ProviderRegistry.class);
        var providerResolutionCalls = new AtomicInteger();
        when(registry.availableProviders()).thenAnswer(ignored -> {
            providerResolutionCalls.incrementAndGet();
            return List.of(selectedProvider, otherProvider);
        });
        runOnEdt(() -> setField(subject, "providerRegistry", registry));
        runOnEdt(() -> {
            subject.invalidateSelectedProviderCapabilityEvidence(Set.of(providerName));
            subject.settleSelectedProviderCredentialChange(Set.of(providerName));
        });

        awaitCondition(2, TimeUnit.SECONDS, () -> providerResolutionCalls.get() == 1 && callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "providerMap")).containsKey(providerName)
                        && (long) readField(subject, "installedProviderScope") >= 0L
        ));

        assertThat(providerResolutionCalls).hasValue(1);
        assertThat(callOnEdt(() -> {
            Map<?, ?> providers = (Map<?, ?>) readField(subject, "providerMap");
            return providers.containsKey(providerName) && providers.containsKey(otherProvider.name());
        })).isTrue();
        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "credentialChangesPending"))).isEmpty();
    }

    @Test
    @DisplayName("Overlapping provider, vault-wide, and OAuth credential changes wait for one final refresh")
    void settleSelectedProviderCredentialChange_whenDifferentCredentialFlowsOverlap_waitsForGlobalSettlement() throws Exception {
        ProviderRegistry registry = mock(ProviderRegistry.class);
        var refreshCalls = new AtomicInteger();
        when(registry.availableProviders()).thenAnswer(ignored -> {
            refreshCalls.incrementAndGet();
            return emptyList();
        });
        runOnEdt(() -> setField(subject, "providerRegistry", registry));
        runOnEdt(() -> {
            subject.invalidateSelectedProviderCapabilityEvidence(Set.of("OpenAI"));
            subject.invalidateSelectedProviderCapabilityEvidence(Set.of("OpenRouter"));
            subject.invalidateSelectedProviderCapabilityEvidence(Set.of("GitHub Copilot"));
            subject.settleSelectedProviderCredentialChange(Set.of("OpenAI"));
            subject.settleSelectedProviderCredentialChange(Set.of("OpenRouter"));
        });

        assertThat(refreshCalls).hasValue(0);
        assertThat(callOnEdt(() -> ((Map<?, ?>) readField(subject, "credentialChangesPending"))
                .containsKey("GitHub Copilot"))).isTrue();

        runOnEdt(() -> subject.settleSelectedProviderCredentialChange(Set.of("GitHub Copilot")));
        awaitCondition(2, TimeUnit.SECONDS, () -> refreshCalls.get() == 1);

        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "credentialChangesPending"))).isEmpty();
    }

    @Test
    @DisplayName("Credential completion while removed clears pending state and addNotify restores providers")
    void settleSelectedProviderCredentialChange_whenPanelIsRemoved_defersRefreshUntilAddNotify() throws Exception {
        setCurrentProvider(subject, immediateProvider("answer"));
        String providerName = callOnEdt(() -> (String) readField(subject, "selectedProviderName"));
        ProviderRegistry.ProviderDef provider = callOnEdt(() ->
                ((Map<String, ProviderRegistry.ProviderDef>) readField(subject, "providerMap")).get(providerName)
        );
        ProviderRegistry registry = mock(ProviderRegistry.class);
        var refreshCalls = new AtomicInteger();
        when(registry.availableProviders()).thenAnswer(ignored -> {
            refreshCalls.incrementAndGet();
            return List.of(provider);
        });
        runOnEdt(() -> setField(subject, "providerRegistry", registry));
        runOnEdt(() -> {
            subject.invalidateSelectedProviderCapabilityEvidence(Set.of(providerName));
            subject.removeNotify();
            subject.settleSelectedProviderCredentialChange(Set.of(providerName));
        });

        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "credentialChangesPending"))).isEmpty();
        assertThat(refreshCalls).hasValue(0);

        runOnEdt(subject::addNotify);
        awaitCondition(2, TimeUnit.SECONDS, () -> refreshCalls.get() == 1);
    }

    @Test
    @DisplayName("A provider refresh from before credential mutation cannot overwrite the final refresh")
    void refreshProviders_whenOldRefreshFinishesAfterCredentialCompletion_keepsFinalGeneration() throws Exception {
        ProviderRegistry.ProviderDef oldProvider = new ProviderRegistry.ProviderDef(
                "Old provider",
                null,
                null,
                null,
                List.of("old-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("old"),
                List::of
        );
        ProviderRegistry.ProviderDef finalProvider = new ProviderRegistry.ProviderDef(
                "Final provider",
                null,
                null,
                null,
                List.of("final-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("final"),
                List::of
        );
        var oldRefreshStarted = new CountDownLatch(1);
        var releaseOldRefresh = new CountDownLatch(1);
        var refreshCalls = new AtomicInteger();
        ProviderRegistry registry = mock(ProviderRegistry.class);
        when(registry.availableProviders()).thenAnswer(ignored -> {
            if (refreshCalls.incrementAndGet() == 1) {
                oldRefreshStarted.countDown();
                if (!releaseOldRefresh.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release stale provider refresh");
                }
                return List.of(oldProvider);
            }
            return List.of(finalProvider);
        });
        runOnEdt(() -> setField(subject, "providerRegistry", registry));
        runOnEdt(subject::refreshProviders);
        try {
            assertThat(oldRefreshStarted.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> subject.invalidateSelectedProviderCapabilityEvidence(Set.of("Together")));
            releaseOldRefresh.countDown();
            flushEdt();
            assertThat(callOnEdt(() -> ((Map<?, ?>) readField(subject, "providerMap"))
                    .containsKey("Old provider"))).isFalse();
            assertThat(refreshCalls).hasValue(1);

            runOnEdt(() -> subject.settleSelectedProviderCredentialChange(Set.of("Together")));
            awaitCondition(2, TimeUnit.SECONDS, () -> refreshCalls.get() >= 2 && callOnEdt(() ->
                    ((Map<?, ?>) readField(subject, "providerMap")).containsKey("Final provider")
            ));
            flushEdt();

            assertThat(callOnEdt(() -> {
                Map<?, ?> providers = (Map<?, ?>) readField(subject, "providerMap");
                return providers.containsKey("Final provider") && !providers.containsKey("Old provider");
            })).isTrue();
        } finally {
            releaseOldRefresh.countDown();
        }
    }

    @Test
    @DisplayName("Empty-state Web Search action is hidden until native search is supported")
    void applyNativeWebSearchOutcome_whenSupportChanges_updatesEmptyStateSearchAction() throws Exception {
        assertThat(callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .map(AbstractButton::getText)
                .noneMatch("Search the web"::equals))).isTrue();

        runOnEdt(() -> {
            Method method = ChatPanel.class.getDeclaredMethod(
                    "applyNativeWebSearchOutcome",
                    NativeWebSearchOutcome.class
            );
            method.setAccessible(true);
            method.invoke(subject, NativeWebSearchOutcome.OPTIONAL);
        });

        assertThat(callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .map(AbstractButton::getText)
                .anyMatch("Search the web"::equals))).isTrue();
    }

    @Test
    @DisplayName("Conversation history load fails before identity changes while Speech to Text is active")
    void loadConversationHistoryEntries_whenSpeechToTextIsActive_keepsCurrentConversation() throws Exception {
        UUID currentConversationId = UUID.randomUUID();
        UUID incomingConversationId = UUID.randomUUID();
        SpeechToTextService speechToTextService = mock(SpeechToTextService.class);
        when(speechToTextService.active()).thenReturn(true);
        setField(subject, "speechToTextService", speechToTextService);
        runOnEdt(() -> subject.setActiveConversationId(currentConversationId));

        assertThatThrownBy(() -> runOnEdt(() -> subject.loadConversationHistoryEntries(
                incomingConversationId,
                List.of()
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Speech to Text");

        assertThat(callOnEdt(() -> readField(subject, "activeConversationId"))).isEqualTo(currentConversationId);
        assertThat(callOnEdt(() -> readField(subject, "persistedConversationId"))).isEqualTo(currentConversationId);
    }

    @Test
    @DisplayName("Pending requested Web Search blocks send before provisional conversation creation")
    void onSend_whenRequestedWebSearchIsPending_blocksBeforeCreatingJob() throws Exception {
        var providerCalls = new AtomicInteger();
        setCurrentProvider(subject, providerReturning("answer", providerCalls));
        runOnEdt(() -> {
            subject.setRequestedWebSearch(true);
            setField(subject, "nativeWebSearchOutcome", NativeWebSearchOutcome.PENDING);
            subject.getInputBar().setText("search");
        });

        invokeOnSend(subject);

        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "activeSendJobs"))).isEmpty();
        assertThat(callOnEdt(() -> (UUID) readField(subject, "activeConversationId"))).isNull();
        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("Turn off Web Search");

        runOnEdt(subject.getInputBar()::activateValidationAction);
        assertThat((boolean) readField(subject, "requestedWebSearch")).isFalse();
    }

    @Test
    @DisplayName("Enabling Agent Mode clears a pending Web Search request")
    void requestAgentModeEnabled_whenWebSearchRequestIsPending_clearsSearchPreference() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("pending-agent"));
        runOnEdt(() -> {
            subject.setRequestedWebSearch(true);
            setField(subject, "nativeWebSearchOutcome", NativeWebSearchOutcome.PENDING);
            subject.getInputBar().setAgentModeAvailable(true);
            subject.getInputBar().setAgentProjectRoot(projectRoot);
            subject.getInputBar().requestAgentModeEnabled(true);
        });

        assertThat((boolean) readField(subject, "requestedWebSearch")).isFalse();
        assertThat(callOnEdt(subject.getInputBar()::isAgentModeEnabled)).isTrue();
    }

    @Test
    @DisplayName("Staged load suppresses Web Search persistence until commit")
    void applyNativeWebSearchOutcome_whenConversationLoadIsStaged_doesNotEmitPrematureEvent() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var events = new ArrayList<ChatPanel.WebSearchSettingsEvent>();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setRequestedWebSearch(true);
            subject.setOnWebSearchSettingsChanged(events::add);
            subject.beginConversationRuntimeLoad(conversationId, 7L, true, null, false, false);
            Method method = ChatPanel.class.getDeclaredMethod(
                    "applyNativeWebSearchOutcome",
                    NativeWebSearchOutcome.class
            );
            method.setAccessible(true);
            method.invoke(subject, NativeWebSearchOutcome.UNSUPPORTED);
        });

        assertThat(events).isEmpty();
        assertThat((boolean) readField(subject, "requestedWebSearch")).isTrue();
    }

    @Test
    @DisplayName("Cancelling a staged load reconciles required-search evidence with outgoing Agent Mode")
    void cancelConversationRuntimeLoad_whenRequiredCapabilityCompletes_clearsOutgoingAgentRequest() throws Exception {
        UUID conversationId = UUID.randomUUID();
        Path projectRoot = Files.createDirectories(tempDir.resolve("outgoing-agent"));
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setRequestedWebSearch(false);
            subject.getInputBar().setAgentModeAvailable(true);
            subject.getInputBar().setAgentProjectRoot(projectRoot);
            subject.getInputBar().setAgentModeEnabled(true);
            subject.beginConversationRuntimeLoad(UUID.randomUUID(), 9L, true, null, false, false);
            Method method = ChatPanel.class.getDeclaredMethod(
                    "applyNativeWebSearchOutcome",
                    NativeWebSearchOutcome.class
            );
            method.setAccessible(true);
            method.invoke(subject, NativeWebSearchOutcome.REQUIRED);
            subject.cancelConversationRuntimeLoad(9L);
        });

        assertThat((boolean) readField(subject, "requestedWebSearch")).isFalse();
        assertThat(callOnEdt(subject.getInputBar()::isAgentModeRequested)).isFalse();
        assertThat(callOnEdt(subject.getInputBar()::getAgentProjectRoot)).isEqualTo(projectRoot);
    }

    @Test
    @DisplayName("Committing conflicting loaded settings retains Web Search and canonically disables Agent Mode")
    void commitConversationRuntimeLoad_whenLoadedSearchAndAgentConflict_retainsSearchPreference() throws Exception {
        UUID conversationId = UUID.randomUUID();
        Path projectRoot = Files.createDirectories(tempDir.resolve("loaded-conflict-agent"));
        var searchEvents = new ArrayList<ChatPanel.WebSearchSettingsEvent>();
        var agentEvents = new ArrayList<ChatPanel.AgentSettingsEvent>();
        runOnEdt(() -> {
            subject.setOnWebSearchSettingsChanged(searchEvents::add);
            subject.setOnAgentSettingsChanged(agentEvents::add);
            setField(subject, "nativeWebSearchOutcome", NativeWebSearchOutcome.OPTIONAL);
            subject.beginConversationRuntimeLoad(conversationId, 11L, true, projectRoot, true, false);
            subject.commitConversationRuntimeLoad(conversationId, 11L);
        });

        assertThat((boolean) readField(subject, "requestedWebSearch")).isTrue();
        assertThat(callOnEdt(subject.getInputBar()::isAgentModeRequested)).isFalse();
        assertThat(searchEvents).isEmpty();
        assertThat(agentEvents).containsExactly(new ChatPanel.AgentSettingsEvent(conversationId, false, projectRoot));
    }

    @Test
    @DisplayName("Cancelling a staged load reconciles capability evidence against the outgoing conversation")
    void cancelConversationRuntimeLoad_whenCapabilityBecomesUnsupported_clearsOutgoingSearchPreference() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var events = new ArrayList<ChatPanel.WebSearchSettingsEvent>();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setRequestedWebSearch(true);
            subject.setOnWebSearchSettingsChanged(events::add);
            subject.beginConversationRuntimeLoad(UUID.randomUUID(), 12L, false, null, false, false);
            Method method = ChatPanel.class.getDeclaredMethod(
                    "applyNativeWebSearchOutcome",
                    NativeWebSearchOutcome.class
            );
            method.setAccessible(true);
            method.invoke(subject, NativeWebSearchOutcome.UNSUPPORTED);
            subject.cancelConversationRuntimeLoad(12L);
        });

        assertThat((boolean) readField(subject, "requestedWebSearch")).isFalse();
        assertThat(events).containsExactly(new ChatPanel.WebSearchSettingsEvent(conversationId, false));
    }

    @Test
    @DisplayName("Committing an invalid loaded Agent configuration emits one canonical correction")
    void commitConversationRuntimeLoad_whenAgentCorrectionIsRequired_emitsDisabledAgentSettings() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var agentEvents = new ArrayList<ChatPanel.AgentSettingsEvent>();
        runOnEdt(() -> {
            subject.setOnAgentSettingsChanged(agentEvents::add);
            subject.beginConversationRuntimeLoad(conversationId, 13L, false, null, false, true);
            subject.commitConversationRuntimeLoad(conversationId, 13L);
        });

        assertThat(agentEvents).containsExactly(new ChatPanel.AgentSettingsEvent(conversationId, false, null));
    }

    @Test
    @DisplayName("Enabling optional Web Search clears a retained unavailable Agent request")
    void setRequestedWebSearch_whenUnavailableAgentWasRequested_clearsAndPersistsAgentRequest() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var agentEvents = new ArrayList<ChatPanel.AgentSettingsEvent>();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setOnAgentSettingsChanged(agentEvents::add);
            subject.getInputBar().setAgentModeAvailable(true);
            subject.getInputBar().setAgentModeEnabled(true);
            subject.getInputBar().setAgentModeAvailable(false);
            subject.setRequestedWebSearch(true);
        });

        assertThat(callOnEdt(subject.getInputBar()::isAgentModeRequested)).isFalse();
        assertThat(agentEvents).containsExactly(new ChatPanel.AgentSettingsEvent(conversationId, false, null));
    }

    @Test
    @DisplayName("Late optional Web Search support clears a conflicting Agent request")
    void applyNativeWebSearchOutcome_whenOptionalSearchResolvesAfterLoad_clearsAgentRequest() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("late-optional-agent"));
        runOnEdt(() -> {
            subject.getInputBar().setAgentModeAvailable(true);
            subject.getInputBar().setAgentProjectRoot(projectRoot);
            subject.getInputBar().setAgentModeEnabled(true);
            subject.setRequestedWebSearch(true);
            Method method = ChatPanel.class.getDeclaredMethod(
                    "applyNativeWebSearchOutcome",
                    NativeWebSearchOutcome.class
            );
            method.setAccessible(true);
            method.invoke(subject, NativeWebSearchOutcome.OPTIONAL);
        });

        assertThat(callOnEdt(subject.getInputBar()::isAgentModeRequested)).isFalse();
        assertThat(callOnEdt(subject.getInputBar()::isAgentModeEnabled)).isFalse();
        assertThat((boolean) readInputBarField(subject.getInputBar(), "webSearchEnabled")).isTrue();
    }

    @Test
    @DisplayName("Required Web Search accepts a retained true optional preference")
    void onSend_whenWebSearchIsRequiredAndPreferenceIsTrue_startsProvider() throws Exception {
        var providerInvoked = new CountDownLatch(1);
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
                providerInvoked.countDown();
                onComplete.run();
            }
        });
        runOnEdt(() -> {
            subject.setRequestedWebSearch(true);
            setField(subject, "nativeWebSearchOutcome", NativeWebSearchOutcome.REQUIRED);
            subject.getInputBar().setText("search");
        });

        invokeOnSend(subject);

        assertThat(providerInvoked.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("Required Web Search preserves the optional preference in the send snapshot")
    void onSend_whenWebSearchIsRequired_preservesRequestedPreferenceAndEnablesEffectiveSearch() throws Exception {
        setCurrentProvider(subject, immediateProvider("answer"));
        runOnEdt(() -> {
            subject.setRequestedWebSearch(false);
            setField(subject, "nativeWebSearchOutcome", NativeWebSearchOutcome.REQUIRED);
            subject.getInputBar().setText("search");
        });
        var admissionStarted = new CountDownLatch(1);
        var releaseAdmission = new CountDownLatch(1);
        installBlockingProvider(subject, admissionStarted, releaseAdmission);
        runOnEdt(() -> setField(subject, "nativeWebSearchOutcome", NativeWebSearchOutcome.REQUIRED));

        try {
            invokeOnSend(subject);
            assertThat(admissionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            SendJob job = callOnEdt(() -> ((Map<Long, SendJob>) readField(subject, "activeSendJobs"))
                    .values().iterator().next());
            assertThat(job.requestedWebSearch).isFalse();
            assertThat(job.webSearchEnabled).isTrue();
            assertThat(job.runtime.webSearchOutcome()).isEqualTo(NativeWebSearchOutcome.REQUIRED);
        } finally {
            releaseAdmission.countDown();
        }
    }

    @Test
    @DisplayName("Credential invalidation during provider creation prevents stale admission")
    void onSend_whenCredentialsChangeDuringProviderCreation_doesNotPersistOrStream() throws Exception {
        var admissionStarted = new CountDownLatch(1);
        var releaseAdmission = new CountDownLatch(1);
        var persistenceCalls = new AtomicInteger();
        installBlockingProvider(subject, admissionStarted, releaseAdmission);
        runOnEdt(() -> {
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceCalls.incrementAndGet();
                return CompletableFuture.completedFuture(event.conversationId());
            });
            readInputTextArea(subject.getInputBar()).setText("do not send with stale credentials");
        });

        try {
            invokeOnSend(subject);
            assertThat(admissionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            String providerName = callOnEdt(() -> (String) readField(subject, "selectedProviderName"));
            runOnEdt(() -> subject.invalidateSelectedProviderCapabilityEvidence(Set.of(providerName)));
            releaseAdmission.countDown();
            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                    ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()
            ));

            assertThat(persistenceCalls).hasValue(0);
            assertThat(callOnEdt(subject::getHistory)).isEmpty();
            assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                    .contains("Provider credentials changed while the request was being prepared");
        } finally {
            releaseAdmission.countDown();
        }
    }

    @Test
    @DisplayName("Credential changes during durable user persistence prevent ordinary transport with the captured key")
    void onSend_whenCredentialsChangeDuringPersistence_skipsOrdinaryTransport() throws Exception {
        var transportCalls = new AtomicInteger();
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
                transportCalls.incrementAndGet();
                onComplete.run();
            }
        });
        var persistence = new CompletableFuture<UUID>();
        controlledFutures.add(persistence);
        runOnEdt(() -> {
            subject.setOnDurableUserMessageSubmitted(event -> persistence);
            subject.getInputBar().setText("persist first");
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> {
            Map<Long, SendJob> jobs = (Map<Long, SendJob>) readField(subject, "activeSendJobs");
            return !jobs.isEmpty() && jobs.values().iterator().next().durableUserMessageSubmissionStarted;
        }));
        String providerName = callOnEdt(() -> (String) readField(subject, "selectedProviderName"));
        runOnEdt(() -> subject.invalidateSelectedProviderCapabilityEvidence(Set.of(providerName)));
        persistence.complete(UUID.randomUUID());
        flushEdt();
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()
        ));

        assertThat(transportCalls).hasValue(0);
        assertThat(callOnEdt(subject::getHistory)).singleElement().satisfies(message ->
                assertThat(message.role()).isEqualTo(Role.USER)
        );
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("credentials changed while the message was being saved");
    }

    @Test
    @DisplayName("Credential changes during durable user persistence prevent Agent transport with the captured key")
    void onSend_whenCredentialsChangeDuringPersistence_skipsAgentTransport() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("credential-gap-agent"));
        var agentTurns = new AtomicInteger();
        var orchestrator = new AgentOrchestrator(new AgentProviderAdapterFactory(attachmentSupport) {
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
                    agentTurns.incrementAndGet();
                    return AgentTurnResult.complete();
                };
            }
        }, new LocalToolRuntime());
        runOnEdt(() -> {
            subject.setAgentOrchestratorForTests(orchestrator);
            setCurrentProvider(subject, immediateProvider("unused"));
        });
        var persistence = new CompletableFuture<UUID>();
        controlledFutures.add(persistence);
        runOnEdt(() -> {
            subject.getInputBar().setAgentModeAvailable(true);
            subject.getInputBar().setAgentProjectRoot(projectRoot);
            subject.getInputBar().setAgentModeEnabled(true);
            subject.setOnDurableUserMessageSubmitted(event -> persistence);
            subject.getInputBar().setText("persist agent turn first");
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> {
            Map<Long, SendJob> jobs = (Map<Long, SendJob>) readField(subject, "activeSendJobs");
            return !jobs.isEmpty() && jobs.values().iterator().next().durableUserMessageSubmissionStarted;
        }));
        String providerName = callOnEdt(() -> (String) readField(subject, "selectedProviderName"));
        runOnEdt(() -> subject.invalidateSelectedProviderCapabilityEvidence(Set.of(providerName)));
        persistence.complete(UUID.randomUUID());
        flushEdt();
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()
        ));

        assertThat(agentTurns).hasValue(0);
        assertThat(callOnEdt(subject::getHistory)).singleElement().satisfies(message ->
                assertThat(message.role()).isEqualTo(Role.USER)
        );
    }

    @Test
    @DisplayName("Ordinary non-Together sends retain their provider-specific attachment notice")
    void onSend_whenOrdinaryProviderHasFile_persistsProviderFallbackNotice() throws Exception {
        setCurrentProvider(subject, immediateProvider("answer"));
        Path attachmentPath = Files.writeString(tempDir.resolve("ordinary-current.txt"), "attachment");
        var attachment = new ComposerAttachment(
                attachmentPath,
                "text/plain",
                Files.size(attachmentPath),
                false
        );
        var persistedEvent = new AtomicReference<ChatPanel.UserMessageEvent>();
        runOnEdt(() -> {
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistedEvent.set(event);
                return CompletableFuture.completedFuture(event.conversationId());
            });
            subject.getInputBar().setComposerState(new ComposerState("inspect", List.of(attachment), emptyList()));
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> persistedEvent.get() != null);

        assertThat(persistedEvent.get().message().meta().fallbackNotices())
                .singleElement()
                .asString()
                .contains("native file upload")
                .doesNotContain("Together Agent Mode");
    }

    @Test
    @DisplayName("Together Agent sends disclose metadata-only projection for a current attachment")
    void onSend_whenTogetherAgentHasCurrentAttachment_persistsAccurateNotice() throws Exception {
        var agentTurns = new AtomicInteger();
        configureTogetherAgent(agentTurns);
        Path attachmentPath = Files.writeString(tempDir.resolve("together-current.txt"), "attachment");
        var attachment = new ComposerAttachment(
                attachmentPath,
                "text/plain",
                Files.size(attachmentPath),
                false
        );
        var persistedEvent = new AtomicReference<ChatPanel.UserMessageEvent>();
        runOnEdt(() -> {
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistedEvent.set(event);
                return CompletableFuture.completedFuture(event.conversationId());
            });
            subject.getInputBar().setComposerState(new ComposerState("inspect", List.of(attachment), emptyList()));
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> persistedEvent.get() != null && agentTurns.get() == 1);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()
        ));

        assertThat(persistedEvent.get().message().meta().fallbackNotices())
                .containsExactly("Attachment contents were not sent to Together Agent Mode; only metadata labels were provided.");
        assertThat(callOnEdt(subject::getHistory).getFirst().meta().fallbackNotices())
                .isEqualTo(persistedEvent.get().message().meta().fallbackNotices());
    }

    @Test
    @DisplayName("Together Agent sends disclose metadata-only projection for an image-only message")
    void onSend_whenTogetherAgentHasOnlyAnImage_persistsAccurateNotice() throws Exception {
        var agentTurns = new AtomicInteger();
        configureTogetherAgent(agentTurns);
        Path imagePath = Files.writeString(tempDir.resolve("together-current.png"), "image");
        var image = new ComposerAttachment(
                imagePath,
                "image/png",
                Files.size(imagePath),
                true
        );
        var persistedEvent = new AtomicReference<ChatPanel.UserMessageEvent>();
        runOnEdt(() -> {
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistedEvent.set(event);
                return CompletableFuture.completedFuture(event.conversationId());
            });
            subject.getInputBar().setComposerState(new ComposerState("inspect", List.of(image), emptyList()));
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> persistedEvent.get() != null && agentTurns.get() == 1);

        assertThat(persistedEvent.get().message().meta().fallbackNotices())
                .containsExactly("Attachment contents were not sent to Together Agent Mode; only metadata labels were provided.");
    }

    @Test
    @DisplayName("Failed durable retry leaving Together Agent replaces its disclosure with the target provider notice")
    void onSend_whenFailedRetryLeavesTogetherAgent_refinalizesAttachmentNotice() throws Exception {
        var agentTurns = new AtomicInteger();
        configureTogetherAgent(agentTurns);
        Path attachmentPath = Files.writeString(tempDir.resolve("retry-leaves-together.txt"), "attachment");
        var attachment = new ComposerAttachment(
                attachmentPath,
                "text/plain",
                Files.size(attachmentPath),
                false
        );
        var persistenceCalls = new AtomicInteger();
        List<ChatPanel.UserMessageEvent> events = new CopyOnWriteArrayList<>();
        var firstPersistence = new CompletableFuture<UUID>();
        runOnEdt(() -> {
            subject.setOnDurableUserMessageSubmitted(event -> {
                events.add(event);
                if (persistenceCalls.incrementAndGet() == 1) {
                    return firstPersistence;
                }
                return CompletableFuture.completedFuture(event.conversationId());
            });
            subject.getInputBar().setComposerState(new ComposerState("inspect", List.of(attachment), emptyList()));
        });

        invokeOnSend(subject);
        awaitCurrentSendPreparation();
        assertThat(events).hasSize(1);
        assertThat(firstPersistence.completeExceptionally(new SQLException("forced failure"))).isTrue();
        flushEdt();
        configureAgent("OpenAI", "gpt-5", "https://api.openai.com/v1", agentTurns);
        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> events.size() == 2 && agentTurns.get() == 1);

        assertThat(events.getFirst().message().meta().fallbackNotices())
                .containsExactly("Attachment contents were not sent to Together Agent Mode; only metadata labels were provided.");
        assertThat(events.get(1).message().meta().fallbackNotices())
                .singleElement()
                .asString()
                .contains("OpenAI", "native file upload is not mapped")
                .doesNotContain("Together Agent Mode");
    }

    @Test
    @DisplayName("Failed durable retry entering Together Agent replaces the target provider notice with its disclosure")
    void onSend_whenFailedRetryEntersTogetherAgent_refinalizesAttachmentNotice() throws Exception {
        var agentTurns = new AtomicInteger();
        configureAgent("OpenAI", "gpt-5", "https://api.openai.com/v1", agentTurns);
        Path attachmentPath = Files.writeString(tempDir.resolve("retry-enters-together.txt"), "attachment");
        var attachment = new ComposerAttachment(
                attachmentPath,
                "text/plain",
                Files.size(attachmentPath),
                false
        );
        var persistenceCalls = new AtomicInteger();
        List<ChatPanel.UserMessageEvent> events = new CopyOnWriteArrayList<>();
        var firstPersistence = new CompletableFuture<UUID>();
        runOnEdt(() -> {
            subject.setOnDurableUserMessageSubmitted(event -> {
                events.add(event);
                if (persistenceCalls.incrementAndGet() == 1) {
                    return firstPersistence;
                }
                return CompletableFuture.completedFuture(event.conversationId());
            });
            subject.getInputBar().setComposerState(new ComposerState("inspect", List.of(attachment), emptyList()));
        });

        invokeOnSend(subject);
        awaitCurrentSendPreparation();
        assertThat(events).hasSize(1);
        assertThat(firstPersistence.completeExceptionally(new SQLException("forced failure"))).isTrue();
        flushEdt();
        configureTogetherAgent(agentTurns);
        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> events.size() == 2 && agentTurns.get() == 1);

        assertThat(events.getFirst().message().meta().fallbackNotices())
                .singleElement()
                .asString()
                .contains("OpenAI", "native file upload is not mapped");
        assertThat(events.get(1).message().meta().fallbackNotices())
                .containsExactly("Attachment contents were not sent to Together Agent Mode; only metadata labels were provided.");
    }

    @Test
    @DisplayName("Together Agent sends disclose metadata-only projection when only history contains an attachment")
    void onSend_whenTogetherAgentHistoryHasAttachment_persistsAccurateNotice() throws Exception {
        Path stored = Files.writeString(tempDir.resolve("together-history.txt"), "history");
        AttachmentRef attachment = new AttachmentRef(
                UUID.randomUUID(),
                stored.toString(),
                "together-history.txt",
                "text/plain",
                Files.size(stored),
                ""
        );
        runOnEdt(() -> subject.loadHistory(List.of(new Message(
                Role.USER,
                List.of(
                        new TextPart("earlier"),
                        new FilePart(attachment),
                        new ImagePart(attachment, 20, 20),
                        new GeneratedImagePart(attachment, 20, 20, "generated")
                ),
                Instant.now()
        ))));
        var agentTurns = new AtomicInteger();
        configureTogetherAgent(agentTurns);
        var persistedEvent = new AtomicReference<ChatPanel.UserMessageEvent>();
        runOnEdt(() -> {
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistedEvent.set(event);
                return CompletableFuture.completedFuture(event.conversationId());
            });
            subject.getInputBar().setText("continue");
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> persistedEvent.get() != null && agentTurns.get() == 1);

        assertThat(persistedEvent.get().message().meta().fallbackNotices())
                .containsExactly("Attachment contents were not sent to Together Agent Mode; only metadata labels were provided.");
    }

    @Test
    @DisplayName("Together Agent regeneration renders attachment disclosure separately from provider activity")
    void regenerateRecentResponse_whenTogetherAgentHistoryHasAttachment_addsTransientNotice() throws Exception {
        Path stored = Files.writeString(tempDir.resolve("together-regenerate.txt"), "history");
        AttachmentRef attachment = new AttachmentRef(
                UUID.randomUUID(),
                stored.toString(),
                "together-regenerate.txt",
                "text/plain",
                Files.size(stored),
                ""
        );
        Message userMessage = new Message(
                Role.USER,
                List.of(new TextPart("question"), new FilePart(attachment)),
                Instant.now()
        );
        runOnEdt(() -> loadPersistedHistory(userMessage, Message.assistant("old answer")));
        var agentTurns = new AtomicInteger();
        configureTogetherAgent(agentTurns);

        runOnEdt(subject::regenerateRecentResponse);
        awaitCondition(2, TimeUnit.SECONDS, () -> agentTurns.get() == 1);
        flushEdt();

        List<ActivityBubble> notices = callOnEdt(() -> findComponents(subject, ActivityBubble.class).stream()
                .filter(bubble -> "Attachment notice".equals(bubble.getTitleText()))
                .toList());
        assertThat(notices).singleElement().satisfies(notice -> {
            assertThat(callOnEdt(() -> notice == readField(subject, "currentAssistantActivityBubble"))).isFalse();
            assertThat(callOnEdt(notice::getFullText)).contains("only metadata labels were provided");
            assertThat(callOnEdt(notice::isCollapsed)).isFalse();
            assertThat(callOnEdt(() -> readActivityContentPanel(notice).isVisible())).isTrue();
        });
    }

    @Test
    @DisplayName("Together Agent regeneration ignores attachments removed with the discarded assistant tail")
    void regenerateRecentResponse_whenOnlyDiscardedTailHasAttachment_doesNotAddNotice() throws Exception {
        Path stored = Files.writeString(tempDir.resolve("discarded-generated.png"), "image");
        AttachmentRef attachment = new AttachmentRef(
                UUID.randomUUID(),
                stored.toString(),
                "discarded-generated.png",
                "image/png",
                Files.size(stored),
                ""
        );
        Message assistantMessage = new Message(
                Role.ASSISTANT,
                List.of(
                        new TextPart("old answer"),
                        new GeneratedImagePart(attachment, 20, 20, "generated")
                ),
                Instant.now()
        );
        runOnEdt(() -> loadPersistedHistory(Message.user("question"), assistantMessage));
        var agentTurns = new AtomicInteger();
        configureTogetherAgent(agentTurns);

        runOnEdt(subject::regenerateRecentResponse);
        awaitCondition(2, TimeUnit.SECONDS, () -> agentTurns.get() == 1);
        flushEdt();

        assertThat(callOnEdt(() -> findComponents(subject, ActivityBubble.class).stream()
                .map(ActivityBubble::getTitleText)
                .noneMatch("Attachment notice"::equals))).isTrue();
    }

    @Test
    @DisplayName("Send is blocked when agent mode is enabled without a valid project folder")
    void onSend_whenAgentModeEnabledWithoutProjectFolder_showsValidationAndSkipsSend() throws Exception {
        runOnEdt(() -> {
            setCurrentProvider(subject, immediateProvider("pong"));
            subject.getInputBar().setAgentModeAvailable(true);

            Field enabledField = InputBar.class.getDeclaredField("agentModeEnabled");
            enabledField.setAccessible(true);
            enabledField.setBoolean(subject.getInputBar(), true);

            Field projectRootField = InputBar.class.getDeclaredField("agentProjectRoot");
            projectRootField.setAccessible(true);
            projectRootField.set(subject.getInputBar(), null);
            readInputTextArea(subject.getInputBar()).setText("ping");
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()
                        && readValidationLabel(subject.getInputBar()).isVisible()
        ));

        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("Select a valid project folder");
        assertThat(callOnEdt(subject::getHistory)).isEmpty();
    }

    @Test
    @DisplayName("Agent mode routes send flow through orchestrator path")
    void onSend_whenAgentModeEnabled_routesThroughAgentOrchestrator() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("agent-route"));

        runOnEdt(() -> {
            subject.getInputBar().setAgentModeAvailable(true);
            subject.getInputBar().setAgentProjectRoot(projectRoot);
            subject.getInputBar().setAgentModeEnabled(true);
            subject.setAgentOrchestratorForTests(new AgentOrchestrator(new AgentProviderAdapterFactory(attachmentSupport) {
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
            });
            readInputTextArea(subject.getInputBar()).setText("ping");
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));

        assertThat(callOnEdt(subject::getHistory).get(1).content()).isEqualTo("agent-response");
    }

    @Test
    @DisplayName("Agent startup failure completes the active request instead of leaving the composer blocked")
    void onSend_whenAgentOrchestratorThrows_finishesRequestWithError() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("agent-startup-failure"));
        runOnEdt(() -> {
            subject.getInputBar().setAgentModeAvailable(true);
            subject.getInputBar().setAgentProjectRoot(projectRoot);
            subject.getInputBar().setAgentModeEnabled(true);
            subject.setAgentOrchestratorForTests(new AgentOrchestrator(new AgentProviderAdapterFactory(attachmentSupport) {
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
                        throw new IllegalStateException("agent startup failed");
                    };
                }
            }, new LocalToolRuntime()));
            setField(subject, "selectedProviderName", "OpenAI");
            setField(subject, "selectedModelId", "gpt-5-mini");
            setCurrentProvider(subject, immediateProvider("unused"));
            readInputTextArea(subject.getInputBar()).setText("ping");
        });
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(
                () -> !subject.isStreaming() && subject.getHistory().size() == 2
        ));

        List<Message> history = callOnEdt(subject::getHistory);
        assertThat(history.getLast().content()).contains("agent startup failed");
        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "activeSendJobs"))).isEmpty();
        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "activeSessions"))).isEmpty();
    }

    @Test
    @DisplayName("Provider errors cannot persist the request API key in conversation history")
    void onSend_whenProviderErrorContainsApiKey_redactsCredentialBeforePersistence() throws Exception {
        String apiKey = "secret-provider-key";
        ProviderService failingProvider = new ProviderService() {
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
                onError.accept(new IllegalStateException("401 rejected %s".formatted(apiKey)));
            }


            @Override
            public String apiKey() {
                return apiKey;
            }
        };
        runOnEdt(() -> {
            setField(subject, "selectedProviderName", "OpenAI");
            setField(subject, "selectedModelId", "gpt-5-mini");
            setCurrentProvider(subject, failingProvider);
            readInputTextArea(subject.getInputBar()).setText("ping");
        });
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(
                () -> !subject.isStreaming() && subject.getHistory().size() == 2
        ));

        List<Message> history = callOnEdt(subject::getHistory);
        assertThat(history.getLast().content())
                .contains("401 rejected [REDACTED]")
                .doesNotContain(apiKey);
    }

    @Test
    @DisplayName("A synchronous provider linkage failure completes the active request")
    void onSend_whenProviderThrowsError_finishesRequestAndReleasesOwnership() throws Exception {
        ProviderService failingProvider = new ProviderService() {
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
                throw new NoClassDefFoundError("provider linkage failed");
            }

        };
        runOnEdt(() -> {
            setField(subject, "selectedProviderName", "OpenAI");
            setField(subject, "selectedModelId", "gpt-5-mini");
            setCurrentProvider(subject, failingProvider);
            readInputTextArea(subject.getInputBar()).setText("ping");
        });
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(
                () -> !subject.isStreaming() && subject.getHistory().size() == 2
        ));

        List<Message> history = callOnEdt(subject::getHistory);
        assertThat(history.getLast().content()).contains("NoClassDefFoundError");
        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "activeSendJobs"))).isEmpty();
        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "activeSessions"))).isEmpty();
    }

    @Test
    @DisplayName("Agent mode renders tool bubbles before the final answer when tools run first")
    void onSend_whenAgentModeUsesToolsBeforeAnswer_rendersSeparateToolBubblesBeforeAssistantAnswer() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("agent-tools-bubble"));
        Files.writeString(projectRoot.resolve("note.txt"), "hello tool");
        AtomicInteger turns = new AtomicInteger();

        runOnEdt(() -> {
            subject.getInputBar().setAgentModeAvailable(true);
            subject.getInputBar().setAgentProjectRoot(projectRoot);
            subject.getInputBar().setAgentModeEnabled(true);
            subject.setAgentOrchestratorForTests(new AgentOrchestrator(new AgentProviderAdapterFactory(attachmentSupport) {
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
                                    new ToolInvocationRequest("list-root", "ls", "{\"path\":\".\"}"),
                                    new ToolInvocationRequest("read-note", "read", "{\"path\":\"note.txt\"}")
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
            readInputTextArea(subject.getInputBar()).setText("ping");
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));

        callOnEdt(() -> {
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
            return null;
        });
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

        callOnEdt(() -> {
            subject.loadHistory(List.of(Message.user("run tools"), assistantMessage));
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            List<ActivityBubble> toolBubbles = findComponents(messagesPanel, ActivityBubble.class).stream()
                    .filter(bubble -> !hasVisibleCollapseToggle(bubble))
                    .toList();

            assertThat(toolBubbles).hasSize(2);
            assertThat(thinkingBubbleTitle(toolBubbles.getFirst())).isEqualTo("✓ read note.txt");
            assertThat(thinkingBubbleTitle(toolBubbles.get(1))).isEqualTo("✗ grep . todo — no matches");
            assertThat(messageRowIndex(messagesPanel, toolBubbles.getFirst()))
                    .isLessThan(messageRowIndex(messagesPanel, assistantBubble(messagesPanel)));
            return null;
        });
    }

    @Test
    @DisplayName("Agent mode passes configured prompt addendum to orchestrator")
    void onSend_whenAgentPromptAddendumConfigured_passesAddendumToOrchestrator() throws Exception {
        Path projectRoot = Files.createDirectories(tempDir.resolve("agent-route-addendum"));
        AtomicReference<String> observedPromptAppend = new AtomicReference<>();

        runOnEdt(() -> {
            subject.getInputBar().setAgentModeAvailable(true);
            subject.getInputBar().setAgentProjectRoot(projectRoot);
            subject.getInputBar().setAgentModeEnabled(true);
            subject.setAgentSystemPromptAppend("Always include key project files.");

            subject.setAgentOrchestratorForTests(new AgentOrchestrator(new AgentProviderAdapterFactory(attachmentSupport) {
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
            readInputTextArea(subject.getInputBar()).setText("ping");
        });

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));

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

        });
        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> {
            textArea.setText("ping");
            subject.setConversationLoading(true);
        });

        invokeOnSend(subject);
        flushEdt();

        assertThat(providerCalls).hasValue(0);
        callOnEdt(() -> {
            assertThat(subject.getHistory()).isEmpty();
            assertThat(subject.getInputBar().isEnabled()).isFalse();
            subject.setConversationLoading(false);
            assertThat(subject.getInputBar().isEnabled()).isTrue();
            return null;
        });
    }

    @Test
    @DisplayName("Browser typing remains through thinking and whitespace until visible answer content")
    void onSend_whenBrowserStreamAwaitsVisibleAnswer_updatesTransientTypingLifecycle() throws Exception {
        var transcript = new AtomicReference<List<ConversationEntry>>();
        installSystemWebViewCapture(transcript);
        var streamStarted = new CountDownLatch(1);
        var releaseThinking = new CountDownLatch(1);
        var thinkingSent = new CountDownLatch(1);
        var releaseWhitespace = new CountDownLatch(1);
        var whitespaceSent = new CountDownLatch(1);
        var releaseAnswer = new CountDownLatch(1);
        var streamFinished = new CountDownLatch(1);
        String invisiblePrefix = " \n\u200B\u00A0";
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
                    releaseThinking.await();
                    onToken.accept("<think>Reviewing the request</think>");
                    thinkingSent.countDown();
                    releaseWhitespace.await();
                    onToken.accept(invisiblePrefix);
                    whitespaceSent.countDown();
                    releaseAnswer.await();
                    onToken.accept("Visible answer");
                    onComplete.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    streamFinished.countDown();
                }
            }
        });
        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("Question"));

        try {
            invokeOnSend(subject);
            assertThat(streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
            flushEdt();

            assertThat(transcript.get()).extracting(ConversationEntry::kind)
                    .containsExactly(ConversationEntryKind.MESSAGE, ConversationEntryKind.TYPING);
            assertThat(transcript.get().getLast().messageIndex()).isEqualTo(-1);

            releaseThinking.countDown();
            assertThat(thinkingSent.await(2, TimeUnit.SECONDS)).isTrue();
            flushEdt();

            assertThat(transcript.get()).extracting(ConversationEntry::kind)
                    .containsExactly(
                            ConversationEntryKind.MESSAGE,
                            ConversationEntryKind.ACTIVITY,
                            ConversationEntryKind.TYPING
                    );

            releaseWhitespace.countDown();
            assertThat(whitespaceSent.await(2, TimeUnit.SECONDS)).isTrue();
            flushEdt();

            assertThat(transcript.get()).extracting(ConversationEntry::kind)
                    .containsExactly(
                            ConversationEntryKind.MESSAGE,
                            ConversationEntryKind.ACTIVITY,
                            ConversationEntryKind.TYPING
                    );

            releaseAnswer.countDown();
            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));
            flushEdt();

            assertThat(transcript.get()).extracting(ConversationEntry::kind)
                    .containsExactlyInAnyOrder(
                            ConversationEntryKind.MESSAGE,
                            ConversationEntryKind.MESSAGE,
                            ConversationEntryKind.ACTIVITY
                    )
                    .doesNotContain(ConversationEntryKind.TYPING);
            assertThat(transcript.get()).filteredOn(entry -> entry.role() == Role.ASSISTANT)
                    .filteredOn(entry -> entry.kind() == ConversationEntryKind.MESSAGE)
                    .singleElement()
                    .extracting(ConversationEntry::text)
                    .isEqualTo("%sVisible answer".formatted(invisiblePrefix));
        } finally {
            releaseThinking.countDown();
            releaseWhitespace.countDown();
            releaseAnswer.countDown();
            assertThat(streamFinished.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("Browser typing clears when a generated image becomes visible in preview mode")
    void setRenderMode_whenBrowserGeneratedImageBecomesVisible_replacesTypingEntry() throws Exception {
        var transcript = new AtomicReference<List<ConversationEntry>>();
        installSystemWebViewCapture(transcript);
        runOnEdt(() -> subject.setRenderMode(RenderMode.MARKDOWN, true));
        var streamStarted = new CountDownLatch(1);
        var releaseFilePart = new CountDownLatch(1);
        var filePartSent = new CountDownLatch(1);
        var releaseGeneratedImage = new CountDownLatch(1);
        var generatedImageSent = new CountDownLatch(1);
        var releaseComplete = new CountDownLatch(1);
        var streamFinished = new CountDownLatch(1);
        Path output = tempDir.resolve("generated.png");
        assertThat(ImageIO.write(
                new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB),
                "png",
                output.toFile()
        )).isTrue();
        var attachment = new AttachmentRef(
                UUID.randomUUID(),
                output.toString(),
                "generated.png",
                "image/png",
                Files.size(output),
                "sha"
        );
        var filePart = new FilePart(attachment);
        var generatedImagePart = new GeneratedImagePart(attachment, 512, 512, "Generated image");
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
                throw new AssertionError("Legacy stream overload should not be used");
            }

            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    WebSearchRequestOptions webSearchOptions,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Consumer<ContentPart> onPart,
                    Consumer<CitationRef> onCitation,
                    Consumer<String> onWebSearchQuery,
                    Consumer<WebSearchSource> onWebSearchSource,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled,
                    Consumer<AutoCloseable> registerActiveStream,
                    Runnable clearActiveStream
            ) {
                streamStarted.countDown();
                try {
                    releaseFilePart.await();
                    onPart.accept(filePart);
                    filePartSent.countDown();
                    releaseGeneratedImage.await();
                    onPart.accept(generatedImagePart);
                    generatedImageSent.countDown();
                    releaseComplete.await();
                    onComplete.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    streamFinished.countDown();
                }
            }
        });
        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("Create a file"));

        try {
            invokeOnSend(subject);
            assertThat(streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertSingleTypingEntryIsLast(transcript.get());

            releaseFilePart.countDown();
            assertThat(filePartSent.await(2, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertSingleTypingEntryIsLast(transcript.get());

            releaseGeneratedImage.countDown();
            assertThat(generatedImageSent.await(2, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertSingleTypingEntryIsLast(transcript.get());

            runOnEdt(() -> subject.setRenderMode(RenderMode.PREVIEW, true));
            flushEdt();
            assertThat(transcript.get()).extracting(ConversationEntry::kind)
                    .doesNotContain(ConversationEntryKind.TYPING);

            releaseComplete.countDown();
            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));
            flushEdt();

            assertThat(transcript.get()).extracting(ConversationEntry::kind)
                    .doesNotContain(ConversationEntryKind.TYPING);
            assertThat(transcript.get()).filteredOn(entry -> entry.role() == Role.ASSISTANT)
                    .filteredOn(entry -> entry.kind() == ConversationEntryKind.MESSAGE)
                    .singleElement()
                    .extracting(ConversationEntry::parts)
                    .asList()
                    .containsExactly(filePart, generatedImagePart);
        } finally {
            releaseFilePart.countDown();
            releaseGeneratedImage.countDown();
            releaseComplete.countDown();
            assertThat(streamFinished.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("Browser typing follows its streaming conversation when the visible conversation changes")
    void setActiveConversationId_whenBrowserStreamHasNoAnswer_movesTypingWithConversation() throws Exception {
        var transcript = new AtomicReference<List<ConversationEntry>>();
        installSystemWebViewCapture(transcript);
        var originalConversationId = UUID.randomUUID();
        var otherConversationId = UUID.randomUUID();
        var streamStarted = new CountDownLatch(1);
        var releaseAnswer = new CountDownLatch(1);
        var streamFinished = new CountDownLatch(1);
        runOnEdt(() -> {
            subject.setActiveConversationId(originalConversationId);
            subject.setConversationIdSupplier(() -> originalConversationId);
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
                streamStarted.countDown();
                try {
                    releaseAnswer.await();
                    onToken.accept("Answer");
                    onComplete.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    streamFinished.countDown();
                }
            }
        });
        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("Question"));

        try {
            invokeOnSend(subject);
            assertThat(streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertSingleTypingEntryIsLast(transcript.get());

            runOnEdt(() -> {
                subject.setActiveConversationId(otherConversationId);
                subject.loadHistory(List.of(Message.user("Other conversation")));
            });
            flushEdt();
            assertThat(transcript.get()).extracting(ConversationEntry::kind)
                    .containsExactly(ConversationEntryKind.MESSAGE)
                    .doesNotContain(ConversationEntryKind.TYPING);

            runOnEdt(() -> {
                subject.setActiveConversationId(originalConversationId);
                subject.loadHistory(List.of(Message.user("Question")));
            });
            flushEdt();
            assertSingleTypingEntryIsLast(transcript.get());

            releaseAnswer.countDown();
            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));
            flushEdt();
            assertThat(transcript.get()).extracting(ConversationEntry::kind)
                    .doesNotContain(ConversationEntryKind.TYPING);
        } finally {
            releaseAnswer.countDown();
            assertThat(streamFinished.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("Agent tool activity does not expose an orphaned provisional assistant row")
    @SuppressWarnings("unchecked")
    void handleAgentToolActivity_whenRegenerationBubbleBecomesOrphaned_keepsOnlyActivityAndTyping() throws Exception {
        var transcript = new AtomicReference<List<ConversationEntry>>();
        installSystemWebViewCapture(transcript);
        UUID conversationId = UUID.randomUUID();
        var session = new StreamingSession(42L, conversationId, null);
        Method prepareRegeneration = ChatPanel.class.getDeclaredMethod("prepareRegenerationBubbles");
        prepareRegeneration.setAccessible(true);
        Method appendVisibleToken = ChatPanel.class.getDeclaredMethod(
                "appendAssistantVisibleToken",
                StreamingSession.class,
                String.class
        );
        appendVisibleToken.setAccessible(true);
        Method handleActivity = ChatPanel.class.getDeclaredMethod(
                "handleAgentToolActivity",
                StreamingSession.class,
                AgentToolActivity.class
        );
        handleActivity.setAccessible(true);

        try {
            runOnEdt(() -> {
                subject.setActiveConversationId(conversationId);
                ((Map<Long, StreamingSession>) readField(subject, "activeSessions")).put(session.sessionId, session);
                setField(subject, "activeStreamSessionId", session.sessionId);
                setField(subject, "streaming", true);
                prepareRegeneration.invoke(subject);
            });
            appendVisibleToken.invoke(subject, session, " \n");
            flushEdt();
            handleActivity.invoke(subject, session, new AgentToolActivity(
                    "invocation-1",
                    "read_file",
                    AgentToolActivity.Status.STARTED,
                    "README.md",
                    ""
            ));
            flushEdt();

            assertThat(transcript.get()).extracting(ConversationEntry::kind)
                    .containsExactly(ConversationEntryKind.ACTIVITY, ConversationEntryKind.TYPING);
            assertSingleTypingEntryIsLast(transcript.get());

            runOnEdt(subject::cancelStreaming);
            flushEdt();
            assertThat(transcript.get()).extracting(ConversationEntry::kind)
                    .containsExactly(ConversationEntryKind.ACTIVITY);
        } finally {
            if (session.isLive()) {
                runOnEdt(subject::cancelStreaming);
                flushEdt();
            }
        }
    }

    @Test
    @DisplayName("Browser typing clears when a stream completes without visible output")
    void onSend_whenBrowserStreamCompletesWithoutOutput_removesTypingEntry() throws Exception {
        var transcript = new AtomicReference<List<ConversationEntry>>();
        installSystemWebViewCapture(transcript);
        var streamStarted = new CountDownLatch(1);
        var releaseCompletion = new CountDownLatch(1);
        var streamFinished = new CountDownLatch(1);
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
                    releaseCompletion.await();
                    onComplete.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    streamFinished.countDown();
                }
            }
        });
        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("Question"));

        try {
            invokeOnSend(subject);
            assertThat(streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertSingleTypingEntryIsLast(transcript.get());

            releaseCompletion.countDown();
            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> !(boolean) readField(subject, "streaming")));
            flushEdt();

            assertThat(transcript.get()).extracting(ConversationEntry::kind)
                    .containsExactly(ConversationEntryKind.MESSAGE)
                    .doesNotContain(ConversationEntryKind.TYPING);
            assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                    .containsExactly("Question");
        } finally {
            releaseCompletion.countDown();
            assertThat(streamFinished.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("Browser typing clears when a stream is cancelled before answer content")
    void cancelStreamingAndMarkCancelled_whenBrowserAwaitsAnswer_removesTypingEntry() throws Exception {
        var transcript = new AtomicReference<List<ConversationEntry>>();
        installSystemWebViewCapture(transcript);
        var streamStarted = new CountDownLatch(1);
        var releaseStream = new CountDownLatch(1);
        var streamFinished = new CountDownLatch(1);
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
                    releaseStream.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    streamFinished.countDown();
                }
            }
        });
        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("Question"));

        try {
            invokeOnSend(subject);
            assertThat(streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertSingleTypingEntryIsLast(transcript.get());

            runOnEdt(subject::cancelStreamingAndMarkCancelled);
            flushEdt();

            assertThat(transcript.get()).extracting(ConversationEntry::kind)
                    .doesNotContain(ConversationEntryKind.TYPING);
            assertThat(transcript.get()).filteredOn(entry -> entry.role() == Role.ASSISTANT)
                    .filteredOn(entry -> entry.kind() == ConversationEntryKind.MESSAGE)
                    .singleElement()
                    .extracting(ConversationEntry::text)
                    .isEqualTo("\n\n[Cancelled]");
        } finally {
            releaseStream.countDown();
            assertThat(streamFinished.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("Cancelling an active stream invalidates the session and clears streaming state")
    void cancelStreaming_whenStreamIsActive_invalidatesSessionAndClearsStreamingState() throws Exception {
        runOnEdt(() -> {
            setField(subject, "streaming", true);
            setField(subject, "activeStreamSessionId", 42L);
            subject.cancelStreaming();
        });

        assertThat(callOnEdt(() -> (boolean) readField(subject, "streaming"))).isFalse();
        assertThat(callOnEdt(() -> (long) readField(subject, "activeStreamSessionId"))).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Cancelling a stream flushes buffered partial think-tag text")
    void cancelStreamingAndMarkCancelled_whenTokenEndsWithPartialThinkTag_preservesBufferedText() throws Exception {
        var streamStarted = new CountDownLatch(1);
        var releaseStream = new CountDownLatch(1);
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
                onToken.accept("answer<thi");
                streamStarted.countDown();
                try {
                    releaseStream.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("question"));

        try {
            invokeOnSend(subject);
            assertThat(streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(subject::cancelStreamingAndMarkCancelled);
            flushEdt();

            assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                    .containsExactly("question", "answer<thi\n\n[Cancelled]");
        } finally {
            releaseStream.countDown();
        }
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

        });
        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        assertThat(streamStarted.await(2, TimeUnit.SECONDS)).isTrue();
        StreamingSession session = callOnEdt(() -> ((Map<Long, StreamingSession>) readField(subject, "activeSessions"))
                .values().iterator().next());
        assertThat(session.provider).isNotNull();

        runOnEdt(subject::cancelStreaming);
        flushEdt();

        assertThat(providerCancels).hasValue(1);
        assertThat(session.provider).isNull();
    }

    @Test
    @DisplayName("Send enters preparing state before background preparation completes")
    void onSend_whenPreparationIsInFlight_showsPreparingIndicatorAndDefersHistoryMutation() throws Exception {
        var started = new CountDownLatch(1);
        var releasePreparation = new CountDownLatch(1);

        runOnEdt(() -> subject.setSendPreparerForTests((composerState, providerSnapshot, isCancelled) -> {
            started.countDown();
            while (!releasePreparation.await(20, TimeUnit.MILLISECONDS)) {
                if (isCancelled.getAsBoolean()) {
                    throw new IllegalStateException("Cancelled");
                }
            }
            return Message.user(composerState.text());
        }));

        setCurrentProvider(subject, immediateProvider("pong"));

        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("ping"));
        try {
            invokeOnSend(subject);

            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            flushEdt();

            assertThat(callOnEdt(() -> subject.getInputBar().isEnabled())).isFalse();
            assertThat(callOnEdt(() -> subject.getInputBar().isCancelGenerationVisible())).isTrue();
            assertThat(callOnEdt(subject::getHistory)).isEmpty();

            releasePreparation.countDown();
            awaitCondition(5, TimeUnit.SECONDS, () -> {
                flushEdt();
                return callOnEdt(() -> subject.getHistory().size() == 2 && subject.getInputBar().isEnabled());
            });
        } finally {
            releasePreparation.countDown();
        }
    }

    @Test
    @DisplayName("Abandoning an unsubmitted preparation keeps the draft and skips persistence")
    void abandonVisibleUnsubmittedPreparation_whenPreparationIsBlocked_cancelsJob() throws Exception {
        var started = new CountDownLatch(1);
        var releasePreparation = new CountDownLatch(1);
        var persistenceCalls = new AtomicInteger();
        runOnEdt(() -> {
            subject.setSendPreparerForTests((composerState, providerSnapshot, isCancelled) -> {
                started.countDown();
                while (!releasePreparation.await(20, TimeUnit.MILLISECONDS)) {
                    if (isCancelled.getAsBoolean()) {
                        throw new SendCancelledException();
                    }
                }
                return Message.user(composerState.text());
            });
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceCalls.incrementAndGet();
                return CompletableFuture.completedFuture(event.conversationId());
            });
            readInputTextArea(subject.getInputBar()).setText("keep draft");
        });
        setCurrentProvider(subject, immediateProvider("unused"));

        try {
            invokeOnSend(subject);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(callOnEdt(subject::hasPendingConversationMutation)).isFalse();

            runOnEdt(subject::abandonVisibleUnsubmittedPreparation);
            releasePreparation.countDown();
            flushEdt();

            assertThat(persistenceCalls).hasValue(0);
            assertThat(callOnEdt(() -> readInputTextArea(subject.getInputBar()).getText()))
                    .isEqualTo("keep draft");
            assertThat(callOnEdt(() -> ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty())).isTrue();
        } finally {
            releasePreparation.countDown();
        }
    }

    @Test
    @DisplayName("Permanent cancellation waits for an interrupted preparation worker to stop")
    void cancelAllRequestsAsync_whenPreparationWorkerIsStillRunning_waitsForWorker() throws Exception {
        var started = new CountDownLatch(1);
        var releasePreparation = new CountDownLatch(1);
        runOnEdt(() -> subject.setSendPreparerForTests((composerState, providerSnapshot, isCancelled) -> {
            started.countDown();
            while (releasePreparation.getCount() > 0) {
                try {
                    releasePreparation.await(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                }
            }
            return Message.user(composerState.text());
        }));
        setCurrentProvider(subject, immediateProvider("unused"));
        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("pending"));

        try {
            invokeOnSend(subject);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> cancellation = callOnEdt(subject::cancelAllRequestsAsync);
            assertThat(cancellation).isNotDone();
            releasePreparation.countDown();

            cancellation.join();
        } finally {
            releasePreparation.countDown();
        }
    }

    @Test
    @DisplayName("Shutdown retains an interrupted preparation worker until permanent cancellation joins it")
    void cancelAllRequestsAsync_afterBeginShutdown_waitsForPreparationWorker() throws Exception {
        var started = new CountDownLatch(1);
        var releasePreparation = new CountDownLatch(1);
        runOnEdt(() -> subject.setSendPreparerForTests((composerState, providerSnapshot, isCancelled) -> {
            started.countDown();
            while (releasePreparation.getCount() > 0) {
                try {
                    releasePreparation.await(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                }
            }
            return Message.user(composerState.text());
        }));
        setCurrentProvider(subject, immediateProvider("unused"));
        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("pending"));

        try {
            invokeOnSend(subject);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            runOnEdt(subject::beginShutdown);
            CompletableFuture<Void> cancellation = callOnEdt(subject::cancelAllRequestsAsync);
            assertThat(cancellation).isNotDone();
            releasePreparation.countDown();

            cancellation.join();
        } finally {
            releasePreparation.countDown();
        }
    }

    @Test
    @DisplayName("Permanent cancellation reports provider request close failures")
    void cancelAllRequestsAsync_whenRequestCloseFails_completesExceptionally() throws Exception {
        var session = new StreamingSession(91L, UUID.randomUUID(), immediateProvider("unused"));
        session.registerActiveRequest(() -> {
            throw new IOException("close failed");
        });
        runOnEdt(() -> {
            @SuppressWarnings("unchecked")
            Map<Long, StreamingSession> sessions = (Map<Long, StreamingSession>) readField(subject, "activeSessions");
            sessions.put(session.sessionId, session);
        });

        CompletableFuture<Void> cancellation = callOnEdt(subject::cancelAllRequestsAsync);

        assertThatThrownBy(cancellation::join).hasRootCauseMessage("close failed");
    }

    @Test
    @DisplayName("Permanent cancellation still waits for attachment cleanup when request close fails")
    void cancelAllRequestsAsync_whenRequestCloseFails_waitsForAttachmentCleanup() throws Exception {
        var session = new StreamingSession(92L, UUID.randomUUID(), immediateProvider("unused"));
        session.registerActiveRequest(() -> {
            throw new IOException("close failed");
        });
        CompletableFuture<Void> attachmentCleanup = controlledFuture();
        runOnEdt(() -> {
            @SuppressWarnings("unchecked")
            Map<Long, StreamingSession> sessions = (Map<Long, StreamingSession>) readField(subject, "activeSessions");
            sessions.put(session.sessionId, session);
            @SuppressWarnings("unchecked")
            Set<CompletableFuture<Void>> cleanupTasks =
                    (Set<CompletableFuture<Void>>) readField(subject, "attachmentDiscardTasks");
            cleanupTasks.add(attachmentCleanup);
        });

        CompletableFuture<Void> cancellation = callOnEdt(subject::cancelAllRequestsAsync);
        assertThat(cancellation).isNotDone();

        attachmentCleanup.complete(null);

        assertThatThrownBy(cancellation::join).hasRootCauseMessage("close failed");
    }

    @Test
    @DisplayName("Cancelling during preparing restores draft and clears busy state")
    void cancelStreaming_whenPreparing_restoresDraftAndClearsIndicator() throws Exception {
        var started = new CountDownLatch(1);
        var releasePreparation = new CountDownLatch(1);

        JTextArea textArea = callOnEdt(() -> {
            subject.setSendPreparerForTests((composerState, providerSnapshot, isCancelled) -> {
                started.countDown();
                while (!releasePreparation.await(20, TimeUnit.MILLISECONDS)) {
                    if (isCancelled.getAsBoolean()) {
                        throw new IllegalStateException("Cancelled");
                    }
                }
                return Message.user(composerState.text());
            });
            JTextArea input = readInputTextArea(subject.getInputBar());
            input.setText("ping");
            return input;
        });

        setCurrentProvider(subject, immediateProvider("pong"));
        invokeOnSend(subject);

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        SwingUtilities.invokeAndWait(subject::cancelStreamingAndMarkCancelled);
        flushEdt();

        callOnEdt(() -> {
            assertThat(subject.getInputBar().isEnabled()).isTrue();
            assertThat(subject.getInputBar().isCancelGenerationVisible()).isFalse();
            assertThat(subject.getHistory()).isEmpty();
            assertThat(textArea.getText()).isEqualTo("ping");
            return null;
        });

        releasePreparation.countDown();
        flushEdt();
    }

    @Test
    @DisplayName("Cancelling after durable submission adopts the saved user message without invoking the provider")
    void cancelStreaming_whenUserPersistenceIsPending_adoptsSuccessWithoutProvider() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var persistenceStarted = new CountDownLatch(1);
        CompletableFuture<UUID> persistence = controlledFuture();
        var providerCalls = new AtomicInteger();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceStarted.countDown();
                return persistence;
            });
            readInputTextArea(subject.getInputBar()).setText("save without answer");
        });
        setCurrentProvider(subject, providerReturning("unexpected", providerCalls));

        invokeOnSend(subject);
        assertThat(persistenceStarted.await(2, TimeUnit.SECONDS)).isTrue();
        runOnEdt(subject::cancelStreamingAndMarkCancelled);
        flushEdt();
        assertThat(callOnEdt(() -> subject.getInputBar().isEnabled())).isFalse();
        persistence.complete(conversationId);
        flushEdt();

        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("save without answer");
        assertThat(callOnEdt(() -> readInputTextArea(subject.getInputBar()).getText())).isEmpty();
    }

    @Test
    @DisplayName("Canonical reconciliation after cancellation adopts the user message without reviving generation")
    void resolveIndeterminateUserMessage_whenCancelledAfterSubmission_doesNotInvokeProvider() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var persistenceStarted = new CountDownLatch(1);
        CompletableFuture<UUID> persistence = controlledFuture();
        var providerCalls = new AtomicInteger();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceStarted.countDown();
                return persistence;
            });
            readInputTextArea(subject.getInputBar()).setText("reconciled without answer");
        });
        setCurrentProvider(subject, providerReturning("unexpected", providerCalls));

        invokeOnSend(subject);
        assertThat(persistenceStarted.await(2, TimeUnit.SECONDS)).isTrue();
        runOnEdt(subject::cancelStreamingAndMarkCancelled);
        persistence.completeExceptionally(new ConversationPersistenceIndeterminateException(
                new SQLException("read unavailable")
        ));
        flushEdt();

        runOnEdt(() -> subject.resolveIndeterminateUserMessage(conversationId, true));
        flushEdt();

        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("reconciled without answer");
    }

    @Test
    @DisplayName("Preparation failure keeps composer draft and shows inline error")
    void onSend_whenPreparationFails_keepsDraftAndShowsValidationError() throws Exception {
        JTextArea textArea = callOnEdt(() -> {
            subject.setSendPreparerForTests((composerState, providerSnapshot, isCancelled) -> {
                throw new IOException("Failed to stage attachment: boom");
            });
            JTextArea input = readInputTextArea(subject.getInputBar());
            input.setText("ping");
            return input;
        });

        setCurrentProvider(subject, immediateProvider("pong"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                subject.getInputBar().isEnabled()
                        && !subject.getInputBar().isCancelGenerationVisible()
                        && readValidationLabel(subject.getInputBar()).getText().contains("Failed to stage attachment")
        ));

        callOnEdt(() -> {
            assertThat(subject.getHistory()).isEmpty();
            assertThat(textArea.getText()).isEqualTo("ping");
            assertThat(readValidationLabel(subject.getInputBar()).getText()).contains("Failed to stage attachment");
            return null;
        });
    }

    @Test
    @DisplayName("Auto-scroll setting can be enabled and disabled at runtime")
    void setAutoScrollEnabled_whenCalled_updatesAutoScrollBehaviorFlag() throws Exception {
        callOnEdt(() -> {
            subject.setAutoScrollEnabled(false);
            assertThat(subject.isAutoScrollEnabled()).isFalse();

            subject.setAutoScrollEnabled(true);
            assertThat(subject.isAutoScrollEnabled()).isTrue();
            return null;
        });
    }

    @Test
    @DisplayName("Jump to latest remains visible when scroll is not at conversation end")
    void setAutoScrollEnabled_whenScrollNotAtBottom_showsJumpToLatestButton() throws Exception {
        callOnEdt(() -> {
            setField(subject, "atBottom", false);
            subject.setAutoScrollEnabled(true);
            JComponent jumpToLatestOverlay = (JComponent) readField(subject, "jumpToLatestOverlay");
            assertThat(jumpToLatestOverlay.isVisible()).isTrue();
            return null;
        });
    }

    @Test
    @DisplayName("Browser transcripts hide the duplicate Swing fades and jump button")
    void refreshJumpOverlay_whenBrowserTranscriptActive_hidesSwingTranscriptChrome() throws Exception {
        installSystemWebViewCapture(new AtomicReference<>());
        Method refreshMethod = ChatPanel.class.getDeclaredMethod("refreshJumpOverlay");
        refreshMethod.setAccessible(true);

        callOnEdt(() -> {
            setField(subject, "atBottom", false);
            JumpToLatestButton jumpToLatestOverlay = (JumpToLatestButton) readField(subject, "jumpToLatestOverlay");
            jumpToLatestOverlay.setStreaming(true);
            JLayeredPane bodyLayered = (JLayeredPane) readField(subject, "bodyLayered");
            bodyLayered.setSize(800, 600);
            bodyLayered.doLayout();
            refreshMethod.invoke(subject);

            assertThat(((JComponent) readField(subject, "topFadeOverlay")).isVisible()).isFalse();
            assertThat(((JComponent) readField(subject, "composerFadeOverlay")).isVisible()).isFalse();
            assertThat(jumpToLatestOverlay.isVisible()).isFalse();
            assertThat(jumpToLatestOverlay.isStreaming()).isFalse();
            return null;
        });
    }

    @Test
    @DisplayName("Jump to latest stays hidden when streaming at conversation end")
    void setAutoScrollEnabled_whenStreamingAtBottom_keepsJumpToLatestHidden() throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod("refreshJumpOverlay");
        method.setAccessible(true);
        callOnEdt(() -> {
            setField(subject, "atBottom", true);
            setField(subject, "streaming", true);
            method.invoke(subject);
            JComponent jumpToLatestOverlay = (JComponent) readField(subject, "jumpToLatestOverlay");
            assertThat(jumpToLatestOverlay.isVisible()).isFalse();
            return null;
        });
    }

    @Test
    @DisplayName("Jump to latest stops animating when streaming ends away from bottom")
    void updateGenerationIndicator_whenStreamingEndsAwayFromBottom_stopsJumpAnimation() throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod("updateGenerationIndicator");
        method.setAccessible(true);
        callOnEdt(() -> {
            setField(subject, "atBottom", false);
            JumpToLatestButton jumpToLatestOverlay = (JumpToLatestButton) readField(
                    subject,
                    "jumpToLatestOverlay"
            );
            jumpToLatestOverlay.setVisible(true);
            jumpToLatestOverlay.setStreaming(true);
            method.invoke(subject);
            assertThat(jumpToLatestOverlay.isVisible()).isTrue();
            assertThat(jumpToLatestOverlay.isStreaming()).isFalse();
            return null;
        });
    }

    @Test
    @DisplayName("Render mode switch updates state and emits change callback")
    void setRenderMode_whenChanged_updatesStateAndNotifiesListener() throws Exception {
        var capturedMode = new AtomicReference<RenderMode>();
        callOnEdt(() -> {
            subject.setOnRenderModeChanged(capturedMode::set);
            subject.setRenderMode(RenderMode.MARKDOWN, true);
            assertThat(subject.getRenderMode()).isEqualTo(RenderMode.MARKDOWN);
            return null;
        });
        assertThat(capturedMode.get()).isEqualTo(RenderMode.MARKDOWN);
    }

    @Test
    @DisplayName("Render mode buttons use icons instead of visible text")
    void renderModeToggle_whenCreated_usesIconButtons() throws Exception {
        runOnEdt(() -> {
            JToggleButton previewToggle = (JToggleButton) readField(subject, "previewToggle");
            JToggleButton markdownToggle = (JToggleButton) readField(subject, "markdownToggle");

            assertThat(previewToggle.getText()).isEmpty();
            assertThat(markdownToggle.getText()).isEmpty();
            assertThat(previewToggle.getIcon()).isNotNull();
            assertThat(markdownToggle.getIcon()).isNotNull();
            assertThat(previewToggle.getAccessibleContext().getAccessibleName())
                    .isEqualTo(RenderMode.PREVIEW.displayName());
            assertThat(markdownToggle.getAccessibleContext().getAccessibleName())
                    .isEqualTo(RenderMode.MARKDOWN.displayName());
        });
    }

    @Test
    @DisplayName("Clear chat button is visible only when chat history has messages")
    void loadHistoryAndClearChat_whenHistoryChanges_updatesClearChatButtonVisibility() throws Exception {
        assertThat(callOnEdt(() -> subject.getInputBar().isClearChatVisible())).isFalse();

        runOnEdt(() -> subject.loadHistory(List.of(Message.user("hello"))));
        assertThat(callOnEdt(() -> subject.getInputBar().isClearChatVisible())).isTrue();

        runOnEdt(subject::clearChatView);
        assertThat(callOnEdt(() -> subject.getInputBar().isClearChatVisible())).isFalse();
    }

    @Test
    @DisplayName("Model selector interaction text follows whether conversation history has messages")
    void loadHistoryAndClearChat_whenHistoryChanges_updatesModelSelectionInteraction() throws Exception {
        assertThat(callOnEdt(subject::hasConversationMessages)).isFalse();
        assertThat(callOnEdt(() -> subject.getModelSelectorButton().getToolTipText()))
                .endsWith("Select a model.");

        runOnEdt(() -> subject.loadHistory(List.of(Message.user("hello"))));
        flushEdt();

        assertThat(callOnEdt(subject::hasConversationMessages)).isTrue();
        assertThat(callOnEdt(() -> subject.getModelSelectorButton().getToolTipText()))
                .endsWith("Start a new chat with another model.");

        runOnEdt(subject::clearChatView);
        flushEdt();

        assertThat(callOnEdt(subject::hasConversationMessages)).isFalse();
        assertThat(callOnEdt(() -> subject.getModelSelectorButton().getToolTipText()))
                .endsWith("Select a model.");
    }

    @Test
    @DisplayName("New-chat view clearing retains draft text and attachments")
    void clearChatView_whenComposerHasDraft_retainsComposerState() throws Exception {
        Path attachmentPath = Files.writeString(tempDir.resolve("draft.txt"), "draft attachment");
        var attachment = new ComposerAttachment(attachmentPath, "text/plain", Files.size(attachmentPath), false);
        var expectedComposer = new ComposerState("draft question", List.of(attachment), emptyList());

        runOnEdt(() -> {
            subject.getInputBar().setComposerState(expectedComposer);
            subject.loadHistory(List.of(Message.user("previous question")));
            subject.abandonVisibleUnsubmittedPreparation();
            subject.clearChatView();
        });
        flushEdt();

        assertThat(callOnEdt(() -> subject.getInputBar().getComposerState())).isEqualTo(expectedComposer);
    }

    @Test
    @DisplayName("Bubble context menu clear item follows clear chat visibility and requests clear")
    void bubbleContextMenu_whenClearChatAvailabilityChanges_updatesItemVisibilityAndAction() throws Exception {
        var requested = new AtomicInteger();
        JMenuItem clearChatItem = callOnEdt(() -> {
            subject.setOnClearChatRequested(requested::incrementAndGet);
            subject.loadHistory(List.of(Message.user("hello")));
            MessageBubble bubble = findComponents(
                    (JPanel) readField(subject, "messagesPanel"),
                    MessageBubble.class
            ).getFirst();
            JPopupMenu popup = contentPopupMenu(bubble);
            JMenuItem item = findMenuItem(popup, "Clear Chat");
            notifyPopupWillBecomeVisible(popup);
            assertThat(item.isVisible()).isTrue();
            return item;
        });

        runOnEdt(clearChatItem::doClick);
        assertThat(requested).hasValue(1);

        runOnEdt(() -> {
            subject.getInputBar().setEnabled(false);
            notifyPopupWillBecomeVisible((JPopupMenu) clearChatItem.getParent());
            assertThat(clearChatItem.isVisible()).isFalse();
            clearChatItem.doClick();
        });
        assertThat(requested).hasValue(1);
    }

    @Test
    @DisplayName("Bubble PDF export item follows conversation and export availability")
    void bubbleContextMenu_whenPdfExportAvailabilityChanges_updatesItemAndInvokesCallback() throws Exception {
        var requested = new AtomicInteger();
        runOnEdt(() -> {
            subject.setOnExportPdfRequested(requested::incrementAndGet);
            subject.setActiveConversationId(UUID.randomUUID());
            subject.loadHistory(List.of(Message.user("hello")));
        });
        flushEdt();

        MessageBubble bubble = callOnEdt(() ->
                findComponents((JPanel) readField(subject, "messagesPanel"), MessageBubble.class).getFirst()
        );
        JPopupMenu popup = callOnEdt(() -> contentPopupMenu(bubble));
        JMenuItem exportItem = callOnEdt(() -> findMenuItem(popup, "Export to PDF…"));

        runOnEdt(() -> notifyPopupWillBecomeVisible(popup));
        assertThat(callOnEdt(exportItem::isEnabled)).isTrue();

        runOnEdt(() -> {
            subject.setPdfExportRunning(true);
            notifyPopupWillBecomeVisible(popup);
        });
        assertThat(callOnEdt(exportItem::isEnabled)).isFalse();

        runOnEdt(() -> {
            subject.setPdfExportRunning(false);
            notifyPopupWillBecomeVisible(popup);
            exportItem.doClick();
        });
        assertThat(requested).hasValue(1);
    }

    @Test
    @DisplayName("PDF export remains disabled until the visible assistant response is durably saved")
    void canExportPdf_whenAssistantPersistenceIsPending_waitsForPersistence() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));
        Method queue = ChatPanel.class.getDeclaredMethod(
                "queuePendingAssistantRecovery",
                UUID.class,
                ConversationHistoryEntry.class
        );
        Method remove = ChatPanel.class.getDeclaredMethod(
                "removePendingAssistantRecovery",
                UUID.class,
                UUID.class
        );
        queue.setAccessible(true);
        remove.setAccessible(true);
        runOnEdt(() -> subject.setActiveConversationId(conversationId));

        runOnEdt(() -> queue.invoke(subject, conversationId, entry));
        assertThat(callOnEdt(subject::canExportPdf)).isFalse();

        runOnEdt(() -> remove.invoke(subject, conversationId, entry.messageId()));
        assertThat(callOnEdt(subject::canExportPdf)).isTrue();
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
        runOnEdt(() -> subject.loadHistory(List.of(
                Message.user("hello"),
                Message.assistant("hi")
        )));
        flushEdt();

        callOnEdt(() -> {
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            MessageBubble userBubble = findComponents(messagesPanel, MessageBubble.class).stream()
                    .filter(bubble -> bubble.getRole() == Role.USER)
                    .findFirst()
                    .orElseThrow();
            Container hoverGroup = userBubble.getParent();
            List<JButton> buttons = findComponents(hoverGroup, JButton.class);

            Dimension before = hoverGroup.getPreferredSize();
            buttons.forEach(button -> button.setVisible(true));
            Dimension after = hoverGroup.getPreferredSize();

            assertThat(buttons).hasSize(3);
            assertThat(after.height).isEqualTo(before.height);
            return null;
        });
    }

    @Test
    @DisplayName("Read aloud action passes canonical Markdown to Text to Speech")
    void readAloudButton_whenClicked_invokesTextToSpeechServiceWithCanonicalMarkdown() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        String markdown = """
                Intro.
                ```java
                CODE_SENTINEL
                ```
                Done.
                """.strip();
        runOnEdt(() -> subject.loadHistory(List.of(Message.assistant(markdown))));
        awaitReadAloudAvailability();

        assertThat(readAloudMessageIndexes()).containsExactly(0);
        JButton readAloudButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Read aloud".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow());

        runOnEdt(readAloudButton::doClick);
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isEqualTo(markdown);
        assertThat(callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .map(JButton::getToolTipText)
                .toList())).contains("Stop");
    }

    @Test
    @DisplayName("Messages containing only excluded content have no Read aloud action")
    void loadHistory_whenAssistantContentIsOnlyExcluded_omitsReadAloudButton() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        runOnEdt(() -> subject.loadHistory(List.of(Message.assistant("```smiles\nCCO\n```\n$x = y$"))));
        awaitReadAloudAvailability();

        assertThat(readAloudMessageIndexes()).isEmpty();
        List<String> tooltips = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .map(JButton::getToolTipText)
                .toList());

        assertThat(tooltips).doesNotContain("Read aloud", "Stop");
    }

    @Test
    @DisplayName("Excluded-only assistant context menus hide Read aloud and its separator")
    void contextMenu_whenAssistantContentIsOnlyExcluded_hidesReadAloudSection() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        runOnEdt(() -> subject.loadHistory(List.of(Message.assistant("```smiles\nCCO\n```"))));
        awaitReadAloudAvailability();

        callOnEdt(() -> {
            JPopupMenu popup = findComponents(subject, JComponent.class).stream()
                    .map(JComponent::getComponentPopupMenu)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElseThrow();
            PopupMenuEvent event = new PopupMenuEvent(popup);
            Arrays.stream(popup.getPopupMenuListeners()).forEach(listener -> listener.popupMenuWillBecomeVisible(event));
            JMenuItem readAloudItem = Arrays.stream(popup.getComponents())
                    .filter(JMenuItem.class::isInstance)
                    .map(JMenuItem.class::cast)
                    .filter(item -> "Read aloud".equals(item.getText()))
                    .findFirst()
                    .orElseThrow();
            int itemIndex = popup.getComponentIndex(readAloudItem);

            assertThat(readAloudItem.isVisible()).isFalse();
            assertThat(popup.getComponent(itemIndex - 1)).isInstanceOf(JPopupMenu.Separator.class);
            assertThat(popup.getComponent(itemIndex - 1).isVisible()).isFalse();
            return null;
        });
    }

    @Test
    @DisplayName("Panel removal preserves application speech services until explicit disposal")
    void removeNotify_whenPanelIsRemoved_defersSpeechDisposalToOwner() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);

        runOnEdt(subject::removeNotify);
        assertThat(textToSpeechService.stopCount()).isPositive();
        assertThat(textToSpeechService.disposed()).isFalse();

        textToSpeechService.dispose();
        assertThat(textToSpeechService.disposed()).isTrue();
    }

    @Test
    @DisplayName("Panel removal preserves browser resources until permanent disposal")
    void removeNotify_whenSystemWebViewExists_defersBrowserDisposalToOwner() throws Exception {
        SystemWebView systemWebView = mock(SystemWebView.class);
        runOnEdt(() -> setField(subject, "systemWebView", systemWebView));

        runOnEdt(subject::removeNotify);
        verify(systemWebView, never()).dispose();

        runOnEdt(subject::disposeViewResources);
        verify(systemWebView).dispose();
    }

    @Test
    @DisplayName("Permanent panel disposal releases loaded activity renderers")
    void disposeViewResources_whenActivityBubbleIsLoaded_disposesItsRenderer() throws Exception {
        Message assistant = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("answer")),
                Instant.now(),
                new MessageMeta(emptyList(), emptyList(), false, "", "reasoning")
        );
        runOnEdt(() -> subject.loadHistory(List.of(Message.user("question"), assistant)));
        ActivityBubble bubble = callOnEdt(() -> findComponents(subject, ActivityBubble.class).getFirst());
        MessageBubble assistantBubble = callOnEdt(() -> findComponents(subject, MessageBubble.class).stream()
                .filter(messageBubble -> "answer".equals(messageBubble.getFullText()))
                .findFirst()
                .orElseThrow());

        runOnEdt(subject::disposeViewResources);

        assertThat(callOnEdt(bubble::isDisposed)).isTrue();
        assertThat(callOnEdt(assistantBubble::isDisposed)).isTrue();
    }

    @Test
    @DisplayName("Speech callback from an earlier attachment cannot update a reattached panel")
    void speechToTextCallbacks_whenPanelIsReattached_ignoresOldAttachmentGeneration() throws Exception {
        JTextArea textArea = readInputTextArea(subject.getInputBar());
        runOnEdt(() -> textArea.setText("current draft"));
        @SuppressWarnings("unchecked")
        AtomicLong generation = (AtomicLong) readField(subject, "speechToTextUiGeneration");
        SpeechToTextService.Callbacks staleCallbacks = invokeSpeechToTextCallbacks(subject, generation.get());

        runOnEdt(subject::removeNotify);
        runOnEdt(() -> setField(subject, "removed", false));
        runOnEdt(() -> staleCallbacks.transcript("stale transcript"));

        assertThat(callOnEdt(() -> textArea.getText())).isEqualTo("current draft");
    }

    @Test
    @DisplayName("Open-action errors from an earlier attachment stay stale after reattachment")
    void isOpenActionUiCurrent_whenPanelIsReattached_rejectsEarlierGeneration() throws Exception {
        UUID conversationId = UUID.randomUUID();
        runOnEdt(() -> subject.setActiveConversationId(conversationId));
        long historyRevision = (long) readField(subject, "historyRevision");
        long openActionGeneration = ((AtomicLong) readField(subject, "openActionUiGeneration")).get();

        runOnEdt(subject::removeNotify);
        runOnEdt(() -> setField(subject, "removed", false));

        assertThat(callOnEdt(() -> subject.isOpenActionUiCurrent(
                historyRevision,
                conversationId,
                openActionGeneration
        ))).isFalse();
    }

    @Test
    @DisplayName("WebView pointer presses dismiss the model selector popup")
    void handleWebTranscriptAction_whenWebViewPointerPressed_hidesModelPopup() throws Exception {
        ModelSelectorPopup popup = mock(ModelSelectorPopup.class);
        runOnEdt(() -> setField(subject, "modelPopup", popup));
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "webview-pointer-down", -1, ""));
        flushEdt();

        verify(popup).hidePopup();
    }

    @Test
    @DisplayName("WebView content clicks return typing focus to the composer")
    void handleWebTranscriptAction_whenWebViewContentClicked_requestsInputFocus() throws Exception {
        var focusHandoffs = new AtomicInteger();
        runOnEdt(() -> subject.getInputBar().setNativeFocusRelease(focusHandoffs::incrementAndGet));
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "webview-content-click", -1, ""));
        flushEdt();

        assertThat(focusHandoffs).hasValue(1);
    }

    @Test
    @DisplayName("Browser transcript PDF action invokes the whole-conversation export callback")
    void handleWebTranscriptAction_whenPdfExportRequested_invokesExportCallback() throws Exception {
        var requested = new AtomicInteger();
        runOnEdt(() -> {
            subject.setActiveConversationId(UUID.randomUUID());
            subject.setOnExportPdfRequested(requested::incrementAndGet);
        });
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "export-pdf", 0, ""));
        flushEdt();

        assertThat(requested).hasValue(1);
    }

    @Test
    @DisplayName("Browser transcript edit action loads the original user message into the edit composer")
    void handleWebTranscriptAction_whenEditTargetsUser_startsExistingEditWorkflow() throws Exception {
        UUID conversationId = UUID.randomUUID();
        runOnEdt(() -> subject.loadConversationHistoryEntries(conversationId, List.of(
                new ConversationRepository.MessageRecord(UUID.randomUUID(), 1, Message.user("original question")),
                new ConversationRepository.MessageRecord(UUID.randomUUID(), 2, Message.assistant("original answer"))
        )));
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "edit", 0, ""));
        flushEdt();

        assertThat(callOnEdt(subject::isEditingUserMessage)).isTrue();
        assertThat(callOnEdt(() -> readInputTextArea(subject.getInputBar()).getText())).isEqualTo("original question");
        assertThat(callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .map(JButton::getText)
                .filter(StringUtils::isNotBlank)))
                .contains("Save only", "Save & regenerate");
    }

    @Test
    @DisplayName("Browser transcript edit action ignores assistant messages")
    void handleWebTranscriptAction_whenEditTargetsAssistant_doesNotStartEditing() throws Exception {
        runOnEdt(() -> subject.loadHistory(List.of(Message.assistant("answer"))));
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "edit", 0, ""));
        flushEdt();

        assertThat(callOnEdt(subject::isEditingUserMessage)).isFalse();
        assertThat(callOnEdt(() -> readInputTextArea(subject.getInputBar()).getText())).isEmpty();
    }

    @Test
    @DisplayName("Read aloud web transcript action uses message indexes without duplicate action bars")
    void handleWebTranscriptAction_whenReadAloudAfterUserMessage_invokesTextToSpeechService() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        runOnEdt(() -> subject.loadHistory(List.of(
                Message.user("question"),
                Message.assistant("assistant answer")
        )));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "read-aloud", 1, create(1, "assistant answer")));
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isEqualTo("assistant answer");
    }

    @Test
    @DisplayName("Read aloud web transcript action uses stored message text for valid indexes")
    void handleWebTranscriptAction_whenReadAloudIndexIsValid_usesStoredMessageText() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        runOnEdt(() -> subject.loadHistory(List.of(Message.assistant("stored assistant answer"))));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "read-aloud", 0, create(0, "stored assistant answer")));
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isEqualTo("stored assistant answer");
    }

    @Test
    @DisplayName("Read aloud rejects a callback from a transcript replaced at the same index")
    void handleWebTranscriptAction_whenTranscriptChangesBeforeDispatch_rejectsStaleMessageToken() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        runOnEdt(() -> subject.loadHistory(List.of(Message.assistant("old assistant answer"))));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> {
            method.invoke(subject, "read-aloud", 0, create(0, "old assistant answer"));
            subject.loadHistory(List.of(Message.assistant("new assistant answer")));
        });
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isBlank();
        assertThat(textToSpeechService.requestedKey()).isBlank();
    }

    @Test
    @DisplayName("Read aloud rejects browser text without a canonical message index")
    void handleWebTranscriptAction_whenReadAloudIndexUnavailable_ignoresTextPayload() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "read-aloud", -1, "MERMAID_RENDERED_TEXT_SENTINEL"));
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isBlank();
        assertThat(textToSpeechService.requestedKey()).isBlank();
    }

    @Test
    @DisplayName("Read aloud rejects stale callback indexes")
    void handleWebTranscriptAction_whenIndexIsOutsideTranscript_ignoresCallback() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        runOnEdt(() -> subject.loadHistory(List.of(Message.assistant("assistant answer"))));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "read-aloud", 99, "browser text"));
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isBlank();
        assertThat(textToSpeechService.requestedKey()).isBlank();
    }

    @Test
    @DisplayName("Read aloud ignores callbacks resolved to user messages")
    void handleWebTranscriptAction_whenIndexResolvesToUser_doesNotSelectEarlierAssistant() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        runOnEdt(() -> subject.loadHistory(List.of(
                Message.assistant("earlier assistant answer"),
                Message.user("user question")
        )));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "read-aloud", 1, "browser text"));
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isBlank();
        assertThat(textToSpeechService.requestedKey()).isBlank();
    }

    @Test
    @DisplayName("Read aloud rejects pre-normalization indexes for merged activity-only messages")
    void handleWebTranscriptAction_whenActivityOnlyAssistantIsMerged_rejectsStaleIndex() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        Message activityOnlyAssistant = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("")),
                Instant.now(),
                new MessageMeta(emptyList(), emptyList(), false, "", "thinking", "searching")
        );
        runOnEdt(() -> subject.loadHistory(List.of(
                Message.user("question"),
                activityOnlyAssistant,
                Message.assistant("assistant answer")
        )));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "read-aloud", 2, "assistant answer"));
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isBlank();
        assertThat(textToSpeechService.requestedKey()).isBlank();
    }

    @Test
    @DisplayName("Read aloud web transcript action resolves visible indexes shifted by skipped activity messages")
    void handleWebTranscriptAction_whenVisibleIndexIsShiftedByActivity_resolvesAssistantBubbleIndex() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        Message activityOnlyAssistant = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("")),
                Instant.now(),
                new MessageMeta(emptyList(), emptyList(), false, "", "thinking", "searching")
        );
        runOnEdt(() -> subject.loadHistory(List.of(
                Message.user("question"),
                activityOnlyAssistant,
                Message.assistant("assistant answer")
        )));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "read-aloud", 1, create(1, "assistant answer")));
        flushEdt();

        assertThat(textToSpeechService.requestedText()).isEqualTo("assistant answer");
        assertThat(textToSpeechService.requestedKey()).isEqualTo("web:1");
    }

    @Test
    @DisplayName("Web transcript actions use visible message indexes when web search activity is present")
    void handleWebTranscriptAction_whenWebSearchActivityPresent_usesVisibleMessageIndex() throws Exception {
        var textToSpeechService = new RecordingTextToSpeechService(tempDir.resolve("tts-settings.properties"));
        subject = chatPanelWithTextToSpeech(textToSpeechService);
        Message assistantMessage = new Message(
                Role.ASSISTANT,
                List.of(new TextPart("assistant answer")),
                Instant.now(),
                new MessageMeta(emptyList(), emptyList(), false, "", "", "**Searched**\n- java copy message")
        );
        runOnEdt(() -> subject.loadHistory(List.of(Message.user("question"), assistantMessage)));
        flushEdt();
        Method method = ChatPanel.class.getDeclaredMethod("handleWebTranscriptAction", String.class, int.class, String.class);
        method.setAccessible(true);

        runOnEdt(() -> method.invoke(subject, "read-aloud", 1, create(1, "assistant answer")));
        flushEdt();

        assertThat(callOnEdt(() -> {
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            return findComponents(messagesPanel, ActivityBubble.class);
        })).hasSize(1);
        assertThat(textToSpeechService.requestedText()).isEqualTo("assistant answer");
        assertThat(textToSpeechService.requestedKey()).isEqualTo("web:1");
    }

    @Test
    @DisplayName("Contentless successful sends do not retain an empty assistant response")
    void onSend_whenProviderCompletesWithoutContent_removesAssistantPlaceholder() throws Exception {
        setCurrentProvider(subject, immediateProvider(""));
        SwingUtilities.invokeAndWait(() -> subject.getInputBar().setText("question"));

        Method sendMethod = ChatPanel.class.getDeclaredMethod("onSend");
        sendMethod.setAccessible(true);
        runOnEdt(() -> sendMethod.invoke(subject));
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()));
        flushEdt();

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content).containsExactly("question");
        assertThat(callOnEdt(() -> (List<?>) readField(subject, "assistantBubbles"))).isEmpty();
    }

    @Test
    @DisplayName("Regenerating recent assistant response uses stored message indexes")
    void regenerateRecentResponse_whenRecentBubbleIsAssistant_usesStoredMessageIndex() throws Exception {
        runOnEdt(() -> loadPersistedHistory(Message.user("question"), Message.assistant("old answer")));
        setCurrentProvider(subject, immediateProvider("new answer"));
        flushEdt();

        assertThat(callOnEdt(subject::canRegenerateRecentResponse)).isTrue();

        runOnEdt(subject::regenerateRecentResponse);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> {
            List<Message> history = subject.getHistory();
            return history.size() == 2 && "new answer".equals(history.getLast().content());
        }));

        assertThat(callOnEdt(subject::getHistory))
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
        runOnEdt(() -> loadPersistedHistory(
                Message.user("first question"),
                activityOnlyAssistant,
                Message.user("second question")
        ));
        setCurrentProvider(subject, immediateProvider("second answer"));
        flushEdt();

        assertThat(callOnEdt(subject::canRegenerateRecentResponse)).isTrue();

        runOnEdt(subject::regenerateRecentResponse);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> {
            List<Message> history = subject.getHistory();
            return history.size() == 4 && "second answer".equals(history.getLast().content());
        }));

        assertThat(callOnEdt(subject::getHistory))
                .extracting(Message::content)
                .containsExactly("first question", "", "second question", "second answer");
    }

    @Test
    @DisplayName("Contentless successful regeneration does not retain an empty assistant response")
    void regenerateRecentResponse_whenProviderCompletesWithoutContent_removesAssistantPlaceholder() throws Exception {
        runOnEdt(() -> loadPersistedHistory(Message.user("question"), Message.assistant("old answer")));
        setCurrentProvider(subject, immediateProvider(""));
        flushEdt();

        runOnEdt(subject::regenerateRecentResponse);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()));
        flushEdt();

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content).containsExactly("question");
        assertThat(callOnEdt(() -> (List<?>) readField(subject, "assistantBubbles"))).isEmpty();
    }

    @Test
    @DisplayName("Contentless successful edited regeneration does not retain an empty assistant response")
    void saveEditedUserMessageAndRegenerate_whenProviderCompletesWithoutContent_removesAssistantPlaceholder() throws Exception {
        runOnEdt(() -> loadPersistedHistory(Message.user("old question"), Message.assistant("old answer")));
        setCurrentProvider(subject, immediateProvider(""));
        flushEdt();
        JButton editButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Edit message".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow());
        runOnEdt(editButton::doClick);
        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("updated question"));
        JButton regenerateButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Save & regenerate".equals(button.getText()))
                .findFirst()
                .orElseThrow());

        runOnEdt(regenerateButton::doClick);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()));
        flushEdt();

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content).containsExactly("updated question");
        assertThat(callOnEdt(() -> (List<?>) readField(subject, "assistantBubbles"))).isEmpty();
    }

    @Test
    @DisplayName("Regeneration admission failure preserves the existing response")
    void regenerateRecentResponse_whenProviderAdmissionFails_keepsHistoryAndSkipsTruncation() throws Exception {
        runOnEdt(() -> {
            subject.loadHistory(List.of(Message.user("question"), Message.assistant("old answer")));
        });
        installFailingProvider(subject);
        flushEdt();

        runOnEdt(subject::regenerateRecentResponse);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()));
        flushEdt();

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("question", "old answer");
    }

    @Test
    @DisplayName("Edited regeneration admission failure preserves the message and edit state")
    void saveEditedUserMessageAndRegenerate_whenProviderAdmissionFails_keepsHistoryAndDraft() throws Exception {
        runOnEdt(() -> {
            subject.loadHistory(List.of(Message.user("old question"), Message.assistant("old answer")));
        });
        installFailingProvider(subject);
        flushEdt();
        JButton editButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Edit message".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow());
        runOnEdt(editButton::doClick);
        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("updated question"));
        JButton regenerateButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Save & regenerate".equals(button.getText()))
                .findFirst()
                .orElseThrow());

        runOnEdt(regenerateButton::doClick);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()));
        flushEdt();

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("old question", "old answer");
        assertThat(callOnEdt(() -> textArea.getText())).isEqualTo("updated question");
    }

    @Test
    @DisplayName("Delete admission cancels the targeted response without creating another write")
    void cancelConversationsForDeletion_whenTargetIsStreaming_discardsPartialResponse() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var persistedEvent = new AtomicReference<ChatPanel.AssistantMessageEvent>();
        var session = new StreamingSession(91L, conversationId, null);
        session.response.append("partial answer");
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setOnDurableAssistantMessageCompleted(event -> {
                persistedEvent.set(event);
                return CompletableFuture.completedFuture(null);
            });
            @SuppressWarnings("unchecked")
            Map<Long, StreamingSession> activeSessions =
                    (Map<Long, StreamingSession>) readField(subject, "activeSessions");
            activeSessions.put(session.sessionId, session);
            subject.cancelConversationsForDeletion(List.of(conversationId));
        });

        assertThat(persistedEvent.get()).isNull();
        assertThat(callOnEdt(() -> {
            @SuppressWarnings("unchecked")
            Map<Long, StreamingSession> activeSessions =
                    (Map<Long, StreamingSession>) readField(subject, "activeSessions");
            return activeSessions.containsKey(session.sessionId);
        })).isFalse();
        assertThat(callOnEdt(() -> readConversationBusy(subject))).isTrue();

        runOnEdt(() -> subject.finishConversationDeletion(List.of(conversationId)));

        assertThat(callOnEdt(() -> readConversationBusy(subject))).isFalse();
    }

    @Test
    @DisplayName("Assistant persistence rejected after deletion does not recreate recovery state")
    void persistAssistantMessageEvent_whenDeletionWins_discardsRejectedRecovery() throws Exception {
        UUID conversationId = UUID.randomUUID();
        CompletableFuture<Void> persistence = controlledFuture();
        var submitted = new CountDownLatch(1);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("late answer"));
        runOnEdt(() -> subject.setOnDurableAssistantMessageCompleted(event -> {
            submitted.countDown();
            return persistence;
        }));
        Method persistMethod = ChatPanel.class.getDeclaredMethod(
                "persistAssistantMessageEvent",
                UUID.class,
                ConversationHistoryEntry.class
        );
        persistMethod.setAccessible(true);

        runOnEdt(() -> persistMethod.invoke(subject, conversationId, entry));
        assertThat(submitted.await(2, TimeUnit.SECONDS)).isTrue();
        runOnEdt(() -> subject.discardConversations(List.of(conversationId)));
        persistence.completeExceptionally(new IllegalStateException("deleted"));
        flushEdt();

        assertThat(callOnEdt(() -> {
            @SuppressWarnings("unchecked")
            Map<UUID, List<ConversationHistoryEntry>> recoveries =
                    (Map<UUID, List<ConversationHistoryEntry>>) readField(
                            subject,
                            "pendingCompletedAssistantRecoveries"
                    );
            return recoveries.containsKey(conversationId);
        })).isFalse();
    }

    @Test
    @DisplayName("Failed delete settlement re-enables the composer after preparation cancellation")
    void finishConversationDeletion_whenPreparationWasCancelled_releasesDeletionBusyState() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var admissionStarted = new CountDownLatch(1);
        var releaseAdmission = new CountDownLatch(1);
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.loadHistory(List.of(Message.user("question"), Message.assistant("answer")));
        });
        installBlockingProvider(subject, admissionStarted, releaseAdmission);

        try {
            runOnEdt(subject::regenerateRecentResponse);
            assertThat(admissionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> subject.cancelConversationsForDeletion(List.of(conversationId)));
            assertThat(readConversationBusy(subject)).isTrue();

            runOnEdt(() -> subject.finishConversationDeletion(List.of(conversationId)));

            assertThat(readConversationBusy(subject)).isFalse();
        } finally {
            releaseAdmission.countDown();
        }
    }

    @Test
    @DisplayName("Durable user completion during temporary removal still continues the request")
    void removeNotify_whenUserPersistenceCompletes_preservesCommittedContinuation() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var persistenceStarted = new CountDownLatch(1);
        CompletableFuture<UUID> persistence = controlledFuture();
        var providerCalls = new AtomicInteger();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceStarted.countDown();
                return persistence;
            });
            readInputTextArea(subject.getInputBar()).setText("persist while detached");
        });
        setCurrentProvider(subject, providerReturning("answer", providerCalls));

        invokeOnSend(subject);
        assertThat(persistenceStarted.await(2, TimeUnit.SECONDS)).isTrue();
        runOnEdt(subject::removeNotify);
        persistence.complete(conversationId);
        awaitCondition(2, TimeUnit.SECONDS, () -> providerCalls.get() == 1);
        flushEdt();

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("persist while detached", "answer");
    }

    @Test
    @DisplayName("Temporary panel removal does not cancel provider admission")
    void removeNotify_whenProviderAdmissionIsBlocked_preservesActiveRequest() throws Exception {
        var admissionStarted = new CountDownLatch(1);
        var releaseAdmission = new CountDownLatch(1);
        runOnEdt(() -> subject.loadHistory(List.of(Message.user("question"), Message.assistant("answer"))));
        installBlockingProvider(subject, admissionStarted, releaseAdmission);

        try {
            runOnEdt(subject::regenerateRecentResponse);
            assertThat(admissionStarted.await(2, TimeUnit.SECONDS)).isTrue();

            runOnEdt(subject::removeNotify);

            assertThat(callOnEdt(() ->
                    ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty())).isFalse();
        } finally {
            releaseAdmission.countDown();
            callOnEdt(subject::cancelAllRequestsAsync).join();
        }
    }

    @Test
    @DisplayName("Delayed regeneration admission cannot truncate a reloaded original conversation")
    void regenerateRecentResponse_whenConversationIsReloadedDuringAdmission_keepsReloadedHistory() throws Exception {
        UUID originalConversationId = UUID.randomUUID();
        UUID replacementConversationId = UUID.randomUUID();
        var admissionStarted = new CountDownLatch(1);
        var releaseAdmission = new CountDownLatch(1);
        runOnEdt(() -> {
            subject.setActiveConversationId(originalConversationId);
            subject.setConversationIdSupplier(() -> originalConversationId);
            subject.loadHistory(List.of(Message.user("original question"), Message.assistant("original answer")));
        });
        installBlockingProvider(subject, admissionStarted, releaseAdmission);
        flushEdt();

        try {
            runOnEdt(subject::regenerateRecentResponse);
            assertThat(admissionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> {
                subject.setActiveConversationId(replacementConversationId);
                subject.loadHistory(List.of(Message.user("replacement question")));
                subject.setActiveConversationId(originalConversationId);
                subject.loadHistory(List.of(Message.user("reloaded original question")));
            });
        } finally {
            releaseAdmission.countDown();
        }
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()));
        flushEdt();

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("reloaded original question");
    }

    @Test
    @DisplayName("Delayed edited regeneration admission cannot rewrite a reloaded original conversation")
    void saveEditedUserMessageAndRegenerate_whenConversationIsReloadedDuringAdmission_keepsReloadedHistory() throws Exception {
        UUID originalConversationId = UUID.randomUUID();
        UUID replacementConversationId = UUID.randomUUID();
        var admissionStarted = new CountDownLatch(1);
        var releaseAdmission = new CountDownLatch(1);
        runOnEdt(() -> {
            subject.setActiveConversationId(originalConversationId);
            subject.setConversationIdSupplier(() -> originalConversationId);
            subject.loadHistory(List.of(Message.user("old question"), Message.assistant("old answer")));
        });
        installBlockingProvider(subject, admissionStarted, releaseAdmission);
        flushEdt();
        JButton editButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Edit message".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow());
        runOnEdt(editButton::doClick);
        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("updated question"));
        JButton regenerateButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Save & regenerate".equals(button.getText()))
                .findFirst()
                .orElseThrow());

        try {
            runOnEdt(regenerateButton::doClick);
            assertThat(admissionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> {
                subject.setActiveConversationId(replacementConversationId);
                subject.loadHistory(List.of(Message.user("replacement question")));
                subject.setActiveConversationId(originalConversationId);
                subject.loadHistory(List.of(Message.user("reloaded original question")));
            });
        } finally {
            releaseAdmission.countDown();
        }
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()));
        flushEdt();

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("reloaded original question");
    }

    @Test
    @DisplayName("Edit user message save only updates history and preserves later assistant response")
    void editUserMessage_whenSaveOnly_updatesHistoryWithoutTruncating() throws Exception {
        var submitted = new AtomicInteger();
        runOnEdt(() -> {
            subject.setActiveConversationId(UUID.randomUUID());
            subject.setOnDurableHistoryMutation(event -> {
                submitted.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            subject.loadHistory(List.of(
                    Message.user("old question"),
                    Message.assistant("old answer")
            ));
        });
        flushEdt();

        JButton editButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Edit message".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow());
        runOnEdt(editButton::doClick);
        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        assertThat(callOnEdt(() -> textArea.getText())).isEqualTo("old question");

        runOnEdt(() -> textArea.setText("updated question"));
        JButton saveOnlyButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Save only".equals(button.getText()))
                .findFirst()
                .orElseThrow());
        runOnEdt(saveOnlyButton::doClick);
        flushEdt();

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("updated question", "old answer");
        assertThat(submitted).hasValue(1);
    }

    @Test
    @DisplayName("Retrying a failed durable user message reuses its stable identity")
    void onSend_whenDurableUserMessageRetrySucceeds_reusesMessageIdentity() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var persistenceCalls = new AtomicInteger();
        var firstPersistenceCalled = new CountDownLatch(1);
        var providerInvoked = new CountDownLatch(1);
        List<ChatPanel.UserMessageEvent> events = new ArrayList<>();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                events.add(event);
                if (persistenceCalls.incrementAndGet() == 1) {
                    firstPersistenceCalled.countDown();
                    return CompletableFuture.failedFuture(new SQLException("forced failure"));
                }
                return CompletableFuture.completedFuture(event.conversationId());
            });
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
                providerInvoked.countDown();
                onComplete.run();
            }
        });
        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("retry me"));

        invokeOnSend(subject);
        assertThat(firstPersistenceCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        UUID otherConversationId = UUID.randomUUID();
        runOnEdt(() -> subject.setActiveConversationId(otherConversationId));
        assertThat(callOnEdt(() -> textArea.getText())).isEmpty();
        runOnEdt(() -> subject.setActiveConversationId(conversationId));
        assertThat(callOnEdt(() -> textArea.getText())).isEqualTo("retry me");
        invokeOnSend(subject);
        assertThat(providerInvoked.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        assertThat(events).hasSize(2);
        assertThat(events.get(1).messageId()).isEqualTo(events.getFirst().messageId());
        assertThat(events.get(1).ordinal()).isEqualTo(events.getFirst().ordinal());
        assertThat(events.get(1).message().timestamp()).isEqualTo(events.getFirst().message().timestamp());
        assertThat(events.get(1).conversationId()).isEqualTo(events.getFirst().conversationId());
    }

    @Test
    @DisplayName("Reopening a hidden failed send marks its restored draft as delivered")
    void setActiveConversationId_whenHiddenDurableFailureIsRestored_marksFailureDelivered() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID otherConversationId = UUID.randomUUID();
        var persistenceStarted = new CountDownLatch(1);
        CompletableFuture<UUID> persistence = controlledFuture();
        var delivered = new AtomicInteger();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceStarted.countDown();
                return persistence;
            });
            subject.setOnDurableUserMessageFailureDelivered((ignoredConversation, ignoredMessage) ->
                    delivered.incrementAndGet()
            );
            readInputTextArea(subject.getInputBar()).setText("park me");
        });
        setCurrentProvider(subject, immediateProvider("unused"));

        invokeOnSend(subject);
        assertThat(persistenceStarted.await(2, TimeUnit.SECONDS)).isTrue();
        runOnEdt(() -> subject.setActiveConversationId(otherConversationId));
        persistence.completeExceptionally(new SQLException("forced failure"));
        flushEdt();
        assertThat(delivered).hasValue(0);

        runOnEdt(() -> subject.setActiveConversationId(conversationId));

        assertThat(callOnEdt(() -> readInputTextArea(subject.getInputBar()).getText())).isEqualTo("park me");
        assertThat(delivered).hasValue(1);
    }

    @Test
    @DisplayName("A draft from another conversation cannot silently replace a hidden failed send")
    void onSend_whenHiddenFailureConflictsWithCurrentDraft_preservesBothIntents() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID otherConversationId = UUID.randomUUID();
        var persistenceCalls = new AtomicInteger();
        var firstPersistenceCalled = new CountDownLatch(1);
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceCalls.incrementAndGet();
                firstPersistenceCalled.countDown();
                return CompletableFuture.failedFuture(new SQLException("forced failure"));
            });
            readInputTextArea(subject.getInputBar()).setText("failed message");
        });
        setCurrentProvider(subject, immediateProvider("unused"));

        invokeOnSend(subject);
        assertThat(firstPersistenceCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        runOnEdt(() -> {
            subject.setActiveConversationId(otherConversationId);
            readInputTextArea(subject.getInputBar()).setText("other draft");
            subject.setActiveConversationId(conversationId);
        });

        invokeOnSend(subject);
        flushEdt();

        assertThat(persistenceCalls).hasValue(1);
        assertThat(callOnEdt(() -> readInputTextArea(subject.getInputBar()).getText())).isEqualTo("other draft");
        assertThat(callOnEdt(() -> ((Map<?, ?>) readField(subject, "failedUserSends")))).hasSize(1);
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("unsaved message");
    }

    @Test
    @DisplayName("Attaching the initial blank chat does not query recovery with a null identity")
    void addNotify_whenConversationIsNotAllocated_doesNotFail() throws Exception {
        runOnEdt(() -> subject.setActiveConversationId(null));

        runOnEdt(subject::addNotify);

        assertThat(callOnEdt(() -> readField(subject, "activeConversationId"))).isNull();
    }

    @Test
    @DisplayName("Reattaching after a durable failure marks the visible retry draft as delivered")
    void addNotify_whenDurableFailureArrivedWhileRemoved_marksFailureDelivered() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var persistenceStarted = new CountDownLatch(1);
        CompletableFuture<UUID> persistence = controlledFuture();
        var delivered = new AtomicInteger();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceStarted.countDown();
                return persistence;
            });
            subject.setOnDurableUserMessageFailureDelivered((ignoredConversation, ignoredMessage) ->
                    delivered.incrementAndGet()
            );
            readInputTextArea(subject.getInputBar()).setText("retry after reattach");
        });
        setCurrentProvider(subject, immediateProvider("unused"));

        invokeOnSend(subject);
        assertThat(persistenceStarted.await(2, TimeUnit.SECONDS)).isTrue();
        runOnEdt(subject::removeNotify);
        persistence.completeExceptionally(new SQLException("forced failure"));
        flushEdt();
        assertThat(delivered).hasValue(0);

        runOnEdt(subject::addNotify);

        assertThat(delivered).hasValue(1);
        assertThat(callOnEdt(() -> readInputTextArea(subject.getInputBar()).getText()))
                .isEqualTo("retry after reattach");
    }

    @Test
    @DisplayName("Discarding a failed provisional send acknowledges its recovery intent")
    void discardFailedProvisionalUserSend_whenDraftFailed_marksFailureDelivered() throws Exception {
        var persistenceCalled = new CountDownLatch(1);
        var delivered = new AtomicInteger();
        runOnEdt(() -> {
            subject.setConversationIdSupplier(() -> null);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceCalled.countDown();
                return CompletableFuture.failedFuture(new SQLException("forced failure"));
            });
            subject.setOnDurableUserMessageFailureDelivered((ignoredConversation, ignoredMessage) ->
                    delivered.incrementAndGet()
            );
            readInputTextArea(subject.getInputBar()).setText("discard me");
        });
        setCurrentProvider(subject, immediateProvider("unused"));

        invokeOnSend(subject);
        assertThat(persistenceCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        assertThat(callOnEdt(subject::hasFailedProvisionalUserSend)).isTrue();

        runOnEdt(subject::discardFailedProvisionalUserSend);

        assertThat(callOnEdt(subject::hasFailedProvisionalUserSend)).isFalse();
        assertThat(delivered).hasValue(1);
    }

    @Test
    @DisplayName("Shutdown discards staged attachments for a delivered failed send")
    void beginShutdown_whenFailedSendWasDelivered_discardsStagedAttachments() throws Exception {
        UUID conversationId = UUID.randomUUID();
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("shutdown-failed-send"));
        var attachmentStager = new AttachmentStager(storagePaths);
        Path source = tempDir.resolve("draft.txt");
        Files.writeString(source, "draft");
        AttachmentRef attachment = attachmentStager.stage(new ComposerAttachment(source, "text/plain", 5L, false));
        Path stagedPath = Path.of(attachment.storagePath());
        setField(subject, "attachmentStager", attachmentStager);
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setSendPreparerForTests((composerState, providerSnapshot, isCancelled) ->
                    new Message(
                            Role.USER,
                            List.of(new TextPart(composerState.text()), new FilePart(attachment)),
                            Instant.now()
                    )
            );
            subject.setOnDurableUserMessageSubmitted(event ->
                    CompletableFuture.failedFuture(new SQLException("forced failure"))
            );
            subject.setOnDurableUserMessageFailureDelivered((ignoredConversation, ignoredMessage) -> {});
            readInputTextArea(subject.getInputBar()).setText("failed attachment");
        });
        setCurrentProvider(subject, immediateProvider("unused"));

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> {
            Map<?, ?> failures = (Map<?, ?>) readField(subject, "failedUserSends");
            if (failures.size() != 1) {
                return false;
            }
            Object failure = failures.values().iterator().next();
            Field acknowledged = failure.getClass().getDeclaredField("recoveryAcknowledged");
            acknowledged.setAccessible(true);
            return acknowledged.getBoolean(failure);
        }));
        assertThat(callOnEdt(() -> readField(subject, "attachmentStager"))).isSameAs(attachmentStager);
        runOnEdt(subject::beginShutdown);

        awaitCondition(2, TimeUnit.SECONDS, () -> !Files.exists(stagedPath));
        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "failedUserSends"))).isEmpty();
    }

    @Test
    @DisplayName("A definite persistence failure after shutdown discards staged attachments")
    void beginShutdown_whenPendingUserPersistenceFails_discardsStagedAttachments() throws Exception {
        UUID conversationId = UUID.randomUUID();
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("shutdown-pending-failure"));
        var attachmentStager = new AttachmentStager(storagePaths);
        Path source = tempDir.resolve("pending-draft.txt");
        Files.writeString(source, "draft");
        AttachmentRef attachment = attachmentStager.stage(new ComposerAttachment(source, "text/plain", 5L, false));
        Path stagedPath = Path.of(attachment.storagePath());
        var persistenceStarted = new CountDownLatch(1);
        CompletableFuture<UUID> persistence = controlledFuture();
        setField(subject, "attachmentStager", attachmentStager);
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setSendPreparerForTests((composerState, providerSnapshot, isCancelled) ->
                    new Message(
                            Role.USER,
                            List.of(new TextPart(composerState.text()), new FilePart(attachment)),
                            Instant.now()
                    )
            );
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceStarted.countDown();
                return persistence;
            });
            readInputTextArea(subject.getInputBar()).setText("pending attachment");
        });
        setCurrentProvider(subject, immediateProvider("unused"));

        invokeOnSend(subject);
        assertThat(persistenceStarted.await(2, TimeUnit.SECONDS)).isTrue();
        runOnEdt(subject::beginShutdown);
        CompletableFuture<Void> cleanup = callOnEdt(subject::cancelAllRequestsAsync);
        assertThat(cleanup).isNotDone();

        persistence.completeExceptionally(new SQLException("forced failure"));
        cleanup.get(2, TimeUnit.SECONDS);
        flushEdt();

        assertThat(stagedPath).doesNotExist();
        assertThat(callOnEdt(() -> (Map<?, ?>) readField(subject, "activeSendJobs"))).isEmpty();
    }

    @Test
    @DisplayName("Shutdown discards generated attachments from an unpersisted stream")
    void beginShutdown_whenStreamHasGeneratedAttachment_discardsFile() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("shutdown-generated-stream"));
        var attachmentStager = new AttachmentStager(storagePaths);
        Path source = tempDir.resolve("generated.png");
        Files.writeString(source, "generated");
        AttachmentRef attachment = attachmentStager.stage(new ComposerAttachment(source, "image/png", 9L, true));
        Path stagedPath = Path.of(attachment.storagePath());
        var session = new StreamingSession(93L, UUID.randomUUID(), immediateProvider("unused"));
        session.responseParts.add(new GeneratedImagePart(attachment, null, null, "generated"));
        setField(subject, "attachmentStager", attachmentStager);
        runOnEdt(() -> {
            @SuppressWarnings("unchecked")
            Map<Long, StreamingSession> sessions =
                    (Map<Long, StreamingSession>) readField(subject, "activeSessions");
            sessions.put(session.sessionId, session);
            subject.beginShutdown();
        });

        awaitCondition(2, TimeUnit.SECONDS, () -> !Files.exists(stagedPath));
        assertThat(session.responseParts).isEmpty();
    }

    @Test
    @DisplayName("Permanent cancellation waits for generated attachment discard")
    void cancelAllRequestsAsync_whenGeneratedDiscardIsRunning_waitsForDeletion() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("tracked-generated-discard"));
        var delegate = new AttachmentStager(storagePaths);
        Path source = tempDir.resolve("tracked-generated.png");
        Files.writeString(source, "generated");
        AttachmentRef attachment = delegate.stage(new ComposerAttachment(source, "image/png", 9L, true));
        Path stagedPath = Path.of(attachment.storagePath());
        var discardStarted = new CountDownLatch(1);
        var releaseDiscard = new CountDownLatch(1);
        var blockingStager = new AttachmentStager(storagePaths) {
            @Override
            public void discard(AttachmentRef attachmentRef) {
                discardStarted.countDown();
                try {
                    releaseDiscard.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                delegate.discard(attachmentRef);
            }
        };
        var session = new StreamingSession(96L, UUID.randomUUID(), immediateProvider("unused"));
        session.responseParts.add(new GeneratedImagePart(attachment, null, null, "generated"));
        setField(subject, "attachmentStager", blockingStager);
        runOnEdt(() -> {
            @SuppressWarnings("unchecked")
            Map<Long, StreamingSession> sessions =
                    (Map<Long, StreamingSession>) readField(subject, "activeSessions");
            sessions.put(session.sessionId, session);
            subject.beginShutdown();
        });

        assertThat(discardStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> cancellation = callOnEdt(subject::cancelAllRequestsAsync);
        assertThat(cancellation).isNotDone();
        releaseDiscard.countDown();
        cancellation.join();

        assertThat(stagedPath).doesNotExist();
    }

    @Test
    @DisplayName("Shutdown retains generated attachments after assistant persistence takes ownership")
    void beginShutdown_whenAssistantPersistenceWasSubmitted_retainsGeneratedFile() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("persisted-generated-stream"));
        var attachmentStager = new AttachmentStager(storagePaths);
        Path source = tempDir.resolve("persisted-generated.png");
        Files.writeString(source, "generated");
        AttachmentRef attachment = attachmentStager.stage(new ComposerAttachment(source, "image/png", 9L, true));
        Path stagedPath = Path.of(attachment.storagePath());
        var session = new StreamingSession(95L, UUID.randomUUID(), immediateProvider("unused"));
        session.responseParts.add(new GeneratedImagePart(attachment, null, null, "generated"));
        session.persisted.set(true);
        setField(subject, "attachmentStager", attachmentStager);
        runOnEdt(() -> {
            @SuppressWarnings("unchecked")
            Map<Long, StreamingSession> sessions =
                    (Map<Long, StreamingSession>) readField(subject, "activeSessions");
            sessions.put(session.sessionId, session);
            subject.beginShutdown();
        });

        assertThat(stagedPath).exists();
        assertThat(session.responseParts).hasSize(1);
    }

    @Test
    @DisplayName("A generated attachment arriving after shutdown is discarded")
    void handleAssistantPart_whenShutdownWinsBeforeCallback_discardsGeneratedFile() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDir.resolve("late-generated-stream"));
        var attachmentStager = new AttachmentStager(storagePaths);
        Path source = tempDir.resolve("late-generated.png");
        Files.writeString(source, "generated");
        AttachmentRef attachment = attachmentStager.stage(new ComposerAttachment(source, "image/png", 9L, true));
        Path stagedPath = Path.of(attachment.storagePath());
        var session = new StreamingSession(94L, UUID.randomUUID(), immediateProvider("unused"));
        setField(subject, "attachmentStager", attachmentStager);
        runOnEdt(() -> {
            @SuppressWarnings("unchecked")
            Map<Long, StreamingSession> sessions =
                    (Map<Long, StreamingSession>) readField(subject, "activeSessions");
            sessions.put(session.sessionId, session);
            subject.beginShutdown();
        });
        Method callback = ChatPanel.class.getDeclaredMethod(
                "handleAssistantPart",
                StreamingSession.class,
                com.github.drafael.chat4j.provider.api.content.ContentPart.class
        );
        callback.setAccessible(true);

        runOnEdt(() -> callback.invoke(
                subject,
                session,
                new GeneratedImagePart(attachment, null, null, "generated")
        ));

        awaitCondition(2, TimeUnit.SECONDS, () -> !Files.exists(stagedPath));
        assertThat(session.responseParts).isEmpty();
    }

    @Test
    @DisplayName("Indeterminate user persistence stays blocked until confirmed failure is delivered")
    void onSend_whenUserPersistenceIsIndeterminate_defersFailureDelivery() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var persistenceCalled = new CountDownLatch(1);
        var delivered = new AtomicInteger();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceCalled.countDown();
                return CompletableFuture.failedFuture(new ConversationPersistenceIndeterminateException(
                        new SQLException("read unavailable")
                ));
            });
            subject.setOnDurableUserMessageFailureDelivered((ignoredConversation, ignoredMessage) ->
                    delivered.incrementAndGet()
            );
            readInputTextArea(subject.getInputBar()).setText("retry after check");
        });
        setCurrentProvider(subject, immediateProvider("unused"));

        invokeOnSend(subject);
        assertThat(persistenceCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        assertThat(delivered).hasValue(0);
        assertThat(callOnEdt(() -> readField(subject, "activeConversationId"))).isEqualTo(conversationId);

        runOnEdt(() -> {
            subject.setConversationPersistenceBlocked(conversationId, true);
            subject.resolveIndeterminateUserMessage(conversationId, false);
        });

        assertThat(delivered).hasValue(1);
        assertThat(callOnEdt(() -> readInputTextArea(subject.getInputBar()).getText()))
                .isEqualTo("retry after check");
    }

    @Test
    @DisplayName("Editing a confirmed-absent provisional draft allocates a replacement conversation")
    void onSend_whenConfirmedAbsentProvisionalDraftIsEdited_createsNewConversationIdentity() throws Exception {
        var events = new ArrayList<ChatPanel.UserMessageEvent>();
        var firstPersistenceCalled = new CountDownLatch(1);
        var secondPersistenceCalled = new CountDownLatch(1);
        runOnEdt(() -> {
            subject.setConversationIdSupplier(() -> null);
            subject.setOnDurableUserMessageSubmitted(event -> {
                events.add(event);
                if (events.size() == 1) {
                    firstPersistenceCalled.countDown();
                    return CompletableFuture.failedFuture(new ConversationPersistenceIndeterminateException(
                            new SQLException("read unavailable")
                    ));
                }
                secondPersistenceCalled.countDown();
                return CompletableFuture.completedFuture(event.conversationId());
            });
            readInputTextArea(subject.getInputBar()).setText("original draft");
        });
        setCurrentProvider(subject, immediateProvider(""));

        invokeOnSend(subject);
        assertThat(firstPersistenceCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        UUID absentConversationId = events.getFirst().conversationId();
        runOnEdt(() -> subject.resolveIndeterminateUserMessage(absentConversationId, false));
        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("replacement draft"));

        invokeOnSend(subject);
        assertThat(secondPersistenceCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        assertThat(events).hasSize(2);
        assertThat(events.get(1).createsConversation()).isTrue();
        assertThat(events.get(1).conversationId()).isNotEqualTo(absentConversationId);
    }

    @Test
    @DisplayName("Canonical indeterminate user persistence resumes one provider continuation")
    void resolveIndeterminateUserMessage_whenWriteCommitted_startsProviderOnce() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var persistenceCalls = new AtomicInteger();
        var providerCalls = new AtomicInteger();
        var persistenceCalled = new CountDownLatch(1);
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceCalls.incrementAndGet();
                persistenceCalled.countDown();
                return CompletableFuture.failedFuture(new ConversationPersistenceIndeterminateException(
                        new SQLException("read unavailable")
                ));
            });
            readInputTextArea(subject.getInputBar()).setText("committed message");
        });
        setCurrentProvider(subject, providerReturning("answer", providerCalls));

        invokeOnSend(subject);
        assertThat(persistenceCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        runOnEdt(() -> {
            subject.setConversationPersistenceBlocked(conversationId, true);
            subject.resolveIndeterminateUserMessage(conversationId, true);
        });
        awaitCondition(2, TimeUnit.SECONDS, () -> providerCalls.get() == 1);
        flushEdt();

        assertThat(persistenceCalls).hasValue(1);
        assertThat(providerCalls).hasValue(1);
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("committed message", "answer");
    }

    @Test
    @DisplayName("Canonical Together reconciliation retains initial model admission after the catalog changes")
    void resolveIndeterminateUserMessage_whenTogetherCatalogChangesAfterPersistence_doesNotReadmitModel() throws Exception {
        UUID conversationId = UUID.randomUUID();
        String providerName = "Together";
        String modelId = "Qwen/Qwen3.5-9B";
        String baseUrl = "https://api.together.ai/v1";
        var persistenceCalled = new CountDownLatch(1);
        var factoryCalls = new AtomicInteger();
        var transportCalls = new AtomicInteger();
        ProviderService providerService = new ProviderService() {
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
                transportCalls.incrementAndGet();
                onComplete.run();
            }
        };
        var provider = new ProviderRegistry.ProviderDef(
                providerName,
                "TOGETHER_API_KEY",
                baseUrl,
                baseUrl,
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                ignored -> {
                    factoryCalls.incrementAndGet();
                    return providerService;
                },
                List::of
        );
        ProviderModelCacheService cacheService = (ProviderModelCacheService) readField(subject, "modelCacheService");
        long scope = cacheService.nextScopeVersion();
        cacheService.synchronizeScope(providerName, baseUrl, scope);
        ProviderModelCacheService.RefreshAttempt attempt = cacheService.tryBeginRefreshIfNeeded(
                providerName,
                baseUrl,
                Duration.ZERO
        ).orElseThrow();
        assertThat(cacheService.update(attempt, List.of(modelId))).isTrue();
        runOnEdt(() -> {
            setField(subject, "providerMap", Map.of(providerName, provider));
            setField(subject, "selectedProviderName", providerName);
            setField(subject, "selectedModelId", modelId);
            setField(subject, "installedProviderScope", scope);
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceCalled.countDown();
                return CompletableFuture.failedFuture(new ConversationPersistenceIndeterminateException(
                        new SQLException("read unavailable")
                ));
            });
            subject.getInputBar().setText("canonical Together message");
        });

        invokeOnSend(subject);
        assertThat(persistenceCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        cacheService.invalidate(providerName);
        runOnEdt(() -> subject.resolveIndeterminateUserMessage(conversationId, true));
        awaitCondition(2, TimeUnit.SECONDS, () -> transportCalls.get() == 1);
        flushEdt();

        assertThat(factoryCalls).hasValue(2);
        assertThat(transportCalls).hasValue(1);
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("canonical Together message");
    }

    @Test
    @DisplayName("Canonical reconciliation adopts the saved message when provider recreation fails")
    void resolveIndeterminateUserMessage_whenProviderRecreationFails_keepsCanonicalMessage() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var persistenceCalled = new CountDownLatch(1);
        var factoryCalls = new AtomicInteger();
        ProviderRegistry.ProviderDef provider = new ProviderRegistry.ProviderDef(
                "Reconciliation Provider",
                null,
                null,
                null,
                List.of("reconciliation-model"),
                ProviderCapabilities.chatAndModels(),
                ignored -> {
                    if (factoryCalls.incrementAndGet() == 1) {
                        return immediateProvider("unused");
                    }
                    throw new IllegalStateException("provider recreation failed");
                },
                List::of
        );
        runOnEdt(() -> {
            setField(subject, "providerMap", Map.of(provider.name(), provider));
            setField(subject, "installedProviderScope", 1L);
            subject.setSelectedModel("Reconciliation Provider > reconciliation-model");
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceCalled.countDown();
                return CompletableFuture.failedFuture(new ConversationPersistenceIndeterminateException(
                        new SQLException("read unavailable")
                ));
            });
            readInputTextArea(subject.getInputBar()).setText("canonical message");
        });

        invokeOnSend(subject);
        assertThat(persistenceCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        runOnEdt(() -> subject.resolveIndeterminateUserMessage(conversationId, true));
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()
        ));
        flushEdt();

        assertThat(factoryCalls).hasValue(2);
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("canonical message");
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("message was saved", "provider recreation failed");
    }

    @Test
    @DisplayName("Canonical reconciliation does not reuse admission after a settled credential change")
    void resolveIndeterminateUserMessage_whenCredentialsChangedBeforeReconciliation_adoptsMessageWithoutProvider() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var persistenceCalled = new CountDownLatch(1);
        var providerCalls = new AtomicInteger();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceCalled.countDown();
                return CompletableFuture.failedFuture(new ConversationPersistenceIndeterminateException(
                        new SQLException("read unavailable")
                ));
            });
            readInputTextArea(subject.getInputBar()).setText("committed during credential update");
        });
        setCurrentProvider(subject, providerReturning("unexpected", providerCalls));
        String providerName = callOnEdt(() -> (String) readField(subject, "selectedProviderName"));

        invokeOnSend(subject);
        assertThat(persistenceCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        runOnEdt(() -> {
            subject.invalidateSelectedProviderCapabilityEvidence(Set.of(providerName));
            @SuppressWarnings("unchecked")
            Map<String, Integer> pendingChanges = (Map<String, Integer>) readField(subject, "credentialChangesPending");
            pendingChanges.remove(providerName);
            subject.resolveIndeterminateUserMessage(conversationId, true);
        });
        flushEdt();

        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("committed during credential update");
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("Regenerate the response after the update finishes");
    }

    @Test
    @DisplayName("Durable save-only edit keeps the original history until persistence succeeds")
    void editUserMessage_whenDurableSaveIsPending_commitsUiAfterPersistence() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        Message userMessage = Message.user("old question");
        Message assistantMessage = Message.assistant("old answer");
        CompletableFuture<Void> persistence = controlledFuture();
        var mutation = new AtomicReference<ChatPanel.HistoryMutationEvent>();
        runOnEdt(() -> {
            subject.loadConversationHistoryEntries(conversationId, List.of(
                    new ConversationRepository.MessageRecord(userMessageId, 1, userMessage),
                    new ConversationRepository.MessageRecord(UUID.randomUUID(), 2, assistantMessage)
            ));
            subject.setOnDurableHistoryMutation(event -> {
                mutation.set(event);
                return persistence;
            });
        });
        JButton editButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Edit message".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow());
        runOnEdt(editButton::doClick);
        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("updated question"));
        JButton saveOnlyButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Save only".equals(button.getText()))
                .findFirst()
                .orElseThrow());

        runOnEdt(saveOnlyButton::doClick);

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("old question", "old answer");
        assertThat(callOnEdt(subject::hasPendingConversationMutation)).isTrue();
        assertThat(mutation.get().type()).isEqualTo(ChatPanel.HistoryMutationType.EDIT);
        assertThat(mutation.get().retainedEntry().messageId()).isEqualTo(userMessageId);
        assertThat(mutation.get().retainedEntry().ordinal()).isEqualTo(1);

        persistence.complete(null);
        flushEdt();

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("updated question", "old answer");
        assertThat(callOnEdt(subject::isEditingUserMessage)).isFalse();
    }

    @Test
    @DisplayName("Reattaching after an edit failure delivers the retained editor recovery")
    void addNotify_whenEditFailureArrivedWhileRemoved_marksHistoryFailureDelivered() throws Exception {
        UUID conversationId = UUID.randomUUID();
        CompletableFuture<Void> persistence = controlledFuture();
        var delivered = new AtomicInteger();
        runOnEdt(() -> {
            subject.loadConversationHistoryEntries(conversationId, List.of(
                    new ConversationRepository.MessageRecord(UUID.randomUUID(), 1, Message.user("old question"))
            ));
            subject.setOnDurableHistoryMutation(event -> persistence);
            subject.setOnDurableHistoryMutationFailureDelivered(event -> delivered.incrementAndGet());
        });
        JButton editButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Edit message".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow());
        runOnEdt(editButton::doClick);
        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("updated question"));
        JButton saveOnlyButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Save only".equals(button.getText()))
                .findFirst()
                .orElseThrow());
        runOnEdt(saveOnlyButton::doClick);
        runOnEdt(subject::removeNotify);

        persistence.completeExceptionally(new SQLException("forced failure"));
        flushEdt();
        assertThat(delivered).hasValue(0);

        runOnEdt(subject::addNotify);

        assertThat(delivered).hasValue(1);
        assertThat(callOnEdt(subject::isEditingUserMessage)).isTrue();
        assertThat(callOnEdt(() -> readInputTextArea(subject.getInputBar()).getText()))
                .isEqualTo("updated question");
    }

    @Test
    @DisplayName("Canonical indeterminate edit applies its original continuation without a retry")
    void editUserMessage_whenPersistenceIsIndeterminateAndCanonical_appliesEditOnce() throws Exception {
        UUID conversationId = UUID.randomUUID();
        Message userMessage = Message.user("old question");
        runOnEdt(() -> {
            subject.loadConversationHistoryEntries(conversationId, List.of(
                    new ConversationRepository.MessageRecord(UUID.randomUUID(), 1, userMessage)
            ));
            subject.setOnDurableHistoryMutation(event -> CompletableFuture.failedFuture(
                    new ConversationPersistenceIndeterminateException(new SQLException("read unavailable"))
            ));
        });
        JButton editButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Edit message".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow());
        runOnEdt(editButton::doClick);
        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("updated question"));
        JButton saveOnlyButton = callOnEdt(() -> findComponents(subject, JButton.class).stream()
                .filter(button -> "Save only".equals(button.getText()))
                .findFirst()
                .orElseThrow());

        runOnEdt(saveOnlyButton::doClick);
        flushEdt();

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content).containsExactly("old question");
        assertThat(callOnEdt(subject::isEditingUserMessage)).isTrue();

        runOnEdt(() -> subject.resolveIndeterminateHistoryMutation(conversationId, true));

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content).containsExactly("updated question");
        assertThat(callOnEdt(subject::isEditingUserMessage)).isFalse();
    }

    @Test
    @DisplayName("Canonical indeterminate regeneration invokes the provider exactly once")
    void regenerateRecentResponse_whenPersistenceIsIndeterminateAndCanonical_continuesProviderOnce() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        var mutationCalled = new CountDownLatch(1);
        runOnEdt(() -> {
            subject.loadConversationHistoryEntries(conversationId, List.of(
                    new ConversationRepository.MessageRecord(userMessageId, 1, Message.user("question")),
                    new ConversationRepository.MessageRecord(UUID.randomUUID(), 2, Message.assistant("old answer"))
            ));
            subject.setOnDurableHistoryMutation(event -> {
                mutationCalled.countDown();
                return CompletableFuture.failedFuture(
                        new ConversationPersistenceIndeterminateException(new SQLException("read unavailable"))
                );
            });
        });
        var providerCalls = new AtomicInteger();
        setCurrentProvider(subject, providerReturning("new answer", providerCalls));
        flushEdt();

        runOnEdt(subject::regenerateRecentResponse);
        assertThat(mutationCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        assertThat(providerCalls).hasValue(0);

        runOnEdt(() -> subject.resolveIndeterminateHistoryMutation(conversationId, true));
        awaitCondition(2, TimeUnit.SECONDS, () -> providerCalls.get() == 1);
        flushEdt();

        assertThat(providerCalls).hasValue(1);
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("question", "new answer");
    }

    @Test
    @DisplayName("Durable regeneration persists truncation before invoking the provider")
    void regenerateRecentResponse_whenDurableTruncationIsPending_waitsBeforeProviderInvocation() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        Message userMessage = Message.user("question");
        Message assistantMessage = Message.assistant("old answer");
        CompletableFuture<Void> persistence = controlledFuture();
        var mutation = new AtomicReference<ChatPanel.HistoryMutationEvent>();
        runOnEdt(() -> {
            subject.loadConversationHistoryEntries(conversationId, List.of(
                    new ConversationRepository.MessageRecord(userMessageId, 1, userMessage),
                    new ConversationRepository.MessageRecord(UUID.randomUUID(), 2, assistantMessage)
            ));
            subject.setOnDurableHistoryMutation(event -> {
                mutation.set(event);
                return persistence;
            });
        });
        var providerCalls = new AtomicInteger();
        setCurrentProvider(subject, providerReturning("new answer", providerCalls));
        flushEdt();

        runOnEdt(subject::regenerateRecentResponse);
        awaitCondition(2, TimeUnit.SECONDS, () -> mutation.get() != null);

        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("question", "old answer");
        assertThat(mutation.get().type()).isEqualTo(ChatPanel.HistoryMutationType.TRUNCATE);
        assertThat(mutation.get().retainedEntry().messageId()).isEqualTo(userMessageId);

        persistence.complete(null);
        awaitCondition(2, TimeUnit.SECONDS, () -> providerCalls.get() == 1);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                subject.getHistory().size() == 2
                        && "new answer".equals(subject.getHistory().getLast().content())));
    }

    @Test
    @DisplayName("Credential changes during durable regeneration prevent transport after committed truncation")
    void regenerateRecentResponse_whenCredentialsChangeDuringPersistence_skipsTransportAfterTruncation() throws Exception {
        UUID conversationId = UUID.randomUUID();
        CompletableFuture<Void> persistence = controlledFuture();
        var mutationCalled = new CountDownLatch(1);
        runOnEdt(() -> {
            subject.loadConversationHistoryEntries(conversationId, List.of(
                    new ConversationRepository.MessageRecord(UUID.randomUUID(), 1, Message.user("question")),
                    new ConversationRepository.MessageRecord(UUID.randomUUID(), 2, Message.assistant("old answer"))
            ));
            subject.setOnDurableHistoryMutation(event -> {
                mutationCalled.countDown();
                return persistence;
            });
        });
        var providerCalls = new AtomicInteger();
        setCurrentProvider(subject, providerReturning("new answer", providerCalls));
        flushEdt();

        runOnEdt(subject::regenerateRecentResponse);
        assertThat(mutationCalled.await(2, TimeUnit.SECONDS)).isTrue();
        String providerName = callOnEdt(() -> (String) readField(subject, "selectedProviderName"));
        runOnEdt(() -> subject.invalidateSelectedProviderCapabilityEvidence(Set.of(providerName)));
        persistence.complete(null);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()
        ));
        flushEdt();

        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content).containsExactly("question");
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("credentials changed while the conversation change was being saved");
    }

    @Test
    @DisplayName("Cancelling after durable regeneration submission applies committed truncation without invoking the provider")
    void regenerateRecentResponse_whenCancelledAfterSubmission_appliesCommittedTruncationOnly() throws Exception {
        UUID conversationId = UUID.randomUUID();
        CompletableFuture<Void> persistence = controlledFuture();
        var mutationCalled = new CountDownLatch(1);
        runOnEdt(() -> {
            subject.loadConversationHistoryEntries(conversationId, List.of(
                    new ConversationRepository.MessageRecord(UUID.randomUUID(), 1, Message.user("question")),
                    new ConversationRepository.MessageRecord(UUID.randomUUID(), 2, Message.assistant("old answer"))
            ));
            subject.setOnDurableHistoryMutation(event -> {
                mutationCalled.countDown();
                return persistence;
            });
        });
        var providerCalls = new AtomicInteger();
        setCurrentProvider(subject, providerReturning("unexpected", providerCalls));

        runOnEdt(subject::regenerateRecentResponse);
        assertThat(mutationCalled.await(2, TimeUnit.SECONDS)).isTrue();
        runOnEdt(subject::cancelStreamingAndMarkCancelled);
        persistence.complete(null);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(subject::getHistory).size() == 1);
        flushEdt();

        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content).containsExactly("question");
        assertThat(callOnEdt(() -> subject.getInputBar().isEnabled())).isTrue();
    }

    @Test
    @DisplayName("Canonical regeneration reconciliation after cancellation applies truncation without invoking the provider")
    void resolveIndeterminateHistoryMutation_whenRegenerationWasCancelled_appliesCanonicalTruncationOnly() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var mutationCalled = new CountDownLatch(1);
        runOnEdt(() -> {
            subject.loadConversationHistoryEntries(conversationId, List.of(
                    new ConversationRepository.MessageRecord(UUID.randomUUID(), 1, Message.user("question")),
                    new ConversationRepository.MessageRecord(UUID.randomUUID(), 2, Message.assistant("old answer"))
            ));
            subject.setOnDurableHistoryMutation(event -> {
                mutationCalled.countDown();
                return CompletableFuture.failedFuture(
                        new ConversationPersistenceIndeterminateException(new SQLException("read unavailable"))
                );
            });
        });
        var providerCalls = new AtomicInteger();
        setCurrentProvider(subject, providerReturning("unexpected", providerCalls));

        runOnEdt(subject::regenerateRecentResponse);
        assertThat(mutationCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        assertThat(callOnEdt(subject::hasPendingConversationMutation)).isTrue();
        runOnEdt(subject::abandonVisibleUnsubmittedPreparation);
        assertThat(callOnEdt(subject::hasPendingConversationMutation)).isTrue();
        runOnEdt(subject::cancelStreamingAndMarkCancelled);
        runOnEdt(() -> subject.resolveIndeterminateHistoryMutation(conversationId, true));
        flushEdt();

        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content).containsExactly("question");
    }

    @Test
    @DisplayName("Regeneration without durable persistence leaves history unchanged and does not invoke the provider")
    void regenerateRecentResponse_whenPersistenceIsNotConfigured_failsClosed() throws Exception {
        UUID conversationId = UUID.randomUUID();
        runOnEdt(() -> {
            subject.setOnDurableHistoryMutation(null);
            subject.loadConversationHistoryEntries(conversationId, List.of(
                new ConversationRepository.MessageRecord(UUID.randomUUID(), 1, Message.user("question")),
                new ConversationRepository.MessageRecord(UUID.randomUUID(), 2, Message.assistant("old answer"))
            ));
        });
        var providerCalls = new AtomicInteger();
        setCurrentProvider(subject, providerReturning("unexpected", providerCalls));

        runOnEdt(subject::regenerateRecentResponse);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getInputBar().isEnabled()));
        flushEdt();

        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("question", "old answer");
    }

    @Test
    @DisplayName("Cancelling user message edit restores composer draft and leaves history unchanged")
    void editUserMessage_whenCancelled_restoresDraftAndKeepsHistory() throws Exception {
        JTextArea textArea = callOnEdt(() -> {
            subject.loadHistory(List.of(Message.user("old question")));
            JTextArea area = readInputTextArea(subject.getInputBar());
            area.setText("draft text");
            findComponents(subject, JButton.class).stream()
                    .filter(button -> "Edit message".equals(button.getToolTipText()))
                    .findFirst()
                    .orElseThrow()
                    .doClick();
            area.setText("changed edit");
            findComponents(subject, JButton.class).stream()
                    .filter(button -> "Cancel editing".equals(button.getToolTipText()))
                    .findFirst()
                    .orElseThrow()
                    .doClick();
            return area;
        });

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content).containsExactly("old question");
        assertThat(callOnEdt(() -> textArea.getText())).isEqualTo("draft text");
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
    @DisplayName("Shutdown suppresses provider continuation after a pending durable user write settles")
    void beginShutdown_whenUserPersistenceIsPending_suppressesProviderAndLateUiMutation() throws Exception {
        UUID conversationId = UUID.randomUUID();
        CompletableFuture<UUID> persistence = controlledFuture();
        var persistenceStarted = new CountDownLatch(1);
        var providerCalls = new AtomicInteger();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setConversationIdSupplier(() -> conversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                persistenceStarted.countDown();
                return persistence;
            });
            readInputTextArea(subject.getInputBar()).setText("pending");
        });
        setCurrentProvider(subject, providerReturning("unexpected", providerCalls));

        invokeOnSend(subject);
        assertThat(persistenceStarted.await(2, TimeUnit.SECONDS)).isTrue();
        runOnEdt(subject::beginShutdown);
        persistence.complete(conversationId);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()
        ));
        flushEdt();

        assertThat(providerCalls).hasValue(0);
        assertThat(callOnEdt(subject::getHistory)).isEmpty();
        assertThat(callOnEdt(() -> readInputTextArea(subject.getInputBar()).getText())).isEqualTo("pending");
    }

    @Test
    @DisplayName("Terminal provider completion submits assistant persistence before queued shutdown")
    void onSend_whenTerminalCallbackPrecedesQueuedShutdown_submitsAssistantBeforeEdtDelivery() throws Exception {
        var providerStarted = new CountDownLatch(1);
        var releaseProvider = new CountDownLatch(1);
        var assistantPersisted = new CountDownLatch(1);
        var edtBlocked = new CountDownLatch(1);
        var releaseEdt = new CountDownLatch(1);
        runOnEdt(() -> subject.setOnDurableAssistantMessageCompleted(event -> {
            assistantPersisted.countDown();
            return CompletableFuture.completedFuture(null);
        }));
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
                providerStarted.countDown();
                awaitLatch(releaseProvider);
                onToken.accept("durable answer");
                onComplete.run();
            }
        });
        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("question"));

        invokeOnSend(subject);
        assertThat(providerStarted.await(2, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            awaitLatch(releaseEdt);
        });
        assertThat(edtBlocked.await(2, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeLater(subject::beginShutdown);

        try {
            releaseProvider.countDown();
            assertThat(assistantPersisted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseEdt.countDown();
        }
        flushEdt();
    }

    @Test
    @DisplayName("Completing a stream triggers save callback for both user and assistant messages")
    void onSend_whenStreamCompletes_notifiesMessageSubmittedForAssistantResponse() throws Exception {
        var callbackCount = new AtomicInteger();
        var callbacks = new CountDownLatch(2);
        runOnEdt(() -> {
            subject.setOnDurableUserMessageSubmitted(event -> {
                callbackCount.incrementAndGet();
                callbacks.countDown();
                return CompletableFuture.completedFuture(event.conversationId());
            });
            subject.setOnDurableAssistantMessageCompleted(event -> {
                callbackCount.incrementAndGet();
                callbacks.countDown();
                return CompletableFuture.completedFuture(null);
            });
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

        });

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("ping"));
        invokeOnSend(subject);

        assertThat(callbacks.await(2, TimeUnit.SECONDS)).isTrue();
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));

        assertThat(callbackCount.get()).isEqualTo(2);
        assertThat(callOnEdt(subject::getHistory))
                .hasSize(2)
                .element(1)
                .satisfies(message -> {
                    assertThat(message.role()).isEqualTo(Role.ASSISTANT);
                    assertThat(message.content()).isEqualTo("pong");
                });
    }

    @Test
    @DisplayName("Thinking bubble is created only when thinking tokens are emitted")
    void onSend_whenProviderDoesNotEmitThinking_doesNotRenderActivityBubble() throws Exception {
        setCurrentProvider(subject, immediateProvider("pong"));

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("ping"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));

        assertThat(callOnEdt(() -> {
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            return findComponents(messagesPanel, ActivityBubble.class);
        })).isEmpty();
    }

    @Test
    @DisplayName("Hidden activity bubbles are omitted from browser transcript entries")
    void toConversationEntry_whenActivityBubbleIsHidden_returnsNoEntry() throws Exception {
        ActivityBubble activityBubble = callOnEdt(() -> new ActivityBubble("Thinking", true));
        Method method = ChatPanel.class.getDeclaredMethod(
                "toConversationEntry",
                Component.class,
                int[].class,
                boolean.class
        );
        method.setAccessible(true);

        try {
            runOnEdt(() -> {
                activityBubble.setText("Internal reasoning");
                activityBubble.setVisible(false);
            });
            ConversationEntry hiddenEntry = callOnEdt(() ->
                    (ConversationEntry) method.invoke(subject, activityBubble, new int[]{0}, false)
            );
            runOnEdt(() -> activityBubble.setVisible(true));
            ConversationEntry visibleEntry = callOnEdt(() ->
                    (ConversationEntry) method.invoke(subject, activityBubble, new int[]{0}, false)
            );

            assertThat(hiddenEntry).isNull();
            assertThat(visibleEntry).isEqualTo(ConversationEntry.activity("Thinking", "Internal reasoning", true));
        } finally {
            runOnEdt(activityBubble::dispose);
            runOnEdt(() -> {});
        }
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

        runOnEdt(() -> subject.loadHistory(messages));

        assertThat(callOnEdt(() -> {
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            return findComponents(messagesPanel, ActivityBubble.class);
        })).isEmpty();
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

        runOnEdt(() -> subject.loadHistory(messages));

        callOnEdt(() -> {
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
            assertThat(thinkingBubbles).hasSize(1);
            assertThat(thinkingBubbles.getFirst().isCollapsed()).isTrue();
            return null;
        });
    }

    @Test
    @DisplayName("Consecutive assistant artifacts install and render as one assistant message")
    void loadHistory_whenHistoryContainsConsecutiveAssistantArtifacts_installsAndRendersSingleAssistantMessage() throws Exception {
        List<Message> messages = List.of(
                Message.user("question"),
                new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("first answer")),
                        Instant.now(),
                        MessageMeta.empty()
                ),
                new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("")),
                        Instant.now(),
                        MessageMeta.empty()
                ),
                new Message(
                        Role.ASSISTANT,
                        List.of(new TextPart("final answer")),
                        Instant.now(),
                        MessageMeta.empty()
                )
        );

        runOnEdt(() -> subject.loadHistory(messages));

        assertThat(callOnEdt(subject::getHistory))
                .extracting(Message::role)
                .containsExactly(Role.USER, Role.ASSISTANT);
        assertThat(callOnEdt(() -> {
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            return findComponents(messagesPanel, MessageBubble.class).stream()
                    .filter(bubble -> bubble.getRole() == Role.ASSISTANT)
                    .toList();
        })).hasSize(1);
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

        callOnEdt(() -> {
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
            assertThat(thinkingBubbles).hasSize(1);

            List<JEditorPane> panes = findComponents(thinkingBubbles.getFirst(), JEditorPane.class);
            assertThat(panes).isNotEmpty();
            assertThat(panes.getFirst().getDocument().getLength()).isGreaterThan(1);
            return null;
        });
    }

    @Test
    @DisplayName("Thinking bubble renders without nested inner scroll containers")
    void onSend_whenProviderEmitsThinking_usesSingleRenderedPath() throws Exception {
        runOnEdt(() -> {
            subject.getInputBar().setThinkingAvailable(true);
            subject.getInputBar().setThinkingEnabled(true);
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
                onThinkingToken.accept("Thinking step one\n\n- detail");
                onToken.accept("final answer");
                onComplete.run();
            }

        });

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("question"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));

        callOnEdt(() -> {
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
            assertThat(thinkingBubbles).hasSize(1);

            ActivityBubble thinkingBubble = thinkingBubbles.getFirst();
            assertThat(findComponents(thinkingBubble, JScrollPane.class)).isEmpty();

            List<JEditorPane> panes = findComponents(thinkingBubble, JEditorPane.class);
            assertThat(panes).isNotEmpty();
            assertThat(panes.getFirst().getDocument().getLength()).isGreaterThan(5);
            return null;
        });
    }

    @Test
    @DisplayName("Thinking-only completions do not create an empty assistant message bubble")
    void onSend_whenProviderEmitsOnlyThinking_keepsOnlyTheActivityBubble() throws Exception {
        runOnEdt(() -> {
            subject.getInputBar().setThinkingAvailable(true);
            subject.getInputBar().setThinkingEnabled(true);
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
                onThinkingToken.accept("Thinking without a text answer");
                onComplete.run();
            }
        });
        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("question"));

        invokeOnSend(subject);
        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));
        flushEdt();

        assertThat(callOnEdt(() -> (List<?>) readField(subject, "assistantBubbles"))).isEmpty();
        assertThat(callOnEdt(() -> {
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            return findComponents(messagesPanel, ActivityBubble.class).stream()
                    .map(ActivityBubble::getFullText)
                    .toList();
        })).singleElement().asString().contains("Thinking without a text answer");
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

        ActivityBubble thinkingBubble = callOnEdt(() -> {
            subject.loadHistory(messages);
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            return findComponents(messagesPanel, ActivityBubble.class).getFirst();
        });
        JButton copyButton = callOnEdt(() -> findComponents(thinkingBubble, JButton.class).stream()
                .filter(button -> "Copy thinking".equals(button.getToolTipText()))
                .findFirst()
                .orElseThrow());

        assertThat(callOnEdt(copyButton::isVisible)).isFalse();

        runOnEdt(() -> {
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
            Arrays.stream(thinkingBubble.getMouseListeners()).forEach(listener -> listener.mouseEntered(hoverEvent));
        });

        assertThat(callOnEdt(copyButton::isVisible)).isTrue();
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

        ActivityBubble thinkingBubble = callOnEdt(() -> {
            subject.loadHistory(messages);
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            return findComponents(messagesPanel, ActivityBubble.class).getFirst();
        });
        JLabel titleLabel = callOnEdt(() -> findComponents(thinkingBubble, JLabel.class).stream()
                .filter(label -> "Thinking".equals(label.getText()))
                .findFirst()
                .orElseThrow());

        assertThat(callOnEdt(thinkingBubble::isCollapsed)).isTrue();

        runOnEdt(() -> clickLabel(titleLabel));
        awaitCondition(1, TimeUnit.SECONDS, () -> callOnEdt(() -> !thinkingBubble.isCollapsed()));
        assertThat(callOnEdt(thinkingBubble::isCollapsed)).isFalse();

        runOnEdt(() -> clickLabel(titleLabel));
        awaitCondition(1, TimeUnit.SECONDS, () -> callOnEdt(thinkingBubble::isCollapsed));
        assertThat(callOnEdt(thinkingBubble::isCollapsed)).isTrue();
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

        });

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("question"));
        invokeOnSend(subject);

        // Allow headroom for asynchronous send/completion dispatch on slower CI.
        awaitCondition(5, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));

        callOnEdt(() -> {
            Message assistant = subject.getHistory().get(1);
            assertThat(assistant.content()).contains("final answer");
            assertThat(assistant.meta().assistantThinking()).isEmpty();
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            assertThat(findComponents(messagesPanel, ActivityBubble.class)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("Thinking stream ignores non-visible control tokens and keeps visible text")
    void onSend_whenThinkingIncludesControlSequences_keepsVisibleThinkingText() throws Exception {
        runOnEdt(() -> {
            subject.getInputBar().setThinkingAvailable(true);
            subject.getInputBar().setThinkingEnabled(true);
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
                onThinkingToken.accept("First visible part");
                onThinkingToken.accept("\u001B[2J\u001B[H\u200B\u200C");
                onThinkingToken.accept(" and second visible part");
                onToken.accept("final answer");
                onComplete.run();
            }

        });

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("question"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));

        callOnEdt(() -> {
            Message assistant = subject.getHistory().get(1);
            assertThat(assistant.meta().assistantThinking()).contains("First visible part");
            assertThat(assistant.meta().assistantThinking()).contains("second visible part");
            assertThat(assistant.meta().assistantThinking()).doesNotContain("\u001B");

            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
            assertThat(thinkingBubbles).hasSize(1);
            assertThat(thinkingBubbles.getFirst().getFullText()).contains("First visible part");
            assertThat(thinkingBubbles.getFirst().getFullText()).contains("second visible part");
            return null;
        });
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

        });

        runOnEdt(() -> {
            subject.getInputBar().setThinkingAvailable(true);
            subject.getInputBar().setThinkingEnabled(true);
            readInputTextArea(subject.getInputBar()).setText("question");
        });
        invokeOnSend(subject);

        awaitCondition(5, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));

        long assistantCount = callOnEdt(() -> subject.getHistory().stream()
                .filter(message -> message.role() == Role.ASSISTANT)
                .count());
        assertThat(assistantCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Provider errors flush buffered partial think-tag text before persistence")
    void onSend_whenProviderErrorsWithPartialThinkTag_preservesBufferedText() throws Exception {
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
                onToken.accept("answer<thi");
                onError.accept(new IllegalStateException("provider failed"));
            }
        });

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("question"));
        invokeOnSend(subject);

        awaitCondition(5, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));

        assertThat(callOnEdt(() -> subject.getHistory().get(1).content()))
                .contains("answer<thi", "provider failed");
    }

    @Test
    @DisplayName("Think tags emitted in answer tokens are rendered as thinking for any provider")
    void onSend_whenProviderEmitsThinkTagsInAnswerTokens_extractsThinkingModelAgnostically() throws Exception {
        runOnEdt(() -> {
            subject.getInputBar().setThinkingAvailable(true);
            subject.getInputBar().setThinkingEnabled(true);
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
                onToken.accept("<thi");
                onToken.accept("nk>hidden reasoning</thi");
                onToken.accept("nk>visible answer");
                onComplete.run();
            }

        });

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("question"));
        invokeOnSend(subject);

        awaitCondition(5, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));
        assertRenderedThinking("visible answer", "hidden reasoning");
    }

    @Test
    @DisplayName("Think tags in answer tokens render as thinking even when reasoning is disabled")
    void onSend_whenReasoningDisabledAndProviderEmitsThinkTags_rendersActivityBubble() throws Exception {
        runOnEdt(() -> {
            subject.getInputBar().setThinkingAvailable(false);
            subject.getInputBar().setThinkingEnabled(false);
        });
        setCurrentProvider(subject, immediateProvider("<think>hidden reasoning</think>visible answer"));

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("question"));
        invokeOnSend(subject);

        awaitCondition(5, TimeUnit.SECONDS, () -> callOnEdt(() -> subject.getHistory().size() == 2));
        assertRenderedThinking("visible answer", "hidden reasoning");
    }

    @Test
    @DisplayName("Native thinking tokens are rendered and persisted separately from assistant answer text")
    void onSend_whenProviderEmitsThinking_persistsThinkingInAssistantMetaAndRendersActivityBubble() throws Exception {
        runOnEdt(() -> {
            subject.getInputBar().setThinkingAvailable(true);
            subject.getInputBar().setThinkingEnabled(true);
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
                onThinkingToken.accept("We should compare enum features.");
                onToken.accept("Java enums are classes; TypeScript enums compile to JS objects.");
                onComplete.run();
            }

        });

        JTextArea textArea = callOnEdt(() -> readInputTextArea(subject.getInputBar()));
        runOnEdt(() -> textArea.setText("compare java and ts enums"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            flushEdt();
            return callOnEdt(() -> subject.getHistory().size() == 2);
        });

        Message assistant = callOnEdt(() -> subject.getHistory().get(1));
        assertThat(assistant.content()).contains("Java enums are classes");
        assertThat(assistant.meta().assistantThinking()).contains("compare enum features");

        callOnEdt(() -> {
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
            assertThat(thinkingBubbles).hasSize(1);
            assertThat(thinkingBubbles.getFirst().getFullText()).contains("compare enum features");
            assertThat(messageRowIndex(messagesPanel, thinkingBubbles.getFirst()))
                    .isLessThan(messageRowIndex(messagesPanel, assistantBubble(messagesPanel)));
            return null;
        });
    }

    @Test
    @DisplayName("Assistant persistence failure leaves the completed response visible")
    void onSend_whenAssistantPersistenceListenerFails_keepsCompletedResponseVisible() throws Exception {
        var persistenceCalled = new CountDownLatch(1);
        runOnEdt(() -> subject.setOnDurableAssistantMessageCompleted(event -> {
            persistenceCalled.countDown();
            return CompletableFuture.failedFuture(new IllegalStateException("boom"));
        }));

        setCurrentProvider(subject, immediateProvider("pong"));

        JTextArea textArea = readInputTextArea(subject.getInputBar());
        SwingUtilities.invokeAndWait(() -> textArea.setText("ping"));
        invokeOnSend(subject);

        assertThat(persistenceCalled.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();
        assertThat(callOnEdt(subject::getHistory)).hasSize(2);
        assertThat(callOnEdt(() -> subject.getHistory().get(1).role())).isEqualTo(Role.ASSISTANT);
        assertThat(callOnEdt(() -> subject.getHistory().get(1).content())).isEqualTo("pong");
    }

    @Test
    @DisplayName("First send in unsaved chat keeps assistant stream bound after conversation is created")
    void onSend_whenUnsavedConversationGetsPersisted_streamRemainsVisibleAndPersistsAssistantMessage() throws Exception {
        var currentConversationId = new AtomicReference<UUID>();

        runOnEdt(() -> {
            subject.setActiveConversationId(null);
            subject.setConversationIdSupplier(currentConversationId::get);
            subject.setOnDurableUserMessageSubmitted(event -> {
                UUID persistedConversationId = event.conversationId();
                currentConversationId.set(persistedConversationId);
                subject.setActiveConversationId(persistedConversationId);
                return CompletableFuture.completedFuture(persistedConversationId);
            });
        });

        setCurrentProvider(subject, immediateProvider("pong"));

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("ping"));
        invokeOnSend(subject);

        awaitCondition(2, TimeUnit.SECONDS, () -> {
            List<Message> history = callOnEdt(subject::getHistory);
            return history.size() == 2
                    && history.get(1).role() == Role.ASSISTANT
                    && "pong".equals(history.get(1).content());
        });

        assertThat(callOnEdt(subject::getHistory)).hasSize(2);
        assertThat(callOnEdt(() -> subject.getHistory().get(0).content())).isEqualTo("ping");
        assertThat(callOnEdt(() -> subject.getHistory().get(1).content())).isEqualTo("pong");
        assertThat(callOnEdt(() -> subject.getInputBar().isEnabled())).isTrue();
        assertThat(callOnEdt(() -> subject.getInputBar().isCancelGenerationVisible())).isFalse();
    }

    @Test
    @DisplayName("Switching visible conversation while preparing keeps original send flow running in background")
    void onSend_whenConversationChangesDuringPreparing_continuesBackgroundFlowForOriginalConversation() throws Exception {
        var originalConversationId = UUID.randomUUID();
        var visibleConversationId = UUID.randomUUID();
        var preparationStarted = new CountDownLatch(1);
        var releasePreparation = new CountDownLatch(1);
        var persistedMessages = new ArrayList<Message>();
        var completion = new CountDownLatch(2);

        runOnEdt(() -> {
            subject.setActiveConversationId(originalConversationId);
            subject.setConversationIdSupplier(() -> originalConversationId);
            subject.setOnDurableUserMessageSubmitted(event -> {
                synchronized (persistedMessages) {
                    persistedMessages.add(event.message());
                }
                completion.countDown();
                return CompletableFuture.completedFuture(event.conversationId());
            });
            subject.setOnDurableAssistantMessageCompleted(event -> {
                synchronized (persistedMessages) {
                    persistedMessages.add(event.message());
                }
                completion.countDown();
                return CompletableFuture.completedFuture(null);
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
        });

        setCurrentProvider(subject, immediateProvider("pong"));

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("ping"));
        invokeOnSend(subject);

        try {
            assertThat(preparationStarted.await(2, TimeUnit.SECONDS)).isTrue();
            callOnEdt(() -> {
                subject.setActiveConversationId(visibleConversationId);
                subject.loadHistory(List.of(Message.user("visible conversation")));
                assertThat(subject.getInputBar().isEnabled()).isTrue();
                assertThat(subject.getInputBar().isCancelGenerationVisible()).isFalse();
                return null;
            });

            releasePreparation.countDown();
            assertThat(completion.await(2, TimeUnit.SECONDS)).isTrue();

            synchronized (persistedMessages) {
                assertThat(persistedMessages).hasSize(2);
                assertThat(persistedMessages).extracting(Message::role)
                        .containsExactlyInAnyOrder(Role.USER, Role.ASSISTANT);
            }

            assertThat(callOnEdt(subject::getHistory)).hasSize(1);
            assertThat(callOnEdt(() -> subject.getHistory().getFirst().content()))
                    .isEqualTo("visible conversation");
        } finally {
            releasePreparation.countDown();
        }
    }

    @Test
    @DisplayName("Loading stable conversation records recovers pending assistant for the conversation")
    void loadConversationHistoryEntries_whenPendingAssistantExists_recoversForLoadedConversation() throws Exception {
        UUID loadedConversationId = UUID.randomUUID();
        UUID otherConversationId = UUID.randomUUID();
        Message recoveredAssistant = Message.assistant("sonar result");
        Map<UUID, ?> pendingRecoveries = callOnEdt(() -> pendingAssistantRecoveryMap(subject));
        queuePendingAssistantRecovery(
                subject,
                loadedConversationId,
                new ConversationHistoryEntry(UUID.randomUUID(), 2, recoveredAssistant)
        );

        SwingUtilities.invokeAndWait(() -> {
            subject.setActiveConversationId(otherConversationId);
            subject.loadConversationHistoryEntries(loadedConversationId, List.of(
                    new ConversationRepository.MessageRecord(
                            UUID.randomUUID(),
                            1,
                            Message.user("question")
                    )
            ));
        });

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("question", "sonar result");
        assertThat(callOnEdt(() -> readField(subject, "activeConversationId"))).isEqualTo(loadedConversationId);
        assertThat(callOnEdt(() -> pendingRecoveries.containsKey(loadedConversationId))).isTrue();
    }

    @Test
    @DisplayName("A conflicting stored assistant identity does not discard the pending canonical recovery")
    void loadConversationHistoryEntries_whenStoredAssistantConflictsWithRecovery_keepsRecoveryVisible() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID assistantId = UUID.randomUUID();
        var recovery = new ConversationHistoryEntry(assistantId, 2, Message.assistant("recovered answer"));
        Map<UUID, ?> pendingRecoveries = callOnEdt(() -> pendingAssistantRecoveryMap(subject));
        queuePendingAssistantRecovery(subject, conversationId, recovery);

        runOnEdt(() -> subject.loadConversationHistoryEntries(conversationId, List.of(
                new ConversationRepository.MessageRecord(UUID.randomUUID(), 1, Message.user("question")),
                new ConversationRepository.MessageRecord(assistantId, 3, Message.assistant("conflicting answer"))
        )));

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("question", "recovered answer");
        assertThat(pendingAssistantRecoveryEntries(subject, conversationId)).containsExactly(recovery);
        assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                .contains("has not been saved");
    }

    @Test
    @DisplayName("Canonical assistant recoveries are pruned while missing recoveries remain pending")
    void loadConversationHistoryEntries_whenOnlySomeRecoveriesAreCanonical_retainsOnlyMissingEntries() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var canonical = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("saved answer"));
        var missing = new ConversationHistoryEntry(UUID.randomUUID(), 3, Message.assistant("unsaved answer"));
        Map<UUID, ?> pendingRecoveries = callOnEdt(() -> pendingAssistantRecoveryMap(subject));
        queuePendingAssistantRecovery(subject, conversationId, canonical);
        queuePendingAssistantRecovery(subject, conversationId, missing);

        runOnEdt(() -> subject.loadConversationHistoryEntries(conversationId, List.of(
                new ConversationRepository.MessageRecord(UUID.randomUUID(), 1, Message.user("question")),
                new ConversationRepository.MessageRecord(
                        canonical.messageId(),
                        canonical.ordinal(),
                        canonical.message()
                )
        )));

        assertThat(pendingAssistantRecoveryEntries(subject, conversationId)).containsExactly(missing);
    }

    @Test
    @DisplayName("Record-based history recovery advances past every stable assistant ordinal")
    void loadConversationHistoryEntries_whenRecoveriesHaveIdenticalContent_advancesOrdinal() throws Exception {
        UUID conversationId = UUID.randomUUID();
        Message repeated = Message.assistant("same answer");
        var userRecord = new ConversationRepository.MessageRecord(
                UUID.randomUUID(),
                1,
                Message.user("question")
        );
        Map<UUID, ?> pendingRecoveries = callOnEdt(() -> pendingAssistantRecoveryMap(subject));
        queuePendingAssistantRecovery(
                subject,
                conversationId,
                new ConversationHistoryEntry(UUID.randomUUID(), 2, repeated)
        );
        queuePendingAssistantRecovery(
                subject,
                conversationId,
                new ConversationHistoryEntry(UUID.randomUUID(), 3, repeated)
        );

        SwingUtilities.invokeAndWait(() ->
                subject.loadConversationHistoryEntries(conversationId, List.of(userRecord))
        );

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("question", "same answer");
        assertThat(callOnEdt(() -> (int) readField(subject, "nextMessageOrdinal"))).isEqualTo(4);
        assertThat(callOnEdt(() -> pendingRecoveries.containsKey(conversationId))).isTrue();
    }

    @Test
    @DisplayName("Reconciliation preserves an equivalent assistant recovery queued after its snapshot")
    void reconcilePendingAssistantRecoveries_whenEquivalentRecoveryIsRequeued_preservesLatestAcceptance() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));
        queuePendingAssistantRecovery(subject, conversationId, entry);
        Set<UUID> pendingIds = new AbstractSet<>() {
            @Override
            public Iterator<UUID> iterator() {
                return List.<UUID>of().iterator();
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public boolean contains(Object candidate) {
                try {
                    queuePendingAssistantRecovery(subject, conversationId, entry);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return false;
            }
        };

        runOnEdt(() -> subject.reconcilePendingAssistantRecoveries(conversationId, pendingIds));

        assertThat(pendingAssistantRecoveryEntries(subject, conversationId)).containsExactly(entry);
    }

    @Test
    @DisplayName("Failed visible assistant persistence remains available across repeated reopen attempts")
    void persistAssistantResponse_whenVisiblePersistenceFails_retainsOverlayRecovery() throws Exception {
        UUID conversationId = UUID.randomUUID();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setOnDurableAssistantMessageCompleted(event ->
                    CompletableFuture.failedFuture(new SQLException("still unavailable"))
            );
        });
        StreamingSession session = new StreamingSession(1L, conversationId, null);
        session.response.append("visible unsaved answer");

        invokePersistAssistantResponse(subject, session, null);
        flushEdt();

        Map<UUID, ?> pendingRecoveries = callOnEdt(() -> pendingAssistantRecoveryMap(subject));
        assertThat(callOnEdt(() -> pendingRecoveries.containsKey(conversationId))).isTrue();
        var userRecord = new ConversationRepository.MessageRecord(
                UUID.randomUUID(),
                1,
                Message.user("question")
        );
        SwingUtilities.invokeAndWait(() ->
                subject.loadConversationHistoryEntries(conversationId, List.of(userRecord))
        );
        SwingUtilities.invokeAndWait(() ->
                subject.loadConversationHistoryEntries(conversationId, List.of(userRecord))
        );

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("question", "visible unsaved answer");
        assertThat(callOnEdt(() -> pendingRecoveries.containsKey(conversationId))).isTrue();
    }

    @Test
    @DisplayName("Failed hidden assistant persistence queues recovery for the original conversation")
    void persistAssistantResponse_whenHiddenPersistenceFails_queuesPendingRecovery() throws Exception {
        UUID originalConversationId = UUID.randomUUID();
        UUID visibleConversationId = UUID.randomUUID();
        AtomicInteger persistCalls = new AtomicInteger();
        runOnEdt(() -> {
            subject.setActiveConversationId(visibleConversationId);
            subject.setOnDurableAssistantMessageCompleted(event -> {
                persistCalls.incrementAndGet();
                return CompletableFuture.failedFuture(new SQLException("forced persistence failure"));
            });
        });
        StreamingSession session = new StreamingSession(1L, originalConversationId, null);
        session.response.append("background sonar result");

        invokePersistAssistantResponse(subject, session, null);

        Map<UUID, ?> pendingRecoveries = callOnEdt(() -> pendingAssistantRecoveryMap(subject));
        assertThat(persistCalls).hasValue(1);
        assertThat(callOnEdt(() -> pendingRecoveries.containsKey(originalConversationId))).isTrue();
        assertThat(callOnEdt(subject::getHistory)).isEmpty();

        SwingUtilities.invokeAndWait(() -> {
            subject.setActiveConversationId(originalConversationId);
            subject.loadConversationHistoryEntries(originalConversationId, List.of(
                    new ConversationRepository.MessageRecord(
                            UUID.randomUUID(),
                            1,
                            Message.user("question")
                    )
            ));
        });

        assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                .containsExactly("question", "background sonar result");
        assertThat(callOnEdt(() -> pendingRecoveries.containsKey(originalConversationId))).isTrue();
    }

    @Test
    @DisplayName("Assistant persistence failure while removed is surfaced after reattachment")
    void addNotify_whenAssistantPersistenceFailedWhileRemoved_showsUnsavedMessage() throws Exception {
        UUID conversationId = UUID.randomUUID();
        CompletableFuture<Void> persistence = controlledFuture();
        runOnEdt(() -> {
            subject.setActiveConversationId(conversationId);
            subject.setOnDurableAssistantMessageCompleted(event -> persistence);
        });
        StreamingSession session = new StreamingSession(1L, conversationId, null);
        session.response.append("unsaved answer");
        try {
            invokePersistAssistantResponse(subject, session, null);
            runOnEdt(subject::removeNotify);
            persistence.completeExceptionally(new SQLException("unavailable"));
            flushEdt();

            runOnEdt(subject::addNotify);

            assertThat(callOnEdt(() -> readValidationLabel(subject.getInputBar()).getText()))
                    .contains("assistant response could not be saved");
        } finally {
            persistence.complete(null);
            runOnEdt(subject::removeNotify);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Reloading a streaming conversation reattaches the full in-flight assistant text")
    void loadHistory_whenConversationIsStillStreaming_restoresBufferedAssistantText() throws Exception {
        var originalConversationId = UUID.randomUUID();
        var visibleConversationId = UUID.randomUUID();
        var firstTokenDelivered = new CountDownLatch(1);
        var releaseSecondToken = new CountDownLatch(1);

        runOnEdt(() -> {
            subject.setActiveConversationId(originalConversationId);
            subject.setConversationIdSupplier(() -> originalConversationId);
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

        });

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("ping"));
        invokeOnSend(subject);

        try {
            assertThat(firstTokenDelivered.await(2, TimeUnit.SECONDS)).isTrue();
            callOnEdt(() -> {
                subject.setActiveConversationId(visibleConversationId);
                subject.loadHistory(List.of(Message.user("other chat")));
                subject.setActiveConversationId(originalConversationId);
                subject.loadHistory(List.of(Message.user("ping")));
                JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
                assertThat(assistantBubble(messagesPanel).getFullText()).isEqualTo("first ");
                return null;
            });

            releaseSecondToken.countDown();
            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> {
                JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
                return subject.getHistory().size() == 2
                        && "first second".equals(subject.getHistory().get(1).content())
                        && "first second".equals(assistantBubble(messagesPanel).getFullText());
            }));
        } finally {
            releaseSecondToken.countDown();
        }
    }

    @Test
    @DisplayName("Reloading a streaming conversation during Ollama thinking restores buffered thinking text")
    void loadHistory_whenStreamingThinkingOnly_restoresBufferedThinkingActivity() throws Exception {
        var originalConversationId = UUID.randomUUID();
        var visibleConversationId = UUID.randomUUID();
        var thinkingDelivered = new CountDownLatch(1);
        var releaseAnswer = new CountDownLatch(1);

        runOnEdt(() -> {
            subject.setActiveConversationId(originalConversationId);
            subject.setConversationIdSupplier(() -> originalConversationId);
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

        });

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("ping"));
        invokeOnSend(subject);

        try {
            assertThat(thinkingDelivered.await(2, TimeUnit.SECONDS)).isTrue();
            callOnEdt(() -> {
                subject.setActiveConversationId(visibleConversationId);
                subject.loadHistory(List.of(Message.user("other chat")));
                subject.setActiveConversationId(originalConversationId);
                subject.loadHistory(List.of(Message.user("ping")));
                JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
                ActivityBubble thinkingBubble = findComponents(messagesPanel, ActivityBubble.class).stream()
                        .filter(bubble -> bubble.getFullText().contains("hidden reasoning"))
                        .findFirst()
                        .orElseThrow();
                assertThat(thinkingBubble.isVisible()).isTrue();
                return null;
            });

            releaseAnswer.countDown();
            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> {
                JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
                return subject.getHistory().size() == 2
                        && "visible answer".equals(subject.getHistory().get(1).content())
                        && assistantBubble(messagesPanel).getFullText().equals("visible answer");
            }));
        } finally {
            releaseAnswer.countDown();
        }
    }

    @Test
    @DisplayName("Loading stale history after background stream completion keeps completed assistant response")
    void loadHistory_whenBackgroundStreamCompletedAfterRecordsLoaded_keepsAssistantResponse() throws Exception {
        var originalConversationId = UUID.randomUUID();
        var visibleConversationId = UUID.randomUUID();
        var firstTokenDelivered = new CountDownLatch(1);
        var releaseCompletion = new CountDownLatch(1);
        var persistedAssistant = new CountDownLatch(1);
        var releasePersistenceCallback = new CountDownLatch(1);

        runOnEdt(() -> {
            subject.setActiveConversationId(originalConversationId);
            subject.setConversationIdSupplier(() -> originalConversationId);
            subject.setOnDurableAssistantMessageCompleted(event -> {
                if (event.message().role() == Role.ASSISTANT) {
                    persistedAssistant.countDown();
                    awaitLatch(releasePersistenceCallback);
                }
                return CompletableFuture.failedFuture(new SQLException("forced persistence failure"));
            });
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

        });

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("ping"));
        invokeOnSend(subject);

        try {
            assertThat(firstTokenDelivered.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> {
                subject.setActiveConversationId(visibleConversationId);
                subject.loadHistory(List.of(Message.user("other chat")));
            });

            releaseCompletion.countDown();
            assertThat(persistedAssistant.await(2, TimeUnit.SECONDS)).isTrue();

            var originalUserRecord = new ConversationRepository.MessageRecord(
                    UUID.randomUUID(),
                    1,
                    Message.user("ping")
            );
            runOnEdt(() ->
                    subject.loadConversationHistoryEntries(originalConversationId, List.of(originalUserRecord))
            );
            releasePersistenceCallback.countDown();
            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() ->
                    ((Map<?, ?>) readField(subject, "activeSendJobs")).isEmpty()
                            && ((Map<?, ?>) readField(subject, "activeSessions")).isEmpty()));
            flushEdt();

            assertThat(callOnEdt(subject::getHistory))
                    .hasSize(2)
                    .element(1)
                    .satisfies(message -> {
                        assertThat(message.role()).isEqualTo(Role.ASSISTANT);
                        assertThat(message.content()).isEqualTo("saved answer");
                    });

            runOnEdt(() ->
                    subject.loadConversationHistoryEntries(originalConversationId, List.of(originalUserRecord))
            );
            assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                    .containsExactly("ping", "saved answer");
        } finally {
            releaseCompletion.countDown();
            releasePersistenceCallback.countDown();
        }
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

        runOnEdt(() -> {
            subject.setActiveConversationId(originalConversationId);
            subject.setConversationIdSupplier(() -> originalConversationId);
            subject.setOnDurableAssistantMessageCompleted(event -> {
                if (event.message().role() == Role.ASSISTANT) {
                    persistedAssistant.countDown();
                }
                return CompletableFuture.failedFuture(new SQLException("forced persistence failure"));
            });
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

        });

        runOnEdt(() -> readInputTextArea(subject.getInputBar()).setText("ping"));
        invokeOnSend(subject);

        try {
            assertThat(tokenDelivered.await(2, TimeUnit.SECONDS)).isTrue();
            runOnEdt(() -> {
                subject.setActiveConversationId(visibleConversationId);
                subject.loadHistory(List.of(Message.user("other chat")));
            });

            releaseCompletion.countDown();
            assertThat(persistedAssistant.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(cancellationObserved.get()).isFalse();
            assertThat(callOnEdt(subject::getHistory)).extracting(Message::content).containsExactly("other chat");

            runOnEdt(() -> subject.loadConversationHistoryEntries(originalConversationId, List.of(
                    new ConversationRepository.MessageRecord(
                            UUID.randomUUID(),
                            1,
                            Message.user("ping")
                    )
            )));
            assertThat(callOnEdt(subject::getHistory)).extracting(Message::content)
                    .containsExactly("ping", "background answer");
        } finally {
            releaseCompletion.countDown();
        }
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

        };
    }

    private static ProviderService providerReturning(String responseText, AtomicInteger calls) {
        ProviderService delegate = immediateProvider(responseText);
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
                calls.incrementAndGet();
                delegate.streamCompletion(
                        history,
                        reasoningLevel,
                        onToken,
                        onThinkingToken,
                        onComplete,
                        onError,
                        isCancelled
                );
            }
        };
    }

    private static ProviderService providerWithApiKey(String apiKey) {
        ProviderService delegate = immediateProvider("ok");
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
                delegate.streamCompletion(
                        history,
                        reasoningLevel,
                        onToken,
                        onThinkingToken,
                        onComplete,
                        onError,
                        isCancelled
                );
            }


            @Override
            public String apiKey() {
                return apiKey;
            }
        };
    }

    private static void clickLabel(JLabel label) {
        MouseEvent event = new MouseEvent(
                label,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                2,
                2,
                1,
                false
        );
        Arrays.stream(label.getMouseListeners()).forEach(listener -> listener.mouseClicked(event));
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

    private void configureTogetherAgent(AtomicInteger agentTurns) throws Exception {
        configureAgent("Together", "Qwen/Qwen3.5-9B", "https://api.together.ai/v1", agentTurns);
    }

    private void configureAgent(
            String providerName,
            String modelId,
            String baseUrl,
            AtomicInteger agentTurns
    ) throws Exception {
        ProviderModelCacheService cacheService = (ProviderModelCacheService) readField(subject, "modelCacheService");
        long scope = cacheService.nextScopeVersion();
        if ("Together".equals(providerName)) {
            cacheService.synchronizeScope(providerName, baseUrl, scope);
            ProviderModelCacheService.RefreshAttempt attempt = cacheService.tryBeginRefreshIfNeeded(
                    providerName,
                    baseUrl,
                    Duration.ZERO
            ).orElseThrow();
            assertThat(cacheService.update(attempt, List.of(modelId))).isTrue();
        }
        var provider = new ProviderRegistry.ProviderDef(
                providerName,
                "Together".equals(providerName) ? "TOGETHER_API_KEY" : "OPENAI_API_KEY",
                baseUrl,
                baseUrl,
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                ignored -> immediateProvider("unused"),
                List::of
        );
        var orchestrator = new AgentOrchestrator(new AgentProviderAdapterFactory(attachmentSupport) {
            @Override
            public AgentProviderAdapter create(
                    String ignoredProviderName,
                    String ignoredModelId,
                    String ignoredBaseUrl,
                    String ignoredApiKey,
                    ProviderService providerService,
                    String agentSystemPromptAppend
            ) {
                return (request, callbacks) -> {
                    agentTurns.incrementAndGet();
                    callbacks.onToken().accept("agent answer");
                    return AgentTurnResult.complete();
                };
            }
        }, new LocalToolRuntime());
        Path projectRoot = Files.createDirectories(tempDir.resolve("agent-project"));
        runOnEdt(() -> {
            subject.setAgentOrchestratorForTests(orchestrator);
            setField(subject, "providerMap", Map.of(providerName, provider));
            setField(subject, "selectedProviderName", providerName);
            setField(subject, "selectedModelId", modelId);
            setField(subject, "installedProviderScope", scope);
            setField(subject, "nativeWebSearchOutcome", NativeWebSearchOutcome.UNSUPPORTED);
            subject.getInputBar().setAgentModeAvailable(true);
            subject.getInputBar().setAgentProjectRoot(projectRoot);
            subject.getInputBar().setAgentModeEnabled(true);
        });
    }

    @SuppressWarnings("unchecked")
    private static void setCurrentProvider(ChatPanel chatPanel, ProviderService provider) throws Exception {
        runOnEdt(() -> {
            String providerName = StringUtils.defaultIfBlank(
                    (String) readField(chatPanel, "selectedProviderName"),
                    "OpenAI"
            );
            String modelId = StringUtils.defaultIfBlank(
                    (String) readField(chatPanel, "selectedModelId"),
                    "gpt-5-mini"
            );
            setField(chatPanel, "selectedProviderName", providerName);
            setField(chatPanel, "selectedModelId", modelId);
            Map<String, ProviderRegistry.ProviderDef> providers =
                    (Map<String, ProviderRegistry.ProviderDef>) readField(chatPanel, "providerMap");
            ProviderRegistry.ProviderDef existing = providers.get(providerName);
            ProviderRegistry.ProviderDef replacement = new ProviderRegistry.ProviderDef(
                    providerName,
                    existing == null ? null : existing.envVar(),
                    existing == null ? null : existing.baseUrl(),
                    existing == null ? null : existing.defaultBaseUrl(),
                    existing == null ? List.of(modelId) : existing.seedModels(),
                    existing == null ? ProviderCapabilities.chatAndModels() : existing.capabilities(),
                    ignored -> provider,
                    existing == null ? List::of : existing.fetcher()
            );
            Map<String, ProviderRegistry.ProviderDef> updated = new LinkedHashMap<>(providers);
            updated.put(providerName, replacement);
            setField(chatPanel, "providerMap", Map.copyOf(updated));
            setField(chatPanel, "installedProviderScope", 1L);
            setField(chatPanel, "nativeWebSearchOutcome", NativeWebSearchOutcome.OPTIONAL);
        });
    }

    private static boolean readConversationBusy(ChatPanel chatPanel) throws Exception {
        Field field = InputBar.class.getDeclaredField("conversationBusy");
        field.setAccessible(true);
        return field.getBoolean(chatPanel.getInputBar());
    }

    private static Object readInputBarField(InputBar inputBar, String fieldName) throws Exception {
        Field field = InputBar.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(inputBar);
    }

    private static JComponent readActivityContentPanel(ActivityBubble activityBubble) throws Exception {
        Field field = ActivityBubble.class.getDeclaredField("contentPanel");
        field.setAccessible(true);
        return (JComponent) field.get(activityBubble);
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

    private static void invokeApplyProviderModels(ChatPanel chatPanel, List<ProviderRegistry.ProviderDef> providers) throws Exception {
        ProviderModelCacheService cacheService = (ProviderModelCacheService) readField(chatPanel, "modelCacheService");
        invokeApplyProviderModels(chatPanel, providers, cacheService.nextScopeVersion());
    }

    private static void invokeApplyProviderModels(
            ChatPanel chatPanel,
            List<ProviderRegistry.ProviderDef> providers,
            long scopeVersion
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod("applyProviderModels", List.class, long.class);
        method.setAccessible(true);
        method.invoke(chatPanel, providers, scopeVersion);
    }

    private static void invokePrepareProviderModels(
            ChatPanel chatPanel,
            List<ProviderRegistry.ProviderDef> providers,
            long scopeVersion
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod("prepareProviderModels", List.class, long.class);
        method.setAccessible(true);
        method.invoke(chatPanel, providers, scopeVersion);
    }

    private static void initializeProviderModels(ChatPanel chatPanel) throws Exception {
        ProviderRegistry registry = (ProviderRegistry) readField(chatPanel, "providerRegistry");
        ProviderModelCacheService cacheService = (ProviderModelCacheService) readField(chatPanel, "modelCacheService");
        long scopeVersion = cacheService.nextScopeVersion();
        setField(chatPanel, "providerScopeVersion", scopeVersion);
        List<ProviderRegistry.ProviderDef> providers = registry.availableProviders();
        invokePrepareProviderModels(chatPanel, providers, scopeVersion);
        Method method = ChatPanel.class.getDeclaredMethod("applyProviderModels", List.class, long.class);
        method.setAccessible(true);
        method.invoke(chatPanel, providers, scopeVersion);
    }

    private static boolean invokeUpdateProviderModelsFromPopup(
            ChatPanel chatPanel,
            List<ProviderRegistry.ProviderDef> providers
    ) throws Exception {
        ProviderModelCacheService cacheService = (ProviderModelCacheService) readField(chatPanel, "modelCacheService");
        return invokeUpdateProviderModelsFromPopup(chatPanel, providers, cacheService.nextScopeVersion());
    }

    private static boolean invokeUpdateProviderModelsFromPopup(
            ChatPanel chatPanel,
            List<ProviderRegistry.ProviderDef> providers,
            long scopeVersion
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod("updateProviderModelsFromPopup", List.class, long.class);
        method.setAccessible(true);
        return (boolean) method.invoke(chatPanel, providers, scopeVersion);
    }

    private static void installFailingProvider(ChatPanel chatPanel) throws Exception {
        ProviderRegistry.ProviderDef provider = new ProviderRegistry.ProviderDef(
                "Failing Provider",
                null,
                null,
                null,
                List.of("failing-model"),
                ProviderCapabilities.chatAndModels(),
                model -> {
                    throw new IllegalStateException("credential unavailable");
                },
                List::of
        );
        runOnEdt(() -> {
            setField(chatPanel, "providerMap", Map.of(provider.name(), provider));
            setField(chatPanel, "installedProviderScope", 1L);
            chatPanel.setSelectedModel("Failing Provider > failing-model");
        });
    }

    private static void installBlockingProvider(
            ChatPanel chatPanel,
            CountDownLatch admissionStarted,
            CountDownLatch releaseAdmission
    ) throws Exception {
        ProviderRegistry.ProviderDef provider = new ProviderRegistry.ProviderDef(
                "Blocking Provider",
                null,
                null,
                null,
                List.of("blocking-model"),
                ProviderCapabilities.chatAndModels(),
                model -> {
                    admissionStarted.countDown();
                    try {
                        releaseAdmission.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("provider admission interrupted", e);
                    }
                    return immediateProvider("new answer");
                },
                List::of
        );
        runOnEdt(() -> {
            setField(chatPanel, "providerMap", Map.of(provider.name(), provider));
            setField(chatPanel, "installedProviderScope", 1L);
            chatPanel.setSelectedModel("Blocking Provider > blocking-model");
        });
    }

    private static ProviderRegistry.ProviderDef testProviderDefinition(
            String name,
            ProviderCapabilities capabilities
    ) {
        return new ProviderRegistry.ProviderDef(
                name,
                null,
                null,
                null,
                List.of("test-model"),
                capabilities,
                model -> immediateProvider("pong"),
                List::of
        );
    }

    private static ProviderRegistry.ProviderDef providerDef(String baseUrl) {
        return new ProviderRegistry.ProviderDef(
                "OpenAI",
                "OPENAI_API_KEY",
                baseUrl,
                baseUrl,
                List.of("seed-model"),
                ProviderCapabilities.chatAndModels(),
                model -> immediateProvider("ok"),
                List::of
        );
    }

    private static void assertSingleTypingEntryIsLast(List<ConversationEntry> entries) {
        assertThat(entries).filteredOn(entry -> entry.kind() == ConversationEntryKind.TYPING)
                .singleElement()
                .isSameAs(entries.getLast());
    }

    private void installSystemWebViewCapture(AtomicReference<List<ConversationEntry>> transcript) throws Exception {
        SystemWebView systemWebView = mock(SystemWebView.class);
        doAnswer(invocation -> {
            assertThat(SwingUtilities.isEventDispatchThread()).isTrue();
            List<ConversationEntry> entries = invocation.getArgument(0);
            transcript.set(List.copyOf(entries));
            return null;
        }).when(systemWebView).setTranscript(
                anyList(),
                any(RenderMode.class),
                anyBoolean(),
                anyBoolean(),
                anyBoolean(),
                anyBoolean(),
                anySet(),
                anyInt()
        );
        runOnEdt(() -> {
            setField(subject, "webViewEngine", WebViewEngine.SYSTEM);
            setField(subject, "systemWebView", systemWebView);
        });
    }

    private ChatPanel newChatPanel(
            ProviderModelCacheService cacheService,
            ModelFavoritesService modelFavoritesService
    ) {
        return new ChatPanel(
                cacheService,
                modelFavoritesService,
                new ChatMessageViewFactory(),
                WebViewEngine.JEDITOR_PANE,
                TextToSpeechService.disabled(),
                SpeechToTextService.disabled(),
                providerRegistry,
                copilotAuthResolver,
                codexAuthResolver,
                credentialResolver,
                storagePaths,
                attachmentSupport,
                McpRunProvider.disabled(),
                McpApprovalHandler.denyAll()
        );
    }

    private ProviderModelCacheService modelCacheService(Path configHome) {
        var cacheService = new ProviderModelCacheService(new ProviderModelCache(StoragePaths.ofConfigHome(configHome)));
        cacheService.primeFromDisk(providerRegistry.availableProviders().stream()
                .map(ProviderRegistry.ProviderDef::name)
                .toList());
        return cacheService;
    }

    private <T> CompletableFuture<T> controlledFuture() {
        var future = new CompletableFuture<T>();
        controlledFutures.add(future);
        return future;
    }

    private static void updateModels(
            ProviderModelCacheService cacheService,
            String providerName,
            String scope,
            List<String> models
    ) {
        ProviderModelCacheService.RefreshAttempt attempt = cacheService
                .tryBeginRefreshIfNeeded(providerName, scope, Duration.ZERO)
                .orElseThrow();
        assertThat(cacheService.update(attempt, models)).isTrue();
    }

    private static void runOnEdt(ThrowingAction action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }

        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static void invokePersistAssistantResponse(
            ChatPanel chatPanel,
            StreamingSession session,
            SendJob sendJob
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod(
                "persistAssistantResponse",
                StreamingSession.class,
                SendJob.class
        );
        method.setAccessible(true);
        runOnEdt(() -> method.invoke(chatPanel, session, sendJob));
    }

    private static SpeechToTextService.Callbacks invokeSpeechToTextCallbacks(
            ChatPanel chatPanel,
            long uiGeneration
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod("speechToTextCallbacks", long.class);
        method.setAccessible(true);
        return (SpeechToTextService.Callbacks) method.invoke(chatPanel, uiGeneration);
    }

    private StreamingSession initializedConsultedSourceSession(long sessionId, String query) throws Exception {
        StreamingSession session = new StreamingSession(sessionId, UUID.randomUUID(), null);
        invokeInitializeConsultedSourceActivity(subject, session);
        invokeHandleAssistantWebSearchQuery(subject, session, query);
        return session;
    }

    private static void invokeInitializeConsultedSourceActivity(
            ChatPanel chatPanel,
            StreamingSession session
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod(
                "initializeConsultedSourceActivity",
                StreamingSession.class
        );
        method.setAccessible(true);
        method.invoke(chatPanel, session);
    }

    @SuppressWarnings("unchecked")
    private static List<Message> invokePrepareNativeWebSearchActivity(
            ChatPanel chatPanel,
            SendJob sendJob,
            StreamingSession session,
            List<Message> history
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod(
                "prepareNativeWebSearchActivity",
                SendJob.class,
                StreamingSession.class,
                List.class,
                BooleanSupplier.class
        );
        method.setAccessible(true);
        return (List<Message>) method.invoke(chatPanel, sendJob, session, history, (BooleanSupplier) () -> false);
    }

    private static void invokeHandleAssistantToken(
            ChatPanel chatPanel,
            StreamingSession session,
            SendJob sendJob,
            String token
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod(
                "handleAssistantToken",
                StreamingSession.class,
                SendJob.class,
                String.class
        );
        method.setAccessible(true);
        method.invoke(chatPanel, session, sendJob, token);
    }

    private static void invokeHandleAssistantWebSearchQuery(
            ChatPanel chatPanel,
            StreamingSession session,
            String query
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod(
                "handleAssistantWebSearchQuery",
                StreamingSession.class,
                String.class
        );
        method.setAccessible(true);
        method.invoke(chatPanel, session, query);
    }

    private static void invokeHandleAssistantWebSearchSource(
            ChatPanel chatPanel,
            StreamingSession session,
            WebSearchSource source
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod(
                "handleAssistantWebSearchSource",
                StreamingSession.class,
                WebSearchSource.class
        );
        method.setAccessible(true);
        method.invoke(chatPanel, session, source);
    }

    private static void invokeFinalizeConsultedSourceActivity(ChatPanel chatPanel, StreamingSession session) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod("finalizeConsultedSourceActivity", StreamingSession.class);
        method.setAccessible(true);
        method.invoke(chatPanel, session);
    }

    private static Object invokePrepareAssistantResponse(
            ChatPanel chatPanel,
            StreamingSession session,
            SendJob sendJob
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod(
                "prepareAssistantResponse",
                StreamingSession.class,
                SendJob.class
        );
        method.setAccessible(true);
        return method.invoke(chatPanel, session, sendJob);
    }

    private static ConversationHistoryEntry preparedAssistantEntry(Object preparedResponse) throws Exception {
        Method method = preparedResponse.getClass().getDeclaredMethod("entry");
        method.setAccessible(true);
        return (ConversationHistoryEntry) method.invoke(preparedResponse);
    }

    private static void appendAssistantResponse(StreamingSession session, String text) {
        synchronized (session.response) {
            session.response.append(text);
        }
        synchronized (session.responseParts) {
            session.responseParts.add(new TextPart(text));
        }
    }

    private static void invokeAdmitProvider(ChatPanel chatPanel, SendJob sendJob) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod("admitProvider", SendJob.class);
        method.setAccessible(true);
        method.invoke(chatPanel, sendJob);
    }

    private static boolean invokeNativeWebSearchEnabled(ChatPanel chatPanel, SendJob sendJob) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod("nativeWebSearchEnabled", SendJob.class);
        method.setAccessible(true);
        return (boolean) method.invoke(chatPanel, sendJob);
    }

    private static SendJob deepSeekWebSearchSendJob(UUID conversationId) {
        return webSearchSendJob(
                2L,
                conversationId,
                "DeepSeek",
                "deepseek-v4-pro",
                "https://api.deepseek.com"
        );
    }

    private static SendJob webSearchSendJob(long jobId, UUID conversationId, String providerName, String modelId, String baseUrl) {
        ProviderCapabilities capabilities = ProviderCapabilities.chatAndModels();
        ProviderRegistry.ProviderDef providerDefinition = testProviderDefinition(providerName, capabilities);
        return new SendJob(
                jobId,
                conversationId,
                new SendRuntimeSnapshot(
                        new ProviderRegistry.ProviderDef(
                                providerDefinition.name(),
                                providerDefinition.envVar(),
                                baseUrl,
                                providerDefinition.defaultBaseUrl(),
                                providerDefinition.seedModels(),
                                capabilities,
                                providerDefinition.factory(),
                                providerDefinition.fetcher()
                        ),
                        modelId,
                        NativeWebSearchOutcome.OPTIONAL
                ),
                List.of(Message.user("Search")),
                ReasoningLevel.OFF,
                true,
                false,
                null,
                ""
        );
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

    @SuppressWarnings("unchecked")
    private static Map<UUID, ?> pendingAssistantRecoveryMap(ChatPanel chatPanel) throws Exception {
        return (Map<UUID, ?>) readField(chatPanel, "pendingCompletedAssistantRecoveries");
    }

    private static void queuePendingAssistantRecovery(
            ChatPanel chatPanel,
            UUID conversationId,
            ConversationHistoryEntry entry
    ) throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod(
                "queuePendingAssistantRecovery",
                UUID.class,
                ConversationHistoryEntry.class
        );
        method.setAccessible(true);
        runOnEdt(() -> method.invoke(chatPanel, conversationId, entry));
    }

    private static List<ConversationHistoryEntry> pendingAssistantRecoveryEntries(
            ChatPanel chatPanel,
            UUID conversationId
    ) throws Exception {
        return callOnEdt(() -> {
            Object value = pendingAssistantRecoveryMap(chatPanel).get(conversationId);
            if (!(value instanceof List<?> recoveries)) {
                return emptyList();
            }
            List<ConversationHistoryEntry> entries = new ArrayList<>();
            for (Object recovery : recoveries) {
                Field field = recovery.getClass().getDeclaredField("entry");
                field.setAccessible(true);
                entries.add((ConversationHistoryEntry) field.get(recovery));
            }
            return List.copyOf(entries);
        });
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

    private void loadPersistedHistory(Message... messages) {
        var ordinal = new AtomicInteger();
        List<ConversationRepository.MessageRecord> records = List.of(messages).stream()
                .map(message -> new ConversationRepository.MessageRecord(
                        UUID.randomUUID(),
                        ordinal.incrementAndGet(),
                        message
                ))
                .toList();
        subject.loadConversationHistoryEntries(UUID.randomUUID(), records);
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

    @SuppressWarnings("unchecked")
    private void awaitCurrentSendPreparation() throws Exception {
        Thread worker = callOnEdt(() -> ((Map<Long, SendJob>) readField(subject, "activeSendJobs"))
                .values()
                .iterator()
                .next()
                .worker);
        worker.join();
        flushEdt();
    }

    private void awaitReadAloudAvailability() throws Exception {
        List<CompletableFuture<?>> futures = callOnEdt(() -> {
            Map<?, ?> availability = (Map<?, ?>) readField(subject, "readAloudAvailability");
            List<CompletableFuture<?>> pending = new ArrayList<>();
            availability.values().stream()
                    .filter(CompletableFuture.class::isInstance)
                    .map(CompletableFuture.class::cast)
                    .forEach(pending::add);
            return pending;
        });
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        flushEdt();
    }

    @SuppressWarnings("unchecked")
    private Set<Integer> readAloudMessageIndexes() throws Exception {
        Method method = ChatPanel.class.getDeclaredMethod("readAloudMessageIndexes");
        method.setAccessible(true);
        return callOnEdt(() -> (Set<Integer>) method.invoke(subject));
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            // flush pending UI tasks
        });
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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

    private void assertRenderedThinking(String answer, String thinking) throws Exception {
        callOnEdt(() -> {
            Message assistant = subject.getHistory().get(1);
            assertThat(assistant.content()).isEqualTo(answer);
            assertThat(assistant.meta().assistantThinking()).contains(thinking);
            JPanel messagesPanel = (JPanel) readField(subject, "messagesPanel");
            List<ActivityBubble> thinkingBubbles = findComponents(messagesPanel, ActivityBubble.class);
            assertThat(thinkingBubbles).hasSize(1);
            assertThat(thinkingBubbles.getFirst().getFullText()).contains(thinking);
            assertThat(messageRowIndex(messagesPanel, thinkingBubbles.getFirst()))
                    .isLessThan(messageRowIndex(messagesPanel, assistantBubble(messagesPanel)));
            return null;
        });
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

    private void disposePanel(ChatPanel panel) throws Exception {
        callOnEdt(panel::cancelAllRequestsAsync).join();
        runOnEdt(() -> {
            panel.disposeViewResources();
            panel.removeNotify();
        });
        runOnEdt(() -> {});
    }

    private ChatPanel chatPanelWithTextToSpeech(TextToSpeechService textToSpeechService) throws Exception {
        disposePanel(subject);
        testTextToSpeechServices.add(textToSpeechService);
        var panelRef = new AtomicReference<ChatPanel>();
        ProviderModelCacheService cacheService = modelCacheService(tempDir.resolve("tts-subject-cache"));
        runOnEdt(() -> panelRef.set(new ChatPanel(
                cacheService,
                ModelFavoritesService.createInMemory(),
                new ChatMessageViewFactory(),
                WebViewEngine.JEDITOR_PANE,
                textToSpeechService,
                SpeechToTextService.disabled(),
                providerRegistry,
                copilotAuthResolver,
                codexAuthResolver,
                credentialResolver,
                storagePaths,
                attachmentSupport,
                McpRunProvider.disabled(),
                McpApprovalHandler.denyAll()
        )));
        return panelRef.get();
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

    private static final class BlockingModelCacheService extends ProviderModelCacheService {
        private final AtomicBoolean blockNextLookup = new AtomicBoolean();
        private final CountDownLatch lookupStarted = new CountDownLatch(1);
        private final CountDownLatch lookupReleased = new CountDownLatch(1);

        private BlockingModelCacheService(Path configHome) {
            super(new ProviderModelCache(StoragePaths.ofConfigHome(configHome)));
        }

        @Override
        public Optional<List<String>> findUsableModels(String providerName, String scope) {
            if (blockNextLookup.compareAndSet(true, false)) {
                lookupStarted.countDown();
                try {
                    if (!lookupReleased.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release model lookup");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Model lookup was interrupted", e);
                }
            }
            return super.findUsableModels(providerName, scope);
        }

        private void blockNextLookup() {
            blockNextLookup.set(true);
        }

        private boolean awaitLookupStarted() throws InterruptedException {
            return lookupStarted.await(2, TimeUnit.SECONDS);
        }

        private void releaseLookup() {
            lookupReleased.countDown();
        }
    }

    private static final class RecordingTextToSpeechService extends TextToSpeechService {
        private String requestedText = "";
        private String requestedKey = "";
        private String activeMessageKey = "";
        private int stopCount;
        private boolean disposed;

        private RecordingTextToSpeechService(Path settingsFile) {
            super(
                    new TextToSpeechSettings(
                            new SettingsRepository(settingsFile),
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
            stopCount++;
        }

        @Override
        public void dispose() {
            disposed = true;
            super.dispose();
        }

        private boolean disposed() {
            return disposed;
        }

        private int stopCount() {
            return stopCount;
        }

        private String requestedText() {
            return requestedText;
        }

        private String requestedKey() {
            return requestedKey;
        }
    }
}
