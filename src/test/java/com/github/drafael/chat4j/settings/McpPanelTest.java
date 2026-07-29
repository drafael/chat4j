package com.github.drafael.chat4j.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.chat.agent.ToolInvocationRequest;
import com.github.drafael.chat4j.mcp.McpApplyResult;
import com.github.drafael.chat4j.mcp.McpConfiguration;
import com.github.drafael.chat4j.mcp.McpConfigurationDraft;
import com.github.drafael.chat4j.mcp.McpConfigurationRepository;
import com.github.drafael.chat4j.mcp.McpManager;
import com.github.drafael.chat4j.mcp.McpSecretReference;
import com.github.drafael.chat4j.mcp.McpServerConfiguration;
import com.github.drafael.chat4j.mcp.McpTransportType;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.McpSecretVault;
import com.sun.net.httpserver.HttpServer;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;

class McpPanelTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("Adding and saving a server keeps Swing work on the EDT and publishes MCP JSON")
    void savePendingChangesAsync_whenServerIsAdded_publishesConfigurationOffEdt() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var manager = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        try {
            McpPanel subject = callOnEdt(() -> new McpPanel(manager));
            try {
                var publication = callOnEdt(() -> {
                    findButton(subject, "+").doClick();
                    field(subject, "nameField", JTextField.class).setText("Local tools");
                    field(subject, "modelIdField", JTextField.class).setText("local_tools");
                    field(subject, "executableField", JTextField.class).setText("java");
                    field(subject, "enabledBox", JCheckBox.class).setSelected(false);
                    field(subject, "transportBox", JComboBox.class).setSelectedItem(McpTransportType.STDIO);
                    return subject.savePendingChangesAsync();
                });

                assertThat(publication.join()).isTrue();
                assertThat(Files.readString(storagePaths.mcpFile()))
                        .contains("local_tools")
                        .doesNotContain("replacementSecrets");
            } finally {
                try {
                    runOnEdt(subject::disposePanel);
                } finally {
                    runOnEdt(() -> { });
                }
            }
        } finally {
            manager.close();
        }
    }

    @Test
    @DisplayName("Selecting another server after Verify starts enables Verify for the new selection")
    void selectionChanged_whenVerifyIsDisabled_enablesVerifyForNewSelection() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        McpServerConfiguration first = disabledServer("first_server");
        McpServerConfiguration second = disabledServer("second_server");
        var repository = new McpConfigurationRepository(storagePaths.mcpFile());
        repository.save(new McpConfiguration(1, List.of(first, second)));
        var manager = new McpManager(
                repository,
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        try {
            McpPanel subject = callOnEdt(() -> new McpPanel(manager));
            try {
                runOnEdt(() -> field(subject, "verifyButton", JButton.class).setEnabled(false));
                runOnEdt(() -> field(subject, "serverList", JList.class).setSelectedIndex(1));

                assertThat(callOnEdt(() -> field(subject, "verifyButton", JButton.class).isEnabled())).isTrue();
            } finally {
                try {
                    runOnEdt(subject::disposePanel);
                } finally {
                    runOnEdt(() -> { });
                }
            }
        } finally {
            manager.close();
        }
    }

    @Test
    @DisplayName("Editing the selected server cancels Verify without overwriting the newer draft")
    void verifySelected_whenSameServerIsEdited_preservesNewerEditorValue() throws Exception {
        var initializeReceived = new CountDownLatch(1);
        var releaseInitialize = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody());
            if ("initialize".equals(request.path("method").asText())) {
                initializeReceived.countDown();
                try {
                    releaseInitialize.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            byte[] response = new ObjectMapper().writeValueAsBytes(Map.of(
                    "jsonrpc", "2.0",
                    "id", new ObjectMapper().convertValue(request.path("id"), Object.class),
                    "result", Map.of(
                            "protocolVersion", "2025-06-18",
                            "capabilities", Map.of("tools", emptyMap()),
                            "serverInfo", Map.of("name", "settings-test", "version", "1")
                    )
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        withStartedServer(server, () -> {
            StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
            var manager = new McpManager(
                    new McpConfigurationRepository(storagePaths.mcpFile()),
                    new McpSecretVault(new ApiTokenVault(storagePaths)),
                    emptyMap(),
                    storagePaths.appConfigDirectory()
            );
            try (manager) {
                var configured = new McpServerConfiguration(
                        UUID.randomUUID().toString(),
                        "Original name",
                        "settings_server",
                        true,
                        false,
                        McpTransportType.STREAMABLE_HTTP,
                        "http://127.0.0.1:%d/mcp".formatted(server.getAddress().getPort()),
                        "",
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        false,
                        emptySet()
                );
                manager.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                        new McpConfiguration(1, List.of(configured))
                )).join();
                McpPanel subject = callOnEdt(() -> new McpPanel(manager));
                try {
                    runOnEdt(() -> findButton(subject, "Verify / View Tools").doClick());
                    assertThat(initializeReceived.await(5, TimeUnit.SECONDS)).isTrue();

                    runOnEdt(() -> field(subject, "nameField", JTextField.class).setText("Newer name"));
                    releaseInitialize.countDown();
                    manager.beginRuntimeShutdown();
                    runOnEdt(() -> { });

                    assertThat(callOnEdt(() -> field(subject, "nameField", JTextField.class).getText()))
                            .isEqualTo("Newer name");
                    assertThat(callOnEdt(() -> field(subject, "statusLabel", JLabel.class).getText()))
                            .isEqualTo("Verifying Original name…");
                } finally {
                    releaseInitialize.countDown();
                    try {
                        manager.beginRuntimeShutdown();
                    } finally {
                        try {
                            runOnEdt(subject::disposePanel);
                        } finally {
                            runOnEdt(() -> { });
                        }
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("Verify reconciliation preserves newer secrets and fences an immediate non-secret save")
    void verifySelected_whenExistingSecretIsReplacedAndEditedAgain_savesAndRunsWithNewestValue() throws Exception {
        var initializeReceived = new CountDownLatch(1);
        var releaseInitialize = new CountDownLatch(1);
        var secondInitializeReceived = new CountDownLatch(1);
        var releaseSecondInitialize = new CountDownLatch(1);
        var initializeCount = new AtomicInteger();
        var deleteReceived = new CountDownLatch(1);
        var authorizationValues = new CopyOnWriteArrayList<String>();
        ObjectMapper json = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null) {
                authorizationValues.add(authorization);
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deleteReceived.countDown();
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            JsonNode request = json.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            if ("initialize".equals(method)) {
                int currentInitialize = initializeCount.incrementAndGet();
                CountDownLatch received = currentInitialize == 1
                        ? initializeReceived
                        : currentInitialize == 3 ? secondInitializeReceived : null;
                CountDownLatch release = currentInitialize == 1
                        ? releaseInitialize
                        : currentInitialize == 3 ? releaseSecondInitialize : null;
                if (received != null) {
                    received.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            Object id = json.convertValue(request.path("id"), Object.class);
            Map<String, Object> result = switch (method) {
                case "initialize" -> Map.of(
                        "protocolVersion", "2025-06-18",
                        "capabilities", Map.of("tools", emptyMap()),
                        "serverInfo", Map.of("name", "settings-secret-test", "version", "1")
                );
                case "tools/list" -> Map.of("tools", List.of(Map.of(
                        "name", "echo",
                        "inputSchema", Map.of("type", "object", "properties", emptyMap())
                )));
                case "tools/call" -> Map.of(
                        "content", List.of(Map.of("type", "text", "text", "ok")),
                        "isError", false
                );
                default -> emptyMap();
            };
            byte[] response = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", id, "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().set("Mcp-Session-Id", UUID.randomUUID().toString());
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        withStartedServer(server, () -> {
            StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
            var secrets = new McpSecretVault(new ApiTokenVault(storagePaths));
            var manager = new McpManager(
                    new McpConfigurationRepository(storagePaths.mcpFile()),
                    secrets,
                    emptyMap(),
                    storagePaths.appConfigDirectory()
            );
            try (manager) {
                String rowId = UUID.randomUUID().toString();
                var configured = new McpServerConfiguration(
                        UUID.randomUUID().toString(),
                        "Secret server",
                        "secret_server",
                        true,
                        true,
                        McpTransportType.STREAMABLE_HTTP,
                        "http://127.0.0.1:%d/mcp".formatted(server.getAddress().getPort()),
                        "",
                        emptyList(),
                        List.of(new McpSecretReference(rowId, "Authorization", "")),
                        emptyList(),
                        false,
                        emptySet()
                );
                McpApplyResult initial = manager.saveAndApply(new McpConfigurationDraft(
                        new McpConfiguration(1, List.of(configured)),
                        Map.of(rowId, "Bearer old".toCharArray())
                )).join();
                assertThat(initial.outcome().applied()).isTrue();
                McpPanel subject = callOnEdt(() -> new McpPanel(manager));
                try {
                    runOnEdt(() -> setCredentialReplacement(subject, "Bearer verify"));
                    runOnEdt(() -> findButton(subject, "Verify / View Tools").doClick());
                    assertThat(initializeReceived.await(5, TimeUnit.SECONDS)).isTrue();

                    runOnEdt(() -> setCredentialReplacement(subject, "Bearer newest"));
                    releaseInitialize.countDown();
                    assertThat(deleteReceived.await(5, TimeUnit.SECONDS)).isTrue();
                    awaitCondition(() -> callOnEdt(() -> StringUtils.isBlank(selectedHeader(subject).secretId())));

                    assertThat(callOnEdt(subject::savePendingChangesAsync).join()).isTrue();
                    try (var run = manager.openRun(() -> false)) {
                        String alias = run.tools().getFirst().name();
                        var request = new ToolInvocationRequest("secret-call", alias, "{}");
                        assertThat(run.invoke(alias, emptyMap(), request, () -> false).success()).isTrue();
                    }

                    assertThat(authorizationValues).contains("Bearer verify", "Bearer newest");
                    assertThat(authorizationValues.getLast()).isEqualTo("Bearer newest");

                    runOnEdt(() -> setCredentialReplacement(subject, "Bearer settled"));
                    runOnEdt(() -> findButton(subject, "Verify / View Tools").doClick());
                    assertThat(secondInitializeReceived.await(5, TimeUnit.SECONDS)).isTrue();
                    runOnEdt(() -> field(subject, "nameField", JTextField.class).setText("Non-secret edit"));

                    CompletableFuture<Boolean> immediateSave = callOnEdt(subject::savePendingChangesAsync);
                    assertThat(immediateSave.get(5, TimeUnit.SECONDS)).isTrue();
                    McpSecretReference savedReference = callOnEdt(() -> selectedHeader(subject));

                    assertThat(savedReference.secretId()).matches("MCP_[A-F0-9]{32}");
                    assertThat(Files.readString(storagePaths.mcpFile())).contains(savedReference.secretId());
                    try (var lookup = secrets.lookup(savedReference.secretId())) {
                        assertThat(lookup.present()).isTrue();
                        assertThat(lookup.token()).containsExactly("Bearer settled".toCharArray());
                    }
                    assertThat(authorizationValues).contains("Bearer settled");
                } finally {
                    releaseInitialize.countDown();
                    releaseSecondInitialize.countDown();
                    try {
                        manager.beginRuntimeShutdown();
                    } finally {
                        try {
                            runOnEdt(subject::disposePanel);
                        } finally {
                            runOnEdt(() -> { });
                        }
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("Invalid MCP configuration exposes a standalone replacement action")
    void constructor_whenConfigurationIsInvalid_showsReplaceAction() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        Files.createDirectories(storagePaths.mcpFile().getParent());
        Files.writeString(storagePaths.mcpFile(), "{invalid", StandardCharsets.UTF_8);
        var manager = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        try (manager) {
            McpPanel subject = callOnEdt(() -> new McpPanel(manager));
            try {
                assertThat(callOnEdt(() -> field(subject, "replaceInvalidButton", JButton.class).isVisible()))
                        .isTrue();
            } finally {
                try {
                    runOnEdt(subject::disposePanel);
                } finally {
                    runOnEdt(() -> { });
                }
            }
        }
    }

    private McpServerConfiguration disabledServer(String modelId) {
        return new McpServerConfiguration(
                UUID.randomUUID().toString(),
                modelId,
                modelId,
                false,
                false,
                McpTransportType.STDIO,
                "",
                "java",
                emptyList(),
                emptyList(),
                emptyList(),
                false,
                emptySet()
        );
    }

    private void withStartedServer(HttpServer server, ThrowingRunnable action) throws Exception {
        server.start();
        try {
            action.run();
        } finally {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void setCredentialReplacement(McpPanel subject, String replacement) throws Exception {
        Object editor = field(subject, "headerEditor", Object.class);
        field(editor, "valueField", JPasswordField.class).setText(replacement);
        Method apply = editor.getClass().getDeclaredMethod("applySelectedRow");
        apply.setAccessible(true);
        apply.invoke(editor);
    }

    private McpSecretReference selectedHeader(McpPanel subject) throws Exception {
        DefaultListModel<?> model = field(subject, "serverModel", DefaultListModel.class);
        return ((McpServerConfiguration) model.get(0)).headers().getFirst();
    }

    private void awaitCondition(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.call()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condition did not become true.");
            }
            Thread.onSpinWait();
        }
    }

    private JButton findButton(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton found = findButtonOrNull(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError("Button not found: %s".formatted(text));
    }

    private JButton findButtonOrNull(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton found = findButtonOrNull(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private <T> T field(Object target, String name, Class<T> type) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return type.cast(field.get(target));
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private void runOnEdt(ThrowingAction action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
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
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
