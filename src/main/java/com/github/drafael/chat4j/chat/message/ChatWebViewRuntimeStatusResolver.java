package com.github.drafael.chat4j.chat.message;

import ca.weblite.webview.swing.WebViewComponent;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;
import org.apache.commons.lang3.exception.ExceptionUtils;

public final class ChatWebViewRuntimeStatusResolver {

    private final SettingsRepo settingsRepo;

    public ChatWebViewRuntimeStatusResolver(@NonNull SettingsRepo settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public ChatWebViewRuntimeStatus resolve() {
        ChatWebViewEngine configuredEngine = resolveConfiguredEngine();
        SwingWebViewAvailability availability = resolveSwingWebViewAvailability();

        if (configuredEngine == ChatWebViewEngine.SWING_WEBVIEW && !availability.available()) {
            return new ChatWebViewRuntimeStatus(
                    configuredEngine,
                    ChatWebViewEngine.JEDITOR_PANE,
                    false,
                    availability.mode(),
                    availability.reason()
            );
        }

        return new ChatWebViewRuntimeStatus(
                configuredEngine,
                configuredEngine,
                availability.available(),
                availability.mode(),
                ""
        );
    }

    private ChatWebViewEngine resolveConfiguredEngine() {
        try {
            return ChatWebViewEngine.fromSettingValue(settingsRepo.get(
                    SettingsKeys.CHAT_WEB_VIEW_ENGINE,
                    ChatWebViewEngine.JEDITOR_PANE.settingValue()
            ));
        } catch (Exception e) {
            return ChatWebViewEngine.JEDITOR_PANE;
        }
    }

    private SwingWebViewAvailability resolveSwingWebViewAvailability() {
        try {
            WebViewComponent.Mode mode = WebViewComponent.resolveDefaultMode();
            return new SwingWebViewAvailability(true, mode.name(), "");
        } catch (Throwable t) {
            return new SwingWebViewAvailability(false, "Unavailable", ExceptionUtils.getRootCauseMessage(t));
        }
    }

    private record SwingWebViewAvailability(boolean available, String mode, String reason) {
    }
}
