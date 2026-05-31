package com.github.drafael.chat4j.chat.message;

import ca.weblite.webview.swing.WebViewComponent;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.function.BooleanSupplier;

public final class ChatWebViewRuntimeStatusResolver {

    private final SettingsRepo settingsRepo;
    private final BooleanSupplier macOsSupplier;

    public ChatWebViewRuntimeStatusResolver(@NonNull SettingsRepo settingsRepo) {
        this(settingsRepo, () -> ChatWebViewEngine.defaultForCurrentPlatform() == ChatWebViewEngine.SWING_WEBVIEW);
    }

    ChatWebViewRuntimeStatusResolver(@NonNull SettingsRepo settingsRepo, @NonNull BooleanSupplier macOsSupplier) {
        this.settingsRepo = settingsRepo;
        this.macOsSupplier = macOsSupplier;
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
            String configuredValue = settingsRepo.get(SettingsKeys.CHAT_WEB_VIEW_ENGINE, "");
            if (StringUtils.isBlank(configuredValue)) {
                return defaultEngine();
            }
            return ChatWebViewEngine.fromSettingValue(configuredValue);
        } catch (Exception e) {
            return defaultEngine();
        }
    }

    private ChatWebViewEngine defaultEngine() {
        return macOsSupplier.getAsBoolean() ? ChatWebViewEngine.SWING_WEBVIEW : ChatWebViewEngine.JEDITOR_PANE;
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
