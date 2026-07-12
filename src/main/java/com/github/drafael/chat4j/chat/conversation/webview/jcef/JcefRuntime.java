package com.github.drafael.chat4j.chat.conversation.webview.jcef;

import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.persistence.StoragePaths;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;

@Slf4j
public final class JcefRuntime {
    private static final String INSTALL_DIR_PROPERTY = "chat4j.jcef.installDir";
    private static final String DEVTOOLS_PROPERTY = "chat4j.jcef.devtools";
    private static final int DEVTOOLS_PORT = 9222;
    private static final JcefRuntime INSTANCE = new JcefRuntime(defaultInstallDir());

    private final Path installDir;
    private CefApp cefApp;
    private Availability availability;

    private JcefRuntime(Path installDir) {
        this.installDir = installDir.toAbsolutePath().normalize();
    }

    public static JcefRuntime getInstance() {
        return INSTANCE;
    }

    public synchronized Availability availability() {
        return availability(JcefProgressListener.NO_OP);
    }

    public synchronized Availability availability(@NonNull JcefProgressListener progressListener) {
        if (availability != null) {
            return availability;
        }
        availability = initialize(progressListener);
        return availability;
    }

    public synchronized Optional<Availability> cachedAvailability() {
        return Optional.ofNullable(availability);
    }

    public synchronized CefClient createClient() {
        Availability current = availability();
        if (!current.available()) {
            throw new IllegalStateException("JCEF unavailable: %s".formatted(current.reason()));
        }
        return cefApp.createClient();
    }

    private Availability initialize(@NonNull JcefProgressListener progressListener) {
        if (GraphicsEnvironment.isHeadless()) {
            return new Availability(false, "Headless", "headless environment");
        }
        if (!hasRequiredAwtModuleAccess()) {
            return new Availability(false, "Unavailable", "macOS JCEF requires AWT module exports/opens");
        }

        try {
            deleteLegacyProfileRootInsideInstallDir();
            Files.createDirectories(installDir);
            CefAppBuilder builder = new CefAppBuilder();
            builder.setInstallDir(installDir.toFile());
            builder.setProgressHandler((stage, progress) ->
                    progressListener.onProgress(JcefInitializationProgress.from(stage, progress)));
            CefSettings cefSettings = builder.getCefSettings();
            cefSettings.windowless_rendering_enabled = false;
            Path rootCachePath = installDir.resolveSibling("jcef-profile-root");
            Files.createDirectories(rootCachePath);
            cefSettings.root_cache_path = rootCachePath.toString();
            cefSettings.cache_path = rootCachePath.resolve("profile").toString();
            cefSettings.log_file = installDir.resolve("jcef.log").toString();
            cefSettings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_FATAL;
            if (Boolean.getBoolean(DEVTOOLS_PROPERTY)) {
                cefSettings.remote_debugging_port = DEVTOOLS_PORT;
            }
            addProductionJcefArgs(builder);
            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                @Override
                public void stateHasChanged(CefApp.CefAppState state) {
                    log.debug("JCEF state changed: {}", state);
                }
            });

            cefApp = builder.build();
            String mode = Boolean.getBoolean(DEVTOOLS_PROPERTY)
                    ? "Windowed/native · DevTools :%d".formatted(DEVTOOLS_PORT)
                    : "Windowed/native";
            return new Availability(true, mode, "");
        } catch (Throwable t) {
            return new Availability(false, "Unavailable", ExceptionUtils.getRootCauseMessage(t));
        }
    }

    private void deleteLegacyProfileRootInsideInstallDir() {
        Path legacyProfileRoot = installDir.resolve("profile-root");
        if (!Files.exists(legacyProfileRoot) && !Files.isSymbolicLink(legacyProfileRoot)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(legacyProfileRoot)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    log.debug("Failed to delete legacy JCEF profile path {}: {}", path, ExceptionUtils.getMessage(e));
                }
            });
        } catch (Exception e) {
            log.debug("Failed to delete legacy JCEF profile root {}: {}", legacyProfileRoot, ExceptionUtils.getMessage(e));
        }
    }

    private static void addProductionJcefArgs(@NonNull CefAppBuilder builder) {
        builder.addJcefArgs("--disable-gpu");
        builder.addJcefArgs("--disable-background-networking");
        builder.addJcefArgs("--disable-breakpad");
        builder.addJcefArgs("--disable-component-update");
        builder.addJcefArgs("--disable-sync");
        builder.addJcefArgs("--disable-extensions");
        builder.addJcefArgs("--disable-notifications");
        builder.addJcefArgs("--disable-default-apps");
        builder.addJcefArgs("--disable-gpu-shader-disk-cache");
        builder.addJcefArgs("--disable-in-process-stack-traces");
        builder.addJcefArgs("--disable-logging");
        builder.addJcefArgs("--disable-machine-learning-model-loader");
        builder.addJcefArgs("--gcm-channel-status=2");
        builder.addJcefArgs("--log-level=3");
        builder.addJcefArgs("--use-mock-keychain");
        builder.addJcefArgs("--no-first-run");
        builder.addJcefArgs("--no-default-browser-check");
        builder.addJcefArgs("--disable-client-side-phishing-detection");
        builder.addJcefArgs("--disable-component-extensions-with-background-pages");
        builder.addJcefArgs("--disable-domain-reliability");
        builder.addJcefArgs("--disable-features=AutofillServerCommunication,GatherProcessRequirementMetrics,GCM,MediaRouter,OnDeviceModelService,Optimization,OptimizationHints,PushMessaging,SpareRendererForSitePerProcess,TranslateUI");
    }

    static boolean hasRequiredAwtModuleAccess() {
        if (!SystemInfo.isMacOS) {
            return true;
        }

        Module desktopModule = ModuleLayer.boot().findModule("java.desktop").orElse(null);
        if (desktopModule == null) {
            return false;
        }

        Module targetModule = JcefRuntime.class.getModule();
        return hasPackageAccess(desktopModule, "sun.awt", targetModule)
                && hasPackageAccess(desktopModule, "sun.lwawt", targetModule)
                && hasPackageAccess(desktopModule, "sun.lwawt.macosx", targetModule);
    }

    private static boolean hasPackageAccess(Module sourceModule, String packageName, Module targetModule) {
        return sourceModule.isExported(packageName, targetModule) || sourceModule.isOpen(packageName, targetModule);
    }

    private static Path defaultInstallDir() {
        String configured = System.getProperty(INSTALL_DIR_PROPERTY);
        if (StringUtils.isNotBlank(configured)) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return StoragePaths.defaultPaths().jcefBundleDirectory();
    }

    public record Availability(boolean available, String mode, String reason) {
        public Availability {
            mode = StringUtils.defaultIfBlank(mode, available ? "Available" : "Unavailable");
            reason = StringUtils.defaultString(reason);
        }

        @Override
        public String toString() {
            return "Availability[available=%s, mode=%s, reason=<masked>]".formatted(available, mode);
        }
    }
}
