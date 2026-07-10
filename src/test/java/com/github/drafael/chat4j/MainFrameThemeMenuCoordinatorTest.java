package com.github.drafael.chat4j;

import com.github.drafael.chat4j.settings.ThemeMenuApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuApplyDispatchCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuReadyDispatchCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionFlowCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuStructureRebuildApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.settings.ThemeSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MainFrameThemeMenuCoordinatorTest {

    @Test
    @DisplayName("Ensure ready rebuilds the theme menu and syncs selection")
    void ensureReady_whenMenuNotBuilt_runsReadyFlowAndUpdatesState() {
        ThemeMenuReadyDispatchCoordinator readyDispatchCoordinator = mock(ThemeMenuReadyDispatchCoordinator.class);
        ThemeMenuStructureRebuildCoordinator rebuildCoordinator = mock(ThemeMenuStructureRebuildCoordinator.class);
        var rebuildApplyCoordinator = new ThemeMenuStructureRebuildApplyCoordinator();
        ThemeMenuSelectionFlowCoordinator selectionFlowCoordinator = mock(ThemeMenuSelectionFlowCoordinator.class);
        var subject = new MainFrameThemeMenuCoordinator(
                readyDispatchCoordinator,
                rebuildCoordinator,
                rebuildApplyCoordinator,
                selectionFlowCoordinator,
                mock(ThemeMenuApplyDispatchCoordinator.class),
                mock(ThemeMenuApplyCoordinator.class)
        );
        MainFrameBoundMenusState boundMenusState = new MainFrameBoundMenusState();
        boundMenusState.setThemesMenu(new JMenu("Themes"));
        var menuItemsState = new MainFrameMenuItemsState();
        var themeMenuState = new MainFrameThemeMenuState(false, "Old Theme");
        MainFrameThemeMenuCoordinator.ThemeMenuContext context = new MainFrameThemeMenuCoordinator.ThemeMenuContext(
                boundMenusState,
                menuItemsState,
                themeMenuState,
                () -> {
                },
                () -> {
                },
                ignored -> {
                }
        );
        when(rebuildCoordinator.rebuild(
                eq(boundMenusState.themesMenu()),
                eq(menuItemsState.themeMenuItemsByName()),
                any(),
                eq(false),
                eq("Old Theme")
        )).thenReturn(new ThemeMenuStructureRebuildCoordinator.RebuildState(true, null));
        doAnswer(invocation -> {
            invocation.getArgument(4, Consumer.class).accept("Dracula");
            return "Dracula";
        }).when(selectionFlowCoordinator).refreshAndApply(
                eq(menuItemsState.themeMenuItemsByName()),
                eq(null),
                eq(true),
                eq(ThemeSettings.DEFAULT_THEME),
                any()
        );
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            invocation.getArgument(2, Runnable.class).run();
            return null;
        }).when(readyDispatchCoordinator).ensureReady(anyBoolean(), any(), any());

        subject.ensureReady(context);

        assertThat(themeMenuState.themesMenuBuilt()).isTrue();
        assertThat(themeMenuState.lastMenuSelectedTheme()).isEqualTo("Dracula");
    }

    @Test
    @DisplayName("Apply theme delegates to dispatch coordinator with menu refresh callbacks")
    void applyTheme_whenRequested_delegatesWithCallbacks() {
        ThemeMenuApplyDispatchCoordinator applyDispatchCoordinator = mock(ThemeMenuApplyDispatchCoordinator.class);
        ThemeMenuSelectionFlowCoordinator selectionFlowCoordinator = mock(ThemeMenuSelectionFlowCoordinator.class);
        ThemeMenuApplyCoordinator applyCoordinator = mock(ThemeMenuApplyCoordinator.class);
        var subject = new MainFrameThemeMenuCoordinator(
                mock(ThemeMenuReadyDispatchCoordinator.class),
                mock(ThemeMenuStructureRebuildCoordinator.class),
                new ThemeMenuStructureRebuildApplyCoordinator(),
                selectionFlowCoordinator,
                applyDispatchCoordinator,
                applyCoordinator
        );
        var modelsMarkedDirty = new AtomicBoolean();
        var fontSelectionSynced = new AtomicBoolean();
        var presentedError = new AtomicReference<String>();
        MainFrameThemeMenuCoordinator.ThemeMenuContext context = new MainFrameThemeMenuCoordinator.ThemeMenuContext(
                new MainFrameBoundMenusState(),
                new MainFrameMenuItemsState(),
                new MainFrameThemeMenuState(true, "Dracula"),
                () -> modelsMarkedDirty.set(true),
                () -> fontSelectionSynced.set(true),
                presentedError::set
        );
        doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            invocation.getArgument(4, Runnable.class).run();
            invocation.getArgument(5, Runnable.class).run();
            return true;
        }).when(applyDispatchCoordinator).apply(eq("Dracula"), eq("com.example.Dracula"), any(), any(), any(), any(), any());

        boolean applied = subject.applyTheme("Dracula", "com.example.Dracula", context);

        assertThat(applied).isTrue();
        assertThat(modelsMarkedDirty).isTrue();
        assertThat(fontSelectionSynced).isTrue();
        assertThat(presentedError).hasValue(null);
        verify(selectionFlowCoordinator).refreshAndApply(
                eq(context.menuItemsState().themeMenuItemsByName()),
                eq("Dracula"),
                eq(true),
                eq(ThemeSettings.DEFAULT_THEME),
                any()
        );
    }
}
