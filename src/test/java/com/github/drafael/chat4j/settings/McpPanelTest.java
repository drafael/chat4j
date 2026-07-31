package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.mcp.McpApplyOutcome;
import com.github.drafael.chat4j.mcp.McpApplyResult;
import com.github.drafael.chat4j.mcp.McpConfiguration;
import com.github.drafael.chat4j.mcp.McpConfigurationDraft;
import com.github.drafael.chat4j.mcp.McpConfigurationLoadResult;
import com.github.drafael.chat4j.mcp.McpConfigurationRepository;
import com.github.drafael.chat4j.mcp.McpDiscoveredTool;
import com.github.drafael.chat4j.mcp.McpManager;
import com.github.drafael.chat4j.mcp.McpSecretReference;
import com.github.drafael.chat4j.mcp.McpServerConfiguration;
import com.github.drafael.chat4j.mcp.McpTransportType;
import com.github.drafael.chat4j.mcp.McpVerificationResult;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.McpSecretVault;
import java.awt.Component;
import java.awt.Container;
import java.awt.SecondaryLoop;
import java.awt.event.MouseWheelEvent;
import java.awt.Toolkit;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpPanelTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("Adding and saving a server publishes the redesigned editor draft")
    void savePendingChangesAsync_whenServerIsAdded_publishesConfiguration() throws Exception {
        try (var fixture = fixture("save", null)) {
            CompletableFuture<Boolean> publication = callOnEdt(() -> {
                button(fixture.subject(), "Add server").doClick();
                component(fixture.subject(), "MCP server name", JTextField.class).setText("Local tools");
                component(fixture.subject(), "MCP model ID", JTextField.class).setText("local_tools");
                component(fixture.subject(), "MCP executable", JTextField.class).setText("java");
                return fixture.subject().savePendingChangesAsync();
            });

            assertThat(publication.get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertThat(Files.readString(fixture.storagePaths().mcpFile()))
                    .contains("local_tools")
                    .doesNotContain("replacementSecrets");
            assertThat(callOnEdt(fixture.subject()::lastSaveError)).isEmpty();
        }
    }

    @Test
    @DisplayName("Empty, search, and Add states preserve rail semantics and unique model IDs")
    void serverRail_whenSelectionAndSearchChange_preservesMasterDetailState() throws Exception {
        McpServerConfiguration first = server("Alpha", "SERVER_3", McpTransportType.STDIO);
        McpServerConfiguration second = server("Beta", "beta_tools", McpTransportType.STDIO);
        try (var fixture = fixture("rail", new McpConfiguration(1, List.of(first, second)))) {
            runOnEdt(() -> {
                JList<?> list = component(fixture.subject(), "MCP servers", JList.class);
                list.clearSelection();
                assertThat(button(fixture.subject(), "Add server").isEnabled()).isTrue();
                assertThat(button(fixture.subject(), "Remove server").isEnabled()).isFalse();
                assertThat(button(fixture.subject(), "Verify / Refresh").isEnabled()).isFalse();

                JTextField search = component(fixture.subject(), "Search MCP servers", JTextField.class);
                search.setText("  beta_tools  ");
                assertThat(((McpServerConfiguration) list.getSelectedValue()).id()).isEqualTo(second.id());
                search.setText("no match");
                assertThat(((McpServerConfiguration) list.getSelectedValue()).id()).isEqualTo(second.id());
                search.setText("");
                assertThat(((McpServerConfiguration) list.getSelectedValue()).id()).isEqualTo(second.id());

                button(fixture.subject(), "Add server").doClick();
                McpServerConfiguration added = (McpServerConfiguration) list.getSelectedValue();
                assertThat(added.modelId()).isEqualTo("server_4");
                assertThat(component(fixture.subject(), "MCP server editor", JTabbedPane.class).getSelectedIndex())
                        .isZero();
            });
        }
    }

    @Test
    @DisplayName("Transport toggles and ordered arguments preserve inactive values exactly")
    void finishActiveEditing_whenArgumentIsEdited_commitsExactValueWithoutPublishing() throws Exception {
        McpServerConfiguration configured = new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Arguments",
                "arguments",
                false,
                false,
                McpTransportType.STDIO,
                "https://example.test/mcp",
                "java",
                List.of("--flag", "", "--flag", "  ", "value"),
                emptyList(),
                emptyList(),
                true,
                emptySet()
        );
        try (var fixture = fixture("arguments", new McpConfiguration(1, List.of(configured)))) {
            runOnEdt(() -> {
                JTable arguments = component(fixture.subject(), "Ordered MCP arguments", JTable.class);
                assertThat(tableValues(arguments)).containsExactly("--flag", "", "--flag", "  ", "value");
                assertThat(arguments.editCellAt(0, 0)).isTrue();
                ((JTextField) arguments.getEditorComponent()).setText("<html>精🔧é</html>");
                fixture.subject().finishActiveEditing();
                assertThat(arguments.getValueAt(0, 0)).isEqualTo("<html>精🔧é</html>");
                assertThat(fixture.manager().generation()).isZero();

                JToggleButton http = component(fixture.subject(), "Streamable HTTP transport", JToggleButton.class);
                JToggleButton stdio = component(fixture.subject(), "STDIO transport", JToggleButton.class);
                http.doClick();
                assertThat(http.isSelected()).isTrue();
                assertThat(stdio.isSelected()).isFalse();
                assertThat(findCheckBox(fixture.subject(), "Long-running").isSelected()).isFalse();
                stdio.doClick();
                assertThat(stdio.isSelected()).isTrue();
                assertThat(http.isSelected()).isFalse();
                assertThat(tableValues(arguments)).containsExactly("<html>精🔧é</html>", "", "--flag", "  ", "value");
            });
        }
    }

    @Test
    @DisplayName("Credential state exposes no plaintext and server removal wipes pending replacement")
    void removeServer_whenCredentialReplacementIsPending_wipesOwnedSecret() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory.resolve("credential"));
        var manager = manager(storagePaths);
        String rowId = UUID.randomUUID().toString();
        McpServerConfiguration configured = new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "HTTP",
                "http_tools",
                false,
                false,
                McpTransportType.STREAMABLE_HTTP,
                "https://example.test/mcp",
                "",
                emptyList(),
                List.of(new McpSecretReference(rowId, "Authorization", "")),
                emptyList(),
                false,
                emptySet()
        );
        manager.saveAndApply(new McpConfigurationDraft(
                new McpConfiguration(1, List.of(configured)),
                Map.of(rowId, "saved-value".toCharArray())
        )).join();
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try (var fixture = new PanelFixture(storagePaths, manager, subject)) {
            AtomicReference<char[]> owned = new AtomicReference<>();
            runOnEdt(() -> {
                JTable headers = component(subject, "HTTP headers", JTable.class);
                assertThat(headers.getValueAt(0, 1)).isEqualTo("Saved");
                Component rendered = headers.prepareRenderer(headers.getCellRenderer(0, 1), 0, 1);
                assertThat(((JLabel) rendered).getToolTipText()).contains("has not been rechecked");

                Object headerEditor = field(subject, "headerEditor", Object.class);
                JPasswordField password = findComponent((Container) headerEditor, JPasswordField.class);
                password.setText("unique-sentinel-secret");
                findButton((Container) headerEditor, "Apply").doClick();
                assertThat(headers.getValueAt(0, 1)).isEqualTo("New value entered");
                assertThat(password.getPassword()).isEmpty();
                assertThat(tableValues(headers).toString()).doesNotContain("unique-sentinel-secret");
                assertThat(subject.toString()).doesNotContain("unique-sentinel-secret");

                Map<String, char[]> replacements = field(subject, "replacementSecrets", Map.class);
                owned.set(replacements.get(rowId));
                assertThat(owned.get()).containsExactly("unique-sentinel-secret".toCharArray());
                button(subject, "Remove server").doClick();
                assertThat(replacements).isEmpty();
            });
            assertThat(owned.get()).containsOnly('\0');
        }
    }

    @Test
    @DisplayName("Tool table uses exact raw names and Hammer identity")
    void toolTable_whenToolIsDisabled_persistsExactRawName() throws Exception {
        McpServerConfiguration configured = server("Tools", "tools", McpTransportType.STDIO);
        try (var fixture = fixture("tools", new McpConfiguration(1, List.of(configured)))) {
            runOnEdt(() -> {
                Map<String, List<McpDiscoveredTool>> tools = field(fixture.subject(), "lastTools", Map.class);
                tools.put(configured.id(), List.of(
                        new McpDiscoveredTool(
                                "Echo",
                                "<html><b>Echo title</b></html>",
                                "Uppercase tool",
                                Map.of("type", "object"),
                                null
                        ),
                        new McpDiscoveredTool("echo", "", "Lowercase tool", Map.of("type", "object"), null)
                ));
                invoke(fixture.subject(), "refreshToolPresentation");
                JTable table = component(fixture.subject(), "Discovered MCP tools", JTable.class);
                assertThat(table.getColumnCount()).isEqualTo(4);
                assertThat(table.getColumnClass(0)).isEqualTo(Boolean.class);
                assertThat(table.getColumnClass(1)).isEqualTo(String.class);
                table.setRowSelectionInterval(0, 0);
                table.getModel().setValueAt(false, 0, 0);
                Component rendered = table.prepareRenderer(table.getCellRenderer(0, 1), 0, 1);
                assertThat(((JLabel) rendered).getIcon()).isNotNull();
                assertThat(rendered.getAccessibleContext().getAccessibleName()).isEqualTo("Echo");
                JLabel heading = field(fixture.subject(), "toolDetailsHeading", JLabel.class);
                assertThat(heading.getIcon()).isNotNull();
                assertThat(heading.getText()).isEqualTo("<html><b>Echo title</b></html>");
                assertThat(heading.getClientProperty("html.disable")).isEqualTo(Boolean.TRUE);
            });

            CompletableFuture<Boolean> saved = callOnEdt(fixture.subject()::savePendingChangesAsync);
            assertThat(saved.get(5, TimeUnit.SECONDS)).isTrue();
            McpConfiguration persisted = ((McpConfigurationLoadResult.Valid) fixture.manager().loadResult())
                    .configuration();
            assertThat(persisted.servers().getFirst().disabledTools())
                    .containsExactly("Echo")
                    .doesNotContain("echo");
        }
    }

    @Test
    @DisplayName("Untouched invalid configuration remains repairable without overwriting its bytes")
    void savePendingChangesAsync_whenInvalidDraftIsUntouched_preservesInvalidFile() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory.resolve("invalid"));
        Files.createDirectories(storagePaths.mcpFile().getParent());
        Files.writeString(storagePaths.mcpFile(), "{invalid", StandardCharsets.UTF_8);
        var manager = manager(storagePaths);
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try (var fixture = new PanelFixture(storagePaths, manager, subject)) {
            assertThat(callOnEdt(() -> button(subject, "Replace / Recreate invalid configuration").isVisible()))
                    .isTrue();
            assertThat(callOnEdt(() -> button(subject, "Add server").isEnabled())).isTrue();

            CompletableFuture<Boolean> untouched = callOnEdt(subject::savePendingChangesAsync);
            assertThat(untouched.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.readString(storagePaths.mcpFile())).isEqualTo("{invalid");

            CompletableFuture<Boolean> dirty = callOnEdt(() -> {
                button(subject, "Add server").doClick();
                component(subject, "MCP executable", JTextField.class).setText("java");
                return subject.savePendingChangesAsync();
            });
            assertThat(dirty.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(callOnEdt(subject::lastSaveError))
                    .isEqualTo("Confirm replacement of the invalid MCP configuration first.");
            assertThat(Files.readString(storagePaths.mcpFile())).isEqualTo("{invalid");
        }
    }

    @Test
    @DisplayName("Settings groups use the full editor width and place credential details below their table")
    void layout_whenSettingsTabIsNarrow_keepsGroupsAlignedAndOrdered() throws Exception {
        McpServerConfiguration configured = server("Layout", "layout", McpTransportType.STDIO);
        try (var fixture = fixture("layout", new McpConfiguration(1, List.of(configured)))) {
            runOnEdt(() -> {
                fixture.subject().setSize(560, 430);
                layoutRecursively(fixture.subject());
                layoutRecursively(fixture.subject());
                JScrollPane settingsScroll = settingsScroll(fixture.subject());
                Container content = (Container) settingsScroll.getViewport().getView();
                JLabel transport = label(fixture.subject(), "Transport");
                JTextField executable = component(fixture.subject(), "MCP executable", JTextField.class);
                JTable environment = component(fixture.subject(), "Environment variables", JTable.class);
                Object environmentEditor = field(fixture.subject(), "environmentEditor", Object.class);
                JTextField name = components((Container) environmentEditor, JTextField.class).getFirst();
                java.awt.Rectangle transportBounds = SwingUtilities.convertRectangle(
                        transport.getParent(),
                        new java.awt.Rectangle(0, 0, transport.getParent().getWidth(), transport.getParent().getHeight()),
                        content
                );
                java.awt.Rectangle executableBounds = SwingUtilities.convertRectangle(
                        executable.getParent(),
                        new java.awt.Rectangle(0, 0, executable.getParent().getWidth(), executable.getParent().getHeight()),
                        content
                );
                int tableBottom = SwingUtilities.convertPoint(
                        environment,
                        0,
                        environment.getHeight(),
                        (Container) environmentEditor
                ).y;
                int detailTop = SwingUtilities.convertPoint(name, 0, 0, (Container) environmentEditor).y;

                assertThat(transportBounds.x).isLessThanOrEqualTo(8);
                assertThat(transportBounds.width).isGreaterThan(content.getWidth() * 4 / 5);
                assertThat(executableBounds.x).isLessThanOrEqualTo(8);
                assertThat(executableBounds.width).isGreaterThan(content.getWidth() * 4 / 5);
                assertThat(detailTop).isGreaterThanOrEqualTo(tableBottom);

                component(
                        fixture.subject(),
                        "Streamable HTTP transport",
                        JToggleButton.class
                ).doClick();
                layoutRecursively(fixture.subject());
                JTextField endpoint = component(fixture.subject(), "MCP HTTP endpoint", JTextField.class);
                java.awt.Rectangle endpointBounds = SwingUtilities.convertRectangle(
                        endpoint.getParent(),
                        new java.awt.Rectangle(0, 0, endpoint.getParent().getWidth(), endpoint.getParent().getHeight()),
                        content
                );
                assertThat(endpointBounds.x).isLessThanOrEqualTo(8);
                assertThat(endpointBounds.width).isGreaterThan(content.getWidth() * 4 / 5);
            });
        }
    }

    @Test
    @DisplayName("Mouse wheel events at a nested table boundary scroll the Settings form")
    void mouseWheelMoved_whenNestedTableCannotScroll_forwardsToSettingsScrollPane() throws Exception {
        McpServerConfiguration configured = server("Scrolling", "scrolling", McpTransportType.STDIO);
        try (var fixture = fixture("scrolling", new McpConfiguration(1, List.of(configured)))) {
            runOnEdt(() -> {
                fixture.subject().setSize(560, 430);
                layoutRecursively(fixture.subject());
                layoutRecursively(fixture.subject());
                JScrollPane outer = settingsScroll(fixture.subject());
                JTable arguments = component(fixture.subject(), "Ordered MCP arguments", JTable.class);
                JScrollPane nested = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, arguments);
                outer.getVerticalScrollBar().setValue(0);
                nested.getVerticalScrollBar().setValue(0);
                MouseWheelEvent wheel = new MouseWheelEvent(
                        nested,
                        MouseWheelEvent.MOUSE_WHEEL,
                        System.currentTimeMillis(),
                        0,
                        4,
                        4,
                        0,
                        false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL,
                        3,
                        1
                );

                nested.dispatchEvent(wheel);

                assertThat(outer.getVerticalScrollBar().getValue()).isPositive();
                assertThat(wheel.isConsumed()).isTrue();
            });
        }
    }

    @Test
    @DisplayName("Footer caps its viewport while preserving scrollable full text")
    void footer_whenStatusIsLong_capsViewportWithoutClippingDocument() throws Exception {
        try (var fixture = fixture("footer", null)) {
            runOnEdt(() -> {
                JTextArea status = component(fixture.subject(), "MCP status", JTextArea.class);
                JScrollPane scroll = field(fixture.subject(), "statusScroll", JScrollPane.class);
                String message = "Long authoritative status\n".repeat(80);
                status.setText(message);
                fixture.subject().setSize(560, 430);
                layoutRecursively(fixture.subject());
                layoutRecursively(fixture.subject());
                int expectedHeight = status.getFontMetrics(status.getFont()).getHeight() * 3
                        + status.getInsets().top + status.getInsets().bottom;

                assertThat(status.getPreferredScrollableViewportSize().height).isEqualTo(expectedHeight);
                assertThat(status.getText()).isEqualTo(message);
                assertThat(scroll.getVerticalScrollBarPolicy()).isEqualTo(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
                assertThat(button(fixture.subject(), "Replace / Recreate invalid configuration").getParent())
                        .isNotSameAs(scroll.getViewport());
            });
        }
    }

    @Test
    @DisplayName("Plug and Hammer resources resolve as scalable FlatLaf icons")
    void icons_whenLoaded_resolveLucideResources() {
        FlatSVGIcon plug = new FlatSVGIcon(McpPanel.class.getResource("/icons/settings/mcp.svg")).derive(24, 24);
        FlatSVGIcon hammer = new FlatSVGIcon(McpPanel.class.getResource("/icons/settings/hammer.svg")).derive(24, 24);

        assertThat(plug.hasFound()).isTrue();
        assertThat(hammer.hasFound()).isTrue();
        assertThat(plug.getIconWidth()).isEqualTo(24);
        assertThat(hammer.getIconHeight()).isEqualTo(24);
    }

    @Test
    @DisplayName("An already-completed manager future reconciles on a later EDT turn")
    void savePendingChangesAsync_whenManagerFutureIsAlreadyComplete_defersCallerCompletion() throws Exception {
        McpServerConfiguration configured = server("Immediate", "immediate", McpTransportType.STDIO);
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        doAnswer(invocation -> {
            McpConfigurationDraft draft = invocation.getArgument(0);
            draft.clearSecrets();
            generation.set(1);
            return CompletableFuture.completedFuture(applied(1, configured));
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            var admission = callOnEdt(() -> {
                CompletableFuture<Boolean> result = subject.savePendingChangesAsync();
                return new SaveAdmission(result, result.isDone());
            });

            assertThat(admission.completedInline()).isFalse();
            assertThat(admission.result().get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertThat(callOnEdt(subject::lastSaveError)).isEmpty();
            assertThat(callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText()))
                    .contains("configuration applied");
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A second unchanged save reuses the last panel-applied generation")
    void savePendingChangesAsync_whenAppliedSnapshotIsUnchanged_skipsRedundantPublication() throws Exception {
        McpServerConfiguration configured = server("Fast path", "fast_path", McpTransportType.STDIO);
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            generation.set(1);
            return CompletableFuture.completedFuture(applied(1, configured));
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            assertThat(callOnEdt(subject::savePendingChangesAsync).get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(callOnEdt(subject::savePendingChangesAsync).get(5, TimeUnit.SECONDS)).isTrue();

            verify(manager, times(1)).saveAndApply(any(McpConfigurationDraft.class));
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A synchronous manager rejection keeps the panel-owned replacement available for retry")
    void savePendingChangesAsync_whenManagerThrowsBeforeReturningFuture_retainsReplacement() throws Exception {
        String rowId = UUID.randomUUID().toString();
        McpServerConfiguration configured = httpServerWithHeader(rowId, "");
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        doThrow(new IllegalStateException("submission failed"))
                .when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> applyHeaderReplacement(subject, "retry-secret"));

            CompletableFuture<Boolean> result = callOnEdt(subject::savePendingChangesAsync);

            assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
            Map<String, char[]> replacements = callOnEdt(() -> field(subject, "replacementSecrets", Map.class));
            assertThat(replacements.get(rowId)).containsExactly("retry-secret".toCharArray());
            assertThat(callOnEdt(subject::lastSaveError)).isEqualTo("submission failed");
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A publication completed after disposal settles the original caller without touching presentation")
    void savePendingChangesAsync_whenPublicationCompletesAfterDisposal_settlesCallerFuture() throws Exception {
        McpServerConfiguration configured = server("Late", "late", McpTransportType.STDIO);
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        CompletableFuture<McpApplyResult> publication = new CompletableFuture<>();
        CountDownLatch submitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            submitted.countDown();
            return publication;
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            CompletableFuture<Boolean> result = callOnEdt(subject::savePendingChangesAsync);
            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
            runOnEdt(subject::disposePanel);
            String statusAfterDisposal = callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText());

            generation.set(1);
            publication.complete(applied(1, configured));

            assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertThat(callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText()))
                    .isEqualTo(statusAfterDisposal);
            assertThat(callOnEdt(() -> field(
                    subject,
                    "serverModel",
                    javax.swing.DefaultListModel.class
            ).getSize())).isZero();
            assertThat(callOnEdt(() -> field(subject, "publicationUiSettlement", CompletableFuture.class)).isDone())
                    .isTrue();
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A save waits for the manager publication barrier before making publication decisions")
    void savePendingChangesAsync_whenExternalPublicationIsUnsettled_waitsForBarrier() throws Exception {
        McpServerConfiguration configured = server("Barrier", "barrier", McpTransportType.STDIO);
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        when(manager.publicationsSettled()).thenReturn(barrier);
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            generation.set(1);
            return CompletableFuture.completedFuture(applied(1, configured));
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            CompletableFuture<Boolean> result = callOnEdt(subject::savePendingChangesAsync);
            runOnEdt(() -> component(subject, "MCP server name", JTextField.class).getText());

            assertThat(result).isNotDone();
            verify(manager, times(0)).saveAndApply(any(McpConfigurationDraft.class));
            barrier.complete(null);
            assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Cancelling standalone Repair keeps invalid mode without publishing")
    void repairInvalidConfiguration_whenConfirmationIsCancelled_preservesInvalidMode() throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> clickWithConfirmation(
                    subject,
                    "Replace / Recreate invalid configuration",
                    JOptionPane.CANCEL_OPTION
            ));
            flushEdt();

            assertThat(callOnEdt(() -> button(
                    subject,
                    "Replace / Recreate invalid configuration"
            ).isVisible())).isTrue();
            verify(manager, times(0)).replaceInvalidAndApply(any(McpConfigurationDraft.class));
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Close waits for standalone Repair settlement without publishing a second repair")
    void repairInvalidConfiguration_whenCloseStartsImmediately_settlesThenSavesOnce() throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        CompletableFuture<McpApplyResult> repair = new CompletableFuture<>();
        CountDownLatch submitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            submitted.countDown();
            return repair;
        }).when(manager).replaceInvalidAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> clickWithConfirmation(
                    subject,
                    "Replace / Recreate invalid configuration",
                    JOptionPane.OK_OPTION
            ));
            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Boolean> closeSave = callOnEdt(subject::savePendingChangesAsync);
            assertThat(closeSave).isNotDone();
            loadResult.set(new McpConfigurationLoadResult.Valid(McpConfiguration.empty()));
            generation.set(1);
            repair.complete(new McpApplyResult(
                    McpApplyOutcome.APPLIED,
                    1,
                    McpConfiguration.empty(),
                    ""
            ));

            assertThat(closeSave.get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertThat(callOnEdt(() -> button(
                    subject,
                    "Replace / Recreate invalid configuration"
            ).isVisible())).isFalse();
            verify(manager, times(1)).replaceInvalidAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).saveAndApply(any(McpConfigurationDraft.class));
        } finally {
            if (!repair.isDone()) {
                repair.completeExceptionally(new IllegalStateException("test cleanup"));
            }
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A mutation during Verify discovery reports cancellation and ignores the stale tool result")
    void verifySelected_whenDiscoveryIsPendingAndDraftMutates_reportsCancellation() throws Exception {
        McpServerConfiguration configured = server("Verify", "verify", McpTransportType.STDIO);
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        McpApplyResult applyResult = applied(1, configured);
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            generation.set(1);
            return CompletableFuture.completedFuture(applyResult);
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        CompletableFuture<McpVerificationResult> discovery = new CompletableFuture<>();
        CountDownLatch discoveryStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            discoveryStarted.countDown();
            return discovery;
        }).when(manager).verifyAppliedAsync(any(), any(), any());
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> button(subject, "Verify / Refresh").doClick());
            assertThat(discoveryStarted.await(5, TimeUnit.SECONDS)).isTrue();

            runOnEdt(() -> component(subject, "MCP server name", JTextField.class).setText("Newer name"));

            assertThat(callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText()))
                    .isEqualTo("Verification cancelled because settings or selection changed.");
            assertThat(callOnEdt(() -> button(subject, "Verify / Refresh").isEnabled())).isTrue();
            discovery.complete(McpVerificationResult.successful(
                    applyResult,
                    configured.id(),
                    List.of(new McpDiscoveredTool(
                            "stale-tool",
                            "",
                            "Must not be displayed",
                            Map.of("type", "object"),
                            null
                    ))
            ));
            assertThat(ForkJoinPool.commonPool().awaitQuiescence(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();

            assertThat(callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText()))
                    .isEqualTo("Verification cancelled because settings or selection changed.");
            assertThat(callOnEdt(() -> component(subject, "Discovered MCP tools", JTable.class).getRowCount()))
                    .isZero();
        } finally {
            discovery.cancel(true);
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Save commits an active editor once and waits for Verify publication reconciliation")
    void savePendingChangesAsync_whenPublicationReconciliationIsPending_waitsForSettlement() throws Exception {
        McpServerConfiguration configured = new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Pending reconciliation",
                "pending_reconciliation",
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
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        CompletableFuture<McpApplyResult> verificationPublication = new CompletableFuture<>();
        CountDownLatch firstSubmitted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<McpConfiguration> savedConfiguration = new AtomicReference<>();
        doAnswer(invocation -> {
            McpConfigurationDraft draft = invocation.getArgument(0);
            int call = calls.incrementAndGet();
            if (call == 1) {
                draft.clearSecrets();
                firstSubmitted.countDown();
                return verificationPublication;
            }
            savedConfiguration.set(draft.configuration());
            draft.clearSecrets();
            generation.set(2);
            return CompletableFuture.completedFuture(new McpApplyResult(
                    McpApplyOutcome.APPLIED,
                    2,
                    savedConfiguration.get(),
                    ""
            ));
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> button(subject, "Verify / Refresh").doClick());
            assertThat(firstSubmitted.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Boolean> save = callOnEdt(() -> {
                JTable arguments = component(subject, "Ordered MCP arguments", JTable.class);
                assertThat(arguments.editCellAt(0, 0)).isTrue();
                ((JTextField) arguments.getEditorComponent()).setText("save-edit");
                return subject.savePendingChangesAsync();
            });
            assertThat(save).isNotDone();

            generation.set(1);
            verificationPublication.complete(applied(1, configured));

            assertThat(save.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(savedConfiguration.get().servers().getFirst().arguments()).containsExactly("save-edit");
            verify(manager, times(2)).saveAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).verifyAppliedAsync(any(), any(), any());
        } finally {
            if (!verificationPublication.isDone()) {
                verificationPublication.completeExceptionally(new IllegalStateException("test cleanup"));
            }
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("An unchanged save does not wait for blocked Verify discovery")
    void savePendingChangesAsync_whenVerifyDiscoveryIsBlockedAndSnapshotUnchanged_doesNotWait() throws Exception {
        McpServerConfiguration configured = server("Blocked discovery", "blocked_discovery", McpTransportType.STDIO);
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        McpApplyResult applyResult = applied(1, configured);
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            generation.set(1);
            return CompletableFuture.completedFuture(applyResult);
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        CompletableFuture<McpVerificationResult> discovery = new CompletableFuture<>();
        CountDownLatch discoveryStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            discoveryStarted.countDown();
            return discovery;
        }).when(manager).verifyAppliedAsync(any(), any(), any());
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> button(subject, "Verify / Refresh").doClick());
            assertThat(discoveryStarted.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Boolean> save = callOnEdt(subject::savePendingChangesAsync);

            assertThat(save.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(discovery).isNotDone();
            verify(manager, times(1)).saveAndApply(any(McpConfigurationDraft.class));
        } finally {
            discovery.cancel(true);
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Disposal settles a save that is still waiting before manager submission")
    void savePendingChangesAsync_whenDisposedDuringBarrierWait_returnsClosedError() throws Exception {
        McpServerConfiguration configured = server("Barrier disposal", "barrier_disposal", McpTransportType.STDIO);
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        when(manager.publicationsSettled()).thenReturn(barrier);
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            CompletableFuture<Boolean> result = callOnEdt(subject::savePendingChangesAsync);

            runOnEdt(subject::disposePanel);

            assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(callOnEdt(subject::lastSaveError))
                    .isEqualTo("MCP settings save was cancelled because Settings closed.");
            barrier.complete(null);
            flushEdt();
            verify(manager, times(0)).saveAndApply(any(McpConfigurationDraft.class));
        } finally {
            barrier.complete(null);
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Two logical saves preserve a replacement entered after the first submission")
    void savePendingChangesAsync_whenSecondSaveHasNewerReplacement_publishesBothAttemptsIndependently() throws Exception {
        String rowId = UUID.randomUUID().toString();
        String initialSecretId = "MCP_11111111111111111111111111111111";
        McpServerConfiguration configured = httpServerWithHeader(rowId, initialSecretId);
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        CompletableFuture<McpApplyResult> firstPublication = new CompletableFuture<>();
        CompletableFuture<McpApplyResult> secondPublication = new CompletableFuture<>();
        CountDownLatch firstSubmitted = new CountDownLatch(1);
        CountDownLatch secondSubmitted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<char[]> secondSubmittedSecret = new AtomicReference<>();
        doAnswer(invocation -> {
            McpConfigurationDraft draft = invocation.getArgument(0);
            int call = calls.incrementAndGet();
            if (call == 2) {
                secondSubmittedSecret.set(Arrays.copyOf(draft.replacementSecrets().get(rowId),
                        draft.replacementSecrets().get(rowId).length));
            }
            draft.clearSecrets();
            (call == 1 ? firstSubmitted : secondSubmitted).countDown();
            return call == 1 ? firstPublication : secondPublication;
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> applyHeaderReplacement(subject, "first-secret"));
            CompletableFuture<Boolean> first = callOnEdt(subject::savePendingChangesAsync);
            assertThat(firstSubmitted.await(5, TimeUnit.SECONDS)).isTrue();

            runOnEdt(() -> applyHeaderReplacement(subject, "second-secret"));
            CompletableFuture<Boolean> second = callOnEdt(subject::savePendingChangesAsync);
            assertThat(first).isNotSameAs(second);
            assertThat(second).isNotDone();

            generation.set(1);
            firstPublication.complete(applied(1, withHeaderSecret(configured,
                    "MCP_22222222222222222222222222222222")));
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondSubmitted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondSubmittedSecret.get()).containsExactly("second-secret".toCharArray());

            generation.set(2);
            secondPublication.complete(applied(2, withHeaderSecret(configured,
                    "MCP_33333333333333333333333333333333")));
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertThat(callOnEdt(() -> field(subject, "replacementSecrets", Map.class))).isEmpty();
            assertThat(callOnEdt(subject::lastSaveError)).isEmpty();
            verify(manager, times(2)).saveAndApply(any(McpConfigurationDraft.class));
        } finally {
            if (!firstPublication.isDone()) {
                firstPublication.completeExceptionally(new IllegalStateException("test cleanup"));
            }
            if (!secondPublication.isDone()) {
                secondPublication.completeExceptionally(new IllegalStateException("test cleanup"));
            }
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Disposal cancels active editing and is idempotent on and off the EDT")
    void disposePanel_whenEditingIsActive_cancelsEditorAndRemainsIdempotent() throws Exception {
        McpServerConfiguration configured = new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Arguments",
                "arguments",
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
        PanelFixture fixture = fixture("dispose", new McpConfiguration(1, List.of(configured)));
        try {
            runOnEdt(() -> {
                JTable arguments = component(fixture.subject(), "Ordered MCP arguments", JTable.class);
                assertThat(arguments.editCellAt(0, 0)).isTrue();
                ((JTextField) arguments.getEditorComponent()).setText("uncommitted");
            });

            fixture.subject().disposePanel();
            flushEdt();
            runOnEdt(fixture.subject()::disposePanel);

            assertThat(callOnEdt(() -> component(fixture.subject(), "Ordered MCP arguments", JTable.class).isEditing()))
                    .isFalse();
            assertThat(callOnEdt(() -> field(fixture.subject(), "replacementSecrets", Map.class))).isEmpty();
        } finally {
            fixture.close();
        }
    }

    private McpManager invalidControlledManager(
            AtomicLong generation,
            AtomicReference<McpConfigurationLoadResult> loadResult
    ) {
        McpManager manager = mock(McpManager.class);
        when(manager.loadResult()).thenAnswer(invocation -> loadResult.get());
        when(manager.generation()).thenAnswer(invocation -> generation.get());
        when(manager.cleanupStatus()).thenReturn("");
        when(manager.publicationsSettled()).thenReturn(CompletableFuture.completedFuture(null));
        return manager;
    }

    private void clickWithConfirmation(McpPanel subject, String accessibleName, int result) {
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        try (MockedStatic<JOptionPane> confirmation = mockStatic(JOptionPane.class)) {
            confirmation.when(() -> JOptionPane.showConfirmDialog(
                    any(Component.class),
                    any(),
                    any(String.class),
                    anyInt(),
                    anyInt()
            )).thenReturn(result);
            button(subject, accessibleName).doClick();
            SwingUtilities.invokeLater(loop::exit);
            assertThat(loop.enter()).isTrue();
        }
    }

    private McpManager controlledManager(McpConfiguration configuration, AtomicLong generation) {
        McpManager manager = mock(McpManager.class);
        when(manager.loadResult()).thenReturn(new McpConfigurationLoadResult.Valid(configuration));
        when(manager.generation()).thenAnswer(invocation -> generation.get());
        when(manager.cleanupStatus()).thenReturn("");
        when(manager.publicationsSettled()).thenReturn(CompletableFuture.completedFuture(null));
        return manager;
    }

    private McpApplyResult applied(long generation, McpServerConfiguration server) {
        return new McpApplyResult(
                McpApplyOutcome.APPLIED,
                generation,
                new McpConfiguration(1, List.of(server)),
                ""
        );
    }

    private McpServerConfiguration httpServerWithHeader(String rowId, String secretId) {
        return new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "HTTP",
                "http_tools",
                false,
                false,
                McpTransportType.STREAMABLE_HTTP,
                "https://example.test/mcp",
                "",
                emptyList(),
                List.of(new McpSecretReference(rowId, "Authorization", secretId)),
                emptyList(),
                false,
                emptySet()
        );
    }

    private McpServerConfiguration withHeaderSecret(McpServerConfiguration server, String secretId) {
        McpSecretReference row = server.headers().getFirst();
        return new McpServerConfiguration(
                server.id(),
                server.name(),
                server.modelId(),
                server.enabled(),
                server.automatic(),
                server.transport(),
                server.endpoint(),
                server.executable(),
                server.arguments(),
                List.of(new McpSecretReference(row.rowId(), row.key(), secretId)),
                server.environment(),
                server.longRunning(),
                server.disabledTools()
        );
    }

    private void applyHeaderReplacement(McpPanel subject, String replacement) {
        Object headerEditor = field(subject, "headerEditor", Object.class);
        Container editor = (Container) headerEditor;
        findComponent(editor, JPasswordField.class).setText(replacement);
        findButton(editor, "Apply").doClick();
    }

    private PanelFixture fixture(String name, McpConfiguration configuration) throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory.resolve(name));
        if (configuration != null) {
            new McpConfigurationRepository(storagePaths.mcpFile()).save(configuration);
        }
        McpManager manager = manager(storagePaths);
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        return new PanelFixture(storagePaths, manager, subject);
    }

    private McpManager manager(StoragePaths storagePaths) {
        return new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
    }

    private McpServerConfiguration server(String name, String modelId, McpTransportType transport) {
        return new McpServerConfiguration(
                UUID.randomUUID().toString(),
                name,
                modelId,
                false,
                false,
                transport,
                transport == McpTransportType.STREAMABLE_HTTP ? "https://example.test/mcp" : "",
                transport == McpTransportType.STDIO ? "java" : "",
                emptyList(),
                emptyList(),
                emptyList(),
                false,
                emptySet()
        );
    }

    private List<Object> tableValues(JTable table) {
        var values = new ArrayList<>();
        for (int row = 0; row < table.getRowCount(); row++) {
            for (int column = 0; column < table.getColumnCount(); column++) {
                values.add(table.getValueAt(row, column));
            }
        }
        return List.copyOf(values);
    }

    private JScrollPane settingsScroll(Container root) {
        return components(root, JScrollPane.class).stream()
                .filter(scroll -> scroll.getViewport().getView() != null)
                .filter(scroll -> "WidthTrackingPanel".equals(
                        scroll.getViewport().getView().getClass().getSimpleName()
                ))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Settings scroll pane not found"));
    }

    private JLabel label(Container root, String text) {
        return components(root, JLabel.class).stream()
                .filter(component -> text.equals(component.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Label not found: %s".formatted(text)));
    }

    private JCheckBox findCheckBox(Container root, String text) {
        return components(root, JCheckBox.class).stream()
                .filter(component -> text.equals(component.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Checkbox not found: %s".formatted(text)));
    }

    private JButton button(Container root, String accessibleName) {
        List<JButton> matches = components(root, JButton.class).stream()
                .filter(component -> component.getAccessibleContext() != null)
                .filter(component -> accessibleName.equals(component.getAccessibleContext().getAccessibleName()))
                .filter(component -> !"Add server".equals(accessibleName) || component.getIcon() != null)
                .toList();
        assertThat(matches).as("button named %s", accessibleName).hasSize(1);
        return matches.getFirst();
    }

    private <T extends Component> T component(Container root, String accessibleName, Class<T> type) {
        List<T> matches = components(root, type).stream()
                .filter(component -> component.getAccessibleContext() != null)
                .filter(component -> accessibleName.equals(component.getAccessibleContext().getAccessibleName()))
                .toList();
        assertThat(matches).as("component named %s", accessibleName).hasSize(1);
        return matches.getFirst();
    }

    private <T extends Component> T findComponent(Container root, Class<T> type) {
        return components(root, type).stream().findFirst()
                .orElseThrow(() -> new AssertionError("Component not found: %s".formatted(type.getSimpleName())));
    }

    private JButton findButton(Container root, String text) {
        return components(root, JButton.class).stream()
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Button not found: %s".formatted(text)));
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
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return type.cast(field.get(target));
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Field not found: %s".formatted(name));
    }

    private void layoutRecursively(Container root) {
        root.doLayout();
        Arrays.stream(root.getComponents())
                .filter(Container.class::isInstance)
                .map(Container.class::cast)
                .forEach(this::layoutRecursively);
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

    private record SaveAdmission(CompletableFuture<Boolean> result, boolean completedInline) {
    }

    private final class PanelFixture implements AutoCloseable {
        private final StoragePaths storagePaths;
        private final McpManager manager;
        private final McpPanel subject;
        private boolean closed;

        private PanelFixture(StoragePaths storagePaths, McpManager manager, McpPanel subject) {
            this.storagePaths = storagePaths;
            this.manager = manager;
            this.subject = subject;
        }

        private StoragePaths storagePaths() {
            return storagePaths;
        }

        private McpManager manager() {
            return manager;
        }

        private McpPanel subject() {
            return subject;
        }

        @Override
        public void close() throws Exception {
            if (closed) {
                return;
            }
            closed = true;
            try {
                runOnEdt(subject::disposePanel);
                flushEdt();
            } finally {
                manager.close();
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
