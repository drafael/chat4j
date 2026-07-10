package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.support.ModelMenuDirtyRefreshCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuDirtyRefreshTriggerCoordinator;
import com.github.drafael.chat4j.settings.WindowPlacementCoordinator;
import com.github.drafael.chat4j.settings.WindowStateSettings;
import com.github.drafael.chat4j.util.LookAndFeelMenuRefreshCoordinator;
import com.github.drafael.chat4j.util.MenuPopupVisibleRunner;
import lombok.NonNull;

public class MainFrameLifecycleWiringFactory {

    public LifecycleWiring create(@NonNull SettingsRepository settingsRepo, @NonNull MenuPopupVisibleRunner menuPopupVisibleRunner) {
        var modelMenuDirtyRefreshCoordinator = new ModelMenuDirtyRefreshCoordinator(menuPopupVisibleRunner);
        var modelMenuDirtyRefreshTriggerCoordinator =
                new ModelMenuDirtyRefreshTriggerCoordinator(modelMenuDirtyRefreshCoordinator);
        var lookAndFeelMenuRefreshCoordinator = new LookAndFeelMenuRefreshCoordinator(menuPopupVisibleRunner);
        var windowStateSettings = new WindowStateSettings(settingsRepo);
        var windowPlacementCoordinator = new WindowPlacementCoordinator(windowStateSettings);

        return new LifecycleWiring(
                modelMenuDirtyRefreshCoordinator,
                modelMenuDirtyRefreshTriggerCoordinator,
                lookAndFeelMenuRefreshCoordinator,
                windowPlacementCoordinator
        );
    }

    public record LifecycleWiring(
            ModelMenuDirtyRefreshCoordinator modelMenuDirtyRefreshCoordinator,
            ModelMenuDirtyRefreshTriggerCoordinator modelMenuDirtyRefreshTriggerCoordinator,
            LookAndFeelMenuRefreshCoordinator lookAndFeelMenuRefreshCoordinator,
            WindowPlacementCoordinator windowPlacementCoordinator
    ) {
    }
}
