package com.github.drafael.chat4j.chat.webview;

import com.github.drafael.chat4j.chat.conversation.webview.jcef.JcefRuntime;
import ca.weblite.webview.swing.WebViewComponent;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class WebViewRuntimeStatusResolver {

    private final SettingsRepo settingsRepo;
    private final BooleanSupplier macOsSupplier;
    private final BooleanSupplier windowsSupplier;
    private final Supplier<SwingWebViewAvailability> swingWebViewAvailabilitySupplier;
    private final Supplier<JcefAvailability> jcefAvailabilitySupplier;

    public WebViewRuntimeStatusResolver(@NonNull SettingsRepo settingsRepo) {
        this(
                settingsRepo,
                () -> SystemInfo.isMacOS,
                () -> SystemInfo.isWindows,
                WebViewRuntimeStatusResolver::resolveSwingWebViewAvailability,
                WebViewRuntimeStatusResolver::resolveJcefAvailability
        );
    }

    WebViewRuntimeStatusResolver(
            @NonNull SettingsRepo settingsRepo,
            @NonNull BooleanSupplier macOsSupplier,
            @NonNull BooleanSupplier windowsSupplier,
            @NonNull Supplier<SwingWebViewAvailability> swingWebViewAvailabilitySupplier
    ) {
        this(settingsRepo, macOsSupplier, windowsSupplier, swingWebViewAvailabilitySupplier, () -> new JcefAvailability(false, "Not checked", "Not checked"));
    }

    WebViewRuntimeStatusResolver(
            @NonNull SettingsRepo settingsRepo,
            @NonNull BooleanSupplier macOsSupplier,
            @NonNull BooleanSupplier windowsSupplier,
            @NonNull Supplier<SwingWebViewAvailability> swingWebViewAvailabilitySupplier,
            @NonNull Supplier<JcefAvailability> jcefAvailabilitySupplier
    ) {
        this.settingsRepo = settingsRepo;
        this.macOsSupplier = macOsSupplier;
        this.windowsSupplier = windowsSupplier;
        this.swingWebViewAvailabilitySupplier = swingWebViewAvailabilitySupplier;
        this.jcefAvailabilitySupplier = jcefAvailabilitySupplier;
    }

    public WebViewRuntimeStatus resolve() {
        SwingWebViewAvailability swingAvailability = swingWebViewAvailabilitySupplier.get();
        JcefAvailabilityMemo jcefAvailability = new JcefAvailabilityMemo(jcefAvailabilitySupplier);
        WebViewEngine configuredEngine = resolveConfiguredEngine();
        Resolution resolution = resolveActiveEngine(configuredEngine, swingAvailability, jcefAvailability);
        JcefAvailability resolvedJcefAvailability = jcefAvailability.resolvedOrNotChecked();

        return new WebViewRuntimeStatus(
                configuredEngine,
                resolution.activeEngine(),
                swingAvailability.available(),
                swingAvailability.mode(),
                resolvedJcefAvailability.available(),
                resolvedJcefAvailability.mode(),
                resolution.fallbackReason()
        );
    }

    private Resolution resolveActiveEngine(
            WebViewEngine configuredEngine,
            SwingWebViewAvailability swingAvailability,
            JcefAvailabilityMemo jcefAvailability
    ) {
        if (isAvailable(configuredEngine, swingAvailability, jcefAvailability)) {
            return new Resolution(configuredEngine, "");
        }

        List<String> unavailableReasons = new ArrayList<>();
        unavailableReasons.add(unavailableReason(configuredEngine, swingAvailability, jcefAvailability));

        for (WebViewEngine candidate : platformFallbackChain()) {
            if (candidate == configuredEngine) {
                continue;
            }
            if (isAvailable(candidate, swingAvailability, jcefAvailability)) {
                return new Resolution(candidate, String.join("; ", unavailableReasons));
            }
            unavailableReasons.add(unavailableReason(candidate, swingAvailability, jcefAvailability));
        }

        return new Resolution(WebViewEngine.JEDITOR_PANE, String.join("; ", unavailableReasons));
    }

    private boolean isAvailable(
            WebViewEngine engine,
            SwingWebViewAvailability swingAvailability,
            JcefAvailabilityMemo jcefAvailability
    ) {
        return switch (engine) {
            case JEDITOR_PANE -> true;
            case SYSTEM -> swingAvailability.available();
            case JCEF -> jcefAvailability.get().available();
        };
    }

    private String unavailableReason(
            WebViewEngine engine,
            SwingWebViewAvailability swingAvailability,
            JcefAvailabilityMemo jcefAvailability
    ) {
        return switch (engine) {
            case JEDITOR_PANE -> "";
            case SYSTEM -> "%s unavailable: %s".formatted(
                    engine.displayName(),
                    StringUtils.defaultIfBlank(swingAvailability.reason(), "unknown error")
            );
            case JCEF -> "%s unavailable: %s".formatted(
                    engine.displayName(),
                    StringUtils.defaultIfBlank(jcefAvailability.get().reason(), "unknown error")
            );
        };
    }

    private WebViewEngine resolveConfiguredEngine() {
        try {
            String configuredValue = settingsRepo.get(SettingsKeys.WEBVIEW_ENGINE, "");
            return WebViewEngine.fromSettingValue(configuredValue, platformFallbackChain().getFirst());
        } catch (Exception e) {
            return platformFallbackChain().getFirst();
        }
    }

    private List<WebViewEngine> platformFallbackChain() {
        if (macOsSupplier.getAsBoolean() || windowsSupplier.getAsBoolean()) {
            return List.of(WebViewEngine.SYSTEM, WebViewEngine.JCEF, WebViewEngine.JEDITOR_PANE);
        }
        return List.of(WebViewEngine.JCEF, WebViewEngine.JEDITOR_PANE);
    }

    private static SwingWebViewAvailability resolveSwingWebViewAvailability() {
        try {
            WebViewComponent.Mode mode = WebViewComponent.resolveDefaultMode();
            return new SwingWebViewAvailability(true, mode.name(), "");
        } catch (Throwable t) {
            return new SwingWebViewAvailability(false, "Unavailable", ExceptionUtils.getRootCauseMessage(t));
        }
    }

    private static JcefAvailability resolveJcefAvailability() {
        JcefRuntime.Availability availability = JcefRuntime.getInstance().availability();
        return new JcefAvailability(availability.available(), availability.mode(), availability.reason());
    }

    record SwingWebViewAvailability(boolean available, String mode, String reason) {
        @Override
        public String toString() {
            return "SwingWebViewAvailability[available=%s, mode=%s, reason=<masked>]".formatted(available, mode);
        }
    }

    record JcefAvailability(boolean available, String mode, String reason) {
        @Override
        public String toString() {
            return "JcefAvailability[available=%s, mode=%s, reason=<masked>]".formatted(available, mode);
        }
    }

    private record Resolution(WebViewEngine activeEngine, String fallbackReason) {
    }

    private static final class JcefAvailabilityMemo {
        private final Supplier<JcefAvailability> supplier;
        private JcefAvailability value;
        private boolean resolved;

        private JcefAvailabilityMemo(Supplier<JcefAvailability> supplier) {
            this.supplier = supplier;
        }

        private JcefAvailability get() {
            if (!resolved) {
                value = supplier.get();
                resolved = true;
            }
            return value;
        }

        private JcefAvailability resolvedOrNotChecked() {
            return resolved ? value : new JcefAvailability(false, "Not checked", "");
        }
    }
}
