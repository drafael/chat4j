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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
import javax.swing.UIManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
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
                assertThat(add.getText()).isEqualTo("+");
                assertThat(add.getToolTipText()).isEqualTo("New MCP server");
                assertThat(remove.isEnabled()).isFalse();
                assertThat(remove.getText()).isEqualTo("−");
                assertThat(remove.getToolTipText()).isEqualTo("Remove selected MCP server");
                JPopupMenu creationMenu = field(fixture.subject(), "serverCreationMenu", JPopupMenu.class);
                assertThat(components(creationMenu, JMenuItem.class))
                        .extracting(JMenuItem::getText)
                        .containsExactly("Command-line (stdio)", "HTTP Server (http)");
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
    @DisplayName("FlatLaf keeps the server action buttons square without clipping enlarged text")
    void serverRail_whenFlatLafIsActive_keepsTextActionsSquareAndUnclipped() throws Exception {
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
                            .isEqualTo(FlatClientProperties.BUTTON_TYPE_SQUARE);
                    assertThat(action.getPreferredSize().width).isEqualTo(action.getPreferredSize().height);

                    var enlarged = action.getFont().deriveFont(32f);
                    action.setFont(enlarged);
                    assertThat(action.getPreferredSize().width).isEqualTo(action.getPreferredSize().height);
                    assertThat(action.getPreferredSize().height)
                            .isGreaterThan(action.getFontMetrics(enlarged).getHeight());
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
                Component activeCard = Arrays.stream(transportCards.getComponents())
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
    @DisplayName("Automatic tool approval attempts fence verification before confirmation")
    void toggleAutomaticExecution_whenConfirmationRuns_marksEachAttemptOnce() throws Exception {
        McpServerConfiguration configured = server("Automatic", "automatic", McpTransportType.STDIO);
        try (var fixture = fixture("automatic-cancel", new McpConfiguration(1, List.of(configured)))) {
            runOnEdt(() -> {
                JCheckBox automatic = findCheckBox(fixture.subject(), "Run tools automatically");
                long revision = field(fixture.subject(), "draftRevision", Long.class);
                try (MockedStatic<JOptionPane> confirmation = mockStatic(JOptionPane.class)) {
                    confirmation.when(() -> JOptionPane.showConfirmDialog(
                            any(Component.class),
                            any(),
                            any(String.class),
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

                try (MockedStatic<JOptionPane> confirmation = mockStatic(JOptionPane.class)) {
                    confirmation.when(() -> JOptionPane.showConfirmDialog(
                            any(Component.class),
                            any(),
                            any(String.class),
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
            Arrays.fill(savedValue, '\0');
        }
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try (var fixture = new PanelFixture(storagePaths, manager, subject)) {
            AtomicReference<char[]> owned = new AtomicReference<>();
            runOnEdt(() -> {
                JTable headers = component(subject, "HTTP headers", JTable.class);
                assertThat(headers.getColumnName(0)).isEqualTo("Name");
                assertThat(headers.getColumnName(1)).isEqualTo("Value");
                assertThat(headers.getValueAt(0, 1)).isEqualTo("••••••••");
                Component rendered = headers.prepareRenderer(headers.getCellRenderer(0, 1), 0, 1);
                assertThat(((JLabel) rendered).getToolTipText()).isEqualTo("Credential available. Edit to replace it.");
                assertThat(rendered.getAccessibleContext().getAccessibleName())
                        .isEqualTo("Authorization, credential available");

                assertThat(headers.editCellAt(0, 1)).isTrue();
                JPasswordField password = (JPasswordField) headers.getEditorComponent();
                assertThat(password.getPassword()).isEmpty();
                password.setText("unique-sentinel-secret");
                subject.finishActiveEditing();
                assertThat(headers.getValueAt(0, 1)).isEqualTo("••••••••");
                assertThat(password.getPassword()).isEmpty();
                assertThat(tableValues(headers).toString()).doesNotContain("unique-sentinel-secret");

                Map<String, char[]> replacements = field(subject, "replacementSecrets", Map.class);
                owned.set(replacements.get(rowId));
                assertThat(owned.get()).containsExactly("unique-sentinel-secret".toCharArray());
                assertThat(headers.editCellAt(0, 1)).isTrue();
                JPasswordField uncommitted = (JPasswordField) headers.getEditorComponent();
                uncommitted.setText("must-not-be-committed");
                button(subject, "Remove selected MCP server").doClick();
                assertThat(uncommitted.getPassword()).isEmpty();
                assertThat(replacements).isEmpty();
            });
            assertThat(owned.get()).containsOnly('\0');
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
                assertThat(headers.getValueAt(0, 1)).isEqualTo("");
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
                assertThat(password.getPassword()).isEmpty();
                password.setText("inline-secret");
                subject.finishActiveEditing();
                assertThat(password.getPassword()).isEmpty();
                assertThat(headers.getValueAt(0, 1)).isEqualTo("••••••••");
                assertThat(tableValues(headers).toString()).doesNotContain("inline-secret");
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
            assertThat(Files.readString(fixture.storagePaths().mcpFile())).doesNotContain("inline-secret");
            assertThat(callOnEdt(() -> field(fixture.subject(), "replacementSecrets", Map.class))).isEmpty();
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
                assertThat(password.getPassword()).isEmpty();
                assertThat(environment.getValueAt(0, 1)).isEqualTo("••••••••");
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
            assertThat(Files.readString(fixture.storagePaths().mcpFile()))
                    .doesNotContain("environment-secret");
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
                assertThat(headers.getValueAt(0, 1)).isEqualTo("••••••••");

                applyHeaderReplacement(subject, "first-secret");
                Map<String, char[]> replacements = field(subject, "replacementSecrets", Map.class);
                overwritten.set(replacements.get(rowId));
                applyHeaderReplacement(subject, "pending-secret");
                owned.set(replacements.get(rowId));
                assertThat(overwritten.get()).containsOnly('\0');
                assertThat(owned.get()).containsExactly("pending-secret".toCharArray());
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
                assertThat(blank.getPassword()).isEmpty();
                assertThat(replacements.get(rowId)).isSameAs(owned.get());
                long revisionAfterBlankSettlement = field(subject, "draftRevision", Long.class);
                assertThat(revisionAfterBlankSettlement).isGreaterThan(revisionAfterNameCancellation);

                assertThat(headers.editCellAt(0, 1)).isTrue();
                JPasswordField cancelled = (JPasswordField) headers.getEditorComponent();
                cancelled.setText("cancelled-secret");
                headers.getCellEditor().cancelCellEditing();
                assertThat(cancelled.getPassword()).isEmpty();
                assertThat(replacements.get(rowId)).isSameAs(owned.get());
                assertThat(headers.getValueAt(0, 1)).isEqualTo("••••••••");
                assertThat(field(subject, "draftRevision", Long.class))
                        .isGreaterThan(revisionAfterBlankSettlement);
            });
        }
        assertThat(owned.get()).containsOnly('\0');
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
                        .filteredOn(button -> isNotBlank(button.getText()))
                        .extracting(JButton::getText)
                        .containsExactly("Add", "Remove");
                assertThat(components(editor, JTextField.class)).isEmpty();
                assertThat(components(editor, JPasswordField.class)).isEmpty();
                JButton addRow = findButton(editor, "Add");
                JButton removeRow = findButton(editor, "Remove");
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
                findButton(editor, "Remove").doClick();

                assertThat(password.getPassword()).isEmpty();
                assertThat(headers.isEditing()).isFalse();
                assertThat(headers.getRowCount()).isZero();
                assertThat(field(subject, "replacementSecrets", Map.class)).isEmpty();
            });
        }
    }

    @Test
    @DisplayName("Inline password settlement remains bound to its original credential row")
    void finishActiveEditing_whenCredentialSelectionChanges_commitsValueToOriginalRow() throws Exception {
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

                subject.finishActiveEditing();

                Map<String, char[]> replacements = field(subject, "replacementSecrets", Map.class);
                assertThat(replacements).containsOnlyKeys(firstRowId);
                assertThat(replacements.get(firstRowId)).containsExactly("first-row-secret".toCharArray());
                assertThat(headers.getValueAt(0, 1)).isEqualTo("••••••••");
                assertThat(headers.getValueAt(1, 1)).isEqualTo("");
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

                assertThat(password.getPassword()).isEmpty();
                Map<String, char[]> replacements = field(subject, "replacementSecrets", Map.class);
                assertThat(replacements).containsOnlyKeys(firstRowId);
                assertThat(replacements.get(firstRowId)).containsExactly("first-server-secret".toCharArray());
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

                assertThat(password.getPassword()).isEmpty();
                assertThat(headers.isEditing()).isFalse();
                assertThat(headers.getRowCount()).isZero();
                assertThat(replacements).isEmpty();
            });
            assertThat(owned.get()).containsOnly('\0');
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
    @DisplayName("Settings groups use the full editor width and place credential actions below their table")
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
                JTable environment = component(fixture.subject(), "Environment variables", JTable.class);
                Object environmentEditor = field(fixture.subject(), "environmentEditor", Object.class);
                JButton addCredential = findButton((Container) environmentEditor, "Add");
                java.awt.Rectangle executableBounds = SwingUtilities.convertRectangle(
                        executable.getParent(),
                        new java.awt.Rectangle(0, 0, executable.getParent().getWidth(), executable.getParent().getHeight()),
                        content
                );
                JScrollPane environmentScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(
                        JScrollPane.class,
                        environment
                );
                int tableBottom = SwingUtilities.convertPoint(
                        environmentScroll,
                        0,
                        environmentScroll.getHeight(),
                        (Container) environmentEditor
                ).y;
                int actionsTop = SwingUtilities.convertPoint(
                        addCredential,
                        0,
                        0,
                        (Container) environmentEditor
                ).y;

                assertThat(executableBounds.x).isLessThanOrEqualTo(8);
                assertThat(executableBounds.width).isGreaterThan(content.getWidth() * 4 / 5);
                assertThat(actionsTop).isGreaterThanOrEqualTo(tableBottom);
                assertThat(components((Container) environmentEditor, JTextField.class)).isEmpty();
                assertThat(components((Container) environmentEditor, JButton.class))
                        .filteredOn(button -> isNotBlank(button.getText()))
                        .extracting(JButton::getText)
                        .containsExactly("Add", "Remove");
                JPanel transportCards = field(fixture.subject(), "transportCards", JPanel.class);
                Component visibleStdioCard = Arrays.stream(transportCards.getComponents())
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
                Component visibleHttpCard = Arrays.stream(transportCards.getComponents())
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
            assertThat(replacements.get(rowId)).containsExactly("retry-secret".toCharArray());
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
            submittedSecret.set(Arrays.copyOf(secret, secret.length));
            draft.clearSecrets();
            submitted.countDown();
            return rejectedPublication;
        }).when(manager).saveAndApply(any(McpConfigurationDraft.class));
        McpPanel subject = callOnEdt(() -> new McpPanel(manager));
        try {
            runOnEdt(() -> applyHeaderReplacement(subject, "submitted-secret"));
            CompletableFuture<Boolean> result = callOnEdt(subject::savePendingChangesAsync);
            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(submittedSecret.get()).containsExactly("submitted-secret".toCharArray());
            assertThat(callOnEdt(() -> field(subject, "replacementSecrets", Map.class))).isEmpty();

            rejectedPublication.complete(new McpApplyResult(
                    McpApplyOutcome.REJECTED_OLD_STATE_INTACT,
                    0,
                    new McpConfiguration(1, List.of(configured)),
                    "publication rejected"
            ));

            assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
            flushEdt();
            assertThat(callOnEdt(() -> field(subject, "replacementSecrets", Map.class))).isEmpty();
            assertThat(callOnEdt(() -> component(subject, "HTTP headers", JTable.class).getValueAt(0, 1)))
                    .isEqualTo("");
        } finally {
            if (!rejectedPublication.isDone()) {
                rejectedPublication.completeExceptionally(new IllegalStateException("test cleanup"));
            }
            if (submittedSecret.get() != null) {
                Arrays.fill(submittedSecret.get(), '\0');
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
            assertThat(callOnEdt(() -> field(subject, "replacementSecrets", Map.class))).isEmpty();
            assertThat(callOnEdt(() -> component(subject, "HTTP headers", JTable.class).getValueAt(0, 1)))
                    .isEqualTo("");
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
            assertThat(second).isNotDone();
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
            if (secondSubmittedSecret.get() != null) {
                Arrays.fill(secondSubmittedSecret.get(), '\0');
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
            assertThat(callOnEdt(() -> field(fixture.subject(), "replacementSecrets", Map.class))).isEmpty();
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
                assertThat(token).containsExactly(expected.toCharArray());
            } finally {
                if (token != null) {
                    Arrays.fill(token, '\0');
                }
            }
        }
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
