package com.github.drafael.chat4j.sidebar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JSplitPane;
import javax.swing.plaf.metal.MetalIconFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SidebarToggleCoordinatorTest {

    @Test
    @DisplayName("Toggle hides sidebar when currently visible and applies hidden UI state")
    void toggle_whenVisible_hidesSidebarAndAppliesUiState() {
        var subject = new SidebarToggleCoordinator();
        var splitPane = new JSplitPane();
        splitPane.setDividerLocation(320);
        splitPane.setDividerSize(2);
        JButton button = new JButton();
        Icon filledIcon = MetalIconFactory.getFileChooserHomeFolderIcon();
        Icon outlineIcon = MetalIconFactory.getFileChooserUpFolderIcon();

        SidebarToggleCoordinator.ToggleState state = subject.toggle(
                true,
                250,
                320,
                splitPane,
                button,
                filledIcon,
                outlineIcon
        );

        assertThat(state.sidebarVisible()).isFalse();
        assertThat(state.lastDividerLocation()).isEqualTo(320);
        assertThat(splitPane.getDividerLocation()).isZero();
        assertThat(splitPane.getDividerSize()).isZero();
        assertThat(button.getIcon()).isSameAs(outlineIcon);
    }

    @Test
    @DisplayName("Toggle shows sidebar when currently hidden and restores previous divider location")
    void toggle_whenHidden_showsSidebarAndRestoresDivider() {
        var subject = new SidebarToggleCoordinator();
        var splitPane = new JSplitPane();
        splitPane.setDividerLocation(0);
        splitPane.setDividerSize(0);
        JButton button = new JButton();
        Icon filledIcon = MetalIconFactory.getFileChooserHomeFolderIcon();
        Icon outlineIcon = MetalIconFactory.getFileChooserUpFolderIcon();

        SidebarToggleCoordinator.ToggleState state = subject.toggle(
                false,
                280,
                0,
                splitPane,
                button,
                filledIcon,
                outlineIcon
        );

        assertThat(state.sidebarVisible()).isTrue();
        assertThat(state.lastDividerLocation()).isEqualTo(280);
        assertThat(splitPane.getDividerLocation()).isEqualTo(280);
        assertThat(splitPane.getDividerSize()).isEqualTo(1);
        assertThat(button.getIcon()).isSameAs(filledIcon);
    }

    @Test
    @DisplayName("Toggle validates required collaborators and arguments")
    void toggle_whenDependencyOrArgumentMissing_throwsException() {
        assertThatThrownBy(() -> new SidebarToggleCoordinator(null, new SidebarToggleApplyCoordinator()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sidebarVisibilityCoordinator");

        assertThatThrownBy(() -> new SidebarToggleCoordinator(new SidebarVisibilityCoordinator(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sidebarToggleApplyCoordinator");

        var subject = new SidebarToggleCoordinator();
        assertThatThrownBy(() -> subject.toggle(
                true,
                100,
                100,
                null,
                null,
                null,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("splitPane");
    }
}
