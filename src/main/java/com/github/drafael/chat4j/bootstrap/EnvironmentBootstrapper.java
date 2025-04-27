package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.env.ShellEnvironmentLoader;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.formdev.flatlaf.util.SystemInfo;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Initializes runtime environment variables and computes whether startup should warn the user.
 */
public final class EnvironmentBootstrapper {

    /**
     * Loads shell environment for macOS jpackage launches and initializes {@link CredentialResolver}.
     */
    public EnvironmentInitResult initialize() {
        boolean macJpackageLaunch = isMacJpackageLaunch();
        Map<String, String> shellEnv = macJpackageLaunch ? loadShellEnvironment() : Map.of();

        CredentialResolver.init(shellEnv);

        boolean shouldWarnUser = shouldWarnUser(
            macJpackageLaunch,
            shellEnv,
            CredentialResolver.hasAnyProviderCredentials());

        return new EnvironmentInitResult(shellEnv, shouldWarnUser);
    }

    /**
     * Decision helper kept package-private for focused unit testing.
     */
    static boolean shouldWarnUser(
        boolean macJpackageLaunch,
        Map<String, String> shellEnv,
        boolean hasAnyProviderCredentials
    ) {
        return macJpackageLaunch && shellEnv.isEmpty() && !hasAnyProviderCredentials;
    }

    private boolean isMacJpackageLaunch() {
        return System.getProperty("jpackage.app-path") != null && SystemInfo.isMacOS;
    }

    private Map<String, String> loadShellEnvironment() {
        AtomicReference<Map<String, String>> envResult = new AtomicReference<>(Map.of());

        try {
            SwingUtilities.invokeAndWait(() -> {
                JDialog dialog = new JDialog((Frame) null, "Chat4J", true);

                JPanel content = new JPanel(new BorderLayout(10, 10));
                content.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

                JLabel label = new JLabel("Loading environment\u2026");
                label.setFont(label.getFont().deriveFont(13f));
                content.add(label, BorderLayout.NORTH);

                JProgressBar progress = new JProgressBar();
                progress.setIndeterminate(true);
                content.add(progress, BorderLayout.CENTER);

                dialog.setContentPane(content);
                dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                dialog.setResizable(false);
                dialog.pack();
                dialog.setSize(300, dialog.getHeight());
                dialog.setLocationRelativeTo(null);

                long showTime = System.currentTimeMillis();
                Thread.ofVirtual().start(() -> {
                    try {
                        envResult.set(ShellEnvironmentLoader.loadFromLoginShell());
                        long elapsed = System.currentTimeMillis() - showTime;
                        long remaining = 500 - elapsed;
                        if (remaining > 0) {
                            Thread.sleep(remaining);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        SwingUtilities.invokeLater(dialog::dispose);
                    }
                });

                dialog.setVisible(true);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            // Continue without splash if EDT setup fails
        }
        return envResult.get();
    }
}
