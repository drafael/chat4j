package com.github.drafael.chat4j.sidebar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.plaf.metal.MetalIconFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SidebarToggleApplyCoordinatorTest {

    @Test
    @DisplayName("Apply updates split pane, toggle icon, and returns state when sidebar becomes visible")
    void apply_whenSidebarVisible_updatesUiAndReturnsState() {
        var subject = new SidebarToggleApplyCoordinator();
        var sidebar = new JPanel();
        var splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, new JPanel());
        sidebar.setVisible(false);
        splitPane.setDividerLocation(10);
        splitPane.setDividerSize(1);
        JButton button = new JButton();
        Icon filledIcon = MetalIconFactory.getFileChooserHomeFolderIcon();
        Icon outlineIcon = MetalIconFactory.getFileChooserUpFolderIcon();

        SidebarToggleApplyCoordinator.ApplyResult result = subject.apply(
                new SidebarVisibilityCoordinator.ToggleResult(true, 250, 250, 1),
                splitPane,
                button,
                filledIcon,
                outlineIcon
        );

        assertThat(sidebar.isVisible()).isTrue();
        assertThat(splitPane.getDividerSize()).isEqualTo(1);
        assertThat(splitPane.getDividerLocation()).isEqualTo(250);
        assertThat(button.getIcon()).isSameAs(filledIcon);
        assertThat(result.sidebarVisible()).isTrue();
        assertThat(result.lastDividerLocation()).isEqualTo(250);
    }

    @Test
    @DisplayName("Apply hides sidebar component when sidebar becomes hidden")
    void apply_whenSidebarHidden_hidesSidebarComponentAndUpdatesSplitPane() {
        var subject = new SidebarToggleApplyCoordinator();
        var sidebar = new JPanel();
        var splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, new JPanel());

        SidebarToggleApplyCoordinator.ApplyResult result = subject.apply(
                new SidebarVisibilityCoordinator.ToggleResult(false, 300, 0, 0),
                splitPane,
                null,
                null,
                null
        );

        assertThat(sidebar.isVisible()).isFalse();
        assertThat(splitPane.getDividerSize()).isZero();
        assertThat(splitPane.getDividerLocation()).isZero();
        assertThat(result.sidebarVisible()).isFalse();
        assertThat(result.lastDividerLocation()).isEqualTo(300);
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenArgumentMissing_throwsException() {
        var subject = new SidebarToggleApplyCoordinator();

        assertThatThrownBy(() -> subject.apply(
                null,
                new JSplitPane(),
                null,
                null,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("toggleResult");

        assertThatThrownBy(() -> subject.apply(
                new SidebarVisibilityCoordinator.ToggleResult(true, 1, 1, 1),
                null,
                null,
                null,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("splitPane");
    }
}
