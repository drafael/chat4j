package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.chat.NewChatCoordinator;
import com.github.drafael.chat4j.sidebar.SidebarToggleCoordinator;
import com.github.drafael.chat4j.sidebar.SidebarToggleStateApplyCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JSplitPane;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameStateTransitionsIntegrationTest {

    @Test
    @DisplayName("New-chat transition clears conversation state and applies default render mode")
    void newChatFlow_whenExecuted_clearsConversationStateAndAppliesDefaultMode() {
        var newChatCoordinator = new NewChatCoordinator();
        var conversationState = new MainFrameConversationState();
        conversationState.setCurrentConversationId(UUID.randomUUID());
        conversationState.setPendingUnsavedConversationRenderMode(AssistantRenderMode.MARKDOWN);

        var assistantRenderModeState = new MainFrameAssistantRenderModeState();
        var activeConversationId = new AtomicReference<>(UUID.randomUUID());
        var appliedRenderMode = new AtomicReference<AssistantRenderMode>();
        var flowCalls = new ArrayList<String>();

        newChatCoordinator.start(
                () -> flowCalls.add("save"),
                () -> {
                    flowCalls.add("clear-current");
                    conversationState.clearCurrentConversationId();
                },
                () -> {
                    flowCalls.add("clear-pending");
                    conversationState.clearPendingUnsavedConversationRenderMode();
                },
                () -> {
                    flowCalls.add("clear-active");
                    activeConversationId.set(null);
                },
                () -> flowCalls.add("clear-chat-view"),
                assistantRenderModeState.defaultAssistantRenderMode(),
                (mode, userInitiated) -> {
                    flowCalls.add("apply-render-mode");
                    appliedRenderMode.set(mode);
                },
                () -> flowCalls.add("focus")
        );

        assertThat(conversationState.currentConversationId()).isNull();
        assertThat(conversationState.pendingUnsavedConversationRenderMode()).isNull();
        assertThat(activeConversationId.get()).isNull();
        assertThat(appliedRenderMode.get()).isEqualTo(AssistantRenderMode.PREVIEW);
        assertThat(flowCalls).containsExactly(
                "save",
                "clear-current",
                "clear-pending",
                "clear-active",
                "clear-chat-view",
                "apply-render-mode",
                "focus"
        );
    }

    @Test
    @DisplayName("Sidebar toggle transition updates state, split pane, and button icon")
    void sidebarToggleFlow_whenExecuted_updatesStateAndUiArtifacts() {
        var sidebarToggleCoordinator = new SidebarToggleCoordinator();
        var sidebarToggleStateApplyCoordinator = new SidebarToggleStateApplyCoordinator();
        var sidebarState = new MainFrameSidebarState();
        var sidebarToggleState = new MainFrameSidebarToggleState();
        var filledIcon = new ImageIcon();
        var outlineIcon = new ImageIcon();
        var toggleButton = new JButton();

        sidebarToggleState.setSidebarToggleFilledIcon(filledIcon);
        sidebarToggleState.setSidebarToggleOutlineIcon(outlineIcon);
        sidebarToggleState.setSidebarToggleButton(toggleButton);

        var splitPane = new JSplitPane();
        splitPane.setDividerLocation(250);
        splitPane.setDividerSize(2);

        SidebarToggleCoordinator.ToggleState hideState = sidebarToggleCoordinator.toggle(
                sidebarState.sidebarVisible(),
                sidebarState.lastDividerLocation(),
                splitPane.getDividerLocation(),
                splitPane,
                sidebarToggleState.sidebarToggleButton(),
                sidebarToggleState.sidebarToggleFilledIcon(),
                sidebarToggleState.sidebarToggleOutlineIcon()
        );
        sidebarToggleStateApplyCoordinator.apply(
                hideState,
                sidebarState::setSidebarVisible,
                sidebarState::setLastDividerLocation
        );

        assertThat(sidebarState.sidebarVisible()).isFalse();
        assertThat(sidebarState.lastDividerLocation()).isEqualTo(250);
        assertThat(splitPane.getDividerLocation()).isEqualTo(0);
        assertThat(splitPane.getDividerSize()).isEqualTo(0);
        assertThat(toggleButton.getIcon()).isSameAs(outlineIcon);

        SidebarToggleCoordinator.ToggleState showState = sidebarToggleCoordinator.toggle(
                sidebarState.sidebarVisible(),
                sidebarState.lastDividerLocation(),
                splitPane.getDividerLocation(),
                splitPane,
                sidebarToggleState.sidebarToggleButton(),
                sidebarToggleState.sidebarToggleFilledIcon(),
                sidebarToggleState.sidebarToggleOutlineIcon()
        );
        sidebarToggleStateApplyCoordinator.apply(
                showState,
                sidebarState::setSidebarVisible,
                sidebarState::setLastDividerLocation
        );

        assertThat(sidebarState.sidebarVisible()).isTrue();
        assertThat(sidebarState.lastDividerLocation()).isEqualTo(250);
        assertThat(splitPane.getDividerLocation()).isEqualTo(250);
        assertThat(splitPane.getDividerSize()).isEqualTo(2);
        assertThat(toggleButton.getIcon()).isSameAs(filledIcon);
    }
}
