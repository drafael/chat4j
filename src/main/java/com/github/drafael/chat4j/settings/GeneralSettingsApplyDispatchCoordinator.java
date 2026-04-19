package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
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

    GeneralSettingsApplyDispatchCoordinator(SettingsApplyAction settingsApplyAction, UiApplyAction uiApplyAction) {
        this.settingsApplyAction = Validate.notNull(settingsApplyAction, "settingsApplyAction must not be null");
        this.uiApplyAction = Validate.notNull(uiApplyAction, "uiApplyAction must not be null");
    }

    public AssistantRenderMode apply(
            boolean isMacOS,
            UUID currentConversationId,
            AssistantRenderMode currentConversationRenderMode,
            AssistantRenderMode pendingUnsavedConversationRenderMode,
            GeneralSettingsUiApplyCoordinator.SendOnEnterApplier sendOnEnterApplier,
            GeneralSettingsUiApplyCoordinator.AutoScrollApplier autoScrollApplier,
            GeneralSettingsUiApplyCoordinator.RenderModeApplier renderModeApplier,
            GeneralSettingsUiApplyCoordinator.MenuBarSettingApplier menuBarSettingApplier
    ) {
        Validate.notNull(sendOnEnterApplier, "sendOnEnterApplier must not be null");
        Validate.notNull(autoScrollApplier, "autoScrollApplier must not be null");
        Validate.notNull(renderModeApplier, "renderModeApplier must not be null");
        Validate.notNull(menuBarSettingApplier, "menuBarSettingApplier must not be null");

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
