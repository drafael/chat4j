package com.github.drafael.chat4j;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.settings.AgentModeSettingsCoordinator;
import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MainFrameConversationRuntimeSettingsCoordinatorTest {

    private final ConversationRepo conversationRepo = mock(ConversationRepo.class);
    private final SettingsRepo settingsRepo = mock(SettingsRepo.class);
    private final AgentModeSettingsCoordinator agentModeSettingsCoordinator = mock(AgentModeSettingsCoordinator.class);
    private final MainFrameConversationRuntimeSettingsCoordinator subject = new MainFrameConversationRuntimeSettingsCoordinator(
            conversationRepo,
            settingsRepo,
            agentModeSettingsCoordinator
    );

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Loaded conversation settings apply reasoning, web search, and valid agent root")
    void applyLoadedConversationSettings_whenConversationHasRuntimeSettings_appliesAllSettings() {
        var target = new RecordingRuntimeSettingsTarget();
        ConversationRepo.ConversationRecord conversation = conversationRecord(
                "high",
                true,
                tempDir.toString(),
                true,
                "browse"
        );

        subject.applyLoadedConversationSettings(conversation, target.target());

        assertThat(target.reasoningLevel.get()).isEqualTo(ReasoningLevel.HIGH);
        assertThat(target.webSearchEnabled.get()).isTrue();
        assertThat(target.webSearchOptionId.get()).isEqualTo("browse");
        assertThat(target.agentProjectRoot.get()).isEqualTo(tempDir.toAbsolutePath().normalize());
        assertThat(target.agentModeEnabled.get()).isTrue();
    }

    @Test
    @DisplayName("Loaded conversation settings ignore invalid agent root and reset disabled values")
    void applyLoadedConversationSettings_whenConversationMissingRuntimeSettings_resetsRuntimeState() {
        var target = new RecordingRuntimeSettingsTarget();
        ConversationRepo.ConversationRecord conversation = conversationRecord(
                null,
                true,
                tempDir.resolve("missing").toString(),
                false,
                null
        );

        subject.applyLoadedConversationSettings(conversation, target.target());

        assertThat(target.reasoningLevel.get()).isEqualTo(ReasoningLevel.OFF);
        assertThat(target.webSearchEnabled.get()).isFalse();
        assertThat(target.webSearchOptionId.get()).isNull();
        assertThat(target.agentProjectRoot.get()).isNull();
        assertThat(target.agentModeEnabled.get()).isFalse();
    }

    @Test
    @DisplayName("Reset runtime state clears agent, reasoning, and web search toggles")
    void resetRuntimeState_whenCalled_appliesDefaults() {
        var target = new RecordingRuntimeSettingsTarget();

        subject.resetRuntimeState(target.target());

        assertThat(target.reasoningLevel.get()).isEqualTo(ReasoningLevel.OFF);
        assertThat(target.webSearchEnabled.get()).isFalse();
        assertThat(target.agentProjectRoot.get()).isNull();
        assertThat(target.agentModeEnabled.get()).isFalse();
    }

    @Test
    @DisplayName("Web search settings use stored browse-top value")
    void applyWebSearchSettings_whenStoredValueExists_appliesStoredTopN() throws Exception {
        var appliedTopN = new AtomicReference<Integer>();
        when(settingsRepo.get(SettingsKeys.WEB_AUTO_BROWSE_TOP_N, "3")).thenReturn("7");

        subject.applyWebSearchSettings(appliedTopN::set);

        assertThat(appliedTopN).hasValue(7);
    }

    private ConversationRepo.ConversationRecord conversationRecord(
            String reasoningLevel,
            boolean agentModeEnabled,
            String agentProjectRoot,
            boolean webSearchEnabled,
            String webSearchOption
    ) {
        return new ConversationRepo.ConversationRecord(
                UUID.randomUUID(),
                "Title",
                "OpenAI",
                "gpt-4.1",
                false,
                reasoningLevel,
                agentModeEnabled,
                agentProjectRoot,
                webSearchEnabled,
                webSearchOption,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private static class RecordingRuntimeSettingsTarget {
        private final AtomicReference<ReasoningLevel> reasoningLevel = new AtomicReference<>();
        private final AtomicReference<Boolean> webSearchEnabled = new AtomicReference<>();
        private final AtomicReference<String> webSearchOptionId = new AtomicReference<>();
        private final AtomicReference<Path> agentProjectRoot = new AtomicReference<>();
        private final AtomicReference<Boolean> agentModeEnabled = new AtomicReference<>();

        private MainFrameConversationRuntimeSettingsCoordinator.RuntimeSettingsTarget target() {
            return new MainFrameConversationRuntimeSettingsCoordinator.RuntimeSettingsTarget(
                    reasoningLevel::set,
                    webSearchEnabled::set,
                    webSearchOptionId::set,
                    agentProjectRoot::set,
                    agentModeEnabled::set
            );
        }
    }
}
