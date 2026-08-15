package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.webview.WebViewRuntimeStatus;
import com.github.drafael.chat4j.mcp.McpConfiguration;
import com.github.drafael.chat4j.mcp.McpConfigurationRepository;
import com.github.drafael.chat4j.mcp.McpManager;
import com.github.drafael.chat4j.mcp.McpServerConfiguration;
import com.github.drafael.chat4j.mcp.McpTransportType;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.prompts.PromptCatalogRepo;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CredentialTestSupport;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.McpSecretVault;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementService;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementService;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelUsageTracker;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperNativeRuntime;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.mock;

class SettingsDialogWindowTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("The Settings sidebar shows Agent Mode before MCP and commits active MCP edits")
    void sectionSelectionChanged_whenNavigatingAgentModeAndMcp_showsAgentModeAndCommitsMcpEdit() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A desktop display is required for Settings dialog behavior.");
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var settings = new SettingsRepository(storagePaths);
        var prompts = new PromptCatalogRepo(storagePaths.promptsFile());
        McpServerConfiguration configured = new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Sidebar server",
                "sidebar_server",
                false,
                false,
                McpTransportType.STDIO,
                "",
                "java",
                List.of("original"),
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
        var credentials = CredentialTestSupport.create(storagePaths);
        Path models = tempDirectory.resolve("models");
        var voskModels = new VoskModelManagementService(settings, models.resolve("vosk"), tempDirectory.resolve("tmp"));
        var whisperModels = new WhisperModelManagementService(
                settings,
                models.resolve("whisper"),
                tempDirectory.resolve("tmp"),
                WhisperNativeRuntime.shared(),
                new WhisperModelUsageTracker()
        );
        var exitCalled = new CountDownLatch(1);
        Frame owner = null;
        SettingsDialog subject = null;
        try {
            Frame createdOwner = callOnEdt(Frame::new);
            owner = createdOwner;
            SettingsDialog createdSubject = callOnEdt(() -> new SettingsDialog(
                    createdOwner,
                    settings,
                    prompts,
                    WebViewRuntimeStatus.jEditorPaneDefault(),
                    ignored -> exitCalled.countDown(),
                    deadline -> deadline,
                    models,
                    voskModels,
                    whisperModels,
                    SettingsCredentialChangeListener.NO_OP,
                    mock(CopilotAuthResolver.class),
                    mock(CodexAuthResolver.class),
                    credentials.resolver(),
                    credentials.mutationService(),
                    emptyMap(),
                    WhisperNativeRuntime.shared(),
                    manager,
                    SettingsDialog.ExitTiming.system()
            ));
            subject = createdSubject;

            runOnEdt(() -> {
                JList<?> sections = field(createdSubject, "sectionList", JList.class);
                List<String> sectionTitles = IntStream.range(0, sections.getModel().getSize())
                        .mapToObj(index -> field(sections.getModel().getElementAt(index), "title", String.class))
                        .toList();
                assertThat(sectionTitles).containsSubsequence("Prompts", "Agent Mode", "MCP");

                sections.setSelectedIndex(sectionTitles.indexOf("Agent Mode"));
                AgentModePanel agentModePanel = components(
                        createdSubject.getContentPane(),
                        AgentModePanel.class
                ).getFirst();
                assertThat(agentModePanel.isVisible()).isTrue();

                sections.setSelectedIndex(sections.getModel().getSize() - 1);
                JTable arguments = component(createdSubject.getContentPane(), "Ordered MCP arguments", JTable.class);
                assertThat(arguments.editCellAt(0, 0)).isTrue();
                ((JTextField) arguments.getEditorComponent()).setText("committed by sidebar");

                sections.setSelectedIndex(0);
                sections.setSelectedIndex(sections.getModel().getSize() - 1);

                assertThat(arguments.isEditing()).isFalse();
                assertThat(arguments.getValueAt(0, 0)).isEqualTo("committed by sidebar");
                assertThat(manager.generation()).isZero();
            });
        } finally {
            SettingsDialog dialogToClose = subject;
            if (dialogToClose != null) {
                runOnEdt(() -> dialogToClose.requestApplicationExit(
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                ));
                assertThat(exitCalled.await(10, TimeUnit.SECONDS)).isTrue();
                flushEdt();
            }
            Frame ownerToClose = owner;
            if (ownerToClose != null) {
                runOnEdt(ownerToClose::dispose);
            }
            voskModels.close();
            whisperModels.close();
            credentials.mutationService().closeSecrets();
            manager.close();
            flushEdt();
        }
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

    private <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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
