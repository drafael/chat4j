package com.github.drafael.chat4j.bootstrap;

import ca.weblite.webview.swing.WebViewComponent;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.message.ChatWebViewEngine;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.List;
import java.util.function.BooleanSupplier;

@Slf4j
final class JcefStartupInitializationDecider {

    private final BooleanSupplier macOsSupplier;
    private final BooleanSupplier windowsSupplier;
    private final BooleanSupplier nativeWebViewAvailableSupplier;

    JcefStartupInitializationDecider() {
        this(
                () -> SystemInfo.isMacOS,
                () -> SystemInfo.isWindows,
                JcefStartupInitializationDecider::isNativeWebViewAvailable
        );
    }

    JcefStartupInitializationDecider(
            @NonNull BooleanSupplier macOsSupplier,
            @NonNull BooleanSupplier windowsSupplier,
            @NonNull BooleanSupplier nativeWebViewAvailableSupplier
    ) {
        this.macOsSupplier = macOsSupplier;
        this.windowsSupplier = windowsSupplier;
        this.nativeWebViewAvailableSupplier = nativeWebViewAvailableSupplier;
    }

    boolean shouldInitialize(@NonNull SettingsRepo settingsRepo) {
        ChatWebViewEngine configuredEngine = resolveConfiguredEngine(settingsRepo);
        if (configuredEngine == ChatWebViewEngine.JCEF) {
            return true;
        }
        if (configuredEngine != ChatWebViewEngine.NATIVE_WEBVIEW) {
            return false;
        }
        if (nativeWebViewAvailable()) {
            return false;
        }
        return platformFallbackChain().contains(ChatWebViewEngine.JCEF);
    }

    private ChatWebViewEngine resolveConfiguredEngine(SettingsRepo settingsRepo) {
        try {
            String configuredValue = settingsRepo.get(SettingsKeys.CHAT_WEB_VIEW_ENGINE, "");
            if (StringUtils.isBlank(configuredValue)) {
                return platformFallbackChain().getFirst();
            }
            return ChatWebViewEngine.fromSettingValue(configuredValue);
        } catch (Exception e) {
            log.warn("Failed to resolve configured web-view engine for JCEF startup decision: {}", ExceptionUtils.getMessage(e));
            return platformFallbackChain().getFirst();
        }
    }

    private List<ChatWebViewEngine> platformFallbackChain() {
        if (macOsSupplier.getAsBoolean() || windowsSupplier.getAsBoolean()) {
            return List.of(ChatWebViewEngine.NATIVE_WEBVIEW, ChatWebViewEngine.JCEF, ChatWebViewEngine.JEDITOR_PANE);
        }
        return List.of(ChatWebViewEngine.JCEF, ChatWebViewEngine.JEDITOR_PANE);
    }

    private boolean nativeWebViewAvailable() {
        try {
            return nativeWebViewAvailableSupplier.getAsBoolean();
        } catch (Throwable t) {
            log.debug("Native WebView availability check failed during JCEF startup decision: {}", ExceptionUtils.getMessage(t));
            return false;
        }
    }

    private static boolean isNativeWebViewAvailable() {
        try {
            WebViewComponent.resolveDefaultMode();
            return true;
        } catch (Throwable t) {
            log.debug("Native WebView is unavailable during JCEF startup decision: {}", ExceptionUtils.getMessage(t));
            return false;
        }
    }
}
