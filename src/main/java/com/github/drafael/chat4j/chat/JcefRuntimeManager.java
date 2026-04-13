package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.storage.StoragePaths;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.CefInitializationException;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import me.friwi.jcefmaven.UnsupportedPlatformException;
import org.cef.CefApp;

import java.awt.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JcefRuntimeManager {

    private static final Object LOCK = new Object();
    private static volatile CefApp cefApp;

    private JcefRuntimeManager() {
    }

    public static void initializeOrThrow() {
        initializeOrThrow(StoragePaths.defaultPaths().appConfigDirectory().resolve("jcef"));
    }

    public static void initializeOrThrow(Path installDirectory) {
        synchronized (LOCK) {
            if (cefApp != null) {
                return;
            }

            validateRequiredJvmModuleAccess();

            try {
                Files.createDirectories(installDirectory);

                CefAppBuilder builder = new CefAppBuilder();
                builder.setInstallDir(installDirectory.toFile());
                builder.getCefSettings().cache_path = installDirectory.resolve("cache").toString();
                builder.getCefSettings().windowless_rendering_enabled = false;
                builder.addJcefArgs("--force-color-profile=srgb");
                builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                });
                cefApp = builder.build();
            } catch (IOException | UnsupportedPlatformException | InterruptedException | CefInitializationException e) {
                throw new IllegalStateException("Failed to initialize JCEF runtime", e);
            }
        }
    }

    private static void validateRequiredJvmModuleAccess() {
        Module javaDesktop = Component.class.getModule();
        Module current = JcefRuntimeManager.class.getModule();

        boolean hasSunAwtExport = javaDesktop.isExported("sun.awt", current);
        boolean hasSunAwtOpen = javaDesktop.isOpen("sun.awt", current);
        boolean hasAwtPeerOpen = javaDesktop.isOpen("java.awt.peer", current);

        if (!hasSunAwtExport || !hasSunAwtOpen || !hasAwtPeerOpen) {
            throw new IllegalStateException("Missing JVM module exports for JCEF. "
                    + "Required JVM options: --add-exports=java.desktop/sun.awt=ALL-UNNAMED "
                    + "--add-opens=java.desktop/sun.awt=ALL-UNNAMED "
                    + "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED");
        }

        if (isMacOs()) {
            boolean hasLwawtOpen = javaDesktop.isOpen("sun.lwawt", current);
            boolean hasMacLwawtOpen = !javaDesktop.getPackages().contains("sun.lwawt.macosx")
                    || javaDesktop.isOpen("sun.lwawt.macosx", current);

            if (!hasLwawtOpen || !hasMacLwawtOpen) {
                throw new IllegalStateException("Missing macOS JVM module opens for JCEF. "
                        + "Required JVM options: --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED "
                        + "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED");
            }
        }
    }

    private static boolean isMacOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac");
    }

    public static CefApp getAppOrThrow() {
        CefApp app = cefApp;
        if (app == null) {
            throw new IllegalStateException("JCEF runtime has not been initialized");
        }
        return app;
    }

    public static void shutdown() {
        synchronized (LOCK) {
            if (cefApp == null) {
                return;
            }

            try {
                cefApp.dispose();
            } catch (Exception e) {
                // ignore shutdown failures
            } finally {
                cefApp = null;
            }
        }
    }
}
