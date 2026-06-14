package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.settings.AgentModeSettingsCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class MainFrameConversationRuntimeSettingsCoordinator {

    private static final int DEFAULT_WEB_BROWSE_TOP_N = 3;

    private final ConversationRepository conversationRepo;
    private final SettingsRepository settingsRepo;
    private final AgentModeSettingsCoordinator agentModeSettingsCoordinator;

    public MainFrameConversationRuntimeSettingsCoordinator(
            @NonNull ConversationRepository conversationRepo,
            @NonNull SettingsRepository settingsRepo,
            @NonNull AgentModeSettingsCoordinator agentModeSettingsCoordinator
    ) {
        this.conversationRepo = conversationRepo;
        this.settingsRepo = settingsRepo;
        this.agentModeSettingsCoordinator = agentModeSettingsCoordinator;
    }

    public void applyLoadedConversationSettings(
            ConversationRepository.ConversationRecord conversation,
            @NonNull RuntimeSettingsTarget target
    ) {
        applyLoadedConversationReasoningSettings(conversation, target);
        applyLoadedConversationWebSearchSettings(conversation, target);
        applyLoadedConversationAgentSettings(conversation, target);
    }

    public void resetRuntimeState(@NonNull RuntimeSettingsTarget target) {
        target.setAgentProjectRoot().accept(null);
        target.setAgentModeEnabled().accept(false);
        target.setReasoningLevel().accept(ReasoningLevel.OFF);
        target.setWebSearchEnabled().accept(false);
    }

    public void applyAgentModeSettings(@NonNull Consumer<String> setAgentSystemPromptAppend) {
        try {
            setAgentSystemPromptAppend.accept(agentModeSettingsCoordinator.resolveSystemPromptAppend());
        } catch (Exception e) {
            log.debug("Failed to resolve Agent Mode settings", e);
        }
    }

    public void applyWebSearchSettings(@NonNull IntConsumer setWebBrowseTopN) {
        try {
            int topN = Integer.parseInt(settingsRepo.get(SettingsKeys.WEB_AUTO_BROWSE_TOP_N, "3"));
            setWebBrowseTopN.accept(topN);
        } catch (Exception e) {
            log.debug("Failed to resolve Web Search settings", e);
            setWebBrowseTopN.accept(DEFAULT_WEB_BROWSE_TOP_N);
        }
    }

    public void persistReasoningLevel(UUID currentConversationId, ReasoningLevel reasoningLevel) {
        if (currentConversationId == null) {
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                conversationRepo.updateReasoningLevel(currentConversationId, reasoningLevel);
            } catch (Exception e) {
                log.debug("Failed to persist conversation reasoning level for {}", currentConversationId, e);
            }
        });
    }

    public void persistWebBrowseTopN(int topN) {
        Thread.startVirtualThread(() -> {
            try {
                settingsRepo.put(SettingsKeys.WEB_AUTO_BROWSE_TOP_N, String.valueOf(topN));
            } catch (Exception e) {
                log.debug("Failed to persist Web Search browse-top setting", e);
            }
        });
    }

    public void persistWebSearchSettings(UUID currentConversationId, boolean webSearchEnabled, String webSearchOptionId) {
        if (currentConversationId == null) {
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                conversationRepo.updateWebSearchSettings(currentConversationId, webSearchEnabled, webSearchOptionId);
            } catch (Exception e) {
                log.debug("Failed to persist conversation Web Search settings for {}", currentConversationId, e);
            }
        });
    }

    public void persistAgentSettings(UUID currentConversationId, boolean agentModeRequested, Path agentProjectRoot) {
        if (currentConversationId == null) {
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                conversationRepo.updateAgentSettings(currentConversationId, agentModeRequested, agentProjectRoot);
            } catch (Exception e) {
                log.debug("Failed to persist conversation Agent Mode settings for {}", currentConversationId, e);
            }
        });
    }

    private void applyLoadedConversationReasoningSettings(
            ConversationRepository.ConversationRecord conversation,
            RuntimeSettingsTarget target
    ) {
        ReasoningLevel level = conversation == null
                ? ReasoningLevel.OFF
                : ReasoningLevel.fromSettingValue(conversation.reasoningLevel(), ReasoningLevel.OFF);
        target.setReasoningLevel().accept(level);
    }

    private void applyLoadedConversationWebSearchSettings(
            ConversationRepository.ConversationRecord conversation,
            RuntimeSettingsTarget target
    ) {
        boolean enabled = conversation != null && conversation.webSearchEnabled();
        String option = conversation == null ? null : conversation.webSearchOption();

        if (StringUtils.isNotBlank(option)) {
            target.setWebSearchOptionId().accept(option);
        }
        target.setWebSearchEnabled().accept(enabled);
    }

    private void applyLoadedConversationAgentSettings(
            ConversationRepository.ConversationRecord conversation,
            RuntimeSettingsTarget target
    ) {
        Path projectRoot = null;
        boolean enabled = false;
        if (conversation != null && StringUtils.isNotBlank(conversation.agentProjectRoot())) {
            try {
                Path normalized = Path.of(conversation.agentProjectRoot()).toAbsolutePath().normalize();
                if (Files.isDirectory(normalized)) {
                    projectRoot = normalized;
                    enabled = conversation.agentModeEnabled();
                }
            } catch (Exception e) {
                log.debug("Ignoring invalid persisted Agent project root: {}", conversation.agentProjectRoot(), e);
                projectRoot = null;
            }
        }

        target.setAgentModeEnabled().accept(enabled);
        target.setAgentProjectRoot().accept(projectRoot);
    }

    public record RuntimeSettingsTarget(
            @NonNull Consumer<ReasoningLevel> setReasoningLevel,
            @NonNull Consumer<Boolean> setWebSearchEnabled,
            @NonNull Consumer<String> setWebSearchOptionId,
            @NonNull Consumer<Path> setAgentProjectRoot,
            @NonNull Consumer<Boolean> setAgentModeEnabled
    ) {
    }
}
