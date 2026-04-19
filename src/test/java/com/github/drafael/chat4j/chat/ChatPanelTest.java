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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        List<MessageBubble> bubbles = findComponents(messagesPanel, MessageBubble.class);

        assertThat(chips).hasSize(2);
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
                    java.util.function.Consumer<String> onToken,
                    Runnable onComplete,
                    java.util.function.Consumer<Exception> onError,
                    java.util.function.BooleanSupplier isCancelled
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
    @DisplayName("Switching visible conversation keeps streaming in background and persists assistant response to original conversation")
    void onSend_whenConversationChangesWhileStreaming_persistsAssistantToOriginalConversationOnly() throws Exception {
        var originalConversationId = UUID.randomUUID();
        var visibleConversationId = UUID.randomUUID();
        var persistedEvent = new AtomicReference<ChatPanel.AssistantMessageEvent>();
        var completion = new CountDownLatch(1);
        var tokenDelivered = new CountDownLatch(1);
        var continueStream = new CountDownLatch(1);

        subject.setActiveConversationId(originalConversationId);
        subject.setConversationIdSupplier(() -> originalConversationId);
        subject.setOnAssistantMessageCompleted(event -> {
            persistedEvent.set(event);
            completion.countDown();
        });

        setField(subject, "currentProvider", new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    java.util.function.Consumer<String> onToken,
                    Runnable onComplete,
                    java.util.function.Consumer<Exception> onError,
                    java.util.function.BooleanSupplier isCancelled
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
        SwingUtilities.invokeAndWait(() -> {
            subject.setActiveConversationId(visibleConversationId);
            subject.loadHistory(List.of(Message.user("visible conversation")));
        });

        continueStream.countDown();
        assertThat(completion.await(2, TimeUnit.SECONDS)).isTrue();
        flushEdt();

        assertThat(persistedEvent.get()).isNotNull();
        assertThat(persistedEvent.get().conversationId()).isEqualTo(originalConversationId);
        assertThat(persistedEvent.get().message().content()).isEqualTo("pong");
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
                    java.util.function.Consumer<String> onToken,
                    Runnable onComplete,
                    java.util.function.Consumer<Exception> onError,
                    java.util.function.BooleanSupplier isCancelled
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
