package com.github.drafael.chat4j.tts.provider.system;

import java.util.Locale;
import java.util.Map;

class SystemTtsBackendFactory {

    SystemTtsBackend createDefault(
            String osName,
            Map<String, String> environment,
            SystemTtsProcessRunner runner,
            SystemTtsExecutableLocator locator
    ) {
        String normalizedOs = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalizedOs.contains("mac")) {
            return new MacOsSayBackend(runner, environment);
        }
        if (normalizedOs.contains("win")) {
            return new WindowsSapiBackend(runner, locator, environment);
        }
        if (normalizedOs.contains("linux")) {
            return new LinuxEspeakBackend(runner, locator, environment);
        }
        return new UnavailableSystemTtsBackend();
    }
}
