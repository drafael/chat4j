package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.mcp.McpConfiguration;
import com.github.drafael.chat4j.mcp.McpConfigurationRepository;
import com.github.drafael.chat4j.mcp.McpDiscoveredTool;
import com.github.drafael.chat4j.mcp.McpManager;
import com.github.drafael.chat4j.mcp.McpServerConfiguration;
import com.github.drafael.chat4j.mcp.McpTransportType;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.McpSecretVault;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class McpPanelWindowTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("The real MCP window switches from empty state to the selected-server editor")
    void addServer_whenPanelIsShowing_switchesVisibleCardWithoutClippingRailAction() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A desktop display is required for MCP window behavior.");
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var manager = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        McpPanel subject = null;
        JFrame frame = null;
        try {
            McpPanel createdSubject = callOnEdt(() -> new McpPanel(manager));
            subject = createdSubject;
            JFrame createdFrame = callOnEdt(() -> {
                var window = new JFrame("MCP window test");
                window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                window.setContentPane(createdSubject);
                window.setSize(560, 430);
                window.setLocationRelativeTo(null);
                window.setVisible(true);
                return window;
            });
            frame = createdFrame;
            flushEdt();

            runOnEdt(() -> {
                JButton railAdd = buttons(createdSubject, "Add server").stream()
                        .filter(button -> button.getIcon() != null)
                        .findFirst()
                        .orElseThrow();
                JButton emptyAdd = buttons(createdSubject, "Add server").stream()
                        .filter(button -> button.getIcon() == null)
                        .findFirst()
                        .orElseThrow();
                assertThat(railAdd.isShowing()).isTrue();
                assertThat(emptyAdd.isShowing()).isTrue();
                emptyAdd.doClick();
                assertThat(railAdd.isShowing()).isTrue();
                assertThat(railAdd.getBounds().width).isPositive();
                assertThat(railAdd.getBounds().height).isPositive();
            });
        } finally {
            McpPanel panelToDispose = subject;
            JFrame frameToDispose = frame;
            if (panelToDispose != null || frameToDispose != null) {
                runOnEdt(() -> {
                    if (panelToDispose != null) {
                        panelToDispose.disposePanel();
                    }
                    if (frameToDispose != null) {
                        frameToDispose.dispose();
                    }
                });
                flushEdt();
            }
            manager.close();
        }
    }

    @Test
    @DisplayName("The schema dialog is document-modal, reused, accessible, and disposed locally")
    void showInputSchema_whenDialogIsAlreadyOpen_reusesTrackedDialog() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A desktop display is required for MCP schema dialog behavior.");
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory.resolve("schema"));
        McpServerConfiguration configured = new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Schema server",
                "schema_server",
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
        new McpConfigurationRepository(storagePaths.mcpFile()).save(new McpConfiguration(1, List.of(configured)));
        var manager = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        McpPanel subject = null;
        JFrame frame = null;
        var actionReturned = new CountDownLatch(1);
        Object originalCodeFont = callOnEdt(() -> UIManager.get("monospaced.font"));
        Font selectedCodeFont = new Font(Font.DIALOG_INPUT, Font.PLAIN, 17);
        try {
            runOnEdt(() -> UIManager.put("monospaced.font", selectedCodeFont));
            McpPanel createdSubject = callOnEdt(() -> new McpPanel(manager));
            subject = createdSubject;
            JFrame createdFrame = callOnEdt(() -> {
                var window = new JFrame("MCP schema test");
                window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                window.setContentPane(createdSubject);
                window.setSize(700, 520);
                window.setVisible(true);
                Map<String, List<McpDiscoveredTool>> tools = field(createdSubject, "lastTools", Map.class);
                tools.put(configured.id(), List.of(new McpDiscoveredTool(
                        "echo",
                        "Echo",
                        "Echo input",
                        Map.of("type", "object"),
                        null
                )));
                Map<String, Map<String, String>> schemas = field(createdSubject, "formattedSchemas", Map.class);
                schemas.put(configured.id(), Map.of("echo", "{\n  \"type\": \"object\"\n}"));
                invoke(createdSubject, "refreshToolPresentation");
                JTable toolTable = component(createdSubject, "Discovered MCP tools", JTable.class);
                toolTable.setRowSelectionInterval(0, 0);
                return window;
            });
            frame = createdFrame;
            SwingUtilities.invokeLater(() -> {
                try {
                    button(createdSubject, "Show input schema").doClick();
                } finally {
                    actionReturned.countDown();
                }
            });

            JDialog dialog = awaitSchemaDialog();
            runOnEdt(() -> {
                assertThat(dialog.getModalityType()).isEqualTo(Dialog.ModalityType.DOCUMENT_MODAL);
                assertThat(dialog.getOwner()).isEqualTo(createdFrame);
                JTextArea schemaContent = component(
                        dialog.getContentPane(),
                        "MCP tool input schema",
                        JTextArea.class
                );
                assertThat(schemaContent.isEditable()).isFalse();
                assertThat(schemaContent.getLineWrap()).isTrue();
                assertThat(schemaContent.getWrapStyleWord()).isTrue();
                assertThat(schemaContent.getFont()).isEqualTo(selectedCodeFont);
                button(createdSubject, "Show input schema").doClick();
                assertThat(schemaDialogs()).containsExactly(dialog);
                findTextButton(dialog.getContentPane(), "Close").doClick();
            });
            assertThat(actionReturned.await(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertThat(callOnEdt(() -> schemaDialogs())).isEmpty();
        } finally {
            McpPanel panelToDispose = subject;
            JFrame frameToDispose = frame;
            runOnEdt(() -> {
                schemaDialogs().forEach(Window::dispose);
                if (panelToDispose != null) {
                    panelToDispose.disposePanel();
                }
                if (frameToDispose != null) {
                    frameToDispose.dispose();
                }
                if (originalCodeFont == null) {
                    UIManager.getDefaults().remove("monospaced.font");
                } else {
                    UIManager.put("monospaced.font", originalCodeFont);
                }
            });
            flushEdt();
            manager.close();
        }
    }

    private JDialog awaitSchemaDialog() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            JDialog dialog = callOnEdt(() -> schemaDialogs().stream().findFirst().orElse(null));
            if (dialog != null) {
                return dialog;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        throw new AssertionError("Schema dialog did not open.");
    }

    private List<JDialog> schemaDialogs() {
        return List.of(Window.getWindows()).stream()
                .filter(JDialog.class::isInstance)
                .map(JDialog.class::cast)
                .filter(Window::isDisplayable)
                .filter(dialog -> dialog.getTitle().startsWith("Input schema"))
                .toList();
    }

    private JButton button(Container root, String accessibleName) {
        return buttons(root, accessibleName).stream().findFirst()
                .orElseThrow(() -> new AssertionError("Button not found: %s".formatted(accessibleName)));
    }

    private JButton findTextButton(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton found = findTextButtonOrNull(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError("Button not found: %s".formatted(text));
    }

    private JButton findTextButtonOrNull(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton found = findTextButtonOrNull(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private <T extends Component> T component(Container root, String accessibleName, Class<T> type) {
        List<T> matches = components(root, type).stream()
                .filter(component -> component.getAccessibleContext() != null)
                .filter(component -> accessibleName.equals(component.getAccessibleContext().getAccessibleName()))
                .toList();
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    private <T extends Component> List<T> components(Container root, Class<T> type) {
        var result = new ArrayList<T>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                result.add(type.cast(child));
            }
            if (child instanceof Container container) {
                result.addAll(components(container, type));
            }
        }
        return List.copyOf(result);
    }

    private void invoke(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private List<JButton> buttons(Container root, String accessibleName) {
        var result = new ArrayList<JButton>();
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && button.getAccessibleContext() != null
                    && accessibleName.equals(button.getAccessibleContext().getAccessibleName())) {
                result.add(button);
            }
            if (component instanceof Container child) {
                result.addAll(buttons(child, accessibleName));
            }
        }
        return List.copyOf(result);
    }

    private void runOnEdt(ThrowingAction action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private void flushEdt() throws Exception {
        runOnEdt(() -> { });
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
