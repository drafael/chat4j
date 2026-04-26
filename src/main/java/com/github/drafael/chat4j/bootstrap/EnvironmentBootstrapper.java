package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.env.ShellEnvironmentLoader;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.util.Fonts;
import com.formdev.flatlaf.util.SystemInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Initializes runtime environment variables and computes whether startup should warn the user.
 */
@Slf4j
public final class EnvironmentBootstrapper {

    /**
     * Loads shell environment for macOS jpackage launches and initializes {@link CredentialResolver}.
     */
    public EnvironmentInitResult initialize() {
        boolean macJpackageLaunch = isMacJpackageLaunch();
        log.info("Environment bootstrap started: macJpackageLaunch={}", macJpackageLaunch);

        Instant startedAt = Instant.now();
        Map<String, String> shellEnv = macJpackageLaunch ? loadShellEnvironment() : Map.of();

        CredentialResolver.init(shellEnv);

        boolean hasProviderCredentials = CredentialResolver.hasAnyProviderCredentials();
        boolean shouldWarnUser = shouldWarnUser(macJpackageLaunch, shellEnv, hasProviderCredentials);

        log.info(
                "Environment bootstrap resolved: shellEnvEntries={} providerCredentialsPresent={} warningRequired={} elapsedMs={}",
                shellEnv.size(),
                hasProviderCredentials,
                shouldWarnUser,
                Duration.between(startedAt, Instant.now()).toMillis()
        );

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

                JLabel label = new JLabel("Loading environment…");
                Fonts.apply(label, Font.PLAIN, Fonts.SIZE_BODY);
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

                Thread.ofVirtual().start(() -> {
                    Instant startedAt = Instant.now();
                    envResult.set(ShellEnvironmentLoader.loadFromLoginShell());
                    log.info(
                            "Shell environment bootstrap attempt finished: envEntries={} elapsedMs={}",
                            envResult.get().size(),
                            Duration.between(startedAt, Instant.now()).toMillis()
                    );
                    SwingUtilities.invokeLater(dialog::dispose);
                });

                dialog.setVisible(true);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Shell environment bootstrap interrupted: {}", ExceptionUtils.getMessage(e));
        } catch (InvocationTargetException e) {
            log.warn("Shell environment bootstrap dialog failed: {}", ExceptionUtils.getMessage(e));
        }
        return envResult.get();
    }
}
