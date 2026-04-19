package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPanelTest {

    private ChatPanel subject;

    @BeforeEach
    void setUp() {
        subject = new ChatPanel();
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
        assertThat(subject.getInputBar().isGenerationIndicatorVisible()).isTrue();
        assertThat(readGenerationLabel(subject.getInputBar()).getText()).isEqualTo("Preparing…");
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
        assertThat(subject.getInputBar().isGenerationIndicatorVisible()).isFalse();
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
                    && !subject.getInputBar().isGenerationIndicatorVisible()
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
                    Consumer<String> onToken,
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
        assertThat(subject.getInputBar().isGenerationIndicatorVisible()).isFalse();
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
        assertThat(subject.getInputBar().isGenerationIndicatorVisible()).isFalse();

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
                    Consumer<String> onToken,
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
                    Consumer<String> onToken,
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

    private static JLabel readGenerationLabel(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("generationLabel");
        field.setAccessible(true);
        return (JLabel) field.get(inputBar);
    }

    private static JLabel readValidationLabel(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("validationLabel");
        field.setAccessible(true);
        return (JLabel) field.get(inputBar);
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
        List<T> matches = new java.util.ArrayList<>();
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
