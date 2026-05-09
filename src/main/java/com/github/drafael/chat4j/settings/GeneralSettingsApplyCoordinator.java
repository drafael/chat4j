package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import lombok.NonNull;

import java.util.UUID;

public class GeneralSettingsApplyCoordinator {

    private final SettingsResolver settingsResolver;
    private final ModeResolver modeResolver;

    public GeneralSettingsApplyCoordinator(
            GeneralSettingsResolver generalSettingsResolver,
            AssistantRenderModeSelectionResolver assistantRenderModeSelectionResolver
    ) {
        this(generalSettingsResolver::resolve, assistantRenderModeSelectionResolver::resolve);
    }

    GeneralSettingsApplyCoordinator(@NonNull SettingsResolver settingsResolver, @NonNull ModeResolver modeResolver) {
        this.settingsResolver = settingsResolver;
        this.modeResolver = modeResolver;
    }

    public ApplyResult apply(
            boolean isMacOs,
            UUID currentConversationId,
            AssistantRenderMode conversationRenderMode,
            AssistantRenderMode pendingUnsavedConversationRenderMode
    ) {
        GeneralSettingsResolver.GeneralSettings generalSettings = settingsResolver.resolve(isMacOs);

        AssistantRenderMode modeToApply = modeResolver.resolve(
                currentConversationId,
                conversationRenderMode,
                pendingUnsavedConversationRenderMode,
                generalSettings.defaultAssistantRenderMode()
        );

        return new ApplyResult(
                generalSettings.sendOnEnter(),
                generalSettings.autoScrollEnabled(),
                generalSettings.defaultAssistantRenderMode(),
                modeToApply,
                generalSettings.menuBarEnabled()
        );
    }

    public record ApplyResult(
            boolean sendOnEnter,
            boolean autoScrollEnabled,
            AssistantRenderMode defaultAssistantRenderMode,
            AssistantRenderMode modeToApply,
            boolean menuBarEnabled
    ) {
    }

    @FunctionalInterface
    interface SettingsResolver {
        GeneralSettingsResolver.GeneralSettings resolve(boolean isMacOs);
    }

    @FunctionalInterface
    interface ModeResolver {
        AssistantRenderMode resolve(
                UUID currentConversationId,
                AssistantRenderMode conversationRenderMode,
                AssistantRenderMode pendingUnsavedConversationRenderMode,
                AssistantRenderMode defaultMode
        );
    }
}
