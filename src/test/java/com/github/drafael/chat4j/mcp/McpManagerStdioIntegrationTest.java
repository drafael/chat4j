package com.github.drafael.chat4j.mcp;

import com.github.drafael.chat4j.chat.agent.ToolInvocationRequest;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.McpSecretVault;
import com.github.drafael.chat4j.provider.support.ProcessHandleSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpManagerStdioIntegrationTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("A long-running stdio server is reused across sequential Agent runs")
    void openRun_whenStdioServerIsLongRunning_reusesProcessAcrossRuns() {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var subject = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                System.getenv(),
                storagePaths.appConfigDirectory()
        );
        String javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                Strings.CI.contains(System.getProperty("os.name", ""), "win") ? "java.exe" : "java"
        ).toString();
        var configured = new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Stdio fixture",
                "stdio_fixture",
                true,
                true,
                McpTransportType.STDIO,
                "",
                javaExecutable,
                List.of("-cp", System.getProperty("java.class.path"), McpStdioFixtureMain.class.getName()),
                emptyList(),
                emptyList(),
                true,
                emptySet()
        );
        try {
            subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    new McpConfiguration(1, List.of(configured))
            )).join();
            String firstPid = invokePid(subject);
            String secondPid = invokePid(subject);

            assertThat(firstPid).isNotBlank().isEqualTo(secondPid);
            long processId = Long.parseLong(firstPid);
            subject.close();
            assertThat(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Shutdown forcibly terminates a TERM-resistant stdio descendant")
    void close_whenStdioDescendantIgnoresTerm_forciblyTerminatesDescendant() throws Exception {
        Assumptions.assumeFalse(Strings.CI.contains(System.getProperty("os.name", ""), "win"));
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var subject = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                System.getenv(),
                storagePaths.appConfigDirectory()
        );
        Path childPidFile = tempDirectory.resolve("child.pid");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var configured = new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Stdio fixture",
                "stdio_fixture",
                true,
                true,
                McpTransportType.STDIO,
                "",
                javaExecutable,
                List.of(
                        "-cp",
                        System.getProperty("java.class.path"),
                        McpStdioFixtureMain.class.getName(),
                        "--spawn-term-resistant-child",
                        childPidFile.toString()
                ),
                emptyList(),
                emptyList(),
                true,
                emptySet()
        );
        long childPid = -1;
        try {
            subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    new McpConfiguration(1, List.of(configured))
            )).join();
            try (McpRunSession ignored = subject.openRun(() -> false)) {
                childPid = Long.parseLong(Files.readString(childPidFile));
            }
        } finally {
            subject.close();
        }

        assertThat(childPid).isPositive();
        assertThat(ProcessHandle.of(childPid).map(ProcessHandleSupport::isRunning).orElse(false)).isFalse();
    }

    @Test
    @DisplayName("Enabled server acquisition failure identifies repair or disable actions")
    void openRun_whenEnabledServerCannotStart_providesServerSpecificGuidance() {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var subject = manager(storagePaths);
        McpServerConfiguration configured = stdioServer(UUID.randomUUID().toString(), emptyList());
        configured = new McpServerConfiguration(
                configured.id(),
                "Broken build tools",
                configured.modelId(),
                configured.enabled(),
                configured.automatic(),
                configured.transport(),
                configured.endpoint(),
                tempDirectory.resolve("missing-mcp-executable").toString(),
                configured.arguments(),
                configured.headers(),
                configured.environment(),
                configured.longRunning(),
                configured.disabledTools()
        );
        try {
            subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    new McpConfiguration(1, List.of(configured))
            )).join();

            assertThatThrownBy(() -> subject.openRun(() -> false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Broken build tools", "Verify or repair", "disable it");
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("CRLF-framed stdio responses initialize and execute normally")
    void openRun_whenServerUsesCrLf_acceptsWindowsLineFraming() {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var subject = manager(storagePaths);
        McpServerConfiguration configured = stdioServer(
                UUID.randomUUID().toString(),
                List.of("--crlf")
        );
        try {
            subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    new McpConfiguration(1, List.of(configured))
            )).join();

            assertThat(invokePid(subject)).isNotBlank();
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Stdio environment credentials may contain newlines while NUL remains invalid")
    void openRun_whenEnvironmentValueContainsNewline_passesCompleteValueToProcess() {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var subject = manager(storagePaths);
        String rowId = UUID.randomUUID().toString();
        McpServerConfiguration base = stdioServer(
                UUID.randomUUID().toString(),
                List.of("--echo-env", "MULTILINE_TOKEN")
        );
        var configured = new McpServerConfiguration(
                base.id(),
                base.name(),
                base.modelId(),
                base.enabled(),
                base.automatic(),
                base.transport(),
                base.endpoint(),
                base.executable(),
                base.arguments(),
                base.headers(),
                List.of(new McpSecretReference(rowId, "MULTILINE_TOKEN", "")),
                base.longRunning(),
                base.disabledTools()
        );
        try {
            subject.saveAndApply(new McpConfigurationDraft(
                    new McpConfiguration(1, List.of(configured)),
                    Map.of(rowId, "line1\nline2".toCharArray())
            )).join();

            assertThat(invokePid(subject)).isEqualTo("length=11,newline=true");
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Structurally different argument lists retire a retained stdio client")
    void saveAndApply_whenArgumentListsHaveCollidingStringForms_retiresOldProcess() {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var subject = manager(storagePaths);
        String serverId = UUID.randomUUID().toString();
        McpServerConfiguration first = stdioServer(serverId, List.of("a, b"));
        try {
            subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    new McpConfiguration(1, List.of(first))
            )).join();
            String firstPid = invokePid(subject);
            McpServerConfiguration changed = stdioServer(serverId, List.of("a", "b"));

            subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    new McpConfiguration(1, List.of(changed))
            )).join();
            String secondPid = invokePid(subject);

            assertThat(secondPid).isNotEqualTo(firstPid);
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Unexpected stdio EOF prevents retained client reuse")
    void openRun_whenRetainedServerExits_reconnectsOnNextExplicitRun() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var subject = manager(storagePaths);
        Path pidFile = tempDirectory.resolve("eof.pid");
        McpServerConfiguration configured = stdioServer(
                UUID.randomUUID().toString(),
                List.of("--write-pid", pidFile.toString(), "--exit-after-list")
        );
        try {
            subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    new McpConfiguration(1, List.of(configured))
            )).join();
            long firstPid;
            try (McpRunSession ignored = subject.openRun(() -> false)) {
                firstPid = Long.parseLong(Files.readString(pidFile));
                awaitProcessExit(firstPid);
            }

            try (McpRunSession ignored = subject.openRun(() -> false)) {
                long secondPid = Long.parseLong(Files.readString(pidFile));
                assertThat(secondPid).isNotEqualTo(firstPid);
            }
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Runtime shutdown owns and terminates a client still initializing")
    void beginRuntimeShutdown_whenClientIsConnecting_terminatesCandidateAndSettlesAttempt() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var subject = manager(storagePaths);
        Path pidFile = tempDirectory.resolve("connecting.pid");
        McpServerConfiguration configured = stdioServer(
                UUID.randomUUID().toString(),
                List.of("--write-pid", pidFile.toString(), "--never-read")
        );
        CompletableFuture<Void> connecting = null;
        long pid = -1;
        try {
            subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    new McpConfiguration(1, List.of(configured))
            )).join();
            CompletableFuture<Void> connection = CompletableFuture.runAsync(() -> subject.openRun(() -> false));
            connecting = connection;
            pid = awaitPid(pidFile);

            subject.beginRuntimeShutdown();

            assertThatThrownBy(connection::join).isInstanceOf(CompletionException.class);
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally {
            try {
                subject.beginRuntimeShutdown();
            } finally {
                try {
                    if (connecting != null) {
                        connecting.handle((ignored, error) -> null).join();
                    }
                } finally {
                    try {
                        subject.close();
                    } finally {
                        destroyForciblyAndAwait(pid);
                    }
                }
            }
        }
    }

    private void awaitProcessExit(long pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Fixture process did not exit.");
            }
            Thread.onSpinWait();
        }
    }

    private void destroyForciblyAndAwait(long pid) throws Exception {
        if (pid <= 0) {
            return;
        }
        ProcessHandle process = ProcessHandle.of(pid).orElse(null);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            process.onExit().get(5, TimeUnit.SECONDS);
        }
    }

    private McpManager manager(StoragePaths storagePaths) {
        return new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                System.getenv(),
                storagePaths.appConfigDirectory()
        );
    }

    private McpServerConfiguration stdioServer(String id, List<String> fixtureArguments) {
        String javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                Strings.CI.contains(System.getProperty("os.name", ""), "win") ? "java.exe" : "java"
        ).toString();
        List<String> arguments = new ArrayList<>(List.of(
                "-cp",
                System.getProperty("java.class.path"),
                McpStdioFixtureMain.class.getName()
        ));
        arguments.addAll(fixtureArguments);
        return new McpServerConfiguration(
                id,
                "Stdio fixture",
                "stdio_fixture",
                true,
                true,
                McpTransportType.STDIO,
                "",
                javaExecutable,
                arguments,
                emptyList(),
                emptyList(),
                true,
                emptySet()
        );
    }

    private long awaitPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            if (Files.isRegularFile(pidFile)) {
                String value = Files.readString(pidFile);
                if (StringUtils.isNotBlank(value)) {
                    return Long.parseLong(value);
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Fixture PID file was not created.");
            }
            Thread.onSpinWait();
        }
    }

    private String invokePid(McpManager manager) {
        try (McpRunSession run = manager.openRun(() -> false)) {
            String alias = run.tools().getFirst().name();
            var request = new ToolInvocationRequest(UUID.randomUUID().toString(), alias, "{}");
            return run.invoke(alias, emptyMap(), request, () -> false).output();
        }
    }
}
