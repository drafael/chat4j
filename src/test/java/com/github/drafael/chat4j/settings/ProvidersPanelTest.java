package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.sun.net.httpserver.HttpServer;
import java.awt.Component;
import java.awt.Container;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class ProvidersPanelTest {

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() {
        CredentialResolver.configureTokenVault(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        CredentialResolver.init(emptyMap());
    }

    @AfterEach
    void tearDown() {
        CredentialResolver.configureTokenVault(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        CredentialResolver.init(emptyMap());
    }

    @Test
    @DisplayName("Provider panel configured base URL reports read failures through status path")
    void configuredProviderBaseUrl_whenReadFails_returnsDefaultAndShowsStatusError() throws Exception {
        ProvidersPanel subject = callOnEdt(() -> new ProvidersPanel(new ThrowingSettingsRepo()));
        try {
            callOnEdt(() -> {
                String baseUrl = subject.configuredProviderBaseUrl(
                        "LM Studio",
                        ProvidersPanel.ProviderInfo.local("http://localhost:1234/v1")
                );

                assertThat(baseUrl).isEqualTo("http://localhost:1234/v1");
                assertThat(subject.statusLabel().isVisible()).isTrue();
                assertThat(subject.statusLabel().getText())
                        .contains("Failed to read setting", "chat4j.provider.lm-studio.baseUrl");
                return null;
            });
        } finally {
            runOnEdt(subject::removeNotify);
            runOnEdt(() -> {
            });
        }
    }

    @Test
    @DisplayName("Provider credential refresh hides stale missing token guidance")
    void refreshProviderCredentialUi_whenTokenWasSaved_hidesMissingTokenInfoPanel() throws Exception {
        ProvidersPanel subject = callOnEdt(() -> new ProvidersPanel(
                new SettingsRepository(tempDir.resolve("providers.properties"))
        ));
        try {
            JLabel statusLabel = callOnEdt(JLabel::new);
            JPanel missingTokenInfoPanel = callOnEdt(JPanel::new);
            char[] token = "saved-token".toCharArray();
            try {
                CredentialResolver.saveTokenOverride("OPENAI_API_KEY", token);
            } finally {
                Arrays.fill(token, '\0');
            }

            runOnEdt(() -> subject.refreshProviderCredentialUi(
                    statusLabel,
                    "OpenAI",
                    ProvidersPanel.ProviderInfo.envVar("OPENAI_API_KEY", "https://api.openai.com/v1"),
                    missingTokenInfoPanel
            ));

            callOnEdt(() -> {
                assertThat(statusLabel.getText()).contains("Saved token configured");
                assertThat(missingTokenInfoPanel.isVisible()).isFalse();
                return null;
            });
        } finally {
            runOnEdt(subject::removeNotify);
            runOnEdt(() -> {
            });
        }
    }

    @Test
    @DisplayName("Local provider status updates when the initial background probe completes")
    void createDetailPanel_whenLocalProviderIsReachable_updatesInitialStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        ProvidersPanel subject = null;
        try {
            String baseUrl = "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());
            var settingsRepo = new SettingsRepository(tempDir.resolve("local-provider.properties"));
            settingsRepo.put("chat4j.provider.lm-studio.baseUrl", "  %s  ".formatted(baseUrl));
            subject = callOnEdt(() -> new ProvidersPanel(settingsRepo));
            ProvidersPanel panel = subject;

            awaitCondition(2, TimeUnit.SECONDS, () -> callOnEdt(() -> containsLabelText(
                    panel,
                    "\u2713 Running at %s".formatted(baseUrl)
            )));
        } finally {
            if (subject != null) {
                ProvidersPanel panel = subject;
                runOnEdt(panel::removeNotify);
                runOnEdt(() -> {
                });
            }
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Provider panel base URL helper falls back for blanks and trims configured values")
    void fallbackBlankProviderBaseUrl_whenValueIsConfigured_returnsNormalizedValue() {
        assertThat(ProvidersPanel.fallbackBlankProviderBaseUrl(null, "http://localhost:1234/v1"))
                .isEqualTo("http://localhost:1234/v1");
        assertThat(ProvidersPanel.fallbackBlankProviderBaseUrl("   ", "http://localhost:1234/v1"))
                .isEqualTo("http://localhost:1234/v1");
        assertThat(ProvidersPanel.fallbackBlankProviderBaseUrl(
                "  http://127.0.0.1:1234/v1  ",
                "http://localhost:1234/v1"
        )).isEqualTo("http://127.0.0.1:1234/v1");
    }

    private static boolean containsLabelText(Component component, String expectedText) {
        if (component instanceof JLabel label && expectedText.equals(label.getText())) {
            return true;
        }
        if (!(component instanceof Container container)) {
            return false;
        }
        return Arrays.stream(container.getComponents())
                .anyMatch(child -> containsLabelText(child, expectedText));
    }

    private static void awaitCondition(long timeout, TimeUnit unit, CheckedBooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void runOnEdt(ThrowingAction action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }

        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-providers-panel.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }
}
