package com.github.drafael.chat4j;

import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.bootstrap.ApplicationBootstrap;
import com.github.drafael.chat4j.logging.LoggingBootstrap;
import com.github.drafael.chat4j.startup.NativeStderrNoiseFilter;
import com.github.drafael.chat4j.startup.StartupFallbackLogger;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

@Slf4j
public class App {

    private static final String APP_PATH_PROPERTY = "jpackage.app-path";
    private static final String DOCTOR_SCRIPT_RELATIVE_PATH = "Contents/app/tools/chat4j-doctor.sh";
    private static final String JAVA2D_METAL_PROPERTY = "sun.java2d.metal";

    public static void main(String[] args) {
        configureNativeGraphicsPipeline();
        installMacOsAppKitStderrNoiseFilter();
        StartupFallbackLogger.info("Chat4J startup entrypoint invoked");

        try {
            LoggingBootstrap.initialize();
            installUncaughtExceptionLogger();
            StartupFallbackLogger.info("Primary logging bootstrap initialized");
        } catch (Throwable t) {
            StartupFallbackLogger.error("Primary logging bootstrap initialization failed", t);
        }

        try {
            new ApplicationBootstrap().start();
        } catch (Throwable t) {
            StartupFallbackLogger.error("Application bootstrap failed", t);
            showStartupFailureDialog(t);
            System.exit(1);
        }
    }

    static void configureNativeGraphicsPipeline() {
        configureNativeGraphicsPipeline(() -> SystemInfo.isMacOS);
    }

    static void configureNativeGraphicsPipeline(BooleanSupplier macOsSupplier) {
        if (macOsSupplier.getAsBoolean() && System.getProperty(JAVA2D_METAL_PROPERTY) == null) {
            System.setProperty(JAVA2D_METAL_PROPERTY, "false");
        }
    }

    private static void installMacOsAppKitStderrNoiseFilter() {
        if (SystemInfo.isMacOS) {
            NativeStderrNoiseFilter.installMacOsAppKitThreadFilter();
        }
    }

    private static void installUncaughtExceptionLogger() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String message = "Uncaught exception on thread %s".formatted(thread.getName());
            StartupFallbackLogger.error(message, throwable);
            log.error(message, throwable);
        });
    }

    private static void showStartupFailureDialog(Throwable throwable) {
        String reason = ExceptionUtils.getMessage(throwable);
        String fallbackLogPath = StartupFallbackLogger.fallbackLogPath();
        String doctorHint = doctorCommandHint();

        String message = """
                Chat4J failed to start.

                Reason: %s

                Startup diagnostics were written to:
                %s

                To run startup diagnostics manually:
                %s
                """.formatted(reason, fallbackLogPath, doctorHint);

        StartupFallbackLogger.error(message, null);

        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        try {
            JOptionPane.showMessageDialog(
                    null,
                    message,
                    "Chat4J Startup Error",
                    JOptionPane.ERROR_MESSAGE
            );
        } catch (Exception ignored) {
            // Ignore UI errors to avoid masking startup diagnostics output.
        }
    }

    private static String doctorCommandHint() {
        String appPath = System.getProperty(APP_PATH_PROPERTY);
        if (StringUtils.isBlank(appPath)) {
            return "bash scripts/chat4j-doctor.sh --app \"/Applications/Chat4J.app\"";
        }

        try {
            Path launcherPath = Path.of(appPath);
            Path contentsPath = launcherPath.getParent() != null ? launcherPath.getParent().getParent() : null;
            Path appBundle = contentsPath != null ? contentsPath.getParent() : null;
            if (appBundle == null) {
                return "chat4j-doctor --app \"/Applications/Chat4J.app\"";
            }

            Path bundledScript = appBundle.resolve(DOCTOR_SCRIPT_RELATIVE_PATH);
            if (Files.exists(bundledScript)) {
                return "bash \"%s\" --app \"%s\"".formatted(bundledScript, appBundle);
            }

            return "chat4j-doctor --app \"%s\"".formatted(appBundle);
        } catch (Exception e) {
            return "chat4j-doctor --app \"/Applications/Chat4J.app\"";
        }
    }
}
