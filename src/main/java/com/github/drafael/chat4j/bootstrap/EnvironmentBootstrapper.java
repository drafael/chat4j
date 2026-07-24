package com.github.drafael.chat4j.bootstrap;

import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.env.ShellEnvironmentLoader;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static java.util.Collections.emptyMap;

/**
 * Discovers the login-shell environment without constructing credential services or Swing UI.
 */
@Slf4j
public final class EnvironmentBootstrapper {

    public EnvironmentInitResult initialize() {
        boolean macJpackageLaunch = isMacJpackageLaunch();
        log.info("Environment bootstrap started: macJpackageLaunch={}", macJpackageLaunch);

        Instant startedAt = Instant.now();
        Map<String, String> shellEnvironment = macJpackageLaunch
                ? ShellEnvironmentLoader.loadFromLoginShell()
                : emptyMap();

        log.info(
                "Environment bootstrap resolved: shellEnvEntries={} elapsedMs={}",
                shellEnvironment.size(),
                Duration.between(startedAt, Instant.now()).toMillis()
        );
        return new EnvironmentInitResult(shellEnvironment, macJpackageLaunch);
    }

    static boolean shouldWarnUser(
            boolean macJpackageLaunch,
            Map<String, String> shellEnvironment,
            boolean hasAnyProviderCredentials
    ) {
        return macJpackageLaunch && shellEnvironment.isEmpty() && !hasAnyProviderCredentials;
    }

    private boolean isMacJpackageLaunch() {
        return System.getProperty("jpackage.app-path") != null && SystemInfo.isMacOS;
    }
}
