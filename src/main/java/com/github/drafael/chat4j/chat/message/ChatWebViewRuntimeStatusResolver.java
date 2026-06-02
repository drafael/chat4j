package com.github.drafael.chat4j.chat.message;

import ca.weblite.webview.swing.WebViewComponent;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class ChatWebViewRuntimeStatusResolver {

    private final SettingsRepo settingsRepo;
    private final BooleanSupplier macOsSupplier;
    private final BooleanSupplier windowsSupplier;
    private final Supplier<SwingWebViewAvailability> swingWebViewAvailabilitySupplier;

    public ChatWebViewRuntimeStatusResolver(@NonNull SettingsRepo settingsRepo) {
        this(
                settingsRepo,
                () -> SystemInfo.isMacOS,
                () -> SystemInfo.isWindows,
                ChatWebViewRuntimeStatusResolver::resolveSwingWebViewAvailability
        );
    }

    ChatWebViewRuntimeStatusResolver(
            @NonNull SettingsRepo settingsRepo,
            @NonNull BooleanSupplier macOsSupplier,
            @NonNull BooleanSupplier windowsSupplier,
            @NonNull Supplier<SwingWebViewAvailability> swingWebViewAvailabilitySupplier
    ) {
        this.settingsRepo = settingsRepo;
        this.macOsSupplier = macOsSupplier;
        this.windowsSupplier = windowsSupplier;
        this.swingWebViewAvailabilitySupplier = swingWebViewAvailabilitySupplier;
    }

    public ChatWebViewRuntimeStatus resolve() {
        SwingWebViewAvailability availability = swingWebViewAvailabilitySupplier.get();
        ChatWebViewEngine configuredEngine = resolveConfiguredEngine(availability);

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

    private ChatWebViewEngine resolveConfiguredEngine(SwingWebViewAvailability availability) {
        try {
            String configuredValue = settingsRepo.get(SettingsKeys.CHAT_WEB_VIEW_ENGINE, "");
            if (StringUtils.isBlank(configuredValue)) {
                return defaultEngine(availability);
            }
            return ChatWebViewEngine.fromSettingValue(configuredValue);
        } catch (Exception e) {
            return defaultEngine(availability);
        }
    }

    private ChatWebViewEngine defaultEngine(SwingWebViewAvailability availability) {
        return macOsSupplier.getAsBoolean() || (windowsSupplier.getAsBoolean() && availability.available())
                ? ChatWebViewEngine.SWING_WEBVIEW
                : ChatWebViewEngine.JEDITOR_PANE;
    }

    private static SwingWebViewAvailability resolveSwingWebViewAvailability() {
        try {
            WebViewComponent.Mode mode = WebViewComponent.resolveDefaultMode();
            return new SwingWebViewAvailability(true, mode.name(), "");
        } catch (Throwable t) {
            return new SwingWebViewAvailability(false, "Unavailable", ExceptionUtils.getRootCauseMessage(t));
        }
    }

    record SwingWebViewAvailability(boolean available, String mode, String reason) {
    }
}
