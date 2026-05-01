package com.github.drafael.chat4j;

import com.github.drafael.chat4j.settings.AppFontSizeAdjustCoordinator;
import com.github.drafael.chat4j.settings.FontMenuApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuApplyDispatchCoordinator;
import com.github.drafael.chat4j.settings.FontMenuReadyDispatchCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionFlowCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionSynchronizer;
import com.github.drafael.chat4j.settings.FontMenuStructureRebuildApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.settings.FontSettingsResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MainFrameFontMenuCoordinatorTest {

    @Test
    @DisplayName("Ensure ready rebuilds the font menu and syncs selection")
    void ensureReady_whenMenuNotBuilt_runsReadyFlowAndUpdatesState() {
        FontMenuReadyDispatchCoordinator readyDispatchCoordinator = mock(FontMenuReadyDispatchCoordinator.class);
        FontMenuStructureRebuildCoordinator rebuildCoordinator = mock(FontMenuStructureRebuildCoordinator.class);
        var rebuildApplyCoordinator = new FontMenuStructureRebuildApplyCoordinator();
        FontMenuSelectionFlowCoordinator selectionFlowCoordinator = mock(FontMenuSelectionFlowCoordinator.class);
        var subject = new MainFrameFontMenuCoordinator(
                readyDispatchCoordinator,
                rebuildCoordinator,
                rebuildApplyCoordinator,
                selectionFlowCoordinator,
                mock(FontMenuApplyDispatchCoordinator.class),
                mock(FontMenuApplyCoordinator.class),
                mock(FontSettingsResolver.class),
                mock(AppFontSizeAdjustCoordinator.class)
        );
        MainFrameBoundMenusState boundMenusState = new MainFrameBoundMenusState();
        boundMenusState.setFontMenu(new JMenu("Font"));
        var menuItemsState = new MainFrameMenuItemsState();
        var fontMenuState = new MainFrameFontMenuState(false, "Old App", 13, "Old Code");
        MainFrameFontMenuCoordinator.FontMenuContext context = fontMenuContext(boundMenusState, menuItemsState, fontMenuState);
        when(rebuildCoordinator.rebuild(
                eq(boundMenusState.fontMenu()),
                eq(menuItemsState.appFontMenuItemsByFamily()),
                eq(menuItemsState.appFontSizeMenuItemsBySize()),
                eq(menuItemsState.codeFontMenuItemsByFamily()),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                eq(false),
                eq("Old App"),
                eq(13),
                eq("Old Code")
        )).thenReturn(new FontMenuStructureRebuildCoordinator.RebuildState(true, null, null, null));
        doAnswer(invocation -> {
            invocation.getArgument(7, java.util.function.Consumer.class).accept("Inter");
            invocation.getArgument(8, java.util.function.Consumer.class).accept(14);
            invocation.getArgument(9, java.util.function.Consumer.class).accept("JetBrains Mono");
            return new FontMenuSelectionSynchronizer.FontMenuSelectionState("Inter", 14, "JetBrains Mono");
        }).when(selectionFlowCoordinator).refreshAndApply(
                eq(menuItemsState.appFontMenuItemsByFamily()),
                eq(menuItemsState.appFontSizeMenuItemsBySize()),
                eq(menuItemsState.codeFontMenuItemsByFamily()),
                eq(null),
                eq(null),
                eq(null),
                eq(true),
                any(),
                any(),
                any()
        );
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            invocation.getArgument(2, Runnable.class).run();
            return null;
        }).when(readyDispatchCoordinator).ensureReady(anyBoolean(), any(), any());

        subject.ensureReady(context);

        assertThat(fontMenuState.fontMenuBuilt()).isTrue();
        assertThat(fontMenuState.lastMenuSelectedAppFontFamily()).isEqualTo("Inter");
        assertThat(fontMenuState.lastMenuSelectedAppFontSize()).isEqualTo(14);
        assertThat(fontMenuState.lastMenuSelectedCodeFontFamily()).isEqualTo("JetBrains Mono");
    }

    @Test
    @DisplayName("Apply app font family uses current app font size and syncs selection")
    void applyAppFontFamily_whenRequested_usesCurrentSizeAndDispatchesApply() {
        FontMenuApplyDispatchCoordinator applyDispatchCoordinator = mock(FontMenuApplyDispatchCoordinator.class);
        FontMenuApplyCoordinator applyCoordinator = mock(FontMenuApplyCoordinator.class);
        FontSettingsResolver fontSettingsResolver = mock(FontSettingsResolver.class);
        FontMenuSelectionFlowCoordinator selectionFlowCoordinator = mock(FontMenuSelectionFlowCoordinator.class);
        var subject = new MainFrameFontMenuCoordinator(
                mock(FontMenuReadyDispatchCoordinator.class),
                mock(FontMenuStructureRebuildCoordinator.class),
                new FontMenuStructureRebuildApplyCoordinator(),
                selectionFlowCoordinator,
                applyDispatchCoordinator,
                applyCoordinator,
                fontSettingsResolver,
                mock(AppFontSizeAdjustCoordinator.class)
        );
        var menuItemsState = new MainFrameMenuItemsState();
        menuItemsState.appFontMenuItemsByFamily().put("Inter", new javax.swing.JRadioButtonMenuItem("Inter"));
        MainFrameFontMenuCoordinator.FontMenuContext context = fontMenuContext(new MainFrameBoundMenusState(), menuItemsState, new MainFrameFontMenuState(true, null, null, null));
        when(fontSettingsResolver.resolveAppFontSizeSetting()).thenReturn(16);
        doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return new FontMenuApplyCoordinator.ApplyResult(true, null);
        }).when(applyCoordinator).applyAppFontSelection(eq("Inter"), eq(16), eq(menuItemsState.appFontMenuItemsByFamily().keySet()), any());
        doAnswer(invocation -> invocation.getArgument(0, FontMenuApplyDispatchCoordinator.FontApplyAction.class).apply().success())
                .when(applyDispatchCoordinator).apply(any(), eq("Failed to apply UI font: "), any());

        boolean applied = subject.applyAppFontFamily(context, "Inter");

        assertThat(applied).isTrue();
        verify(applyCoordinator).applyAppFontSelection(eq("Inter"), eq(16), eq(menuItemsState.appFontMenuItemsByFamily().keySet()), any());
        verify(selectionFlowCoordinator).refreshAndApply(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("Adjust app font size applies the resolved size")
    void adjustAppFontSize_whenRequested_appliesResolvedSize() {
        FontMenuApplyDispatchCoordinator applyDispatchCoordinator = mock(FontMenuApplyDispatchCoordinator.class);
        FontMenuApplyCoordinator applyCoordinator = mock(FontMenuApplyCoordinator.class);
        FontSettingsResolver fontSettingsResolver = mock(FontSettingsResolver.class);
        AppFontSizeAdjustCoordinator appFontSizeAdjustCoordinator = mock(AppFontSizeAdjustCoordinator.class);
        var subject = new MainFrameFontMenuCoordinator(
                mock(FontMenuReadyDispatchCoordinator.class),
                mock(FontMenuStructureRebuildCoordinator.class),
                new FontMenuStructureRebuildApplyCoordinator(),
                mock(FontMenuSelectionFlowCoordinator.class),
                applyDispatchCoordinator,
                applyCoordinator,
                fontSettingsResolver,
                appFontSizeAdjustCoordinator
        );
        MainFrameFontMenuCoordinator.FontMenuContext context = fontMenuContext(new MainFrameBoundMenusState(), new MainFrameMenuItemsState(), new MainFrameFontMenuState());
        when(fontSettingsResolver.resolveAppFontFamilySetting()).thenReturn("Inter");
        doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return new FontMenuApplyCoordinator.ApplyResult(true, null);
        }).when(applyCoordinator).applyAppFontSelection(eq("Inter"), eq(15), any(), any());
        doAnswer(invocation -> invocation.getArgument(0, FontMenuApplyDispatchCoordinator.FontApplyAction.class).apply().success())
                .when(applyDispatchCoordinator).apply(any(), eq("Failed to apply UI font: "), any());
        doAnswer(invocation -> {
            invocation.getArgument(3, AppFontSizeAdjustCoordinator.SizeApplier.class).apply(15);
            return 15;
        }).when(appFontSizeAdjustCoordinator).adjust(eq(true), any(), any(), any());

        int adjustedSize = subject.adjustAppFontSize(context, true);

        assertThat(adjustedSize).isEqualTo(15);
        verify(applyCoordinator).applyAppFontSelection(eq("Inter"), eq(15), any(), any());
    }

    private MainFrameFontMenuCoordinator.FontMenuContext fontMenuContext(
            MainFrameBoundMenusState boundMenusState,
            MainFrameMenuItemsState menuItemsState,
            MainFrameFontMenuState fontMenuState
    ) {
        AtomicReference<String> presentedError = new AtomicReference<>();
        return new MainFrameFontMenuCoordinator.FontMenuContext(
                boundMenusState,
                menuItemsState,
                fontMenuState,
                presentedError::set
        );
    }
}
