package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainMenuBarCreatedApplyCoordinatorTest {

    @Test
    @DisplayName("Apply returns mapped menu refs, default build flags, and syncs preview selection")
    void apply_whenCalled_returnsMappedRefsAndFlags() {
        var subject = new MainMenuBarCreatedApplyCoordinator();
        JMenu fileMenu = new JMenu("File");
        JMenu viewMenu = new JMenu("View");
        JMenu modelsMenu = new JMenu("Model");
        JMenu fontMenu = new JMenu("Font");
        JMenu themesMenu = new JMenu("Theme");
        JCheckBoxMenuItem togglePreview = new JCheckBoxMenuItem("Preview");
        var createdMenuBar = new MainMenuBarBuilder.CreatedMenuBar(
                new JMenuBar(),
                fileMenu,
                viewMenu,
                modelsMenu,
                fontMenu,
                themesMenu,
                togglePreview
        );
        var syncCalls = new AtomicInteger();

        MainMenuBarCreatedApplyCoordinator.ApplyResult result =
                subject.apply(createdMenuBar, syncCalls::incrementAndGet);

        assertThat(result.fileMenu()).isSameAs(fileMenu);
        assertThat(result.viewMenu()).isSameAs(viewMenu);
        assertThat(result.modelsMenu()).isSameAs(modelsMenu);
        assertThat(result.fontMenu()).isSameAs(fontMenu);
        assertThat(result.themesMenu()).isSameAs(themesMenu);
        assertThat(result.togglePreviewMenuItem()).isSameAs(togglePreview);
        assertThat(result.modelsMenuDirty()).isTrue();
        assertThat(result.themesMenuBuilt()).isFalse();
        assertThat(result.fontMenuBuilt()).isFalse();
        assertThat(syncCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenArgumentMissing_throwsException() {
        var subject = new MainMenuBarCreatedApplyCoordinator();

        assertThatThrownBy(() -> subject.apply(null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdMenuBar");

        assertThatThrownBy(() -> subject.apply(
                new MainMenuBarBuilder.CreatedMenuBar(
                        new JMenuBar(),
                        new JMenu("File"),
                        new JMenu("View"),
                        new JMenu("Model"),
                        new JMenu("Font"),
                        new JMenu("Theme"),
                        new JCheckBoxMenuItem("Preview")
                ),
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("syncTogglePreviewMenuSelection");
    }
}
