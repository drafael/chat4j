package com.github.drafael.chat4j.bootstrap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.drafael.chat4j.chat.webview.WebViewEngine;
import com.github.drafael.chat4j.chat.webview.WebViewSettings;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class JcefStartupInitializationDeciderTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Linux default initializes JCEF at startup")
    void shouldInitialize_whenDefaultEngineOutsideMacOsAndWindows_returnsTrue() {
        var subject = decider(false, false, true);

        boolean result = subject.shouldInitialize(settingsRepo());

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("macOS default skips JCEF when System WebView is available")
    void shouldInitialize_whenDefaultMacOsSystemWebViewAvailable_returnsFalse() {
        var subject = decider(true, false, true);

        boolean result = subject.shouldInitialize(settingsRepo());

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("macOS default initializes JCEF when System WebView is unavailable")
    void shouldInitialize_whenDefaultMacOsSystemWebViewUnavailable_returnsTrue() {
        var subject = decider(true, false, false);

        boolean result = subject.shouldInitialize(settingsRepo());

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Configured JCEF initializes even when System WebView is available")
    void shouldInitialize_whenJcefConfigured_returnsTrue() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.JCEF.settingValue());
        var subject = decider(true, false, true);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Configured System WebView skips JCEF when system engine is available")
    void shouldInitialize_whenSystemWebViewConfiguredAndAvailable_returnsFalse() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        var subject = decider(false, false, true);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Configured System WebView initializes JCEF when system engine is unavailable")
    void shouldInitialize_whenSystemWebViewConfiguredAndUnavailable_returnsTrue() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.SYSTEM.settingValue());
        var subject = decider(false, false, false);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Configured JEditorPane skips JCEF startup initialization")
    void shouldInitialize_whenJEditorPaneConfigured_returnsFalse() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(WebViewSettings.ENGINE_KEY, WebViewEngine.JEDITOR_PANE.settingValue());
        var subject = decider(false, false, false);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Invalid engine setting uses the non-system platform default")
    void shouldInitialize_whenInvalidEngineConfiguredOutsideMacOsAndWindows_returnsTrue() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(WebViewSettings.ENGINE_KEY, "invalid");
        var subject = decider(false, false, false);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Settings read failure logs warning and uses platform default")
    void shouldInitialize_whenSettingsReadFails_logsWarningAndUsesPlatformDefault() {
        var subject = decider(true, false, true);
        ListAppender<ILoggingEvent> appender = attachLogAppender();

        boolean result;
        try {
            result = subject.shouldInitialize(new ThrowingSettingsRepo());
        } finally {
            detachLogAppender(appender);
        }

        assertThat(result).isFalse();
        assertThat(appender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("Failed to resolve configured web-view engine", "forced failure");
                });
    }

    @Test
    @DisplayName("Obsolete engine setting uses the macOS platform default")
    void shouldInitialize_whenObsoleteEngineConfiguredOnMacOsAndSystemWebViewAvailable_returnsFalse() throws Exception {
        SettingsRepository settingsRepo = settingsRepo();
        settingsRepo.put(WebViewSettings.ENGINE_KEY, "obsolete-webview");
        var subject = decider(true, false, true);

        boolean result = subject.shouldInitialize(settingsRepo);

        assertThat(result).isFalse();
    }

    private JcefStartupInitializationDecider decider(boolean macOs, boolean windows, boolean systemWebViewAvailable) {
        return new JcefStartupInitializationDecider(
                () -> macOs,
                () -> windows,
                () -> systemWebViewAvailable
        );
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(JcefStartupInitializationDecider.class);
        logger.setLevel(Level.WARN);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(JcefStartupInitializationDecider.class);
        logger.detachAppender(appender);
    }

    private SettingsRepository settingsRepo() {
        return new SettingsRepository(tempDir.resolve("settings.properties"));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-jcef-startup.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }
}
