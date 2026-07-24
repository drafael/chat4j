package com.github.drafael.chat4j.bootstrap;

import java.util.Map;

/**
 * Shell-discovery facts produced during application startup.
 */
public record EnvironmentInitResult(Map<String, String> shellEnv, boolean macJpackageLaunch) {
    public EnvironmentInitResult {
        shellEnv = Map.copyOf(shellEnv);
    }
}
