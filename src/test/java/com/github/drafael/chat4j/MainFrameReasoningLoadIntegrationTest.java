package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.ChatPanel;
import com.github.drafael.chat4j.chat.agent.McpApprovalHandler;
import com.github.drafael.chat4j.chat.message.ChatMessageViewFactory;
import com.github.drafael.chat4j.chat.webview.WebViewEngine;
import com.github.drafael.chat4j.mcp.McpRunProvider;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.conversation.ConversationHistoryEntry;
import com.github.drafael.chat4j.persistence.conversation.ConversationLoadApplyCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationLoadApplyDispatchCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationLoadCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationLoadResultPlanner;
import com.github.drafael.chat4j.persistence.conversation.ConversationPersistenceCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.persistence.db.DatabaseBootstrap;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCache;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.settings.AgentModeSettings;
import com.github.drafael.chat4j.settings.RenderModeToggleSelectionSyncCoordinator;
import com.github.drafael.chat4j.sidebar.SidebarPanel;
import com.github.drafael.chat4j.stt.SpeechToTextService;
import com.github.drafael.chat4j.tts.TextToSpeechService;
import com.sun.net.httpserver.HttpServer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MainFrameReasoningLoadIntegrationTest {

    private static final String OUTGOING_MODEL = "claude-opus-5-5";
    private static final String INCOMING_MODEL = "claude-sonnet-4-6";

    @TempDir
    private Path tempDir;
    private MainFrame subject;
    private ChatPanel panel;
    private ConversationRepository repository;
    private ConversationPersistenceCoordinator persistence;
    private ConversationLoadCoordinator loader;
    private JdbcDataSource dataSource;
    private ProviderRegistry.ProviderDef provider;
    private ProviderModelCacheService cache;
    private UUID outgoing;
    private UUID incoming;

    @BeforeEach
    void setUp() throws Exception {
        var paths = StoragePaths.ofConfigHome(tempDir);
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:file:%s".formatted(tempDir.resolve("history")));
        dataSource.setUser("sa");
        new DatabaseBootstrap(paths, dataSource).init();
        repository = new ConversationRepository(dataSource);
        persistence = new ConversationPersistenceCoordinator(repository);
        loader = new ConversationLoadCoordinator(repository, persistence);
        outgoing = createConversation(OUTGOING_MODEL, ReasoningLevel.EXTRA_HIGH);
        incoming = createConversation(INCOMING_MODEL, ReasoningLevel.HIGH);
        provider = new ProviderRegistry.ProviderDef(
                "Anthropic", null, null, null, List.of(OUTGOING_MODEL, INCOMING_MODEL),
                ProviderCapabilities.chatAndModels(), model -> {
                    throw new AssertionError("Restoring a conversation must not invoke a provider");
                }, List::of
        );
        var registry = mock(ProviderRegistry.class);
        when(registry.allProviders()).thenReturn(List.of(provider));
        cache = new ProviderModelCacheService(new ProviderModelCache(paths));
        var attachments = new ProviderAttachmentSupport(Files.createDirectories(paths.attachmentsDirectory()));
        onEdt(() -> {
            panel = new ChatPanel(
                    cache, ModelFavoritesService.createInMemory(), new ChatMessageViewFactory(), WebViewEngine.JEDITOR_PANE,
                    TextToSpeechService.disabled(), SpeechToTextService.disabled(), registry,
                    mock(CopilotAuthResolver.class), mock(CodexAuthResolver.class), mock(CredentialResolver.class),
                    paths, attachments, McpRunProvider.disabled(), McpApprovalHandler.denyAll()
            );
            // Avoid a native JFrame; execute its real load/persistence methods with real collaborators.
            subject = mock(MainFrame.class, CALLS_REAL_METHODS);
            setField(subject, "chatPanel", panel);
            setField(subject, "conversationState", new MainFrameConversationState());
            setField(subject, "conversationPersistenceCoordinator", persistence);
            setField(subject, "conversationRuntimeSettingsCoordinator", new MainFrameConversationRuntimeSettingsCoordinator(
                    persistence, mock(AgentModeSettings.class)
            ));
            setField(subject, "conversationLoadCoordinator", loader);
            setField(subject, "conversationLoadApplyDispatchCoordinator", new ConversationLoadApplyDispatchCoordinator(
                    new ConversationLoadResultPlanner(loader::isCurrentRequest), new ConversationLoadApplyCoordinator()
            ));
            setField(subject, "sidebarPanel", mock(SidebarPanel.class));
            setField(subject, "clearedConversationIds", new HashSet<UUID>());
            setField(subject, "renderModeState", new MainFrameRenderModeState());
            setField(subject, "previewMenuState", new MainFramePreviewMenuState());
            setField(subject, "renderModeToggleSelectionSyncCoordinator", new RenderModeToggleSelectionSyncCoordinator());
            setField(panel, "providerMap", Map.of(provider.name(), provider));
            setField(panel, "installedProviderScope", 1L);
            panel.getInputBar().addReasoningLevelListener(level -> {
                try {
                    invokeFrame("onReasoningLevelChanged", new Class<?>[]{ReasoningLevel.class}, level);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        });
        load(outgoing);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (panel != null) {
                onEdt(panel::removeNotify);
            }
        } finally {
            if (persistence != null) {
                persistence.sealWithoutFinal().get(5, TimeUnit.SECONDS);
            }
            onEdt(() -> {});
        }
    }

    @ParameterizedTest
    @CsvSource({"false,high,HIGH", "true,high,HIGH", "false,ultra,MEDIUM", "true,ultra,MEDIUM",
            "false,invalid,MEDIUM", "true,invalid,MEDIUM", "false,off,OFF", "true,off,OFF"})
    @DisplayName("Real conversation loads preserve the outgoing setting and persist only resolved corrections across reloads")
    void applyLoadedConversation_savedChoiceAndDiscoveryOrder_persistsOnlyFinalIncomingSelection(
            boolean pending, String saved, ReasoningLevel expected
    ) throws Exception {
        try (var connection = dataSource.getConnection();
             var update = connection.prepareStatement("UPDATE conversations SET reasoning_level = ? WHERE id = ?")) {
            update.setString(1, saved);
            update.setObject(2, incoming);
            update.executeUpdate();
        }
        long outgoingRevision = persistence.revision(outgoing);
        if (pending) {
            onEdt(() -> setField(panel, "providerMap", emptyMap()));
        }
        load(incoming);
        if (pending) {
            onEdt(() -> assertThat(panel.getInputBar().isReasoningPending()).isTrue());
            persistence.fenceRevision(incoming).get(5, TimeUnit.SECONDS);
            assertThat(repository.loadConversation(incoming).orElseThrow().conversation().reasoningLevel()).isEqualTo(saved);
            assertThat(persistence.revision(incoming)).isZero();
            long scope = cache.nextScopeVersion();
            cache.synchronizeScope(provider.name(), provider.baseUrl(), scope);
            var refresh = cache.tryBeginRefreshIfNeeded(provider.name(), "", Duration.ZERO).orElseThrow();
            assertThat(cache.update(refresh, provider.seedModels())).isTrue();
            onEdt(() -> {
                Method install = ChatPanel.class.getDeclaredMethod("updateProviderModelsFromPopup", List.class, long.class);
                install.setAccessible(true);
                assertThat(install.invoke(panel, List.of(provider), scope)).isEqualTo(true);
            });
        }
        persistence.fenceRevision(incoming).get(5, TimeUnit.SECONDS);
        onEdt(() -> {
            assertThat(panel.getInputBar().getReasoningLevel()).isEqualTo(expected);
            assertThat(panel.isConversationRuntimeLoadStaged()).isFalse();
            assertThat(panel.getSelectedModel()).isEqualTo("Anthropic > %s".formatted(INCOMING_MODEL));
            Method history = ChatPanel.class.getDeclaredMethod("getHistory");
            history.setAccessible(true);
            assertThat((List<?>) history.invoke(panel)).hasSize(1);
        });
        assertThat(repository.loadConversation(outgoing).orElseThrow().conversation().reasoningLevel()).isEqualTo("extra_high");
        assertThat(persistence.revision(outgoing)).isEqualTo(outgoingRevision);
        assertThat(repository.loadConversation(incoming).orElseThrow().conversation().reasoningLevel())
                .isEqualTo(expected.toSettingValue());
        long corrections = saved.equals("high") || saved.equals("off") ? 0 : 1;
        assertThat(persistence.revision(incoming)).isEqualTo(corrections);

        load(outgoing);
        onEdt(() -> assertThat(panel.getInputBar().getReasoningLevel()).isEqualTo(ReasoningLevel.EXTRA_HIGH));
        load(incoming);
        onEdt(() -> assertThat(panel.getInputBar().getReasoningLevel()).isEqualTo(expected));
        persistence.fenceRevision(incoming).get(5, TimeUnit.SECONDS);
        assertThat(persistence.revision(incoming)).isEqualTo(corrections);
    }

    @ParameterizedTest
    @CsvSource({"Ollama, ultra, ULTRA, 0", "LM Studio, ultra, ULTRA, 0", "Ollama, high, LOW, 1", "LM Studio, high, LOW, 1"})
    @DisplayName("Unrecognized local metadata never rewrites a saved choice and Retry persists only a proven correction")
    void applyLoadedConversation_unknownLocalChoices_preservesDatabaseUntilRetry(
            String providerName, String secondChoice, ReasoningLevel expected, long corrections
    ) throws Exception {
        var choices = new AtomicReference<>("[\"future\"]");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String json = providerName.equals("Ollama")
                    ? "{\"thinking\":{\"values\":%s,\"default\":\"low\"},\"capabilities\":[\"thinking\"]}".formatted(choices.get())
                    : "{\"models\":[{\"key\":\"custom-model\",\"capabilities\":{\"reasoning\":{\"allowed_options\":%s,\"default\":\"low\"}}}]}".formatted(choices.get());
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());
            var localProvider = new ProviderRegistry.ProviderDef(providerName, null, baseUrl, baseUrl, List.of("custom-model"),
                    ProviderCapabilities.chatAndModels(), model -> {
                        throw new AssertionError("Loading metadata must not send a completion");
                    }, List::of);
            UUID localConversation = createConversation(providerName, "custom-model", ReasoningLevel.ULTRA);
            onEdt(() -> setField(panel, "providerMap", Map.of(provider.name(), provider, providerName, localProvider)));
            load(localConversation);
            awaitCapabilityRefresh();
            persistence.fenceRevision(localConversation).get(5, TimeUnit.SECONDS);
            onEdt(() -> {
                assertThat(panel.getInputBar().isReasoningPending()).isTrue();
                assertThat(panel.getSelectedModel()).isEqualTo("%s > custom-model".formatted(providerName));
            });
            assertThat(repository.loadConversation(localConversation).orElseThrow().conversation().reasoningLevel()).isEqualTo("ultra");
            assertThat(persistence.revision(localConversation)).isZero();
            assertThat(repository.loadConversation(outgoing).orElseThrow().conversation().reasoningLevel()).isEqualTo("extra_high");

            choices.set("[\"low\",\"%s\",\"future\"]".formatted(secondChoice));
            onEdt(() -> {
                Field retry = ChatPanel.class.getDeclaredField("pendingReasoningRetry");
                retry.setAccessible(true);
                Runnable action = (Runnable) retry.get(panel);
                assertThat(action).isNotNull();
                action.run();
            });
            awaitCapabilityRefresh();
            persistence.fenceRevision(localConversation).get(5, TimeUnit.SECONDS);
            onEdt(() -> assertThat(panel.getInputBar().getReasoningLevel()).isEqualTo(expected));
            assertThat(repository.loadConversation(localConversation).orElseThrow().conversation().reasoningLevel())
                    .isEqualTo(expected.toSettingValue());
            assertThat(persistence.revision(localConversation)).isEqualTo(corrections);

            load(outgoing);
            load(localConversation);
            awaitCapabilityRefresh();
            persistence.fenceRevision(localConversation).get(5, TimeUnit.SECONDS);
            onEdt(() -> assertThat(panel.getInputBar().getReasoningLevel()).isEqualTo(expected));
            assertThat(persistence.revision(localConversation)).isEqualTo(corrections);
        } finally {
            try {
                awaitCapabilityRefresh();
            } finally {
                server.stop(0);
                onEdt(() -> {});
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"high, HIGH, 0", "minimal, MINIMAL, 0", "off, MINIMAL, 1", "ultra, MINIMAL, 1"})
    @DisplayName("Gemini image conversation loads preserve supported choices and persist Minimal rather than Off as fallback")
    void applyLoadedConversation_googleImage_persistsExactPolicy(String saved, ReasoningLevel expected, long corrections) throws Exception {
        String model = "gemini-3.1-flash-image";
        var google = new ProviderRegistry.ProviderDef("Google AI", null, null, null, List.of(model),
                ProviderCapabilities.chatAndModels(), ignored -> {
                    throw new AssertionError("Loading must not send a completion");
                }, List::of);
        UUID imageConversation = createConversation("Google AI", model, ReasoningLevel.fromSettingValue(saved, null));
        onEdt(() -> setField(panel, "providerMap", Map.of(provider.name(), provider, google.name(), google)));
        load(imageConversation);
        persistence.fenceRevision(imageConversation).get(5, TimeUnit.SECONDS);
        onEdt(() -> assertThat(panel.getInputBar().getReasoningLevel()).isEqualTo(expected));
        assertThat(repository.loadConversation(imageConversation).orElseThrow().conversation().reasoningLevel())
                .isEqualTo(expected.toSettingValue());
        assertThat(persistence.revision(imageConversation)).isEqualTo(corrections);
        assertThat(repository.loadConversation(outgoing).orElseThrow().conversation().reasoningLevel()).isEqualTo("extra_high");
        load(outgoing);
        load(imageConversation);
        persistence.fenceRevision(imageConversation).get(5, TimeUnit.SECONDS);
        assertThat(persistence.revision(imageConversation)).isEqualTo(corrections);
    }

    @ParameterizedTest
    @CsvSource({"OpenAI, gpt-4o", "OpenRouter, openai/gpt-4o-2024-11-20"})
    @DisplayName("Retired GPT-4o history remains loadable without restoring the retired model for new sends")
    void applyLoadedConversation_retiredGpt4o_preservesHistory(String providerName, String model) throws Exception {
        UUID retiredConversation = createConversation(providerName, model, ReasoningLevel.OFF);
        var retiredProvider = new ProviderRegistry.ProviderDef(providerName, null, null, null, List.of(model),
                ProviderCapabilities.chatAndModels(), ignored -> {
                    throw new AssertionError("A retired model must not receive requests");
                }, List::of);
        onEdt(() -> setField(panel, "providerMap", Map.of(provider.name(), provider, providerName, retiredProvider)));
        load(retiredConversation);
        persistence.fenceRevision(retiredConversation).get(5, TimeUnit.SECONDS);
        onEdt(() -> {
            assertThat(panel.getSelectedModel()).isNull();
            Method history = ChatPanel.class.getDeclaredMethod("getHistory");
            history.setAccessible(true);
            assertThat((List<?>) history.invoke(panel)).hasSize(1);
        });
        var restored = repository.loadConversation(retiredConversation).orElseThrow();
        assertThat(restored.conversation().model()).isEqualTo(model);
        assertThat(restored.conversation().reasoningLevel()).isEqualTo("off");
        assertThat(persistence.revision(retiredConversation)).isZero();
    }

    private void awaitCapabilityRefresh() throws Exception {
        var worker = new AtomicReference<Thread>();
        onEdt(() -> {
            Field refresh = ChatPanel.class.getDeclaredField("capabilityRefreshThread");
            refresh.setAccessible(true);
            worker.set((Thread) ((AtomicReference<?>) refresh.get(panel)).get());
        });
        if (worker.get() != null) {
            worker.get().join(TimeUnit.SECONDS.toMillis(5));
            assertThat(worker.get().isAlive()).isFalse();
        }
        onEdt(() -> {});
    }

    private UUID createConversation(String model, ReasoningLevel level) throws Exception {
        return createConversation("Anthropic", model, level);
    }

    private UUID createConversation(String providerName, String model, ReasoningLevel level) throws Exception {
        UUID id = UUID.randomUUID();
        repository.createConversation(new ConversationRepository.CreateConversationCommand(
                id, "Saved conversation", providerName, model, level, false, null, false,
                new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("Saved message"))
        ));
        return id;
    }

    private void load(UUID id) throws Exception {
        var delivered = new CompletableFuture<Delivery>();
        onEdt(() -> setField(subject, "pendingLoadConversationId", id));
        loader.loadAsync(id, new ConversationLoadCoordinator.Listener() {
            @Override
            public void onLoaded(long requestId, UUID conversationId, List<ConversationRepository.MessageRecord> records,
                                 ConversationRepository.ConversationRecord conversation) {
                delivered.complete(new Delivery(requestId, records, conversation, Thread.currentThread()));
            }

            @Override
            public void onFailure(long requestId, UUID conversationId, Exception error) {
                delivered.completeExceptionally(error);
            }
        });
        Delivery delivery = delivered.get(5, TimeUnit.SECONDS);
        delivery.worker().join(TimeUnit.SECONDS.toMillis(5));
        assertThat(delivery.worker().isAlive()).isFalse();
        onEdt(() -> invokeFrame("applyLoadedConversation",
                new Class<?>[]{long.class, UUID.class, List.class, ConversationRepository.ConversationRecord.class},
                delivery.requestId(), id, delivery.records(), delivery.conversation()));
    }

    private void invokeFrame(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = MainFrame.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(subject, arguments);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target instanceof MainFrame ? MainFrame.class : ChatPanel.class;
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void onEdt(ThrowingCallable action) throws Exception {
        SwingUtilities.invokeAndWait(() -> assertThatCode(action).doesNotThrowAnyException());
    }

    private record Delivery(long requestId, List<ConversationRepository.MessageRecord> records,
                            ConversationRepository.ConversationRecord conversation, Thread worker) {
    }
}
