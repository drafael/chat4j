package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLightLaf;
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
import com.github.drafael.chat4j.util.ModalDialogSupport;
import java.awt.Component;
import java.awt.Container;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListSelectionListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import static java.util.Arrays.copyOf;
import static java.util.Arrays.fill;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Objects.deepEquals;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpPanelTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("A new server derives a model ID from its name and keeps it stable after saving")
    void savePendingChangesAsync_whenServerIsAdded_generatesAndStabilizesModelId() throws Exception {
        try (var fixture = fixture("save", null)) {
            CompletableFuture<Boolean> firstPublication = callOnEdt(() -> {
                menuItem(fixture.subject(), "Command-line (stdio)").doClick();
                component(fixture.subject(), "MCP server name", JTextField.class).setText("Local tools");
                component(fixture.subject(), "MCP executable", JTextField.class).setText("java");
                return fixture.subject().savePendingChangesAsync();
            });

            assertThat(firstPublication.get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertThat(Files.readString(fixture.storagePaths().mcpFile()))
                    .contains("local_tools")
                    .doesNotContain("replacementSecrets");

            CompletableFuture<Boolean> secondPublication = callOnEdt(() -> {
                component(fixture.subject(), "MCP server name", JTextField.class).setText("Renamed tools");
                return fixture.subject().savePendingChangesAsync();
            });
            assertThat(secondPublication.get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();

            McpConfiguration persisted = ((McpConfigurationLoadResult.Valid) fixture.manager().loadResult())
                    .configuration();
            assertThat(persisted.servers()).singleElement()
                    .satisfies(server -> {
                        assertThat(server.name()).isEqualTo("Renamed tools");
                        assertThat(server.modelId()).isEqualTo("local_tools");
                    });
            assertThat(callOnEdt(fixture.subject()::lastSaveError)).isEmpty();
        }
    }

    @Test
    @DisplayName("Renaming an existing server preserves its persisted model ID")
    void savePendingChangesAsync_whenExistingServerIsRenamed_preservesModelId() throws Exception {
        McpServerConfiguration configured = server("Existing", "stable_alias", McpTransportType.STDIO);
        try (var fixture = fixture("existing-model-id", new McpConfiguration(1, List.of(configured)))) {
            CompletableFuture<Boolean> publication = callOnEdt(() -> {
                component(fixture.subject(), "MCP server name", JTextField.class).setText("Renamed existing server");
                return fixture.subject().savePendingChangesAsync();
            });

            assertThat(publication.get(5, TimeUnit.SECONDS)).isTrue();
            McpConfiguration persisted = ((McpConfigurationLoadResult.Valid) fixture.manager().loadResult())
                    .configuration();
            assertThat(persisted.servers()).singleElement()
                    .satisfies(server -> assertThat(server.modelId()).isEqualTo("stable_alias"));
        }
    }

    @Test
    @DisplayName("New servers derive unique model IDs from duplicate names")
    void finishActiveEditing_whenNewServerNamesCollide_generatesUniqueModelIds() throws Exception {
        try (var fixture = fixture("unique-model-ids", null)) {
            runOnEdt(() -> {
                menuItem(fixture.subject(), "Command-line (stdio)").doClick();
                component(fixture.subject(), "MCP server name", JTextField.class).setText("Context 7!");
                component(fixture.subject(), "MCP executable", JTextField.class).setText("java");
                fixture.subject().finishActiveEditing();

                menuItem(fixture.subject(), "Command-line (stdio)").doClick();
                component(fixture.subject(), "MCP server name", JTextField.class).setText("Context 7!");
                component(fixture.subject(), "MCP executable", JTextField.class).setText("java");
                fixture.subject().finishActiveEditing();

                JList<?> list = component(fixture.subject(), "MCP servers", JList.class);
                assertThat(List.of(
                        ((McpServerConfiguration) list.getModel().getElementAt(0)).modelId(),
                        ((McpServerConfiguration) list.getModel().getElementAt(1)).modelId()
                )).containsExactly("context_7", "context_7_2");
            });
        }
    }

    @Test
    @DisplayName("Generated model IDs fall back for non-ASCII names and remain within the validator limit")
    void finishActiveEditing_whenNewServerNamesNeedNormalization_generatesBoundedFallbackIds() throws Exception {
        try (var fixture = fixture("model-id-boundaries", null)) {
            runOnEdt(() -> {
                McpPanel subject = fixture.subject();
                menuItem(subject, "Command-line (stdio)").doClick();
                component(subject, "MCP server name", JTextField.class).setText("a".repeat(80));
                subject.finishActiveEditing();

                menuItem(subject, "Command-line (stdio)").doClick();
                component(subject, "MCP server name", JTextField.class).setText("你好");
                subject.finishActiveEditing();

                JList<?> list = component(subject, "MCP servers", JList.class);
                assertThat(((McpServerConfiguration) list.getModel().getElementAt(0)).modelId())
                        .isEqualTo("a".repeat(48));
                assertThat(((McpServerConfiguration) list.getModel().getElementAt(1)).modelId())
                        .isEqualTo("server");
            });
        }
    }

    @Test
    @DisplayName("Verify does not stabilize a new server model ID before coordinated Save")
    void savePendingChangesAsync_whenVerifyRunsFirst_stabilizesIdFromNameAtCoordinatedSave() throws Exception {
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(McpConfiguration.empty(), generation);
        List<McpConfiguration> submissions = new ArrayList<>();
        doAnswer(invocation -> {
            McpConfigurationDraft draft = invocation.getArgument(0);
            submissions.add(draft.configuration());
            draft.clearSecrets();
            long appliedGeneration = generation.incrementAndGet();
            return CompletableFuture.completedFuture(new McpApplyResult(
                    McpApplyOutcome.APPLIED,
                    appliedGeneration,
                    submissions.getLast(),
                    ""
            ));
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        CompletableFuture<McpVerificationResult> discovery = new CompletableFuture<>() {
            @Override
            public Executor defaultExecutor() {
                return Runnable::run;
            }
        };
        CountDownLatch discoveryStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            discoveryStarted.countDown();
            return discovery;
        }).when(manager).verifyAppliedAsync(any(), any(), any());
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> {
                menuItem(subject, "Command-line (stdio)").doClick();
                component(subject, "MCP server name", JTextField.class).setText("Before verify");
                component(subject, "MCP executable", JTextField.class).setText("java");
                button(subject, "Verify / Refresh").doClick();
            });
            assertThat(discoveryStarted.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Boolean> firstSave = callOnEdt(() -> {
                component(subject, "MCP server name", JTextField.class).setText("Saved name");
                return subject.savePendingChangesAsync();
            });
            assertThat(firstSave.get(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Boolean> secondSave = callOnEdt(() -> {
                component(subject, "MCP server name", JTextField.class).setText("Later rename");
                return subject.savePendingChangesAsync();
            });
            assertThat(secondSave.get(5, TimeUnit.SECONDS)).isTrue();

            assertThat(submissions).hasSize(3);
            assertThat(submissions.get(0).servers().getFirst().modelId()).isEqualTo("before_verify");
            assertThat(submissions.get(1).servers().getFirst().modelId()).isEqualTo("saved_name");
            assertThat(submissions.get(2).servers().getFirst().modelId()).isEqualTo("saved_name");
        } finally {
            discovery.cancel(true);
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("The editor hides model IDs and uses separator-free transport headings")
    void transportEditor_whenDisplayed_omitsModelIdWarningAndHeadingSeparators() throws Exception {
        try (var fixture = fixture("simplified-editor", null)) {
            runOnEdt(() -> {
                McpPanel subject = fixture.subject();
                menuItem(subject, "Command-line (stdio)").doClick();
                JPanel transportCards = field(subject, "transportCards", JPanel.class);

                assertThat(components(subject, JLabel.class))
                        .extracting(JLabel::getText)
                        .doesNotContain(
                                "Model ID",
                                "Credentials are encrypted; URL queries remain plaintext."
                        );
                List<JLabel> sectionHeadings = components(transportCards, JLabel.class).stream()
                        .filter(label -> Set.of("Ordered arguments", "Environment variables", "HTTP headers")
                                .contains(label.getText()))
                        .toList();
                assertThat(sectionHeadings)
                        .extracting(JLabel::getText)
                        .containsExactlyInAnyOrder("Ordered arguments", "Environment variables", "HTTP headers");
                assertThat(sectionHeadings)
                        .extracting(label -> label.getClientProperty(FlatClientProperties.STYLE_CLASS))
                        .containsOnly("h4");
                assertThat(components(transportCards, JSeparator.class)).isEmpty();
                for (String tableName : List.of("Environment variables", "HTTP headers")) {
                    JTable credentials = component(subject, tableName, JTable.class);
                    assertThat(credentials.getColumnCount()).isEqualTo(2);
                    assertThat(credentials.getColumnName(0)).isEqualTo("Name");
                    assertThat(credentials.getColumnName(1)).isEqualTo("Value");
                }
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Empty, search, and Add states preserve rail semantics while hiding model IDs")
    void serverRail_whenSelectionAndSearchChange_preservesMasterDetailState() throws Exception {
        McpServerConfiguration first = server("", "SERVER_3", McpTransportType.STDIO);
        McpServerConfiguration second = server("Beta", "beta_tools", McpTransportType.STDIO);
        try (var fixture = fixture("rail", new McpConfiguration(1, List.of(first, second)))) {
            runOnEdt(() -> {
                JList<?> list = component(fixture.subject(), "MCP servers", JList.class);
                list.clearSelection();
                JButton add = button(fixture.subject(), "New MCP server");
                JButton remove = button(fixture.subject(), "Remove selected MCP server");
                assertThat(add.isEnabled()).isTrue();
                assertThat(add.getText()).isNull();
                assertThat(add.getIcon()).isNotNull();
                assertThat(add.getToolTipText()).isEqualTo("New MCP server");
                assertThat(remove.isEnabled()).isFalse();
                assertThat(remove.getText()).isNull();
                assertThat(remove.getIcon()).isNotNull();
                assertThat(remove.getToolTipText()).isEqualTo("Remove selected MCP server");
                JPopupMenu creationMenu = field(fixture.subject(), "serverCreationMenu", JPopupMenu.class);
                assertThat(creationMenu.getComponentCount()).isEqualTo(4);
                assertThat(((JMenuItem) creationMenu.getComponent(0)).getText()).isEqualTo("Command-line (stdio)");
                assertThat(((JMenuItem) creationMenu.getComponent(1)).getText()).isEqualTo("HTTP Server (http)");
                assertThat(creationMenu.getComponent(2)).isInstanceOf(JSeparator.class);
                JMenuItem importItem = (JMenuItem) creationMenu.getComponent(3);
                assertThat(importItem.getText()).isEqualTo("Import JSON from Clipboard");
                assertThat(importItem.getAccessibleContext().getAccessibleName())
                        .isEqualTo("Import JSON from Clipboard");
                assertThat(importItem.getAccessibleContext().getAccessibleDescription())
                        .contains("one MCP server as disabled");
                assertThat(button(fixture.subject(), "Verify / Refresh").isEnabled()).isFalse();

                JTextField search = component(fixture.subject(), "Search MCP servers", JTextField.class);
                search.setText("  BETA  ");
                assertThat(((McpServerConfiguration) list.getSelectedValue()).id()).isEqualTo(second.id());
                search.setText("SERVER_3");
                assertThat(((McpServerConfiguration) list.getSelectedValue()).id()).isEqualTo(second.id());
                search.setText("no match");
                assertThat(((McpServerConfiguration) list.getSelectedValue()).id()).isEqualTo(second.id());
                search.setText("");
                assertThat(((McpServerConfiguration) list.getSelectedValue()).id()).isEqualTo(second.id());

                JList<McpServerConfiguration> typedList = (JList<McpServerConfiguration>) list;
                Component rendered = typedList.getCellRenderer().getListCellRendererComponent(
                        typedList,
                        second,
                        1,
                        false,
                        false
                );
                assertThat(components((Container) rendered, JLabel.class))
                        .extracting(JLabel::getText)
                        .containsExactly("Beta", "STDIO · disabled")
                        .doesNotContain("beta_tools");
                assertThat(rendered.getAccessibleContext().getAccessibleName())
                        .isEqualTo("Beta, STDIO, disabled");
                assertThat(first.displayName()).isEqualTo("SERVER_3");
                Component unnamed = typedList.getCellRenderer().getListCellRendererComponent(
                        typedList,
                        first,
                        0,
                        false,
                        false
                );
                assertThat(components((Container) unnamed, JLabel.class))
                        .extracting(JLabel::getText)
                        .containsExactly("Unnamed server", "STDIO · disabled")
                        .doesNotContain("SERVER_3");
                assertThat(unnamed.getAccessibleContext().getAccessibleName())
                        .isEqualTo("Unnamed server, STDIO, disabled");

                menuItem(fixture.subject(), "Command-line (stdio)").doClick();
                McpServerConfiguration added = (McpServerConfiguration) list.getSelectedValue();
                assertThat(added.modelId()).isEqualTo("new_server");
                assertThat(component(fixture.subject(), "MCP server editor", JTabbedPane.class).getSelectedIndex())
                        .isZero();
            });
        }
    }

    @Test
    @DisplayName("FlatLaf keeps the server toolbar actions square when fonts change")
    void serverRail_whenFlatLafIsActive_keepsIconActionsSquare() throws Exception {
        LookAndFeel originalLookAndFeel = callOnEdt(UIManager::getLookAndFeel);
        PanelFixture fixture = null;
        try {
            runOnEdt(() -> UIManager.setLookAndFeel(new FlatLightLaf()));
            fixture = fixture("flat-square-actions", null);
            McpPanel subject = fixture.subject();

            runOnEdt(() -> {
                JButton add = button(subject, "New MCP server");
                JButton remove = button(subject, "Remove selected MCP server");
                for (JButton action : List.of(add, remove)) {
                    assertThat(action.getClientProperty(FlatClientProperties.BUTTON_TYPE))
                            .isEqualTo(FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
                    assertThat(action.getPreferredSize().width).isEqualTo(action.getPreferredSize().height);

                    action.setFont(action.getFont().deriveFont(32f));
                    assertThat(action.getPreferredSize().width).isEqualTo(action.getPreferredSize().height);
                    assertThat(action.getIcon()).isNotNull();
                }
            });
        } finally {
            try {
                if (fixture != null) {
                    fixture.close();
                }
            } finally {
                runOnEdt(() -> UIManager.setLookAndFeel(originalLookAndFeel));
            }
        }
    }

    @Test
    @DisplayName("Editing and saving an HTTP server preserves its creation-time transport")
    void savePendingChangesAsync_whenHttpServerIsEdited_preservesCreationTransport() throws Exception {
        try (var fixture = fixture("add-http", null)) {
            CompletableFuture<Boolean> publication = callOnEdt(() -> {
                menuItem(fixture.subject(), "HTTP Server (http)").doClick();
                component(fixture.subject(), "MCP server name", JTextField.class).setText("HTTP tools");
                JTextField endpoint = component(fixture.subject(), "MCP HTTP endpoint", JTextField.class);
                endpoint.setText("https://example.test/mcp");
                fixture.subject().finishActiveEditing();

                JList<?> servers = component(fixture.subject(), "MCP servers", JList.class);
                McpServerConfiguration added = (McpServerConfiguration) servers.getSelectedValue();
                assertThat(added.transport()).isEqualTo(McpTransportType.STREAMABLE_HTTP);
                JPanel transportCards = field(fixture.subject(), "transportCards", JPanel.class);
                Component activeCard = stream(transportCards.getComponents())
                        .filter(Component::isVisible)
                        .findFirst()
                        .orElseThrow();
                assertThat(SwingUtilities.isDescendingFrom(endpoint, activeCard)).isTrue();
                assertThat(components(fixture.subject(), JToggleButton.class))
                        .extracting(JToggleButton::getText)
                        .doesNotContain("STDIO", "HTTP");
                return fixture.subject().savePendingChangesAsync();
            });

            assertThat(publication.get(5, TimeUnit.SECONDS)).isTrue();
            McpConfiguration persisted = ((McpConfigurationLoadResult.Valid) fixture.manager().loadResult())
                    .configuration();
            assertThat(persisted.servers()).singleElement()
                    .satisfies(server -> {
                        assertThat(server.transport()).isEqualTo(McpTransportType.STREAMABLE_HTTP);
                        assertThat(server.endpoint()).isEqualTo("https://example.test/mcp");
                    });
        }
    }

    @Test
    @DisplayName("Fixed transport and ordered arguments preserve exact STDIO values")
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
                long revisionBeforeCancellation = field(fixture.subject(), "draftRevision", Long.class);
                assertThat(arguments.editCellAt(0, 0)).isTrue();
                ((JTextField) arguments.getEditorComponent()).setText("cancelled argument");
                arguments.getCellEditor().cancelCellEditing();
                assertThat(arguments.getValueAt(0, 0)).isEqualTo("<html>精🔧é</html>");
                assertThat(field(fixture.subject(), "draftRevision", Long.class))
                        .isGreaterThan(revisionBeforeCancellation);
                assertThat(fixture.manager().generation()).isZero();
                assertThat(findCheckBox(fixture.subject(), "Long-running").isSelected()).isTrue();
                assertThat(tableValues(arguments)).containsExactly("<html>精🔧é</html>", "", "--flag", "  ", "value");
            });
        }
    }

    @Test
    @DisplayName("Argument toolbar actions add, remove, and reorder the selected row")
    void argumentToolbar_whenSelectionChanges_updatesRowsAndActionStates() throws Exception {
        McpServerConfiguration configured = new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Argument actions",
                "argument_actions",
                false,
                false,
                McpTransportType.STDIO,
                "",
                "java",
                List.of("one", "two"),
                emptyList(),
                emptyList(),
                false,
                emptySet()
        );
        try (var fixture = fixture("argument-actions", new McpConfiguration(1, List.of(configured)))) {
            runOnEdt(() -> {
                JTable arguments = component(fixture.subject(), "Ordered MCP arguments", JTable.class);
                JButton add = button(fixture.subject(), "Add argument");
                JButton remove = button(fixture.subject(), "Remove selected argument");
                JButton moveUp = button(fixture.subject(), "Move selected argument up");
                JButton moveDown = button(fixture.subject(), "Move selected argument down");
                assertThat(add.isEnabled()).isTrue();
                assertThat(remove.isEnabled()).isFalse();
                assertThat(moveUp.isEnabled()).isFalse();
                assertThat(moveDown.isEnabled()).isFalse();

                arguments.setRowSelectionInterval(1, 1);
                assertThat(remove.isEnabled()).isTrue();
                assertThat(moveUp.isEnabled()).isTrue();
                assertThat(moveDown.isEnabled()).isFalse();
                moveUp.doClick();
                assertThat(tableValues(arguments)).containsExactly("two", "one");
                assertThat(arguments.getSelectedRow()).isZero();
                assertThat(moveUp.isEnabled()).isFalse();
                assertThat(moveDown.isEnabled()).isTrue();

                add.doClick();
                assertThat(arguments.getRowCount()).isEqualTo(3);
                assertThat(arguments.getSelectedRow()).isEqualTo(2);
                assertThat(arguments.isEditing()).isTrue();
                ((JTextField) arguments.getEditorComponent()).setText("three");
                assertThat(arguments.getCellEditor().stopCellEditing()).isTrue();
                remove.doClick();
                assertThat(tableValues(arguments)).containsExactly("two", "one");
            });
        }
    }

    @Test
    @DisplayName("Automatic tool approval attempts fence verification before confirmation")
    void toggleAutomaticExecution_whenConfirmationRuns_marksEachAttemptOnce() throws Exception {
        McpServerConfiguration configured = server("Automatic", "automatic", McpTransportType.STDIO);
        try (var fixture = fixture("automatic-cancel", new McpConfiguration(1, List.of(configured)))) {
            runOnEdt(() -> {
                JCheckBox automatic = findCheckBox(fixture.subject(), "Run tools automatically");
                long revision = field(fixture.subject(), "draftRevision", Long.class);
                try (MockedStatic<ModalDialogSupport> confirmation = mockStatic(ModalDialogSupport.class)) {
                    confirmation.when(() -> ModalDialogSupport.showConfirmDialog(
                            any(Component.class),
                            any(),
                            anyInt(),
                            anyInt()
                    )).thenAnswer(invocation -> {
                        assertThat(field(fixture.subject(), "draftRevision", Long.class)).isEqualTo(revision + 1);
                        return JOptionPane.CANCEL_OPTION;
                    });

                    automatic.doClick();
                }

                assertThat(automatic.isSelected()).isFalse();
                long revisionAfterCancellation = field(fixture.subject(), "draftRevision", Long.class);
                assertThat(revisionAfterCancellation).isEqualTo(revision + 1);

                try (MockedStatic<ModalDialogSupport> confirmation = mockStatic(ModalDialogSupport.class)) {
                    confirmation.when(() -> ModalDialogSupport.showConfirmDialog(
                            any(Component.class),
                            any(),
                            anyInt(),
                            anyInt()
                    )).thenReturn(JOptionPane.OK_OPTION);

                    automatic.doClick();
                }

                assertThat(automatic.isSelected()).isTrue();
                assertThat(field(fixture.subject(), "draftRevision", Long.class))
                        .isEqualTo(revisionAfterCancellation + 1);
            });
        }
    }

    @Test
    @DisplayName("Masked credential editing exposes no plaintext and server removal wipes pending replacement")
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
        char[] savedValue = "saved-value".toCharArray();
        try {
            manager.saveAndApply(new McpConfigurationDraft(
                    new McpConfiguration(1, List.of(configured)),
                    Map.of(rowId, savedValue)
            )).join();
        } finally {
            fill(savedValue, '\0');
        }
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try (var fixture = new PanelFixture(storagePaths, manager, subject)) {
            AtomicReference<char[]> owned = new AtomicReference<>();
            runOnEdt(() -> {
                JTable headers = component(subject, "HTTP headers", JTable.class);
                assertThat(headers.getColumnName(0)).isEqualTo("Name");
                assertThat(headers.getColumnName(1)).isEqualTo("Value");
                assertCredentialValue(headers, 0, "••••••••", "header value should render as masked");
                Component rendered = headers.prepareRenderer(headers.getCellRenderer(0, 1), 0, 1);
                assertThat(((JLabel) rendered).getToolTipText()).isEqualTo("Credential available. Edit to replace it.");
                assertThat(rendered.getAccessibleContext().getAccessibleName())
                        .isEqualTo("Authorization, credential available");

                assertThat(headers.editCellAt(0, 1)).isTrue();
                JPasswordField password = (JPasswordField) headers.getEditorComponent();
                assertPasswordEmpty(password, "password editor should start empty");
                password.setText("unique-sentinel-secret");
                subject.finishActiveEditing();
                assertCredentialValue(headers, 0, "••••••••", "header value should render as masked");
                assertPasswordEmpty(password, "settled password editor should be empty");
                assertThat(tableValues(headers).toString().contains("unique-sentinel-secret"))
                        .as("credential table should not contain plaintext")
                        .isFalse();

                Map<String, char[]> replacements = field(subject, "replacementSecrets", Map.class);
                owned.set(replacements.get(rowId));
                assertSecretEquals(owned.get(), "unique-sentinel-secret", "pending replacement should match");
                assertThat(headers.editCellAt(0, 1)).isTrue();
                JPasswordField uncommitted = (JPasswordField) headers.getEditorComponent();
                uncommitted.setText("must-not-be-committed");
                button(subject, "Remove selected MCP server").doClick();
                assertPasswordEmpty(uncommitted, "removed password editor should be empty");
                assertSecretMapEmpty(replacements, "server removal should clear pending replacements");
            });
            assertAllNul(owned.get(), "removed replacement should be wiped");
        }
    }

    @Test
    @DisplayName("Inline credential cells persist exact names and encrypted replacement values")
    void savePendingChangesAsync_whenCredentialCellsAreEdited_persistsNameAndEncryptedValue() throws Exception {
        String rowId = UUID.randomUUID().toString();
        McpServerConfiguration configured = httpServerWithHeader(rowId, "");
        try (var fixture = fixture("inline-credential-save", new McpConfiguration(1, List.of(configured)))) {
            CompletableFuture<Boolean> publication = callOnEdt(() -> {
                McpPanel subject = fixture.subject();
                JTable headers = component(subject, "HTTP headers", JTable.class);
                assertThat(headers.getColumnCount()).isEqualTo(2);
                assertThat(headers.getColumnName(0)).isEqualTo("Name");
                assertThat(headers.getColumnName(1)).isEqualTo("Value");
                assertCredentialValue(headers, 0, "", "header value should render as blank");
                Component missing = headers.prepareRenderer(headers.getCellRenderer(0, 1), 0, 1);
                assertThat(((JLabel) missing).getToolTipText())
                        .isEqualTo("No credential value. Edit to add one.");
                assertThat(missing.getAccessibleContext().getAccessibleName())
                        .isEqualTo("Authorization, credential missing");

                assertThat(headers.editCellAt(0, 0)).isTrue();
                assertThat(headers.getEditorComponent().getAccessibleContext().getAccessibleName())
                        .isEqualTo("HTTP headers name");
                ((JTextField) headers.getEditorComponent()).setText("<html><b>X-Token</b></html>");
                assertThat(headers.getCellEditor().stopCellEditing()).isTrue();
                Component literal = headers.prepareRenderer(headers.getCellRenderer(0, 0), 0, 0);
                assertThat(((JLabel) literal).getText()).isEqualTo("<html><b>X-Token</b></html>");
                assertThat(((JComponent) literal).getClientProperty("html.disable")).isEqualTo(Boolean.TRUE);

                assertThat(headers.editCellAt(0, 0)).isTrue();
                ((JTextField) headers.getEditorComponent()).setText("X-Token");
                assertThat(headers.getCellEditor().stopCellEditing()).isTrue();
                assertThat(headers.editCellAt(0, 1)).isTrue();
                assertThat(headers.getEditorComponent()).isInstanceOf(JPasswordField.class);
                JPasswordField password = (JPasswordField) headers.getEditorComponent();
                assertThat(password.getAccessibleContext().getAccessibleName()).isEqualTo("HTTP headers value");
                assertPasswordEmpty(password, "password editor should start empty");
                password.setText("inline-secret");
                subject.finishActiveEditing();
                assertPasswordEmpty(password, "settled password editor should be empty");
                assertCredentialValue(headers, 0, "••••••••", "header value should render as masked");
                assertThat(tableValues(headers).toString().contains("inline-secret"))
                        .as("credential table should not contain plaintext")
                        .isFalse();
                return subject.savePendingChangesAsync();
            });

            assertThat(publication.get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            McpConfiguration persisted = ((McpConfigurationLoadResult.Valid) fixture.manager().loadResult())
                    .configuration();
            McpSecretReference persistedHeader = persisted.servers().getFirst().headers().getFirst();
            assertThat(persistedHeader.key()).isEqualTo("X-Token");
            assertThat(persistedHeader.secretId()).startsWith("MCP_");
            assertStoredSecret(fixture.storagePaths(), persistedHeader.secretId(), "inline-secret");
            String persistedJson = Files.readString(fixture.storagePaths().mcpFile());
            assertThat(persistedJson.contains("inline-secret"))
                    .as("persisted MCP JSON should not contain the inline credential")
                    .isFalse();
            assertThat(callOnEdt(() -> replacementSecretsEmpty(fixture.subject())))
                    .as("saved replacements should be empty")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Inline environment editing is active only for STDIO and persists encrypted values")
    void savePendingChangesAsync_whenEnvironmentCellsAreEdited_persistsStdioCredential() throws Exception {
        String rowId = UUID.randomUUID().toString();
        McpServerConfiguration base = server("STDIO", "stdio", McpTransportType.STDIO);
        McpServerConfiguration configured = new McpServerConfiguration(
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
                List.of(new McpSecretReference(rowId, "TOKEN", "")),
                base.longRunning(),
                base.disabledTools()
        );
        try (var fixture = fixture("inline-environment-save", new McpConfiguration(1, List.of(configured)))) {
            CompletableFuture<Boolean> publication = callOnEdt(() -> {
                McpPanel subject = fixture.subject();
                JTable environment = component(subject, "Environment variables", JTable.class);
                JTable headers = component(subject, "HTTP headers", JTable.class);
                assertThat(environment.isEnabled()).isTrue();
                assertThat(headers.isEnabled()).isFalse();

                assertThat(environment.editCellAt(0, 0)).isTrue();
                ((JTextField) environment.getEditorComponent()).setText("API_TOKEN");
                assertThat(environment.getCellEditor().stopCellEditing()).isTrue();
                assertThat(environment.editCellAt(0, 1)).isTrue();
                JPasswordField password = (JPasswordField) environment.getEditorComponent();
                password.setText("environment-secret");
                subject.finishActiveEditing();
                assertPasswordEmpty(password, "settled environment editor should be empty");
                assertCredentialValue(environment, 0, "••••••••", "environment value should render as masked");
                return subject.savePendingChangesAsync();
            });

            assertThat(publication.get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            McpConfiguration persisted = ((McpConfigurationLoadResult.Valid) fixture.manager().loadResult())
                    .configuration();
            McpSecretReference persistedVariable = persisted.servers().getFirst().environment().getFirst();
            assertThat(persistedVariable.key()).isEqualTo("API_TOKEN");
            assertThat(persistedVariable.secretId()).startsWith("MCP_");
            assertStoredSecret(fixture.storagePaths(), persistedVariable.secretId(), "environment-secret");
            String persistedJson = Files.readString(fixture.storagePaths().mcpFile());
            assertThat(persistedJson.contains("environment-secret"))
                    .as("persisted MCP JSON should not contain the environment credential")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Blank and cancelled inline value edits retain the pending credential")
    void credentialEditor_whenValueEditIsBlankOrCancelled_retainsPendingCredential() throws Exception {
        String rowId = UUID.randomUUID().toString();
        McpServerConfiguration configured = httpServerWithHeader(
                rowId,
                "MCP_11111111111111111111111111111111"
        );
        AtomicReference<char[]> overwritten = new AtomicReference<>();
        AtomicReference<char[]> owned = new AtomicReference<>();
        try (var fixture = fixture("inline-credential-retain", new McpConfiguration(1, List.of(configured)))) {
            runOnEdt(() -> {
                McpPanel subject = fixture.subject();
                JTable headers = component(subject, "HTTP headers", JTable.class);
                assertCredentialValue(headers, 0, "••••••••", "header value should render as masked");

                applyHeaderReplacement(subject, "first-secret");
                Map<String, char[]> replacements = field(subject, "replacementSecrets", Map.class);
                overwritten.set(replacements.get(rowId));
                applyHeaderReplacement(subject, "pending-secret");
                owned.set(replacements.get(rowId));
                assertAllNul(overwritten.get(), "overwritten replacement should be wiped");
                assertSecretEquals(owned.get(), "pending-secret", "current replacement should match");
                long revisionBeforeRetainedEdits = field(subject, "draftRevision", Long.class);

                assertThat(headers.editCellAt(0, 0)).isTrue();
                ((JTextField) headers.getEditorComponent()).setText("Cancelled-Name");
                headers.getCellEditor().cancelCellEditing();
                assertThat(headers.getValueAt(0, 0)).isEqualTo("Authorization");
                long revisionAfterNameCancellation = field(subject, "draftRevision", Long.class);
                assertThat(revisionAfterNameCancellation).isGreaterThan(revisionBeforeRetainedEdits);

                assertThat(headers.editCellAt(0, 1)).isTrue();
                JPasswordField blank = (JPasswordField) headers.getEditorComponent();
                blank.setText("   ");
                assertThat(headers.getCellEditor().stopCellEditing()).isTrue();
                assertPasswordEmpty(blank, "blank settled editor should be empty");
                assertThat(replacements.get(rowId) == owned.get())
                        .as("retained replacement should preserve array identity")
                        .isTrue();
                long revisionAfterBlankSettlement = field(subject, "draftRevision", Long.class);
                assertThat(revisionAfterBlankSettlement).isGreaterThan(revisionAfterNameCancellation);

                assertThat(headers.editCellAt(0, 1)).isTrue();
                JPasswordField cancelled = (JPasswordField) headers.getEditorComponent();
                cancelled.setText("cancelled-secret");
                headers.getCellEditor().cancelCellEditing();
                assertPasswordEmpty(cancelled, "cancelled editor should be empty");
                assertThat(replacements.get(rowId) == owned.get())
                        .as("retained replacement should preserve array identity")
                        .isTrue();
                assertCredentialValue(headers, 0, "••••••••", "header value should render as masked");
                assertThat(field(subject, "draftRevision", Long.class))
                        .isGreaterThan(revisionAfterBlankSettlement);
            });
        }
        assertAllNul(owned.get(), "disposed replacement should be wiped");
    }

    @Test
    @DisplayName("Credential Add starts inline Name editing and Remove discards an active password")
    void credentialEditor_whenRowIsAddedAndRemoved_usesInlineEditorsAndWipesValue() throws Exception {
        McpServerConfiguration configured = server("HTTP", "http", McpTransportType.STREAMABLE_HTTP);
        try (var fixture = fixture("inline-credential-actions", new McpConfiguration(1, List.of(configured)))) {
            runOnEdt(() -> {
                McpPanel subject = fixture.subject();
                JTable headers = component(subject, "HTTP headers", JTable.class);
                Container editor = (Container) field(subject, "headerEditor", Object.class);
                assertThat(components(editor, JButton.class))
                        .extracting(button -> button.getAccessibleContext().getAccessibleName())
                        .containsExactly("Add a row to HTTP headers", "Remove the selected row from HTTP headers");
                assertThat(components(editor, JTextField.class)).isEmpty();
                assertThat(components(editor, JPasswordField.class)).isEmpty();
                JButton addRow = button(editor, "Add a row to HTTP headers");
                JButton removeRow = button(editor, "Remove the selected row from HTTP headers");
                assertThat(addRow.getAccessibleContext().getAccessibleName()).isEqualTo("Add a row to HTTP headers");
                assertThat(removeRow.getAccessibleContext().getAccessibleName())
                        .isEqualTo("Remove the selected row from HTTP headers");

                addRow.doClick();
                assertThat(headers.getRowCount()).isEqualTo(1);
                assertThat(headers.getSelectedRow()).isZero();
                assertThat(headers.getEditingColumn()).isZero();
                assertThat(headers.getEditorComponent()).isInstanceOf(JTextField.class);
                ((JTextField) headers.getEditorComponent()).setText("X-Temporary");
                assertThat(headers.getCellEditor().stopCellEditing()).isTrue();
                assertThat(headers.getValueAt(0, 0)).isEqualTo("X-Temporary");

                assertThat(headers.editCellAt(0, 1)).isTrue();
                JPasswordField password = (JPasswordField) headers.getEditorComponent();
                password.setText("must-not-survive");
                removeRow.doClick();

                assertPasswordEmpty(password, "removed credential editor should be empty");
                assertThat(headers.isEditing()).isFalse();
                assertThat(headers.getRowCount()).isZero();
                assertSecretMapEmpty(
                        field(subject, "replacementSecrets", Map.class),
                        "removed credential row should clear pending replacements"
                );
            });
        }
    }

    @Test
    @DisplayName("Changing credential rows immediately commits the value to its original row")
    void credentialSelection_whenPasswordIsEditing_commitsValueToOriginalRow() throws Exception {
        String firstRowId = UUID.randomUUID().toString();
        String secondRowId = UUID.randomUUID().toString();
        McpServerConfiguration base = server("HTTP", "http", McpTransportType.STREAMABLE_HTTP);
        McpServerConfiguration configured = new McpServerConfiguration(
                base.id(),
                base.name(),
                base.modelId(),
                base.enabled(),
                base.automatic(),
                base.transport(),
                base.endpoint(),
                base.executable(),
                base.arguments(),
                List.of(
                        new McpSecretReference(firstRowId, "X-First", ""),
                        new McpSecretReference(secondRowId, "X-Second", "")
                ),
                base.environment(),
                base.longRunning(),
                base.disabledTools()
        );
        try (var fixture = fixture("inline-credential-row-identity", new McpConfiguration(1, List.of(configured)))) {
            runOnEdt(() -> {
                McpPanel subject = fixture.subject();
                JTable headers = component(subject, "HTTP headers", JTable.class);
                assertThat(headers.editCellAt(0, 1)).isTrue();
                ((JPasswordField) headers.getEditorComponent()).setText("first-row-secret");
                headers.setRowSelectionInterval(1, 1);

                assertThat(headers.isEditing()).isFalse();
                Map<String, char[]> replacements = field(subject, "replacementSecrets", Map.class);
                assertThat(replacements.keySet()).containsOnly(firstRowId);
                assertSecretEquals(
                        replacements.get(firstRowId),
                        "first-row-secret",
                        "selection change should commit to the original row"
                );
                assertCredentialValue(headers, 0, "••••••••", "header value should render as masked");
                assertCredentialValue(headers, 1, "", "header value should render as blank");
            });
        }
    }

    @Test
    @DisplayName("Changing servers settles a password against its original credential row")
    void finishActiveEditing_whenServerSelectionChanges_commitsCredentialToOriginalServer() throws Exception {
        String firstRowId = UUID.randomUUID().toString();
        McpServerConfiguration first = httpServerWithHeader(firstRowId, "");
        McpServerConfiguration second = server("Second HTTP", "second_http", McpTransportType.STREAMABLE_HTTP);
        try (var fixture = fixture(
                "inline-credential-server-identity",
                new McpConfiguration(1, List.of(first, second))
        )) {
            CompletableFuture<Boolean> publication = callOnEdt(() -> {
                McpPanel subject = fixture.subject();
                JTable headers = component(subject, "HTTP headers", JTable.class);
                assertThat(headers.editCellAt(0, 1)).isTrue();
                JPasswordField password = (JPasswordField) headers.getEditorComponent();
                password.setText("first-server-secret");

                component(subject, "MCP servers", JList.class).setSelectedIndex(1);

                assertPasswordEmpty(password, "server change should clear the password editor");
                Map<String, char[]> replacements = field(subject, "replacementSecrets", Map.class);
                assertThat(replacements.keySet()).containsOnly(firstRowId);
                assertSecretEquals(
                        replacements.get(firstRowId),
                        "first-server-secret",
                        "server selection should commit to the original server"
                );
                assertThat(headers.getRowCount()).isZero();
                return subject.savePendingChangesAsync();
            });

            assertThat(publication.get(5, TimeUnit.SECONDS))
                    .as(callOnEdt(fixture.subject()::lastSaveError))
                    .isTrue();
            McpConfiguration persisted = ((McpConfigurationLoadResult.Valid) fixture.manager().loadResult())
                    .configuration();
            McpServerConfiguration persistedFirst = persisted.servers().stream()
                    .filter(server -> server.id().equals(first.id()))
                    .findFirst()
                    .orElseThrow();
            McpServerConfiguration persistedSecond = persisted.servers().stream()
                    .filter(server -> server.id().equals(second.id()))
                    .findFirst()
                    .orElseThrow();
            McpSecretReference persistedHeader = persistedFirst.headers().getFirst();
            assertThat(persistedHeader.secretId()).startsWith("MCP_");
            assertThat(persistedSecond.headers()).isEmpty();
            assertStoredSecret(fixture.storagePaths(), persistedHeader.secretId(), "first-server-secret");
        }
    }

    @Test
    @DisplayName("Disposal cancels an inline password edit and wipes its prior pending replacement")
    void disposePanel_whenCredentialValueIsEditing_wipesEditorAndReplacement() throws Exception {
        String rowId = UUID.randomUUID().toString();
        McpServerConfiguration configured = httpServerWithHeader(rowId, "");
        AtomicReference<char[]> owned = new AtomicReference<>();
        PanelFixture fixture = fixture("inline-credential-dispose", new McpConfiguration(1, List.of(configured)));
        try {
            runOnEdt(() -> {
                McpPanel subject = fixture.subject();
                applyHeaderReplacement(subject, "pending-secret");
                Map<String, char[]> replacements = field(subject, "replacementSecrets", Map.class);
                owned.set(replacements.get(rowId));
                JTable headers = component(subject, "HTTP headers", JTable.class);
                assertThat(headers.editCellAt(0, 1)).isTrue();
                JPasswordField password = (JPasswordField) headers.getEditorComponent();
                password.setText("uncommitted-secret");

                subject.disposePanel();

                assertPasswordEmpty(password, "disposed password editor should be empty");
                assertThat(headers.isEditing()).isFalse();
                assertThat(headers.getRowCount()).isZero();
                assertSecretMapEmpty(replacements, "disposal should clear pending replacements");
            });
            assertAllNul(owned.get(), "disposed replacement should be wiped");
        } finally {
            fixture.close();
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
    @DisplayName("Removing a server clears tool selection before showing the next server")
    void removeServer_whenNextServerHasSameToolName_doesNotCarryToolSelectionAcrossServers() throws Exception {
        McpServerConfiguration first = server("First", "first", McpTransportType.STDIO);
        McpServerConfiguration second = server("Second", "second", McpTransportType.STDIO);
        try (var fixture = fixture("remove-tool-selection", new McpConfiguration(1, List.of(first, second)))) {
            runOnEdt(() -> {
                McpPanel subject = fixture.subject();
                McpDiscoveredTool firstTool = new McpDiscoveredTool(
                        "echo",
                        "First echo",
                        "First server tool",
                        Map.of("type", "object"),
                        null
                );
                McpDiscoveredTool secondTool = new McpDiscoveredTool(
                        "echo",
                        "Second echo",
                        "Second server tool",
                        Map.of("type", "object"),
                        null
                );
                Map<String, List<McpDiscoveredTool>> tools = field(subject, "lastTools", Map.class);
                tools.put(first.id(), List.of(firstTool));
                tools.put(second.id(), List.of(secondTool));
                invoke(subject, "refreshToolPresentation");
                JTable table = component(subject, "Discovered MCP tools", JTable.class);
                table.setRowSelectionInterval(0, 0);

                button(subject, "Remove selected MCP server").doClick();

                assertThat(((McpServerConfiguration) component(subject, "MCP servers", JList.class)
                        .getSelectedValue()).id()).isEqualTo(second.id());
                assertThat(table.getRowCount()).isEqualTo(1);
                assertThat(table.getSelectedRow()).isEqualTo(-1);
                assertThat(field(subject, "toolDetailsHeading", JLabel.class).getText())
                        .isEqualTo("No tool selected");
            });
        }
    }

    @Test
    @DisplayName("Tool schemas retain pretty-printed JSON line breaks and indentation")
    void formatSchemas_whenSchemaIsNested_preservesPrettyPrintedLayout() throws Exception {
        McpServerConfiguration configured = server("Schema", "schema", McpTransportType.STDIO);
        try (var fixture = fixture("schema-format", new McpConfiguration(1, List.of(configured)))) {
            var schema = Map.of(
                    "$schema", "http://json-schema.org/draft-07/schema#",
                    "type", "object",
                    "properties", Map.of("query", Map.of("type", "string"))
            );
            var tool = new McpDiscoveredTool("lookup", "Lookup", "Lookup documentation", schema, null);

            Map<?, ?> formatted = invoke(
                    fixture.subject(),
                    "formatSchemas",
                    List.class,
                    List.of(tool),
                    Map.class
            );

            assertThat((String) formatted.get("lookup"))
                    .startsWith("{")
                    .contains("\n  \"$schema\" : \"http://json-schema.org/draft-07/schema#\"")
                    .contains("\n  \"properties\" : {")
                    .contains("\n    \"query\" : {")
                    .contains("\n      \"type\" : \"string\"")
                    .endsWith("\n}");
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
            assertThat(callOnEdt(() -> button(subject, "New MCP server").isEnabled())).isTrue();

            CompletableFuture<Boolean> untouched = callOnEdt(subject::savePendingChangesAsync);
            assertThat(untouched.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.readString(storagePaths.mcpFile())).isEqualTo("{invalid");

            CompletableFuture<Boolean> dirty = callOnEdt(() -> {
                menuItem(subject, "Command-line (stdio)").doClick();
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
    @DisplayName("Settings groups use the full editor width and place row actions above their tables")
    void layout_whenSettingsTabIsNarrow_keepsGroupsAlignedAndOrdered() throws Exception {
        McpServerConfiguration stdio = server("STDIO layout", "stdio_layout", McpTransportType.STDIO);
        McpServerConfiguration http = server("HTTP layout", "http_layout", McpTransportType.STREAMABLE_HTTP);
        try (var fixture = fixture("layout", new McpConfiguration(1, List.of(stdio, http)))) {
            runOnEdt(() -> {
                fixture.subject().setSize(560, 430);
                layoutRecursively(fixture.subject());
                layoutRecursively(fixture.subject());
                JScrollPane settingsScroll = settingsScroll(fixture.subject());
                Container content = (Container) settingsScroll.getViewport().getView();
                JTextField executable = component(fixture.subject(), "MCP executable", JTextField.class);
                JTable arguments = component(fixture.subject(), "Ordered MCP arguments", JTable.class);
                Container argumentsEditor = (Container) field(fixture.subject(), "argumentsEditor", Object.class);
                JButton addArgument = button(argumentsEditor, "Add argument");
                JTable environment = component(fixture.subject(), "Environment variables", JTable.class);
                Container environmentEditor = (Container) field(
                        fixture.subject(),
                        "environmentEditor",
                        Object.class
                );
                JButton addCredential = button(environmentEditor, "Add a row to Environment variables");
                java.awt.Rectangle executableBounds = SwingUtilities.convertRectangle(
                        executable.getParent(),
                        new java.awt.Rectangle(0, 0, executable.getParent().getWidth(), executable.getParent().getHeight()),
                        content
                );
                assertThat(executableBounds.x).isLessThanOrEqualTo(8);
                assertThat(executableBounds.width).isGreaterThan(content.getWidth() * 4 / 5);
                assertActionAboveTable(argumentsEditor, arguments, addArgument);
                assertActionAboveTable(environmentEditor, environment, addCredential);
                assertThat(components(environmentEditor, JTextField.class)).isEmpty();
                assertThat(components(environmentEditor, JButton.class))
                        .extracting(button -> button.getAccessibleContext().getAccessibleName())
                        .containsExactly(
                                "Add a row to Environment variables",
                                "Remove the selected row from Environment variables"
                        );
                JPanel transportCards = field(fixture.subject(), "transportCards", JPanel.class);
                Component visibleStdioCard = stream(transportCards.getComponents())
                        .filter(Component::isVisible)
                        .findFirst()
                        .orElseThrow();
                assertThat(SwingUtilities.isDescendingFrom(executable, visibleStdioCard)).isTrue();
                assertThat(components(fixture.subject(), JLabel.class))
                        .extracting(JLabel::getText)
                        .doesNotContain("Transport");

                JList<?> servers = component(fixture.subject(), "MCP servers", JList.class);
                servers.setSelectedIndex(1);
                layoutRecursively(fixture.subject());
                JTextField endpoint = component(fixture.subject(), "MCP HTTP endpoint", JTextField.class);
                java.awt.Rectangle endpointBounds = SwingUtilities.convertRectangle(
                        endpoint.getParent(),
                        new java.awt.Rectangle(0, 0, endpoint.getParent().getWidth(), endpoint.getParent().getHeight()),
                        content
                );
                assertThat(endpointBounds.x).isLessThanOrEqualTo(8);
                assertThat(endpointBounds.width).isGreaterThan(content.getWidth() * 4 / 5);
                JTable headers = component(fixture.subject(), "HTTP headers", JTable.class);
                Container headerEditor = (Container) field(fixture.subject(), "headerEditor", Object.class);
                JButton addHeader = button(headerEditor, "Add a row to HTTP headers");
                assertActionAboveTable(headerEditor, headers, addHeader);
                Component visibleHttpCard = stream(transportCards.getComponents())
                        .filter(Component::isVisible)
                        .findFirst()
                        .orElseThrow();
                assertThat(SwingUtilities.isDescendingFrom(endpoint, visibleHttpCard)).isTrue();
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
    @DisplayName("Footer grows from one visual row to its three-row cap while preserving full text")
    void footer_whenStatusChanges_sizesViewportToVisibleRowsWithoutClippingDocument() throws Exception {
        try (var fixture = fixture("footer", null)) {
            runOnEdt(() -> {
                JTextArea status = component(fixture.subject(), "MCP status", JTextArea.class);
                JScrollPane scroll = field(fixture.subject(), "statusScroll", JScrollPane.class);
                fixture.subject().setSize(560, 430);
                status.setText("Short status");
                layoutRecursively(fixture.subject());
                layoutRecursively(fixture.subject());
                int rowHeight = status.getFontMetrics(status.getFont()).getHeight();
                int verticalInsets = status.getInsets().top + status.getInsets().bottom;

                assertThat(status.getPreferredScrollableViewportSize().height).isEqualTo(rowHeight + verticalInsets);

                String message = "Long authoritative status ".repeat(80);
                status.setText(message);
                layoutRecursively(fixture.subject());
                layoutRecursively(fixture.subject());

                assertThat(status.getPreferredScrollableViewportSize().height)
                        .isEqualTo(rowHeight * 3 + verticalInsets);
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
            assertSecretEquals(replacements.get(rowId), "retry-secret", "retry replacement should remain owned");
            assertThat(callOnEdt(subject::lastSaveError)).isEqualTo("submission failed");
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("An asynchronously rejected publication consumes its admitted credential value")
    void savePendingChangesAsync_whenPublicationIsRejected_requiresCredentialReentry() throws Exception {
        String rowId = UUID.randomUUID().toString();
        McpServerConfiguration configured = httpServerWithHeader(rowId, "");
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        CompletableFuture<McpApplyResult> rejectedPublication = new CompletableFuture<>();
        CountDownLatch submitted = new CountDownLatch(1);
        AtomicReference<char[]> submittedSecret = new AtomicReference<>();
        doAnswer(invocation -> {
            McpConfigurationDraft draft = invocation.getArgument(0);
            char[] secret = draft.replacementSecrets().get(rowId);
            submittedSecret.set(copyOf(secret, secret.length));
            draft.clearSecrets();
            submitted.countDown();
            return rejectedPublication;
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> applyHeaderReplacement(subject, "submitted-secret"));
            CompletableFuture<Boolean> result = callOnEdt(subject::savePendingChangesAsync);
            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
            assertSecretEquals(submittedSecret.get(), "submitted-secret", "submitted replacement should match");
            assertThat(callOnEdt(() -> replacementSecretsEmpty(subject)))
                    .as("submitted replacements should be consumed")
                    .isTrue();

            rejectedPublication.complete(new McpApplyResult(
                    McpApplyOutcome.REJECTED_OLD_STATE_INTACT,
                    0,
                    new McpConfiguration(1, List.of(configured)),
                    "publication rejected"
            ));

            assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
            flushEdt();
            assertThat(callOnEdt(() -> replacementSecretsEmpty(subject)))
                    .as("rejected publication should not restore consumed replacements")
                    .isTrue();
            runOnEdt(() -> assertCredentialValue(
                    component(subject, "HTTP headers", JTable.class),
                    0,
                    "",
                    "consumed header replacement should render as blank"
            ));
        } finally {
            if (!rejectedPublication.isDone()) {
                rejectedPublication.completeExceptionally(new IllegalStateException("test cleanup"));
            }
            if (submittedSecret.get() != null) {
                fill(submittedSecret.get(), '\0');
            }
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("An unobservable applied publication still consumes its admitted credential value")
    void savePendingChangesAsync_whenAdvancedStateCannotBeObserved_requiresCredentialReentry() throws Exception {
        String rowId = UUID.randomUUID().toString();
        McpServerConfiguration configured = httpServerWithHeader(rowId, "");
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        when(manager.publicationsSettled()).thenReturn(
                CompletableFuture.completedFuture(null),
                CompletableFuture.failedFuture(new IllegalStateException("observation failed"))
        );
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            generation.set(2);
            return CompletableFuture.completedFuture(applied(
                    1,
                    withHeaderSecret(configured, "MCP_22222222222222222222222222222222")
            ));
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> applyHeaderReplacement(subject, "retry-after-observation-failure"));

            CompletableFuture<Boolean> result = callOnEdt(subject::savePendingChangesAsync);

            assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
            flushEdt();
            assertThat(callOnEdt(subject::lastSaveError)).isEqualTo("observation failed");
            assertThat(callOnEdt(() -> replacementSecretsEmpty(subject)))
                    .as("unobservable publication should consume replacements")
                    .isTrue();
            runOnEdt(() -> assertCredentialValue(
                    component(subject, "HTTP headers", JTable.class),
                    0,
                    "",
                    "consumed header replacement should render as blank"
            ));
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
    @DisplayName("Disposal settles an applied save waiting to observe a newer manager generation")
    void savePendingChangesAsync_whenDisposedDuringAdvancedObservation_settlesFromAppliedOutcome() throws Exception {
        McpServerConfiguration configured = server("Advanced disposal", "advanced_disposal", McpTransportType.STDIO);
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        CompletableFuture<Void> observationBarrier = new CompletableFuture<>();
        CountDownLatch observationStarted = new CountDownLatch(1);
        AtomicInteger observations = new AtomicInteger();
        when(manager.publicationsSettled()).thenAnswer(invocation -> {
            if (observations.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(null);
            }
            observationStarted.countDown();
            return observationBarrier;
        });
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            generation.set(2);
            return CompletableFuture.completedFuture(applied(1, configured));
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            CompletableFuture<Boolean> result = callOnEdt(subject::savePendingChangesAsync);
            assertThat(observationStarted.await(5, TimeUnit.SECONDS)).isTrue();

            runOnEdt(subject::disposePanel);

            assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(observationBarrier).isNotDone();
            assertThat(callOnEdt(() -> field(subject, "serverModel", javax.swing.DefaultListModel.class).isEmpty()))
                    .isTrue();
            assertThat(callOnEdt(() -> field(subject, "publicationUiSettlement", CompletableFuture.class).isDone()))
                    .isTrue();
            observationBarrier.complete(null);
            flushEdt();
            assertThat(callOnEdt(() -> field(subject, "serverModel", javax.swing.DefaultListModel.class).isEmpty()))
                    .isTrue();
        } finally {
            observationBarrier.complete(null);
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Rejected Repair reports a later stable-observation failure")
    void repairInvalidConfiguration_whenRejectedObservationFails_reportsObservationFailure() throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        when(manager.publicationsSettled()).thenReturn(
                CompletableFuture.completedFuture(null),
                CompletableFuture.failedFuture(new IllegalStateException("final observation failed"))
        );
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            return CompletableFuture.completedFuture(new McpApplyResult(
                    McpApplyOutcome.REJECTED_OLD_STATE_INTACT,
                    0,
                    McpConfiguration.empty(),
                    "publication rejected"
            ));
        }).when(manager).replaceInvalidAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> clickWithConfirmation(
                    subject,
                    "Replace / Recreate invalid configuration",
                    JOptionPane.OK_OPTION
            ));
            flushEdt();

            assertThat(callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText()))
                    .isEqualTo("final observation failed");
            assertThat(callOnEdt(() -> field(
                    subject,
                    "publicationUiSettlement",
                    CompletableFuture.class
            ).isDone())).isTrue();
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Disposal prevents rejected Repair observation from restoring cleared models")
    void repairInvalidConfiguration_whenDisposedDuringRejectedObservation_keepsModelsCleared() throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        CompletableFuture<Void> observationBarrier = new CompletableFuture<>();
        CountDownLatch observationStarted = new CountDownLatch(1);
        AtomicInteger observations = new AtomicInteger();
        when(manager.publicationsSettled()).thenAnswer(invocation -> {
            if (observations.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(null);
            }
            observationStarted.countDown();
            return observationBarrier;
        });
        McpServerConfiguration externallyRepaired = server(
                "External repair",
                "external_repair",
                McpTransportType.STDIO
        );
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            return CompletableFuture.completedFuture(new McpApplyResult(
                    McpApplyOutcome.REJECTED_OLD_STATE_INTACT,
                    0,
                    McpConfiguration.empty(),
                    "publication rejected"
            ));
        }).when(manager).replaceInvalidAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> clickWithConfirmation(
                    subject,
                    "Replace / Recreate invalid configuration",
                    JOptionPane.OK_OPTION
            ));
            assertThat(observationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            generation.set(1);
            loadResult.set(new McpConfigurationLoadResult.Valid(
                    new McpConfiguration(1, List.of(externallyRepaired))
            ));

            runOnEdt(subject::disposePanel);

            assertThat(callOnEdt(() -> field(subject, "publicationUiSettlement", CompletableFuture.class).isDone()))
                    .isTrue();
            assertThat(callOnEdt(() -> field(subject, "serverModel", javax.swing.DefaultListModel.class).isEmpty()))
                    .isTrue();
            observationBarrier.complete(null);
            flushEdt();
            assertThat(callOnEdt(() -> field(subject, "serverModel", javax.swing.DefaultListModel.class).isEmpty()))
                    .isTrue();
        } finally {
            observationBarrier.complete(null);
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A save waits for the manager barrier without recommitting editors")
    void savePendingChangesAsync_whenExternalPublicationIsUnsettled_usesInitialEditorSettlement() throws Exception {
        McpServerConfiguration base = server("Barrier", "barrier", McpTransportType.STDIO);
        McpServerConfiguration configured = new McpServerConfiguration(
                base.id(),
                base.name(),
                base.modelId(),
                base.enabled(),
                base.automatic(),
                base.transport(),
                base.endpoint(),
                base.executable(),
                List.of("original"),
                base.headers(),
                base.environment(),
                base.longRunning(),
                base.disabledTools()
        );
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        when(manager.publicationsSettled()).thenReturn(barrier);
        AtomicReference<McpConfiguration> submittedConfiguration = new AtomicReference<>();
        doAnswer(invocation -> {
            McpConfigurationDraft draft = invocation.getArgument(0);
            submittedConfiguration.set(draft.configuration());
            draft.clearSecrets();
            generation.set(1);
            return CompletableFuture.completedFuture(new McpApplyResult(
                    McpApplyOutcome.APPLIED,
                    1,
                    submittedConfiguration.get(),
                    ""
            ));
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            CompletableFuture<Boolean> result = callOnEdt(subject::savePendingChangesAsync);
            runOnEdt(() -> {
                JTable arguments = component(subject, "Ordered MCP arguments", JTable.class);
                assertThat(arguments.editCellAt(0, 0)).isTrue();
                ((JTextField) arguments.getEditorComponent()).setText("late edit");
            });

            assertThat(result).isNotDone();
            verify(manager, times(0)).saveAndApply(any(McpConfigurationDraft.class));
            barrier.complete(null);
            assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(submittedConfiguration.get().servers().getFirst().arguments()).containsExactly("original");
            assertThat(callOnEdt(() -> component(subject, "Ordered MCP arguments", JTable.class).getValueAt(0, 0)))
                    .isEqualTo("original");
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Standalone Repair rechecks manager state after confirmation")
    void repairInvalidConfiguration_whenManagerIsRepairedDuringConfirmation_doesNotPublish() throws Exception {
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
                    JOptionPane.OK_OPTION,
                    () -> {
                        generation.set(1);
                        loadResult.set(new McpConfigurationLoadResult.Valid(McpConfiguration.empty()));
                    }
            ));
            flushEdt();

            assertThat(callOnEdt(() -> button(
                    subject,
                    "Replace / Recreate invalid configuration"
            ).isVisible())).isFalse();
            assertThat(callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText()))
                    .contains("already repaired");
            verify(manager, times(0)).replaceInvalidAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).saveAndApply(any(McpConfigurationDraft.class));
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Verify uses normal publication when invalid configuration is repaired during confirmation")
    void verifySelected_whenManagerIsRepairedDuringConfirmation_usesNormalPublication() throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        CompletableFuture<McpApplyResult> publication = new CompletableFuture<>();
        CompletableFuture<McpApplyResult> obsoleteRepair = new CompletableFuture<>();
        CountDownLatch submitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            submitted.countDown();
            return publication;
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            return obsoleteRepair;
        }).when(manager).replaceInvalidAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> {
                menuItem(subject, "Command-line (stdio)").doClick();
                component(subject, "MCP executable", JTextField.class).setText("java");
                clickWithConfirmation(
                        subject,
                        "Verify / Refresh",
                        JOptionPane.OK_OPTION,
                        () -> {
                            generation.set(1);
                            loadResult.set(new McpConfigurationLoadResult.Valid(McpConfiguration.empty()));
                        }
                );
            });

            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
            verify(manager, times(1)).saveAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).replaceInvalidAndApply(any(McpConfigurationDraft.class));
        } finally {
            publication.completeExceptionally(new IllegalStateException("test cleanup"));
            obsoleteRepair.completeExceptionally(new IllegalStateException("test cleanup"));
            flushEdt();
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Close after Verify repair confirmation publishes the confirmed replacement")
    void verifySelected_whenCloseStartsBeforeRepairRecheck_carriesConfirmationIntoSave() throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        CompletableFuture<Void> verifyObservation = new CompletableFuture<>();
        AtomicInteger observationCalls = new AtomicInteger();
        when(manager.publicationsSettled()).thenAnswer(invocation -> switch (observationCalls.incrementAndGet()) {
            case 1 -> CompletableFuture.completedFuture(null);
            case 2 -> verifyObservation;
            default -> CompletableFuture.completedFuture(null);
        });
        CompletableFuture<McpApplyResult> publication = new CompletableFuture<>();
        AtomicReference<McpConfiguration> submittedConfiguration = new AtomicReference<>();
        CountDownLatch submitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            McpConfigurationDraft draft = invocation.getArgument(0);
            submittedConfiguration.set(draft.configuration());
            draft.clearSecrets();
            submitted.countDown();
            return publication;
        }).when(manager).replaceInvalidAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> {
                menuItem(subject, "Command-line (stdio)").doClick();
                component(subject, "MCP executable", JTextField.class).setText("java");
                clickWithConfirmation(subject, "Verify / Refresh", JOptionPane.OK_OPTION);
            });
            assertThat(observationCalls).hasValue(2);

            CompletableFuture<Boolean> closeSave = callOnEdt(subject::savePendingChangesAsync);
            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(closeSave).isNotDone();

            McpConfiguration appliedConfiguration = submittedConfiguration.get();
            loadResult.set(new McpConfigurationLoadResult.Valid(appliedConfiguration));
            generation.set(1);
            publication.complete(new McpApplyResult(
                    McpApplyOutcome.APPLIED,
                    1,
                    appliedConfiguration,
                    ""
            ));
            assertThat(closeSave.get(5, TimeUnit.SECONDS)).isTrue();

            verifyObservation.complete(null);
            flushEdt();
            verify(manager, times(1)).replaceInvalidAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).saveAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).verifyAppliedAsync(any(McpApplyResult.class), any(), any());
        } finally {
            verifyObservation.complete(null);
            if (!publication.isDone()) {
                publication.completeExceptionally(new IllegalStateException("test cleanup"));
            }
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Verify rechecks repair state after cleartext confirmation")
    void verifySelected_whenManagerIsRepairedDuringCleartextConfirmation_usesNormalPublication() throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        CompletableFuture<McpApplyResult> publication = new CompletableFuture<>();
        CompletableFuture<McpApplyResult> obsoleteRepair = new CompletableFuture<>();
        CountDownLatch submitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            submitted.countDown();
            return publication;
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            return obsoleteRepair;
        }).when(manager).replaceInvalidAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> {
                menuItem(subject, "HTTP Server (http)").doClick();
                component(subject, "MCP HTTP endpoint", JTextField.class).setText("http://example.test/mcp");
                clickWithConfirmations(
                        subject,
                        "Verify / Refresh",
                        JOptionPane.OK_OPTION,
                        2,
                        confirmation -> {
                            if (confirmation == 2) {
                                generation.set(1);
                                loadResult.set(new McpConfigurationLoadResult.Valid(McpConfiguration.empty()));
                            }
                        }
                );
            });

            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
            verify(manager, times(1)).saveAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).replaceInvalidAndApply(any(McpConfigurationDraft.class));
        } finally {
            publication.completeExceptionally(new IllegalStateException("test cleanup"));
            obsoleteRepair.completeExceptionally(new IllegalStateException("test cleanup"));
            flushEdt();
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Cancelling Verify confirmation retires its cancellation state")
    void verifySelected_whenRepairConfirmationIsCancelled_doesNotCancelLaterEdits() throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> {
                menuItem(subject, "Command-line (stdio)").doClick();
                component(subject, "MCP executable", JTextField.class).setText("java");
                clickWithConfirmation(subject, "Verify / Refresh", JOptionPane.CANCEL_OPTION);
            });
            flushEdt();

            String status = callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText());
            assertThat(status).doesNotContain("Verification cancelled");
            runOnEdt(() -> component(subject, "MCP server name", JTextField.class).setText("Edited later"));
            assertThat(callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText()))
                    .isEqualTo(status);
            verify(manager, times(0)).replaceInvalidAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).saveAndApply(any(McpConfigurationDraft.class));
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
    @DisplayName("Repeated Repair activation is ignored while preflight observation is pending")
    void repairInvalidConfiguration_whenPreflightIsPending_rejectsRepeatedActivation() throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        CompletableFuture<Void> observation = new CompletableFuture<>();
        when(manager.publicationsSettled()).thenReturn(observation);
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            long admittedRequest = callOnEdt(() -> {
                menuItem(subject, "Command-line (stdio)").doClick();
                assertThat(button(subject, "Verify / Refresh").isEnabled()).isTrue();
                button(subject, "Replace / Recreate invalid configuration").doClick();
                return field(subject, "requestIdentity", Long.class);
            });

            runOnEdt(() -> {
                assertThat(button(subject, "Replace / Recreate invalid configuration").isEnabled()).isFalse();
                assertThat(button(subject, "Verify / Refresh").isEnabled()).isFalse();
                invoke(subject, "repairInvalidConfiguration");
                assertThat(field(subject, "requestIdentity", Long.class)).isEqualTo(admittedRequest);
            });
            verify(manager, times(1)).publicationsSettled();
        } finally {
            runOnEdt(subject::disposePanel);
            observation.complete(null);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Close after Repair confirmation publishes the confirmed replacement")
    void repairInvalidConfiguration_whenCloseStartsBeforeRepairSubmission_carriesConfirmationIntoSave()
            throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        CompletableFuture<Void> repairObservation = new CompletableFuture<>();
        AtomicInteger observationCalls = new AtomicInteger();
        when(manager.publicationsSettled()).thenAnswer(invocation -> switch (observationCalls.incrementAndGet()) {
            case 1 -> CompletableFuture.completedFuture(null);
            case 2 -> repairObservation;
            default -> CompletableFuture.completedFuture(null);
        });
        CompletableFuture<McpApplyResult> publication = new CompletableFuture<>();
        CountDownLatch submitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            submitted.countDown();
            return publication;
        }).when(manager).replaceInvalidAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> clickWithConfirmation(
                    subject,
                    "Replace / Recreate invalid configuration",
                    JOptionPane.OK_OPTION
            ));
            assertThat(observationCalls).hasValue(2);

            CompletableFuture<Boolean> closeSave = callOnEdt(subject::savePendingChangesAsync);
            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(closeSave).isNotDone();

            loadResult.set(new McpConfigurationLoadResult.Valid(McpConfiguration.empty()));
            generation.set(1);
            publication.complete(new McpApplyResult(
                    McpApplyOutcome.APPLIED,
                    1,
                    McpConfiguration.empty(),
                    ""
            ));
            assertThat(closeSave.get(5, TimeUnit.SECONDS)).isTrue();

            repairObservation.complete(null);
            flushEdt();
            verify(manager, times(1)).replaceInvalidAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).saveAndApply(any(McpConfigurationDraft.class));
        } finally {
            repairObservation.complete(null);
            if (!publication.isDone()) {
                publication.completeExceptionally(new IllegalStateException("test cleanup"));
            }
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
    @DisplayName("A mutation during Verify preflight reports cancellation and prevents publication")
    void verifySelected_whenStableObservationIsPendingAndDraftMutates_reportsCancellation() throws Exception {
        McpServerConfiguration configured = server("Verify preflight", "verify_preflight", McpTransportType.STDIO);
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        when(manager.publicationsSettled()).thenReturn(barrier);
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> {
                button(subject, "Verify / Refresh").doClick();
                component(subject, "MCP server name", JTextField.class).setText("Changed during preflight");
            });

            assertThat(callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText()))
                    .isEqualTo("Verification cancelled because settings or selection changed.");
            assertThat(callOnEdt(() -> button(subject, "Verify / Refresh").isEnabled())).isTrue();
            barrier.complete(null);
            flushEdt();
            verify(manager, times(0)).saveAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).verifyAppliedAsync(any(), any(), any());
        } finally {
            barrier.complete(null);
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A mutation during Verify repair confirmation prevents publication")
    void verifySelected_whenDraftMutatesDuringRepairConfirmation_reportsCancellation() throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> {
                menuItem(subject, "Command-line (stdio)").doClick();
                component(subject, "MCP executable", JTextField.class).setText("java");
                clickWithConfirmation(
                        subject,
                        "Verify / Refresh",
                        JOptionPane.OK_OPTION,
                        () -> component(subject, "MCP server name", JTextField.class)
                                .setText("Changed during confirmation")
                );
            });
            flushEdt();

            assertThat(callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText()))
                    .isEqualTo("Verification cancelled because settings or selection changed.");
            assertThat(callOnEdt(() -> button(subject, "Verify / Refresh").isEnabled())).isTrue();
            verify(manager, times(0)).replaceInvalidAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).saveAndApply(any(McpConfigurationDraft.class));
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A mutation during Verify cleartext confirmation prevents publication")
    void verifySelected_whenDraftMutatesDuringCleartextConfirmation_reportsCancellation() throws Exception {
        McpServerConfiguration base = server("Cleartext", "cleartext", McpTransportType.STREAMABLE_HTTP);
        McpServerConfiguration configured = new McpServerConfiguration(
                base.id(),
                base.name(),
                base.modelId(),
                base.enabled(),
                base.automatic(),
                base.transport(),
                "http://example.test/mcp",
                base.executable(),
                base.arguments(),
                base.headers(),
                base.environment(),
                base.longRunning(),
                base.disabledTools()
        );
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> clickWithConfirmation(
                    subject,
                    "Verify / Refresh",
                    JOptionPane.OK_OPTION,
                    () -> component(subject, "MCP server name", JTextField.class)
                            .setText("Changed during confirmation")
            ));
            flushEdt();

            assertThat(callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText()))
                    .isEqualTo("Verification cancelled because settings or selection changed.");
            assertThat(callOnEdt(() -> button(subject, "Verify / Refresh").isEnabled())).isTrue();
            verify(manager, times(0)).saveAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).verifyAppliedAsync(any(), any(), any());
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A mutation after repair approval requires replacement confirmation again")
    void verifySelected_whenDraftMutatesDuringCleartextConfirmation_clearsRepairApproval() throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> {
                menuItem(subject, "HTTP Server (http)").doClick();
                component(subject, "MCP HTTP endpoint", JTextField.class).setText("http://example.test/mcp");
                clickWithConfirmations(
                        subject,
                        "Verify / Refresh",
                        JOptionPane.OK_OPTION,
                        2,
                        confirmation -> {
                            if (confirmation == 2) {
                                component(subject, "MCP server name", JTextField.class)
                                        .setText("Changed after approval");
                            }
                        }
                );
            });
            flushEdt();

            assertThat(callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText()))
                    .isEqualTo("Verification cancelled because settings or selection changed.");
            CompletableFuture<Boolean> save = callOnEdt(subject::savePendingChangesAsync);
            assertThat(save.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(callOnEdt(subject::lastSaveError))
                    .isEqualTo("Confirm replacement of the invalid MCP configuration first.");
            verify(manager, times(0)).replaceInvalidAndApply(any(McpConfigurationDraft.class));
            verify(manager, times(0)).saveAndApply(any(McpConfigurationDraft.class));
        } finally {
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A completed Verify retires its cancellation state")
    void verifySelected_whenVerificationCompletes_doesNotCancelLaterEdits() throws Exception {
        McpServerConfiguration configured = server("Verify complete", "verify_complete", McpTransportType.STDIO);
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(configured)), generation);
        McpApplyResult applyResult = applied(1, configured);
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            generation.set(1);
            return CompletableFuture.completedFuture(applyResult);
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        CompletableFuture<McpVerificationResult> discovery = new CompletableFuture<>() {
            @Override
            public Executor defaultExecutor() {
                return Runnable::run;
            }
        };
        CountDownLatch discoveryStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            discoveryStarted.countDown();
            return discovery;
        }).when(manager).verifyAppliedAsync(any(), any(), any());
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> button(subject, "Verify / Refresh").doClick());
            assertThat(discoveryStarted.await(5, TimeUnit.SECONDS)).isTrue();
            discovery.complete(McpVerificationResult.successful(
                    applyResult,
                    configured.id(),
                    emptyList()
            ));
            flushEdt();

            String status = callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText());
            assertThat(status).isEqualTo("Verified 0 tool(s).");
            runOnEdt(() -> component(subject, "MCP server name", JTextField.class).setText("Edited later"));
            assertThat(callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText()))
                    .isEqualTo(status);
        } finally {
            discovery.cancel(true);
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
        CompletableFuture<McpVerificationResult> discovery = new CompletableFuture<>() {
            @Override
            public Executor defaultExecutor() {
                return Runnable::run;
            }
        };
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
    @DisplayName("Stale verification publication outcomes preserve newer import feedback")
    void importJson_whenOlderVerificationPublicationSettles_preservesImportStatus() throws Exception {
        for (StalePublicationCase publicationCase : List.of(
                new StalePublicationCase("applied", true),
                new StalePublicationCase("rejected", false)
        )) {
            McpServerConfiguration configured = server(
                    "Existing",
                    "existing_%s".formatted(publicationCase.name()),
                    McpTransportType.STDIO
            );
            McpConfiguration configuration = new McpConfiguration(1, List.of(configured));
            AtomicLong generation = new AtomicLong();
            McpManager manager = controlledManager(configuration, generation);
            CompletableFuture<McpApplyResult> publication = new CompletableFuture<>();
            CountDownLatch submitted = new CountDownLatch(1);
            doAnswer(invocation -> {
                invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
                submitted.countDown();
                return publication;
            }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
            McpPanel subject = callOnEdt(() -> new McpPanel(manager));
            try {
                runOnEdt(() -> button(subject, "Verify / Refresh").doClick());
                assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();

                ImportHandles handles = startImport(
                        subject,
                        new Clipboard("stale-publication-%s".formatted(publicationCase.name())),
                        "{\"name\":\"Imported %s\",\"command\":\"java\",\"enabled\":true}"
                                .formatted(publicationCase.name())
                );
                awaitImport(handles);
                String importStatus = callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText());

                if (publicationCase.applied()) {
                    generation.set(1);
                    publication.complete(new McpApplyResult(
                            McpApplyOutcome.APPLIED,
                            1,
                            configuration,
                            ""
                    ));
                } else {
                    publication.complete(new McpApplyResult(
                            McpApplyOutcome.REJECTED_OLD_STATE_INTACT,
                            0,
                            configuration,
                            "controlled publication failure"
                    ));
                }
                flushEdt();

                String finalStatus = callOnEdt(() -> component(subject, "MCP status", JTextArea.class).getText());
                assertThat(finalStatus.equals(importStatus))
                        .as("stale %s publication should preserve import feedback", publicationCase.name())
                        .isTrue();
                assertThat(callOnEdt(() -> component(subject, "MCP servers", JList.class).getModel().getSize()))
                        .isEqualTo(2);
            } finally {
                if (!publication.isDone()) {
                    publication.completeExceptionally(new IllegalStateException("test cleanup"));
                }
                runOnEdt(subject::disposePanel);
                flushEdt();
            }
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
        CompletableFuture<McpVerificationResult> discovery = new CompletableFuture<>() {
            @Override
            public Executor defaultExecutor() {
                return Runnable::run;
            }
        };
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
                secondSubmittedSecret.set(copyOf(draft.replacementSecrets().get(rowId),
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
            assertThat(second).isNotDone();
            assertSecretEquals(
                    secondSubmittedSecret.get(),
                    "second-secret",
                    "second logical Save should submit its replacement"
            );

            generation.set(2);
            secondPublication.complete(applied(2, withHeaderSecret(configured,
                    "MCP_33333333333333333333333333333333")));
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertThat(callOnEdt(() -> replacementSecretsEmpty(subject)))
                    .as("serialized Saves should consume pending replacements")
                    .isTrue();
            assertThat(callOnEdt(subject::lastSaveError)).isEmpty();
            verify(manager, times(2)).saveAndApply(any(McpConfigurationDraft.class));
        } finally {
            if (!firstPublication.isDone()) {
                firstPublication.completeExceptionally(new IllegalStateException("test cleanup"));
            }
            if (!secondPublication.isDone()) {
                secondPublication.completeExceptionally(new IllegalStateException("test cleanup"));
            }
            if (secondSubmittedSecret.get() != null) {
                fill(secondSubmittedSecret.get(), '\0');
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
            runOnEdt(() -> {
                fixture.subject().disposePanel();
                menuItem(fixture.subject(), "HTTP Server (http)").doClick();
            });

            assertThat(callOnEdt(() -> component(fixture.subject(), "Ordered MCP arguments", JTable.class).isEditing()))
                    .isFalse();
            assertThat(callOnEdt(() -> replacementSecretsEmpty(fixture.subject())))
                    .as("disposal should clear pending replacements")
                    .isTrue();
            assertThat(callOnEdt(() -> component(fixture.subject(), "MCP servers", JList.class)
                    .getModel().getSize())).isZero();
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
        clickWithConfirmation(subject, accessibleName, result, () -> { });
    }

    private void clickWithConfirmation(
            McpPanel subject,
            String accessibleName,
            int result,
            Runnable duringConfirmation
    ) {
        clickWithConfirmations(subject, accessibleName, result, 1, ignored -> duringConfirmation.run());
    }

    private void clickWithConfirmations(
            McpPanel subject,
            String accessibleName,
            int result,
            int expectedConfirmations,
            IntConsumer duringConfirmation
    ) {
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        AtomicInteger confirmationCount = new AtomicInteger();
        AtomicInteger eventTurns = new AtomicInteger();
        try (MockedStatic<ModalDialogSupport> confirmation = mockStatic(ModalDialogSupport.class)) {
            confirmation.when(() -> ModalDialogSupport.showConfirmDialog(
                    any(Component.class),
                    any(),
                    anyInt(),
                    anyInt()
            )).thenAnswer(invocation -> {
                int count = confirmationCount.incrementAndGet();
                duringConfirmation.accept(count);
                return result;
            });
            button(subject, accessibleName).doClick();
            Runnable awaitConfirmations = new Runnable() {
                @Override
                public void run() {
                    boolean complete = confirmationCount.get() >= expectedConfirmations;
                    boolean exhausted = eventTurns.incrementAndGet() >= 100;
                    if (complete || exhausted) {
                        loop.exit();
                    } else {
                        SwingUtilities.invokeLater(this);
                    }
                }
            };
            SwingUtilities.invokeLater(awaitConfirmations);
            assertThat(loop.enter()).isTrue();
            assertThat(confirmationCount.get()).isEqualTo(expectedConfirmations);
        }
    }

    @Test
    @DisplayName("A valid stdio clipboard payload appends one disabled server with masked and Missing credentials")
    void importJson_whenStdioPayloadIsValid_appendsDisabledServerAndCredentials() throws Exception {
        try (var fixture = fixture("import-stdio", McpConfiguration.empty())) {
            ImportHandles handles = startImport(fixture.subject(), new Clipboard("import-stdio"), """
                    {"mcpServers":{"Context7":{"command":"npx","args":["-y","${PACKAGE}"],"env":{
                      "ACTUAL":"  secret value  ","MISSING":"${CONTEXT7_TOKEN}"
                    },"enabled":true,"autoApprove":["*"]}}}
                    """);
            awaitImport(handles);

            runOnEdt(() -> {
                JList<?> servers = component(fixture.subject(), "MCP servers", JList.class);
                assertThat(servers.getModel().getSize()).isEqualTo(1);
                McpServerConfiguration imported = (McpServerConfiguration) servers.getSelectedValue();
                assertThat(imported.name()).isEqualTo("Context7");
                assertThat(imported.enabled()).isFalse();
                assertThat(imported.automatic()).isFalse();
                assertThat(imported.longRunning()).isFalse();
                assertThat(imported.transport()).isEqualTo(McpTransportType.STDIO);
                assertThat(imported.arguments()).containsExactly("-y", "${PACKAGE}");
                assertThat(imported.environment()).hasSize(2);
                assertThat(imported.environment()).allMatch(row -> row.secretId().isBlank());
                imported.environment().forEach(row -> assertThatCode(() -> UUID.fromString(row.rowId()))
                        .doesNotThrowAnyException());
                assertThatCode(() -> UUID.fromString(imported.id())).doesNotThrowAnyException();
                assertThat(component(fixture.subject(), "MCP server editor", JTabbedPane.class).getSelectedIndex())
                        .isZero();

                JTable environment = component(fixture.subject(), "Environment variables", JTable.class);
                assertCredentialValue(environment, 0, "••••••••", "environment value should render as masked");
                assertCredentialValue(environment, 1, "", "environment value should render as blank");
                assertThat(environment.editCellAt(1, 1)).isTrue();
                JPasswordField editor = (JPasswordField) environment.getEditorComponent();
                assertThat(editor.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT))
                        .isEqualTo("Enter a credential value; this server cannot be saved until one is entered.");
                assertThat(editor.getAccessibleContext().getAccessibleDescription())
                        .isEqualTo("Enter a credential value; this server cannot be saved until one is entered.");
                environment.getCellEditor().cancelCellEditing();

                String status = component(fixture.subject(), "MCP status", JTextArea.class).getText();
                assertThat(status.contains("secret value"))
                        .as("import status should not contain credential plaintext")
                        .isFalse();
                assertThat(status.contains("Imported “Context7” as disabled."))
                        .as("import status should identify the disabled server")
                        .isTrue();
                assertThat(status.contains("1 missing credential"))
                        .as("import status should report the missing credential count")
                        .isTrue();
                assertThat(status.contains("Source approval, trust, enablement, or sandbox settings were ignored."))
                        .as("import status should report ignored source authority")
                        .isTrue();
                assertThat(status.contains("Argument placeholders were preserved literally."))
                        .as("import status should report preserved argument placeholders")
                        .isTrue();
            });
        }
    }

    @Test
    @DisplayName("Import fences the draft before revealing the appended server")
    void importJson_whenAppendedServerBecomesSelected_hasAlreadyFencedDraft() throws Exception {
        try (var fixture = fixture("import-selection-fence", McpConfiguration.empty())) {
            JList<?> servers = callOnEdt(() -> component(fixture.subject(), "MCP servers", JList.class));
            long previousRevision = callOnEdt(() -> field(fixture.subject(), "draftRevision", Long.class));
            AtomicLong revisionWhenSelected = new AtomicLong(-1);
            ListSelectionListener listener = event -> {
                if (!event.getValueIsAdjusting() && servers.getSelectedValue() != null) {
                    revisionWhenSelected.compareAndSet(
                            -1,
                            field(fixture.subject(), "draftRevision", Long.class)
                    );
                }
            };
            runOnEdt(() -> servers.addListSelectionListener(listener));
            try {
                ImportHandles handles = startImport(
                        fixture.subject(),
                        new Clipboard("import-selection-fence"),
                        "{\"command\":\"java\"}"
                );
                awaitImport(handles);

                assertThat(revisionWhenSelected.get()).isEqualTo(previousRevision + 1);
            } finally {
                runOnEdt(() -> servers.removeListSelectionListener(listener));
            }
        }
    }

    @Test
    @DisplayName("A valid HTTP clipboard payload remains disabled and stages exact header values")
    void importJson_whenHttpPayloadIsValid_stagesExactHeaderValue() throws Exception {
        try (var fixture = fixture("import-http", McpConfiguration.empty())) {
            ImportHandles handles = startImport(fixture.subject(), new Clipboard("import-http"), """
                    {"servers":{"Docs":{"type":"http","url":"https://example.test/mcp","headers":{
                      "Authorization":"Bearer exact-secret"
                    }}}}
                    """);
            awaitImport(handles);

            runOnEdt(() -> {
                JList<?> servers = component(fixture.subject(), "MCP servers", JList.class);
                McpServerConfiguration imported = (McpServerConfiguration) servers.getSelectedValue();
                assertThat(imported.transport()).isEqualTo(McpTransportType.STREAMABLE_HTTP);
                assertThat(imported.endpoint()).isEqualTo("https://example.test/mcp");
                assertThat(imported.enabled()).isFalse();
                assertCredentialValue(
                        component(fixture.subject(), "HTTP headers", JTable.class),
                        0,
                        "••••••••",
                        "imported HTTP header should render as masked"
                );
                Map<String, char[]> replacements = field(fixture.subject(), "replacementSecrets", Map.class);
                assertThat(replacements.size()).as("one imported header should be staged").isEqualTo(1);
                assertSecretEquals(
                        replacements.values().iterator().next(),
                        "Bearer exact-secret",
                        "staged header should preserve exact value"
                );
            });
        }
    }

    @Test
    @DisplayName("Long imported names remain complete in the editor and bounded in status")
    void importJson_whenNameIsLongAndMultibyte_boundsOnlyStatusPresentation() throws Exception {
        String fullName = "名".repeat(100);
        String presentedName = "名".repeat(80);
        try (var fixture = fixture("import-bounded-name", McpConfiguration.empty())) {
            ImportHandles handles = startImport(
                    fixture.subject(),
                    new Clipboard("import-bounded-name"),
                    "{\"name\":\"%s\",\"command\":\"java\"}".formatted(fullName)
            );
            awaitImport(handles);

            runOnEdt(() -> {
                assertThat(component(fixture.subject(), "MCP server name", JTextField.class).getText())
                        .isEqualTo(fullName);
                String status = component(fixture.subject(), "MCP status", JTextArea.class).getText();
                assertThat(status.contains("Imported “%s” as disabled.".formatted(presentedName)))
                        .as("status should contain only the bounded imported name")
                        .isTrue();
                assertThat(status.contains(fullName)).as("status should not contain the full long name").isFalse();
            });
        }
    }

    @Test
    @DisplayName("Imported model IDs avoid live collisions and stabilize on coordinated Save")
    void savePendingChangesAsync_whenImportedNameCollides_generatesAndStabilizesUniqueModelId() throws Exception {
        McpServerConfiguration existing = server("Docs", "docs", McpTransportType.STDIO);
        try (var fixture = fixture("import-model-collision", new McpConfiguration(1, List.of(existing)))) {
            ImportHandles handles = startImport(
                    fixture.subject(),
                    new Clipboard("import-model-collision"),
                    "{\"name\":\"Docs\",\"command\":\"java\"}"
            );
            awaitImport(handles);

            McpServerConfiguration imported = callOnEdt(() -> (McpServerConfiguration) component(
                    fixture.subject(),
                    "MCP servers",
                    JList.class
            ).getSelectedValue());
            assertThat(imported.modelId()).isEqualTo("docs_2");

            assertThat(callOnEdt(fixture.subject()::savePendingChangesAsync).get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            McpServerConfiguration renamed = callOnEdt(() -> {
                component(fixture.subject(), "MCP server name", JTextField.class).setText("Renamed after Save");
                fixture.subject().finishActiveEditing();
                return (McpServerConfiguration) component(
                        fixture.subject(),
                        "MCP servers",
                        JList.class
                ).getSelectedValue();
            });
            assertThat(renamed.name()).isEqualTo("Renamed after Save");
            assertThat(renamed.modelId()).isEqualTo("docs_2");
        }
    }

    @Test
    @DisplayName("Coordinated Save encrypts an imported credential without persisting plaintext")
    void savePendingChangesAsync_whenImportedCredentialIsActual_encryptsAndWipesPendingValue() throws Exception {
        String sentinel = "imported-secret-sentinel";
        try (var fixture = fixture("import-encrypted", McpConfiguration.empty())) {
            ImportHandles handles = startImport(
                    fixture.subject(),
                    new Clipboard("import-encrypted"),
                    "{\"command\":\"java\",\"env\":{\"TOKEN\":\"%s\"}}".formatted(sentinel)
            );
            awaitImport(handles);
            char[] submitted = callOnEdt(() -> {
                Map<String, char[]> replacements = field(fixture.subject(), "replacementSecrets", Map.class);
                return replacements.values().iterator().next();
            });

            try {
                assertThat(callOnEdt(fixture.subject()::savePendingChangesAsync).get(5, TimeUnit.SECONDS)).isTrue();
                flushEdt();
                assertAllNul(submitted, "submitted imported environment credential should be wiped");

                String persisted = Files.readString(fixture.storagePaths().mcpFile(), StandardCharsets.UTF_8);
                assertThat(persisted.contains(sentinel))
                        .as("persisted MCP JSON should not contain plaintext")
                        .isFalse();
                McpSecretReference savedRow = callOnEdt(() -> {
                    JList<?> servers = component(fixture.subject(), "MCP servers", JList.class);
                    return ((McpServerConfiguration) servers.getSelectedValue()).environment().getFirst();
                });
                assertThat(savedRow.secretId()).startsWith("MCP_");
                assertStoredSecret(fixture.storagePaths(), savedRow.secretId(), sentinel);
                assertThat(callOnEdt(() -> replacementSecretsEmpty(fixture.subject())))
                        .as("saved imported replacements should be empty")
                        .isTrue();
            } finally {
                fill(submitted, '\0');
            }
        }
    }

    @Test
    @DisplayName("Coordinated Save encrypts an imported HTTP header and wipes its submitted value")
    void savePendingChangesAsync_whenImportedHttpHeaderIsActual_encryptsAndWipesPendingValue() throws Exception {
        String sentinel = "imported-http-secret-sentinel";
        try (var fixture = fixture("import-http-encrypted", McpConfiguration.empty())) {
            ImportHandles handles = startImport(
                    fixture.subject(),
                    new Clipboard("import-http-encrypted"),
                    "{\"url\":\"https://example.test/mcp\",\"headers\":{\"Authorization\":\"%s\"}}"
                            .formatted(sentinel)
            );
            awaitImport(handles);
            char[] submitted = callOnEdt(() -> {
                Map<String, char[]> replacements = field(fixture.subject(), "replacementSecrets", Map.class);
                return replacements.values().iterator().next();
            });

            try {
                assertThat(callOnEdt(fixture.subject()::savePendingChangesAsync).get(5, TimeUnit.SECONDS)).isTrue();
                flushEdt();
                assertAllNul(submitted, "submitted imported HTTP credential should be wiped");

                String persisted = Files.readString(fixture.storagePaths().mcpFile(), StandardCharsets.UTF_8);
                assertThat(persisted.contains(sentinel))
                        .as("persisted MCP JSON should not contain the imported HTTP credential")
                        .isFalse();
                McpSecretReference savedRow = callOnEdt(() -> {
                    JList<?> servers = component(fixture.subject(), "MCP servers", JList.class);
                    return ((McpServerConfiguration) servers.getSelectedValue()).headers().getFirst();
                });
                assertThat(savedRow.secretId()).startsWith("MCP_");
                assertStoredSecret(fixture.storagePaths(), savedRow.secretId(), sentinel);
                assertThat(callOnEdt(() -> replacementSecretsEmpty(fixture.subject())))
                        .as("saved imported HTTP replacements should be empty")
                        .isTrue();
            } finally {
                fill(submitted, '\0');
            }
        }
    }

    @Test
    @DisplayName("A Missing imported credential rejects Save until the user enters a value")
    void savePendingChangesAsync_whenImportedCredentialIsMissing_rejectsUntilEntered() throws Exception {
        try (var fixture = fixture("import-missing", McpConfiguration.empty())) {
            ImportHandles handles = startImport(
                    fixture.subject(),
                    new Clipboard("import-missing"),
                    "{\"command\":\"java\",\"env\":{\"TOKEN\":\"${TOKEN}\"}}"
            );
            awaitImport(handles);

            CompletableFuture<Boolean> rejected = callOnEdt(fixture.subject()::savePendingChangesAsync);
            assertThat(rejected.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(callOnEdt(fixture.subject()::lastSaveError))
                    .isEqualTo("A new MCP credential value is required.");

            runOnEdt(() -> {
                JTable environment = component(fixture.subject(), "Environment variables", JTable.class);
                assertThat(environment.editCellAt(0, 1)).isTrue();
                ((JPasswordField) environment.getEditorComponent()).setText("entered-secret");
                assertThat(environment.getCellEditor().stopCellEditing()).isTrue();
            });
            assertThat(callOnEdt(fixture.subject()::savePendingChangesAsync).get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("The popup listener resolves one concrete system clipboard before worker execution")
    void importJson_whenMenuItemIsInvoked_handsConcreteClipboardToWorker() throws Exception {
        Clipboard clipboard = new Clipboard("import-menu-listener");
        clipboard.setContents(new StringSelection(
                "{\"name\":\"Clipboard handoff sentinel\",\"command\":\"java\"}"
        ), null);
        Toolkit toolkit = spy(Toolkit.getDefaultToolkit());
        Thread eventDispatchThread = callOnEdt(Thread::currentThread);
        doAnswer(invocation -> {
            if (Thread.currentThread() != eventDispatchThread) {
                throw new AssertionError("System clipboard access must run on the EDT.");
            }
            return clipboard;
        }).when(toolkit).getSystemClipboard();
        try (var fixture = fixture("import-menu-listener", McpConfiguration.empty())) {
            ImportHandles handles = callOnEdt(() -> {
                try (MockedStatic<Toolkit> toolkitLookup = mockStatic(Toolkit.class)) {
                    toolkitLookup.when(Toolkit::getDefaultToolkit).thenAnswer(invocation -> {
                        if (Thread.currentThread() != eventDispatchThread) {
                            throw new AssertionError("System toolkit lookup must run on the EDT.");
                        }
                        return toolkit;
                    });
                    menuItem(fixture.subject(), "Import JSON from Clipboard").doClick();
                    return importHandles(fixture.subject());
                }
            });
            awaitImport(handles);

            McpServerConfiguration imported = callOnEdt(() -> (McpServerConfiguration) component(
                    fixture.subject(),
                    "MCP servers",
                    JList.class
            ).getSelectedValue());
            assertThat(imported.name()).isEqualTo("Clipboard handoff sentinel");
            assertThat(imported.executable()).isEqualTo("java");
        }
    }

    @Test
    @DisplayName("Singleton validation maps source-bearing validator failures to a fixed safe diagnostic")
    void importJson_whenImportedServerFailsDomainValidation_reportsFixedSafeCategory() throws Exception {
        try (var fixture = fixture("import-domain-invalid", McpConfiguration.empty())) {
            ImportHandles handles = startImport(
                    fixture.subject(),
                    new Clipboard("import-domain-invalid"),
                    "{\"name\":\"sentinel-name\",\"url\":\"not-a-url-sentinel\"}"
            );
            awaitImport(handles);

            runOnEdt(() -> {
                assertThat(component(fixture.subject(), "MCP servers", JList.class).getModel().getSize()).isZero();
                String status = component(fixture.subject(), "MCP status", JTextArea.class).getText();
                assertThat(status.contains("sentinel")).as("validator diagnostic should not contain source text")
                        .isFalse();
                assertThat(status.equals("Imported HTTP endpoint is invalid. Path: $.server.endpoint"))
                        .as("invalid endpoint should use the fixed safe diagnostic")
                        .isTrue();
            });
        }
    }

    @Test
    @DisplayName("Singleton validation translates executable, header, and environment categories safely")
    void importJson_whenSingletonDomainValidationFails_translatesEveryCredentialCategory() throws Exception {
        List<ImportValidationCase> cases = List.of(
                new ImportValidationCase(
                        "executable",
                        "{\"command\":\"./relative-script\"}",
                        "Imported executable is invalid. Path: $.server.executable"
                ),
                new ImportValidationCase(
                        "reserved-header",
                        "{\"url\":\"https://example.test/mcp\",\"headers\":{\"Host\":\"value\"}}",
                        "Imported HTTP headers are invalid. Path: $.server.headers"
                ),
                new ImportValidationCase(
                        "duplicate-header",
                        "{\"url\":\"https://example.test/mcp\",\"headers\":{\"X-Key\":\"one\",\"x-key\":\"two\"}}",
                        "Imported HTTP headers are invalid. Path: $.server.headers"
                ),
                new ImportValidationCase(
                        "invalid-environment",
                        "{\"command\":\"java\",\"env\":{\"BAD-NAME\":\"value\"}}",
                        "Imported environment variables are invalid. Path: $.server.environment"
                )
        );
        for (ImportValidationCase validationCase : cases) {
            try (var fixture = fixture("import-domain-%s".formatted(validationCase.name()), McpConfiguration.empty())) {
                ImportHandles handles = startImport(
                        fixture.subject(),
                        new Clipboard(validationCase.name()),
                        validationCase.payload()
                );
                awaitImport(handles);

                runOnEdt(() -> {
                    assertThat(component(fixture.subject(), "MCP servers", JList.class).getModel().getSize()).isZero();
                    String status = component(fixture.subject(), "MCP status", JTextArea.class).getText();
                    assertThat(status.equals(validationCase.expectedDiagnostic()))
                            .as("domain validation should use the fixed safe diagnostic")
                            .isTrue();
                    assertSecretMapEmpty(
                            field(fixture.subject(), "replacementSecrets", Map.class),
                            "invalid import should not stage replacements"
                    );
                });
            }
        }
    }

    @Test
    @DisplayName("A listener failure during append rolls back every imported value")
    void importJson_whenModelListenerThrowsAfterAppend_rollsBackImportedState() throws Exception {
        var result = new McpJsonImporter().parse(
                "{\"name\":\"Rollback\",\"command\":\"java\",\"env\":{\"TOKEN\":\"rollback-secret\"}}"
        );
        List<McpJsonImporter.ImportedCredential> ownedCredentials = field(result, "credentials", List.class);
        char[] temporarySecret = ownedCredentials.getFirst().value();
        try (result; var fixture = fixture("import-listener-rollback", McpConfiguration.empty())) {
            DefaultListModel<McpServerConfiguration> model = field(
                    fixture.subject(),
                    "serverModel",
                    DefaultListModel.class
            );
            AtomicInteger additions = new AtomicInteger();
            ListDataListener listener = new ListDataListener() {
                @Override
                public void intervalAdded(ListDataEvent event) {
                    if (additions.getAndIncrement() == 0) {
                        throw new IllegalStateException("controlled append listener failure");
                    }
                }

                @Override
                public void intervalRemoved(ListDataEvent event) {
                    throw new IllegalStateException("controlled rollback listener failure");
                }

                @Override
                public void contentsChanged(ListDataEvent event) {
                }
            };
            runOnEdt(() -> model.addListDataListener(listener));
            try {
                Object action = constructNestedType(McpPanel.class, "ImportAction");
                callOnEdt(() -> invoke(
                        fixture.subject(),
                        "applyImportedServer",
                        action.getClass(),
                        action,
                        McpJsonImporter.ImportResult.class,
                        result,
                        Object.class
                ));
            } finally {
                runOnEdt(() -> model.removeListDataListener(listener));
            }

            assertAllNul(temporarySecret, "failed append should wipe the transferred credential");
            runOnEdt(() -> {
                assertThat(model.isEmpty()).isTrue();
                assertSecretMapEmpty(
                        field(fixture.subject(), "replacementSecrets", Map.class),
                        "failed append should not retain replacements"
                );
                assertThat(field(fixture.subject(), "disabledTools", Map.class).isEmpty()).isTrue();
                assertThat(field(fixture.subject(), "unstableModelIdServerIds", Set.class).isEmpty()).isTrue();
                String status = component(fixture.subject(), "MCP status", JTextArea.class).getText();
                assertThat(status.equals("Could not add the imported MCP server. Path: $.server"))
                        .as("failed append should report a fixed safe diagnostic")
                        .isTrue();
            });
        } finally {
            fill(temporarySecret, '\0');
        }
    }

    @Test
    @DisplayName("A late append presentation failure preserves invalid-replacement draft state")
    void importJson_whenPresentationFailsBeforeMutation_preservesDraftState() throws Exception {
        AtomicLong generation = new AtomicLong();
        AtomicReference<McpConfigurationLoadResult> loadResult = new AtomicReference<>(
                new McpConfigurationLoadResult.Invalid("Invalid test configuration")
        );
        McpManager manager = invalidControlledManager(generation, loadResult);
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        var result = new McpJsonImporter().parse("{\"command\":\"java\"}");
        AtomicBoolean listenerFailed = new AtomicBoolean();
        AtomicReference<String> previousServerId = new AtomicReference<>();
        DocumentListener failingListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                failOnce();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                failOnce();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                failOnce();
            }

            private void failOnce() {
                if (listenerFailed.compareAndSet(false, true)) {
                    throw new IllegalStateException("controlled status listener failure");
                }
            }
        };
        try (result) {
            long previousRevision = callOnEdt(() -> {
                menuItem(subject, "Command-line (stdio)").doClick();
                component(subject, "MCP executable", JTextField.class).setText("java");
                JList<?> servers = component(subject, "MCP servers", JList.class);
                McpServerConfiguration existing = (McpServerConfiguration) servers.getSelectedValue();
                previousServerId.set(existing.id());
                Map<String, List<McpDiscoveredTool>> tools = field(subject, "lastTools", Map.class);
                tools.put(existing.id(), List.of(new McpDiscoveredTool(
                        "echo",
                        "Echo",
                        "Existing tool",
                        Map.of("type", "object"),
                        null
                )));
                invoke(subject, "refreshToolPresentation");
                component(subject, "MCP server editor", JTabbedPane.class).setSelectedIndex(1);
                component(subject, "Discovered MCP tools", JTable.class).setRowSelectionInterval(0, 0);
                Field confirmed = McpPanel.class.getDeclaredField("invalidReplacementConfirmed");
                confirmed.setAccessible(true);
                confirmed.setBoolean(subject, true);
                component(subject, "MCP status", JTextArea.class).getDocument()
                        .addDocumentListener(failingListener);
                return field(subject, "draftRevision", Long.class);
            });

            Object action = constructNestedType(McpPanel.class, "ImportAction");
            callOnEdt(() -> invoke(
                    subject,
                    "applyImportedServer",
                    action.getClass(),
                    action,
                    McpJsonImporter.ImportResult.class,
                    result,
                    Object.class
            ));

            runOnEdt(() -> {
                assertThat(listenerFailed).isTrue();
                JList<?> servers = component(subject, "MCP servers", JList.class);
                assertThat(servers.getModel().getSize()).isEqualTo(1);
                assertThat(((McpServerConfiguration) servers.getSelectedValue()).id())
                        .isEqualTo(previousServerId.get());
                assertThat(component(subject, "MCP server editor", JTabbedPane.class).getSelectedIndex())
                        .isEqualTo(1);
                JTable tools = component(subject, "Discovered MCP tools", JTable.class);
                assertThat(tools.getSelectedRow()).isZero();
                assertThat(tools.getValueAt(0, 1)).isEqualTo("echo");
                assertThat(field(subject, "draftRevision", Long.class)).isEqualTo(previousRevision);
                assertThat(field(subject, "invalidDraftDirty", Boolean.class)).isTrue();
                assertThat(field(subject, "invalidReplacementConfirmed", Boolean.class)).isTrue();
                String status = component(subject, "MCP status", JTextArea.class).getText();
                assertThat(status).isEqualTo("Could not add the imported MCP server. Path: $.server");
            });
        } finally {
            runOnEdt(() -> {
                component(subject, "MCP status", JTextArea.class).getDocument()
                        .removeDocumentListener(failingListener);
                subject.disposePanel();
            });
            flushEdt();
        }
    }

    @Test
    @DisplayName("Environment-name uniqueness follows the current platform validator")
    void importJson_whenEnvironmentNamesDifferOnlyByCase_usesPlatformUniquenessRule() throws Exception {
        try (var fixture = fixture("import-environment-case", McpConfiguration.empty())) {
            ImportHandles handles = startImport(
                    fixture.subject(),
                    new Clipboard("import-environment-case"),
                    "{\"command\":\"java\",\"env\":{\"Path\":\"one\",\"PATH\":\"two\"}}"
            );
            awaitImport(handles);

            boolean windows = IS_OS_WINDOWS;
            runOnEdt(() -> {
                int serverCount = component(fixture.subject(), "MCP servers", JList.class).getModel().getSize();
                if (windows) {
                    assertThat(serverCount).isZero();
                    String status = component(fixture.subject(), "MCP status", JTextArea.class).getText();
                    assertThat(status.equals(
                            "Imported environment variables are invalid. Path: $.server.environment"
                    )).as("platform validation should use the fixed safe diagnostic").isTrue();
                } else {
                    assertThat(serverCount).isEqualTo(1);
                }
            });
        }
    }

    @Test
    @DisplayName("A parser failure changes only safe import status when no Save is waiting")
    void importJson_whenPayloadIsInvalid_preservesDraftAndLastSaveError() throws Exception {
        String rowId = UUID.randomUUID().toString();
        McpServerConfiguration existing = httpServerWithHeader(rowId, "");
        AtomicReference<char[]> replacementBefore = new AtomicReference<>();
        try (var fixture = fixture("import-invalid", new McpConfiguration(1, List.of(existing)))) {
            ParserFailureSnapshot before = callOnEdt(() -> {
                applyHeaderReplacement(fixture.subject(), "retained-secret");
                Map<String, List<McpDiscoveredTool>> tools = field(fixture.subject(), "lastTools", Map.class);
                tools.put(existing.id(), List.of(new McpDiscoveredTool(
                        "echo",
                        "Echo",
                        "Existing tool",
                        Map.of("type", "object"),
                        null
                )));
                Map<String, Set<String>> disabled = field(fixture.subject(), "disabledTools", Map.class);
                disabled.get(existing.id()).add("echo");
                invoke(fixture.subject(), "refreshToolPresentation");
                component(fixture.subject(), "MCP server name", JTextField.class).setText("Uncommitted name");
                JList<?> servers = component(fixture.subject(), "MCP servers", JList.class);
                Map<String, char[]> replacements = field(fixture.subject(), "replacementSecrets", Map.class);
                JTable toolTable = component(fixture.subject(), "Discovered MCP tools", JTable.class);
                replacementBefore.set(replacements.get(rowId));
                return new ParserFailureSnapshot(
                        component(fixture.subject(), "MCP server name", JTextField.class).getText(),
                        ((McpServerConfiguration) servers.getSelectedValue()).id(),
                        field(fixture.subject(), "draftRevision", Long.class),
                        Set.copyOf(disabled.get(existing.id())),
                        List.copyOf(tools.get(existing.id())),
                        toolTable.getRowCount(),
                        (McpServerConfiguration) servers.getModel().getElementAt(0)
                );
            });
            assertSecretEquals(replacementBefore.get(), "retained-secret", "pre-import replacement should be staged");

            ImportHandles handles = startImport(
                    fixture.subject(),
                    new Clipboard("import-invalid"),
                    "{\"mcpServers\":{\"sentinel-server\":{\"command\":\"npx\",\"secret-sentinel\":\"x\"}}}"
            );
            awaitImport(handles);

            runOnEdt(() -> {
                JList<?> servers = component(fixture.subject(), "MCP servers", JList.class);
                Map<String, char[]> replacements = field(fixture.subject(), "replacementSecrets", Map.class);
                Map<String, Set<String>> disabled = field(fixture.subject(), "disabledTools", Map.class);
                Map<String, List<McpDiscoveredTool>> tools = field(fixture.subject(), "lastTools", Map.class);
                JTable toolTable = component(fixture.subject(), "Discovered MCP tools", JTable.class);
                assertThat(servers.getModel().getSize()).isEqualTo(1);
                assertThat(servers.getModel().getElementAt(0)).isEqualTo(before.server());
                assertThat(((McpServerConfiguration) servers.getSelectedValue()).id()).isEqualTo(before.selectedId());
                assertThat(component(fixture.subject(), "MCP server name", JTextField.class).getText())
                        .isEqualTo(before.editorName());
                assertThat(field(fixture.subject(), "draftRevision", Long.class)).isEqualTo(before.revision());
                assertThat(replacements.get(rowId) == replacementBefore.get())
                        .as("parser failure should preserve replacement array identity")
                        .isTrue();
                assertSecretEquals(
                        replacements.get(rowId),
                        "retained-secret",
                        "parser failure should preserve pending replacement"
                );
                assertThat(disabled.get(existing.id())).containsExactlyElementsOf(before.disabledTools());
                assertThat(tools.get(existing.id())).containsExactlyElementsOf(before.tools());
                assertThat(toolTable.getRowCount()).isEqualTo(before.toolRowCount());
                assertThat(fixture.subject().lastSaveError()).isEmpty();
                String status = component(fixture.subject(), "MCP status", JTextArea.class).getText();
                assertThat(status.contains("sentinel")).as("safe status should not contain source text").isFalse();
                assertThat(status.contains("MCP JSON contains an unknown field."))
                        .as("parser failure should use the fixed safe diagnostic")
                        .isTrue();
            });
        }
    }

    @Test
    @DisplayName("A pending Save rejects an import admitted after its barrier")
    void importJson_whenSavePublicationIsPending_rejectsPostBarrierAdmission() throws Exception {
        McpServerConfiguration existing = server("Existing", "existing", McpTransportType.STDIO);
        AtomicLong generation = new AtomicLong();
        McpManager manager = controlledManager(new McpConfiguration(1, List.of(existing)), generation);
        CompletableFuture<McpApplyResult> publication = new CompletableFuture<>();
        CountDownLatch submitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.<McpConfigurationDraft>getArgument(0).clearSecrets();
            submitted.countDown();
            return publication;
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            CompletableFuture<Boolean> save = callOnEdt(subject::savePendingChangesAsync);
            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();

            Clipboard clipboard = new Clipboard("post-save-barrier");
            clipboard.setContents(new StringSelection("{\"command\":\"node\"}"), null);
            runOnEdt(() -> invoke(
                    subject,
                    "startClipboardImport",
                    Clipboard.class,
                    clipboard,
                    Void.class
            ));

            runOnEdt(() -> {
                assertThat(field(subject, "activeImport", Object.class)).isNull();
                assertThat(field(subject, "lingeringImportWorker", Thread.class)).isNull();
                assertThat(menuItem(subject, "Import JSON from Clipboard").isEnabled()).isFalse();
                assertThat(component(subject, "MCP servers", JList.class).getModel().getSize()).isEqualTo(1);
            });

            generation.set(1);
            publication.complete(applied(1, existing));
            assertThat(save.get(5, TimeUnit.SECONDS)).isTrue();
            flushEdt();
            assertThat(callOnEdt(() -> menuItem(subject, "Import JSON from Clipboard").isEnabled())).isTrue();
        } finally {
            if (!publication.isDone()) {
                publication.completeExceptionally(new IllegalStateException("test cleanup"));
            }
            runOnEdt(subject::disposePanel);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A Save that captures a parser failure returns the rendered safe import diagnostic")
    void savePendingChangesAsync_whenCapturedImportFails_returnsSafeImportFailure() throws Exception {
        var transfer = new BlockingTransferable("{\"command\":\"java\",\"unknown-sentinel\":true}");
        Clipboard clipboard = new Clipboard("import-save-failure");
        clipboard.setContents(transfer, null);
        try (var fixture = fixture("import-save-failure", McpConfiguration.empty())) {
            ImportHandles handles = startBlockedImport(fixture.subject(), clipboard);
            try {
                assertThat(transfer.entered().await(5, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<Boolean> save = callOnEdt(fixture.subject()::savePendingChangesAsync);

                transfer.release().countDown();
                joinImportWorker(handles.worker());
                assertThat(save.get(5, TimeUnit.SECONDS)).isFalse();
                flushEdt();

                String error = callOnEdt(fixture.subject()::lastSaveError);
                assertThat(error.contains("sentinel")).as("Save error should not contain source text").isFalse();
                assertThat(error.contains("MCP JSON contains an unknown field."))
                        .as("Save error should contain the fixed import reason")
                        .isTrue();
                assertThat(callOnEdt(() -> component(fixture.subject(), "MCP servers", JList.class)
                        .getModel().getSize())).isZero();
            } finally {
                releaseJoinAndFlush(transfer, handles);
            }
        }
    }

    @Test
    @DisplayName("A coordinated Save waits for the import captured at admission")
    void savePendingChangesAsync_whenImportIsBlocked_waitsForCapturedImport() throws Exception {
        var transfer = new BlockingTransferable("{\"command\":\"java\"}");
        Clipboard clipboard = new Clipboard("import-save");
        clipboard.setContents(transfer, null);
        try (var fixture = fixture("import-save", McpConfiguration.empty())) {
            ImportHandles handles = startBlockedImport(fixture.subject(), clipboard);
            try {
                assertThat(transfer.entered().await(5, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<Boolean> save = callOnEdt(fixture.subject()::savePendingChangesAsync);
                assertThat(save).isNotDone();
                assertThat(callOnEdt(() -> menuItem(fixture.subject(), "Import JSON from Clipboard").isEnabled()))
                        .isFalse();

                transfer.release().countDown();
                joinImportWorker(handles.worker());
                assertThat(save.get(5, TimeUnit.SECONDS)).isTrue();
                flushEdt();

                assertThat(callOnEdt(() -> component(fixture.subject(), "MCP servers", JList.class)
                        .getModel().getSize())).isEqualTo(1);
            } finally {
                releaseJoinAndFlush(transfer, handles);
            }
        }
    }

    @Test
    @DisplayName("The one-shot timeout fails a waiting Save and fences a lingering worker")
    void importJson_whenClipboardTransferTimesOut_failsWaitingSaveAndFencesWorker() throws Exception {
        var transfer = new BlockingTransferable("{\"command\":\"java\"}");
        Clipboard clipboard = new Clipboard("import-timeout");
        clipboard.setContents(transfer, null);
        try (var fixture = fixture("import-timeout", McpConfiguration.empty())) {
            ImportHandles handles = startBlockedImport(fixture.subject(), clipboard);
            try {
                assertThat(transfer.entered().await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(callOnEdt(handles.timer()::getDelay)).isEqualTo(5_000);
                assertThat(callOnEdt(handles.timer()::getInitialDelay)).isEqualTo(5_000);
                assertThat(callOnEdt(handles.timer()::isRepeats)).isFalse();
                CompletableFuture<Boolean> save = callOnEdt(fixture.subject()::savePendingChangesAsync);
                AtomicBoolean settlementOnEdt = new AtomicBoolean();
                AtomicBoolean activeSlotClearedBeforeSettlement = new AtomicBoolean();
                handles.settlement().thenRun(() -> {
                    settlementOnEdt.set(SwingUtilities.isEventDispatchThread());
                    activeSlotClearedBeforeSettlement.set(
                            field(fixture.subject(), "activeImport", Object.class) == null
                    );
                });

                fireTimer(handles.timer());

                assertThat(save.get(5, TimeUnit.SECONDS)).isFalse();
                assertThat(settlementOnEdt).isTrue();
                assertThat(activeSlotClearedBeforeSettlement).isTrue();
                assertThat(callOnEdt(fixture.subject()::lastSaveError)).contains("Clipboard import timed out.");
                assertThat(callOnEdt(handles.timer()::isRunning)).isFalse();
                assertThat(callOnEdt(() -> menuItem(fixture.subject(), "Import JSON from Clipboard").isEnabled()))
                        .isFalse();
                assertThat(transfer.interruptObserved().await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(transfer.interruptions()).isEqualTo(1);

                transfer.release().countDown();
                joinImportWorker(handles.worker());
                flushEdt();
                assertThat(callOnEdt(() -> menuItem(fixture.subject(), "Import JSON from Clipboard").isEnabled()))
                        .isTrue();
                assertThat(callOnEdt(() -> component(fixture.subject(), "MCP servers", JList.class)
                        .getModel().getSize())).isZero();
            } finally {
                releaseJoinAndFlush(transfer, handles);
            }
        }
    }

    @Test
    @DisplayName("Disposal resolves an import-waiting Save and prevents late model mutation")
    void disposePanel_whenImportIsBlocked_resolvesSaveAndPreventsLateMutation() throws Exception {
        var transfer = new BlockingTransferable("{\"command\":\"java\"}");
        Clipboard clipboard = new Clipboard("import-dispose");
        clipboard.setContents(transfer, null);
        try (var fixture = fixture("import-dispose", McpConfiguration.empty())) {
            ImportHandles handles = startBlockedImport(fixture.subject(), clipboard);
            try {
                assertThat(transfer.entered().await(5, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<Boolean> save = callOnEdt(fixture.subject()::savePendingChangesAsync);

                runOnEdt(fixture.subject()::disposePanel);
                assertThat(save.get(5, TimeUnit.SECONDS)).isFalse();
                assertThat(callOnEdt(handles.timer()::isRunning)).isFalse();
                assertThat(transfer.interruptObserved().await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(transfer.interruptions()).isEqualTo(1);

                transfer.release().countDown();
                joinImportWorker(handles.worker());
                flushEdt();
                assertThat(callOnEdt(() -> component(fixture.subject(), "MCP servers", JList.class)
                        .getModel().getSize())).isZero();
                assertThat(callOnEdt(() -> menuItem(fixture.subject(), "Import JSON from Clipboard").isEnabled()))
                        .isFalse();
            } finally {
                releaseJoinAndFlush(transfer, handles);
            }
        }
    }

    @Test
    @DisplayName("Import timers stop after ordinary success and failure")
    void importJson_whenWorkerSettles_stopsTimerForEveryOrdinaryOutcome() throws Exception {
        for (ImportTimerCase timerCase : List.of(
                new ImportTimerCase("success", "{\"command\":\"java\"}", true),
                new ImportTimerCase("failure", "{\"command\":\"java\",\"unknown\":true}", false)
        )) {
            var transfer = new BlockingTransferable(timerCase.payload());
            Clipboard clipboard = new Clipboard("import-timer-%s".formatted(timerCase.name()));
            clipboard.setContents(transfer, null);
            try (var fixture = fixture("import-timer-%s".formatted(timerCase.name()), McpConfiguration.empty())) {
                ImportHandles handles = startBlockedImport(fixture.subject(), clipboard);
                try {
                    assertThat(transfer.entered().await(5, TimeUnit.SECONDS)).isTrue();
                    transfer.release().countDown();
                    joinImportWorker(handles.worker());
                    handles.settlement().get(5, TimeUnit.SECONDS);
                    flushEdt();

                    assertThat(callOnEdt(handles.timer()::isRunning)).isFalse();
                    assertThat(callOnEdt(() -> component(fixture.subject(), "MCP servers", JList.class)
                            .getModel().getSize())).isEqualTo(timerCase.applied() ? 1 : 0);
                } finally {
                    releaseJoinAndFlush(transfer, handles);
                }
            }
        }
    }

    @Test
    @DisplayName("Repeated import activation is rejected while active and lingering")
    void importJson_whenActionIsActiveOrLingering_rejectsRepeatedActivation() throws Exception {
        var transfer = new BlockingTransferable("{\"command\":\"java\"}");
        Clipboard clipboard = new Clipboard("import-repeated");
        clipboard.setContents(transfer, null);
        try (var fixture = fixture("import-repeated", McpConfiguration.empty())) {
            ImportHandles handles = startBlockedImport(fixture.subject(), clipboard);
            try {
                assertThat(transfer.entered().await(5, TimeUnit.SECONDS)).isTrue();
                Object originalAction = callOnEdt(() -> field(fixture.subject(), "activeImport", Object.class));
                Clipboard secondClipboard = new Clipboard("import-repeated-second");
                secondClipboard.setContents(new StringSelection("{\"command\":\"node\"}"), null);

                runOnEdt(() -> invoke(
                        fixture.subject(),
                        "startClipboardImport",
                        Clipboard.class,
                        secondClipboard,
                        Void.class
                ));
                assertThat(callOnEdt(() -> field(fixture.subject(), "activeImport", Object.class)))
                        .isSameAs(originalAction);

                fireTimer(handles.timer());
                assertThat(callOnEdt(() -> field(fixture.subject(), "activeImport", Object.class))).isNull();
                assertThat(callOnEdt(() -> field(fixture.subject(), "lingeringImportWorker", Thread.class)))
                        .isSameAs(handles.worker());
                runOnEdt(() -> invoke(
                        fixture.subject(),
                        "startClipboardImport",
                        Clipboard.class,
                        secondClipboard,
                        Void.class
                ));
                assertThat(callOnEdt(() -> field(fixture.subject(), "activeImport", Object.class))).isNull();
            } finally {
                releaseJoinAndFlush(transfer, handles);
            }
        }
    }

    @Test
    @DisplayName("Non-text and failing clipboard owners settle with fixed safe diagnostics")
    void importJson_whenClipboardCannotProvideText_settlesSafely() throws Exception {
        for (ClipboardFailureCase failureCase : List.of(
                new ClipboardFailureCase(
                        "non-text",
                        new NonTextTransferable(),
                        "Clipboard text is unavailable. Path: $"
                ),
                new ClipboardFailureCase(
                        "read-failure",
                        new FailingTransferable(),
                        "Could not read MCP JSON from the clipboard. Path: $"
                )
        )) {
            Clipboard clipboard = new Clipboard(failureCase.name());
            clipboard.setContents(failureCase.transferable(), null);
            try (var fixture = fixture("import-%s".formatted(failureCase.name()), McpConfiguration.empty())) {
                ImportHandles handles = startImport(fixture.subject(), clipboard);
                joinImportWorker(handles.worker());
                handles.settlement().get(5, TimeUnit.SECONDS);
                flushEdt();

                assertThat(callOnEdt(handles.timer()::isRunning)).isFalse();
                assertThat(callOnEdt(() -> component(fixture.subject(), "MCP status", JTextArea.class).getText()))
                        .isEqualTo(failureCase.expectedDiagnostic());
                assertThat(callOnEdt(() -> component(fixture.subject(), "MCP servers", JList.class)
                        .getModel().getSize())).isZero();
            }
        }
    }

    @Test
    @DisplayName("Disposal does not interrupt a timed-out lingering worker twice")
    void disposePanel_whenImportWorkerIsLingering_doesNotInterruptAgain() throws Exception {
        var transfer = new BlockingTransferable("{\"command\":\"java\"}");
        Clipboard clipboard = new Clipboard("import-lingering-dispose");
        clipboard.setContents(transfer, null);
        try (var fixture = fixture("import-lingering-dispose", McpConfiguration.empty())) {
            ImportHandles handles = startBlockedImport(fixture.subject(), clipboard);
            try {
                assertThat(transfer.entered().await(5, TimeUnit.SECONDS)).isTrue();
                fireTimer(handles.timer());
                assertThat(transfer.interruptObserved().await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(transfer.interruptions()).isEqualTo(1);

                runOnEdt(fixture.subject()::disposePanel);
                transfer.release().countDown();
                joinImportWorker(handles.worker());
                flushEdt();

                assertThat(transfer.interruptions()).isEqualTo(1);
                assertThat(callOnEdt(handles.timer()::isRunning)).isFalse();
            } finally {
                releaseJoinAndFlush(transfer, handles);
            }
        }
    }

    @Test
    @DisplayName("Disposal queued before reconciliation prevents imported state from returning")
    void disposePanel_whenQueuedBeforeImportReconciliation_winsOrderingRace() throws Exception {
        var transfer = new BlockingTransferable("{\"command\":\"java\",\"env\":{\"TOKEN\":\"secret\"}}");
        Clipboard clipboard = new Clipboard("import-queued-dispose");
        clipboard.setContents(transfer, null);
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        try (var fixture = fixture("import-queued-dispose", McpConfiguration.empty())) {
            ImportHandles handles = startBlockedImport(fixture.subject(), clipboard);
            try {
                assertThat(transfer.entered().await(5, TimeUnit.SECONDS)).isTrue();
                SwingUtilities.invokeLater(() -> {
                    edtBlocked.countDown();
                    try {
                        releaseEdt.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                });
                assertThat(edtBlocked.await(5, TimeUnit.SECONDS)).isTrue();
                SwingUtilities.invokeLater(fixture.subject()::disposePanel);
                transfer.release().countDown();
                joinImportWorker(handles.worker());
            } finally {
                releaseEdt.countDown();
                releaseJoinAndFlush(transfer, handles);
            }

            assertThat(callOnEdt(() -> component(fixture.subject(), "MCP servers", JList.class)
                    .getModel().getSize())).isZero();
            assertThat(callOnEdt(() -> replacementSecretsEmpty(fixture.subject())))
                    .as("queued disposal should clear imported replacements")
                    .isTrue();
        } finally {
            releaseEdt.countDown();
        }
    }

    @Test
    @DisplayName("Separate Saves independently wait for the same admitted import")
    void savePendingChangesAsync_whenTwoSavesCaptureImport_settlesBothLogicalActions() throws Exception {
        var transfer = new BlockingTransferable("{\"command\":\"java\"}");
        Clipboard clipboard = new Clipboard("import-two-saves");
        clipboard.setContents(transfer, null);
        try (var fixture = fixture("import-two-saves", McpConfiguration.empty())) {
            ImportHandles handles = startBlockedImport(fixture.subject(), clipboard);
            try {
                assertThat(transfer.entered().await(5, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<Boolean> first = callOnEdt(fixture.subject()::savePendingChangesAsync);
                CompletableFuture<Boolean> second = callOnEdt(fixture.subject()::savePendingChangesAsync);
                assertThat(first).isNotSameAs(second);
                assertThat(first).isNotDone();
                assertThat(second).isNotDone();

                transfer.release().countDown();
                joinImportWorker(handles.worker());
                assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
                flushEdt();
                assertThat(callOnEdt(() -> component(fixture.subject(), "MCP servers", JList.class)
                        .getModel().getSize())).isEqualTo(1);
            } finally {
                releaseJoinAndFlush(transfer, handles);
            }
        }
    }

    @Test
    @DisplayName("Ordinary import settlement commits edits made while clipboard transfer is blocked")
    void importJson_whenEditorChangesDuringBlockedTransfer_settlesLatestEditorValue() throws Exception {
        McpServerConfiguration existing = server("Before", "before", McpTransportType.STDIO);
        var transfer = new BlockingTransferable("{\"command\":\"java\"}");
        Clipboard clipboard = new Clipboard("import-editor-settlement");
        clipboard.setContents(transfer, null);
        try (var fixture = fixture(
                "import-editor-settlement",
                new McpConfiguration(1, List.of(existing))
        )) {
            ImportHandles handles = startBlockedImport(fixture.subject(), clipboard);
            try {
                assertThat(transfer.entered().await(5, TimeUnit.SECONDS)).isTrue();
                runOnEdt(() -> component(fixture.subject(), "MCP server name", JTextField.class)
                        .setText("Edited while importing"));
                transfer.release().countDown();
                joinImportWorker(handles.worker());
                handles.settlement().get(5, TimeUnit.SECONDS);
                flushEdt();

                McpServerConfiguration updated = callOnEdt(() -> {
                    JList<?> servers = component(fixture.subject(), "MCP servers", JList.class);
                    return (McpServerConfiguration) servers.getModel().getElementAt(0);
                });
                assertThat(updated.name()).isEqualTo("Edited while importing");
            } finally {
                releaseJoinAndFlush(transfer, handles);
            }
        }
    }

    @Test
    @DisplayName("A Save-captured import does not recommit edits made after Save admission")
    void savePendingChangesAsync_whenEditorChangesAfterImportBarrier_preservesAdmissionValue() throws Exception {
        McpServerConfiguration existing = server("Before", "before", McpTransportType.STDIO);
        var transfer = new BlockingTransferable("{\"command\":\"java\"}");
        Clipboard clipboard = new Clipboard("import-save-editor-settlement");
        clipboard.setContents(transfer, null);
        try (var fixture = fixture(
                "import-save-editor-settlement",
                new McpConfiguration(1, List.of(existing))
        )) {
            ImportHandles handles = startBlockedImport(fixture.subject(), clipboard);
            try {
                assertThat(transfer.entered().await(5, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<Boolean> save = callOnEdt(() -> {
                    component(fixture.subject(), "MCP server name", JTextField.class).setText("Admission value");
                    return fixture.subject().savePendingChangesAsync();
                });
                runOnEdt(() -> component(fixture.subject(), "MCP server name", JTextField.class)
                        .setText("Too late"));

                transfer.release().countDown();
                joinImportWorker(handles.worker());
                assertThat(save.get(5, TimeUnit.SECONDS)).isTrue();
                flushEdt();

                McpConfiguration persisted = ((McpConfigurationLoadResult.Valid) fixture.manager().loadResult())
                        .configuration();
                McpServerConfiguration persistedExisting = persisted.servers().stream()
                        .filter(server -> server.id().equals(existing.id()))
                        .findFirst()
                        .orElseThrow();
                assertThat(persistedExisting.name()).isEqualTo("Admission value");
            } finally {
                releaseJoinAndFlush(transfer, handles);
            }
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
        JTable headers = component(subject, "HTTP headers", JTable.class);
        assertThat(headers.editCellAt(0, 1)).isTrue();
        JPasswordField password = (JPasswordField) headers.getEditorComponent();
        password.setText(replacement);
        assertThat(headers.getCellEditor().stopCellEditing()).isTrue();
    }

    private void assertStoredSecret(StoragePaths storagePaths, String secretId, String expected) {
        try (var lookup = new McpSecretVault(new ApiTokenVault(storagePaths)).lookup(secretId)) {
            char[] token = lookup.token();
            try {
                assertSecretEquals(token, expected, "vault credential should match expected value");
            } finally {
                if (token != null) {
                    fill(token, '\0');
                }
            }
        }
    }

    private void assertSecretEquals(char[] actual, String expected, String description) {
        char[] expectedValue = expected.toCharArray();
        try {
            assertThat(deepEquals(actual, expectedValue)).as(description).isTrue();
        } finally {
            fill(expectedValue, '\0');
        }
    }

    private void assertAllNul(char[] value, String description) {
        boolean wiped = value != null && value.length > 0
                && CharBuffer.wrap(value).chars().allMatch(character -> character == '\0');
        assertThat(wiped).as(description).isTrue();
    }

    private void assertPasswordEmpty(JPasswordField field, String description) {
        char[] value = field.getPassword();
        boolean empty;
        try {
            empty = value.length == 0;
        } finally {
            fill(value, '\0');
        }
        assertThat(empty).as(description).isTrue();
    }

    private void assertCredentialValue(JTable table, int row, String expected, String description) {
        boolean matches = expected.equals(table.getValueAt(row, 1));
        assertThat(matches).as(description).isTrue();
    }

    private void assertSecretMapEmpty(Map<String, char[]> values, String description) {
        assertThat(values.isEmpty()).as(description).isTrue();
    }

    private boolean replacementSecretsEmpty(McpPanel subject) {
        Map<String, char[]> replacements = field(subject, "replacementSecrets", Map.class);
        return replacements.isEmpty();
    }

    private ImportHandles startImport(McpPanel subject, Clipboard clipboard, String content) throws Exception {
        clipboard.setContents(new StringSelection(content), null);
        return startImport(subject, clipboard);
    }

    private void awaitImport(ImportHandles handles) throws Exception {
        joinImportWorker(handles.worker());
        handles.settlement().get(5, TimeUnit.SECONDS);
        flushEdt();
    }

    private ImportHandles startBlockedImport(McpPanel subject, Clipboard clipboard) throws Exception {
        return startImport(subject, clipboard);
    }

    private ImportHandles startImport(McpPanel subject, Clipboard clipboard) throws Exception {
        return callOnEdt(() -> {
            invoke(subject, "startClipboardImport", Clipboard.class, clipboard, Void.class);
            return importHandles(subject);
        });
    }

    private ImportHandles importHandles(McpPanel subject) {
        Object action = field(subject, "activeImport", Object.class);
        return new ImportHandles(
                field(action, "settlement", CompletableFuture.class),
                field(action, "timer", Timer.class),
                field(action, "worker", Thread.class)
        );
    }

    private void fireTimer(Timer timer) throws Exception {
        runOnEdt(() -> stream(timer.getActionListeners()).forEach(listener ->
                listener.actionPerformed(new ActionEvent(timer, ActionEvent.ACTION_PERFORMED, "timeout"))));
    }

    private void releaseJoinAndFlush(BlockingTransferable transfer, ImportHandles handles) throws Exception {
        transfer.release().countDown();
        joinImportWorker(handles.worker());
        flushEdt();
    }

    private void joinImportWorker(Thread worker) throws InterruptedException {
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(worker.isAlive()).as("import worker should terminate").isFalse();
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

    private void assertActionAboveTable(Container editor, JTable table, JButton action) {
        JScrollPane tableScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table);
        int tableTop = SwingUtilities.convertPoint(tableScroll, 0, 0, editor).y;
        int actionBottom = SwingUtilities.convertPoint(action, 0, action.getHeight(), editor).y;
        assertThat(actionBottom).isLessThanOrEqualTo(tableTop);
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

    private JCheckBox findCheckBox(Container root, String text) {
        return components(root, JCheckBox.class).stream()
                .filter(component -> text.equals(component.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Checkbox not found: %s".formatted(text)));
    }

    private JMenuItem menuItem(McpPanel subject, String text) {
        JPopupMenu menu = field(subject, "serverCreationMenu", JPopupMenu.class);
        List<JMenuItem> matches = components(menu, JMenuItem.class).stream()
                .filter(item -> text.equals(item.getText()))
                .toList();
        assertThat(matches).as("menu item %s", text).hasSize(1);
        return matches.getFirst();
    }

    private JButton button(Container root, String accessibleName) {
        List<JButton> matches = components(root, JButton.class).stream()
                .filter(component -> component.getAccessibleContext() != null)
                .filter(component -> accessibleName.equals(component.getAccessibleContext().getAccessibleName()))
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

    private <T> T invoke(
            Object target,
            String methodName,
            Class<?> parameterType,
            Object argument,
            Class<T> resultType
    ) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterType);
            method.setAccessible(true);
            return resultType.cast(method.invoke(target, argument));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private <T> T invoke(
            Object target,
            String methodName,
            Class<?> firstParameterType,
            Object firstArgument,
            Class<?> secondParameterType,
            Object secondArgument,
            Class<T> resultType
    ) {
        try {
            Method method = target.getClass().getDeclaredMethod(
                    methodName,
                    firstParameterType,
                    secondParameterType
            );
            method.setAccessible(true);
            return resultType.cast(method.invoke(target, firstArgument, secondArgument));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Object constructNestedType(Class<?> owner, String simpleName) {
        try {
            Class<?> type = stream(owner.getDeclaredClasses())
                    .filter(candidate -> candidate.getSimpleName().equals(simpleName))
                    .findFirst()
                    .orElseThrow();
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
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
        stream(root.getComponents())
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

    private record ImportValidationCase(String name, String payload, String expectedDiagnostic) {
    }

    private record ImportTimerCase(String name, String payload, boolean applied) {
    }

    private record StalePublicationCase(String name, boolean applied) {
    }

    private record ClipboardFailureCase(String name, Transferable transferable, String expectedDiagnostic) {
    }

    private record ImportHandles(CompletableFuture<?> settlement, Timer timer, Thread worker) {
    }

    private record ParserFailureSnapshot(
            String editorName,
            String selectedId,
            long revision,
            Set<String> disabledTools,
            List<McpDiscoveredTool> tools,
            int toolRowCount,
            McpServerConfiguration server
    ) {
    }

    private static final class NonTextTransferable implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[0];
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return false;
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            throw new UnsupportedFlavorException(flavor);
        }
    }

    private static final class FailingTransferable implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.stringFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            throw new IllegalStateException("controlled clipboard failure");
        }

        @Override
        public Object getTransferData(DataFlavor flavor) {
            throw new IllegalStateException("controlled clipboard failure");
        }
    }

    private static final class BlockingTransferable implements Transferable {
        private final String content;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch interruptObserved = new CountDownLatch(1);
        private final AtomicInteger interruptions = new AtomicInteger();

        private BlockingTransferable(String content) {
            this.content = content;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.stringFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            if (SwingUtilities.isEventDispatchThread()) {
                throw new AssertionError("Clipboard flavor lookup must not run on the EDT.");
            }
            return DataFlavor.stringFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (SwingUtilities.isEventDispatchThread()) {
                throw new AssertionError("Clipboard transfer must not run on the EDT.");
            }
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            entered.countDown();
            boolean waiting = true;
            while (waiting) {
                try {
                    release.await();
                    waiting = false;
                } catch (InterruptedException e) {
                    interruptions.incrementAndGet();
                    interruptObserved.countDown();
                }
            }
            return content;
        }

        private CountDownLatch entered() {
            return entered;
        }

        private CountDownLatch release() {
            return release;
        }

        private CountDownLatch interruptObserved() {
            return interruptObserved;
        }

        private int interruptions() {
            return interruptions.get();
        }
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
