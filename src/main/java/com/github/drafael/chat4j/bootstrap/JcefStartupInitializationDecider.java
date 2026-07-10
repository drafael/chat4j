package com.github.drafael.chat4j.bootstrap;

import ca.weblite.webview.swing.WebViewComponent;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.webview.WebViewEngine;
import com.github.drafael.chat4j.chat.webview.WebViewSettings;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.util.List;
import java.util.function.BooleanSupplier;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

@Slf4j
final class JcefStartupInitializationDecider {

    private final BooleanSupplier macOsSupplier;
    private final BooleanSupplier windowsSupplier;
    private final BooleanSupplier systemWebViewAvailableSupplier;

    JcefStartupInitializationDecider() {
        this(
                () -> SystemInfo.isMacOS,
                () -> SystemInfo.isWindows,
                JcefStartupInitializationDecider::isSystemWebViewAvailable
        );
    }

    JcefStartupInitializationDecider(
            @NonNull BooleanSupplier macOsSupplier,
            @NonNull BooleanSupplier windowsSupplier,
            @NonNull BooleanSupplier systemWebViewAvailableSupplier
    ) {
        this.macOsSupplier = macOsSupplier;
        this.windowsSupplier = windowsSupplier;
        this.systemWebViewAvailableSupplier = systemWebViewAvailableSupplier;
    }

    boolean shouldInitialize(@NonNull SettingsRepository settingsRepo) {
        WebViewEngine configuredEngine = resolveConfiguredEngine(settingsRepo);
        if (configuredEngine == WebViewEngine.JCEF) {
            return true;
        }
        if (configuredEngine != WebViewEngine.SYSTEM) {
            return false;
        }
        if (systemWebViewAvailable()) {
            return false;
        }
        return platformFallbackChain().contains(WebViewEngine.JCEF);
    }

    private WebViewEngine resolveConfiguredEngine(SettingsRepository settingsRepo) {
        try {
            String configuredValue = settingsRepo.get(WebViewSettings.ENGINE_KEY, "");
            return WebViewEngine.fromSettingValue(configuredValue, platformFallbackChain().getFirst());
        } catch (Exception e) {
            log.warn("Failed to resolve configured web-view engine for JCEF startup decision: {}", ExceptionUtils.getMessage(e));
            return platformFallbackChain().getFirst();
        }
    }

    private List<WebViewEngine> platformFallbackChain() {
        return macOsSupplier.getAsBoolean() || windowsSupplier.getAsBoolean()
            ? List.of(WebViewEngine.SYSTEM, WebViewEngine.JCEF, WebViewEngine.JEDITOR_PANE)
            : List.of(WebViewEngine.JCEF, WebViewEngine.JEDITOR_PANE);
    }

    private boolean systemWebViewAvailable() {
        try {
            return systemWebViewAvailableSupplier.getAsBoolean();
        } catch (Throwable t) {
            log.debug("System WebView availability check failed during JCEF startup decision: {}", ExceptionUtils.getMessage(t));
            return false;
        }
    }

    private static boolean isSystemWebViewAvailable() {
        try {
            WebViewComponent.resolveDefaultMode();
            return true;
        } catch (Throwable t) {
            log.debug("System WebView is unavailable during JCEF startup decision: {}", ExceptionUtils.getMessage(t));
            return false;
        }
    }
}
