package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import java.util.UUID;

public class GeneralSettingsApplyDispatchCoordinator {

    private final SettingsApplyAction settingsApplyAction;
    private final UiApplyAction uiApplyAction;

    public GeneralSettingsApplyDispatchCoordinator(
            GeneralSettingsApplyCoordinator generalSettingsApplyCoordinator,
            GeneralSettingsUiApplyCoordinator generalSettingsUiApplyCoordinator
    ) {
        this(generalSettingsApplyCoordinator::apply, generalSettingsUiApplyCoordinator::apply);
    }

    GeneralSettingsApplyDispatchCoordinator(@NonNull SettingsApplyAction settingsApplyAction, @NonNull UiApplyAction uiApplyAction) {
        this.settingsApplyAction = settingsApplyAction;
        this.uiApplyAction = uiApplyAction;
    }

    public AssistantRenderMode apply(
            boolean isMacOS,
            UUID currentConversationId,
            AssistantRenderMode currentConversationRenderMode,
            AssistantRenderMode pendingUnsavedConversationRenderMode,
            @NonNull GeneralSettingsUiApplyCoordinator.SendOnEnterApplier sendOnEnterApplier,
            @NonNull GeneralSettingsUiApplyCoordinator.AutoScrollApplier autoScrollApplier,
            @NonNull GeneralSettingsUiApplyCoordinator.RenderModeApplier renderModeApplier,
            @NonNull GeneralSettingsUiApplyCoordinator.MenuBarSettingApplier menuBarSettingApplier
    ) {

        GeneralSettingsApplyCoordinator.ApplyResult applyResult = Validate.notNull(
                settingsApplyAction.apply(
                        isMacOS,
                        currentConversationId,
                        currentConversationRenderMode,
                        pendingUnsavedConversationRenderMode
                ),
                "applyResult must not be null"
        );

        return uiApplyAction.apply(
                applyResult,
                sendOnEnterApplier,
                autoScrollApplier,
                renderModeApplier,
                menuBarSettingApplier
        );
    }

    @FunctionalInterface
    interface SettingsApplyAction {
        GeneralSettingsApplyCoordinator.ApplyResult apply(
                boolean isMacOS,
                UUID currentConversationId,
                AssistantRenderMode currentConversationRenderMode,
                AssistantRenderMode pendingUnsavedConversationRenderMode
        );
    }

    @FunctionalInterface
    interface UiApplyAction {
        AssistantRenderMode apply(
                GeneralSettingsApplyCoordinator.ApplyResult applyResult,
                GeneralSettingsUiApplyCoordinator.SendOnEnterApplier sendOnEnterApplier,
                GeneralSettingsUiApplyCoordinator.AutoScrollApplier autoScrollApplier,
                GeneralSettingsUiApplyCoordinator.RenderModeApplier renderModeApplier,
                GeneralSettingsUiApplyCoordinator.MenuBarSettingApplier menuBarSettingApplier
        );
    }
}
