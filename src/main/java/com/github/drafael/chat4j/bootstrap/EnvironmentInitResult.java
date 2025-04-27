package com.github.drafael.chat4j.bootstrap;

import java.util.Map;

/**
 * Result of environment initialization performed during application startup.
 */
public record EnvironmentInitResult(Map<String, String> shellEnv, boolean shouldWarnUser) {
}
