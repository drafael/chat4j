package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.mcp.McpManager;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.support.CredentialMutationService;
import com.github.drafael.chat4j.settings.FontSettings;
import com.github.drafael.chat4j.settings.ThemeSettings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationBootstrapTest {

    @Test
    @DisplayName("Saved appearance application aborts later font reads when theme read fails")
    void applySavedAppearance_whenThemeReadFails_doesNotApplySavedFonts() {
        var settingsRepo = new ThemeReadFailingSettingsRepo();
        var subject = new ApplicationBootstrap(new EnvironmentBootstrapper());

        subject.applySavedAppearance(settingsRepo);

        assertThat(settingsRepo.readKeys).contains(ThemeSettings.THEME_ACCENT_KEY, ThemeSettings.THEME_NAME_KEY);
        assertThat(settingsRepo.readKeys)
                .doesNotContain(
                        FontSettings.APP_FONT_FAMILY_KEY,
                        FontSettings.APP_FONT_SIZE_KEY,
                        FontSettings.CODE_FONT_FAMILY_KEY
                );
    }

    @Test
    @DisplayName("Subprocess environment prefers process values except for shell PATH")
    void assembleSubprocessEnvironment_whenSourcesOverlap_appliesRequiredPrecedence() {
        Map<String, String> result = ApplicationBootstrap.assembleSubprocessEnvironment(
                Map.of("OPENAI_API_KEY", "process-key", "PATH", "/process/bin", "PROCESS_ONLY", "yes"),
                Map.of("OPENAI_API_KEY", "shell-key", "PATH", "/shell/bin", "SHELL_ONLY", "yes"),
                false
        );

        assertThat(result)
                .containsEntry("OPENAI_API_KEY", "process-key")
                .containsEntry("PATH", "/shell/bin")
                .containsEntry("PROCESS_ONLY", "yes")
                .containsEntry("SHELL_ONLY", "yes");
    }

    @Test
    @DisplayName("Blank shell PATH does not replace the process PATH")
    void assembleSubprocessEnvironment_whenShellPathBlank_keepsProcessPath() {
        Map<String, String> result = ApplicationBootstrap.assembleSubprocessEnvironment(
                Map.of("PATH", "/process/bin"),
                Map.of("PATH", "  "),
                false
        );

        assertThat(result).containsEntry("PATH", "/process/bin");
    }

    @Test
    @DisplayName("Windows environment assembly coalesces case-variant PATH keys")
    void assembleSubprocessEnvironment_whenWindowsKeysDifferByCase_keepsOneShellPath() {
        Map<String, String> result = ApplicationBootstrap.assembleSubprocessEnvironment(
                Map.of("Path", "C:\\Process", "PathExt", ".EXE;.CMD"),
                Map.of("PATH", "C:\\Shell", "PATHEXT", ".BAT"),
                true
        );

        assertThat(result.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("PATH")))
                .singleElement()
                .extracting(Map.Entry::getValue)
                .isEqualTo("C:\\Shell");
        assertThat(result.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("PATHEXT")))
                .singleElement()
                .extracting(Map.Entry::getValue)
                .isEqualTo(".EXE;.CMD");
    }

    @Test
    @DisplayName("Environment cancellation joins the owned task while preserving caller interruption")
    void cancelEnvironmentTask_whenCallerAlreadyInterrupted_joinsWorkerAndRestoresInterrupt() throws Exception {
        var started = new CountDownLatch(1);
        var cleaned = new CountDownLatch(1);
        var task = new FutureTask<>(() -> {
            started.countDown();
            try {
                TimeUnit.SECONDS.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                cleaned.countDown();
            }
            return null;
        });
        Thread environmentThread = Thread.ofVirtual().start(task);
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            var subject = new ApplicationBootstrap(new EnvironmentBootstrapper());
            Thread.currentThread().interrupt();
            subject.cancelEnvironmentTask(task, environmentThread);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(environmentThread.isAlive()).isFalse();
            assertThat(cleaned.getCount()).isZero();
        } finally {
            Thread.interrupted();
            task.cancel(true);
            environmentThread.interrupt();
            environmentThread.join(2_000);
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Credential cleanup failure remains suppressed behind the startup failure")
    void closeSecretsAfterFailure_whenCleanupThrows_preservesStartupFailure() {
        CredentialMutationService credentialMutationService = mock(CredentialMutationService.class);
        var startupFailure = new IllegalStateException("startup failed");
        var cleanupFailure = new AssertionError("cleanup failed");
        doThrow(cleanupFailure).when(credentialMutationService).closeSecrets();

        ApplicationBootstrap.closeSecretsAfterFailure(credentialMutationService, startupFailure);

        assertThat(startupFailure.getSuppressed()).containsExactly(cleanupFailure);
    }

    @Test
    @DisplayName("Bootstrap failure closes MCP ownership before credential vault ownership")
    void closeSharedServicesAfterFailure_whenBootstrapOwnsServices_closesManagerBeforeVault() {
        AppServices services = mock(AppServices.class);
        McpManager manager = mock(McpManager.class);
        CredentialMutationService credentials = mock(CredentialMutationService.class);
        when(services.mcpManager()).thenReturn(manager);
        when(services.credentialMutationService()).thenReturn(credentials);

        ApplicationBootstrap.closeSharedServicesAfterFailure(services, new IllegalStateException("startup failed"));

        var order = inOrder(manager, credentials);
        order.verify(manager).close();
        order.verify(credentials).closeSecrets();
    }

    @Test
    @DisplayName("Subprocess environment snapshots are immutable")
    void assembleSubprocessEnvironment_whenCreated_rejectsMutation() {
        Map<String, String> result = ApplicationBootstrap.assembleSubprocessEnvironment(
                Map.of("PATH", "/process/bin"),
                emptyMap(),
                false
        );

        assertThat(result).isUnmodifiable();
    }

    private static class ThemeReadFailingSettingsRepo extends SettingsRepository {

        private final List<String> readKeys = new ArrayList<>();

        private ThemeReadFailingSettingsRepo() {
            super(Path.of("unused-application-bootstrap.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            readKeys.add(key);
            if (ThemeSettings.THEME_NAME_KEY.equals(key)) {
                throw new IllegalStateException("forced theme read failure");
            }
            return defaultValue;
        }
    }
}
