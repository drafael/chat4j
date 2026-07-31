package com.github.drafael.chat4j.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.icons.FlatSearchIcon;
import com.github.drafael.chat4j.chat.render.BoundedUtf8;
import com.github.drafael.chat4j.mcp.McpApplyOutcome;
import com.github.drafael.chat4j.mcp.McpApplyResult;
import com.github.drafael.chat4j.mcp.McpConfiguration;
import com.github.drafael.chat4j.mcp.McpConfigurationDraft;
import com.github.drafael.chat4j.mcp.McpConfigurationLoadResult;
import com.github.drafael.chat4j.mcp.McpConfigurationValidator;
import com.github.drafael.chat4j.mcp.McpDiscoveredTool;
import com.github.drafael.chat4j.mcp.McpManager;
import com.github.drafael.chat4j.mcp.McpSecretReference;
import com.github.drafael.chat4j.mcp.McpServerConfiguration;
import com.github.drafael.chat4j.mcp.McpTransportType;
import com.github.drafael.chat4j.mcp.McpVerificationResult;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Arrays.copyOf;
import static java.util.Arrays.fill;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;

public final class McpPanel extends JPanel implements AsyncPendingSettingsSaveParticipant {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EMPTY_CARD = "empty";
    private static final String EDITOR_CARD = "editor";
    private static final String CANCELLED_STATUS = "Verification cancelled because settings or selection changed.";
    private static final String CLOSED_SAVE_ERROR = "MCP settings save was cancelled because Settings closed.";
    private static final int ICON_SIZE = 16;

    private final McpManager manager;
    private final DefaultListModel<McpServerConfiguration> serverModel = new DefaultListModel<>();
    private final JList<McpServerConfiguration> serverList = new JList<>(serverModel);
    private final JTextField searchField = new JTextField();
    private final JButton addButton = iconButton("Add server", "/icons/titlebar/square-pen.svg");
    private final JButton emptyAddButton = new JButton("Add server");
    private final JButton removeButton = iconButton("Remove server", "/icons/input/x.svg");
    private final JTextField nameField = new JTextField();
    private final JTextField modelIdField = new JTextField();
    private final JCheckBox enabledBox = new JCheckBox("Enabled");
    private final JCheckBox automaticBox = new JCheckBox("Run tools automatically");
    private final JToggleButton stdioToggle = new JToggleButton("STDIO");
    private final JToggleButton httpToggle = new JToggleButton("HTTP");
    private final CardLayout transportCardsLayout = new CardLayout();
    private final JPanel transportCards = new VisibleCardPanel(transportCardsLayout);
    private final JTextField endpointField = new JTextField();
    private final JTextField executableField = new JTextField();
    private final ArgumentTableEditor argumentsEditor = new ArgumentTableEditor();
    private final CredentialRowsEditor headerEditor = new CredentialRowsEditor("HTTP headers");
    private final CredentialRowsEditor environmentEditor = new CredentialRowsEditor("Environment variables");
    private final JCheckBox longRunningBox = new JCheckBox("Long-running");
    private final JButton verifyButton = new JButton("Verify / Refresh");
    private final JButton showSchemaButton = new JButton("Show input schema");
    private final JButton replaceInvalidButton = new JButton("Replace / Recreate invalid configuration");
    private final FooterTextArea statusArea = new FooterTextArea();
    private final JScrollPane statusScroll = new JScrollPane(statusArea);
    private final CardLayout editorCardsLayout = new CardLayout();
    private final JPanel editorCards = new JPanel(editorCardsLayout);
    private final JTabbedPane editorTabs = new JTabbedPane();
    private final ToolTableModel toolModel = new ToolTableModel();
    private final JTable toolTable = new JTable(toolModel);
    private final JLabel toolDetailsHeading = new JLabel(loadIcon("/icons/settings/hammer.svg", ICON_SIZE));
    private final JTextArea toolDetails = readOnlyWrappingArea();
    private final Map<String, List<McpDiscoveredTool>> lastTools = new HashMap<>();
    private final Map<String, Map<String, String>> formattedSchemas = new HashMap<>();
    private final Map<String, Set<String>> disabledTools = new HashMap<>();
    private final Map<String, ToolSnapshotState> toolStates = new HashMap<>();
    private final Map<String, char[]> replacementSecrets = new HashMap<>();
    private final Set<SaveAction> pendingSaveActions = new HashSet<>();
    private final AtomicReference<AtomicBoolean> verifyCancellation = new AtomicReference<>(new AtomicBoolean());
    private boolean updating;
    private boolean disposed;
    private boolean invalidBase;
    private boolean invalidReplacementConfirmed;
    private boolean invalidDraftDirty;
    private boolean verificationRunning;
    private boolean publicationFinishing;
    private String editingServerId;
    private McpTransportType editingTransport = McpTransportType.STDIO;
    private long requestIdentity;
    private long draftRevision;
    private CompletableFuture<Void> publicationUiSettlement = CompletableFuture.completedFuture(null);
    private AppliedSnapshot lastPanelApplied;
    private String transientStatus = "";
    private String cleanupStatus = "";
    private String lastSaveError = "";
    private JDialog activeSchemaDialog;

    public McpPanel(@NonNull McpManager manager) {
        this.manager = manager;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(createMasterDetail(), BorderLayout.CENTER);
        add(createFooter(), BorderLayout.SOUTH);
        bindListeners();
        loadInitialState();
    }

    @Override
    public CompletableFuture<Boolean> savePendingChangesAsync() {
        if (!SwingUtilities.isEventDispatchThread()) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            SwingUtilities.invokeLater(() -> savePendingChangesAsync().whenComplete((saved, error) -> {
                if (error == null) {
                    result.complete(saved);
                } else {
                    result.completeExceptionally(error);
                }
            }));
            return result;
        }
        var action = new SaveAction();
        pendingSaveActions.add(action);
        lastSaveError = "";
        finishActiveEditing();
        cancelVerification(true);
        continueSave(action);
        return action.result();
    }

    @Override
    public String lastSaveError() {
        return lastSaveError;
    }

    @Override
    public String settingsSectionName() {
        return "MCP";
    }

    void finishActiveEditing() {
        if (disposed || updating) {
            return;
        }
        argumentsEditor.stopEditing();
        if (toolTable.isEditing()) {
            toolTable.getCellEditor().stopCellEditing();
        }
        headerEditor.commitDetail();
        environmentEditor.commitDetail();
        commitServer(editingServerId);
    }

    public void disposePanel() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::disposePanel);
            return;
        }
        if (disposed) {
            return;
        }
        disposed = true;
        verifyCancellation.get().set(true);
        requestIdentity++;
        pendingSaveActions.stream()
                .filter(action -> !action.submitted())
                .toList()
                .forEach(action -> completeSave(action, false, CLOSED_SAVE_ERROR));
        argumentsEditor.disposeEditor();
        if (toolTable.isEditing()) {
            toolTable.getCellEditor().cancelCellEditing();
        }
        if (activeSchemaDialog != null) {
            activeSchemaDialog.dispose();
            activeSchemaDialog = null;
        }
        headerEditor.disposeEditor();
        environmentEditor.disposeEditor();
        clearReplacementSecrets();
        serverModel.clear();
        toolModel.setTools("", emptyList(), false);
        lastTools.clear();
        formattedSchemas.clear();
        disabledTools.clear();
        toolStates.clear();
    }

    private JComponent createMasterDetail() {
        JPanel rail = createServerRail();
        editorCards.add(createEmptyCard(), EMPTY_CARD);
        editorCards.add(createSelectedCard(), EDITOR_CARD);
        rail.setMinimumSize(new Dimension(180, 0));
        editorCards.setMinimumSize(new Dimension(330, 0));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, rail, editorCards);
        split.setBorder(null);
        split.setContinuousLayout(true);
        split.setResizeWeight(0);
        split.setDividerLocation(205);
        return split;
    }

    private JPanel createServerRail() {
        JPanel rail = new JPanel(new BorderLayout(6, 6));
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search servers");
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, new FlatSearchIcon());
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.getAccessibleContext().setAccessibleName("Search MCP servers");
        rail.add(searchField, BorderLayout.NORTH);
        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverList.setCellRenderer(new ServerRenderer());
        serverList.getAccessibleContext().setAccessibleName("MCP servers");
        JScrollPane scroll = new JScrollPane(serverList);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        rail.add(scroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.add(addButton);
        actions.add(removeButton);
        rail.add(actions, BorderLayout.SOUTH);
        return rail;
    }

    private JPanel createEmptyCard() {
        JPanel card = new JPanel(new GridBagLayout());
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        JLabel icon = new JLabel(loadIcon("/icons/settings/mcp.svg", 32));
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel heading = new JLabel("No MCP server selected");
        heading.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2");
        heading.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel hint = new JLabel("Add a server or select one from the list.");
        hint.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        emptyAddButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(icon);
        content.add(Box.createVerticalStrut(8));
        content.add(heading);
        content.add(Box.createVerticalStrut(4));
        content.add(hint);
        content.add(Box.createVerticalStrut(12));
        content.add(emptyAddButton);
        card.add(content);
        return card;
    }

    private JPanel createSelectedCard() {
        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.add(createHeader(), BorderLayout.NORTH);
        editorTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE, "underlined");
        editorTabs.getAccessibleContext().setAccessibleName("MCP server editor");
        editorTabs.addTab("Settings", createSettingsTab());
        editorTabs.setMnemonicAt(0, 'S');
        editorTabs.addTab("Tools", createToolsTab());
        editorTabs.setMnemonicAt(1, 'T');
        card.add(editorTabs, BorderLayout.CENTER);
        return card;
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = baseConstraints();
        addFormRow(header, constraints, 0, "Name", 'N', nameField);
        addFormRow(header, constraints, 1, "Model ID", 'M', modelIdField);
        constraints.gridx = 1;
        constraints.gridy = 2;
        constraints.weightx = 1;
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        options.add(enabledBox);
        options.add(automaticBox);
        header.add(options, constraints);
        nameField.getAccessibleContext().setAccessibleName("MCP server name");
        modelIdField.getAccessibleContext().setAccessibleName("MCP model ID");
        return header;
    }

    private JComponent createSettingsTab() {
        WidthTrackingPanel content = new WidthTrackingPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 8));
        content.add(sectionHeading("Transport"));
        JPanel toggles = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ButtonGroup group = new ButtonGroup();
        group.add(stdioToggle);
        group.add(httpToggle);
        stdioToggle.putClientProperty(FlatClientProperties.STYLE_CLASS, "segmentedButton");
        httpToggle.putClientProperty(FlatClientProperties.STYLE_CLASS, "segmentedButton");
        stdioToggle.getAccessibleContext().setAccessibleName("STDIO transport");
        httpToggle.getAccessibleContext().setAccessibleName("Streamable HTTP transport");
        toggles.add(stdioToggle);
        toggles.add(httpToggle);
        content.add(toggles);
        content.add(Box.createVerticalStrut(8));
        transportCards.add(createStdioCard(), McpTransportType.STDIO.name());
        transportCards.add(createHttpCard(), McpTransportType.STREAMABLE_HTTP.name());
        content.add(transportCards);
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        installNestedWheelForwarding(content, scroll);
        return scroll;
    }

    private JPanel createStdioCard() {
        JPanel card = verticalPanel();
        card.add(fieldPanel("Executable", 'E', executableField));
        card.add(Box.createVerticalStrut(8));
        card.add(sectionHeading("Ordered arguments"));
        card.add(argumentsEditor);
        card.add(Box.createVerticalStrut(10));
        card.add(sectionHeading("Environment variables"));
        card.add(environmentEditor);
        longRunningBox.setToolTipText("Keep this stdio server connected between Agent runs when possible.");
        JPanel longRunningRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        longRunningRow.add(longRunningBox);
        card.add(longRunningRow);
        executableField.getAccessibleContext().setAccessibleName("MCP executable");
        return card;
    }

    private JPanel createHttpCard() {
        JPanel card = verticalPanel();
        card.add(fieldPanel("Endpoint", 'E', endpointField));
        JLabel warning = new JLabel("Credentials are encrypted; URL queries remain plaintext.");
        warning.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        JPanel warningRow = new JPanel(new BorderLayout());
        warningRow.add(warning, BorderLayout.WEST);
        card.add(warningRow);
        card.add(Box.createVerticalStrut(10));
        card.add(sectionHeading("HTTP headers"));
        card.add(headerEditor);
        endpointField.getAccessibleContext().setAccessibleName("MCP HTTP endpoint");
        return card;
    }

    private JPanel createToolsTab() {
        JPanel tools = new JPanel(new BorderLayout(0, 8));
        tools.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 8));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.add(verifyButton);
        tools.add(actions, BorderLayout.NORTH);
        toolTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        toolTable.setAutoCreateRowSorter(false);
        toolTable.setDefaultRenderer(String.class, new ToolRenderer());
        toolTable.getAccessibleContext().setAccessibleName("Discovered MCP tools");
        tools.add(new JScrollPane(toolTable), BorderLayout.CENTER);
        JPanel details = new JPanel(new BorderLayout(0, 4));
        toolDetailsHeading.putClientProperty("html.disable", Boolean.TRUE);
        toolDetailsHeading.getAccessibleContext().setAccessibleName("Selected MCP tool");
        details.add(toolDetailsHeading, BorderLayout.NORTH);
        toolDetails.setRows(4);
        toolDetails.getAccessibleContext().setAccessibleName("Selected MCP tool details");
        JScrollPane detailsScroll = new JScrollPane(toolDetails);
        detailsScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        detailsScroll.setPreferredSize(new Dimension(0, 92));
        details.add(detailsScroll, BorderLayout.CENTER);
        JPanel detailActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        detailActions.add(showSchemaButton);
        details.add(detailActions, BorderLayout.SOUTH);
        tools.add(details, BorderLayout.SOUTH);
        return tools;
    }

    private JPanel createFooter() {
        JPanel footer = new JPanel(new BorderLayout(0, 6));
        statusArea.getAccessibleContext().setAccessibleName("MCP status");
        statusArea.getAccessibleContext().setAccessibleDescription(
                "Validation, publication, verification, repair, and cleanup status"
        );
        statusScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        statusScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        statusScroll.setBorder(BorderFactory.createEmptyBorder());
        footer.add(statusScroll, BorderLayout.CENTER);
        JPanel repair = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        repair.add(replaceInvalidButton);
        footer.add(repair, BorderLayout.SOUTH);
        return footer;
    }

    private void bindListeners() {
        searchField.getDocument().addDocumentListener(documentListener(this::selectSearchMatch));
        serverList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updating) {
                finishActiveEditing();
                cancelVerification(true);
                loadSelected();
            }
        });
        addButton.addActionListener(event -> addServer());
        emptyAddButton.addActionListener(event -> addServer());
        removeButton.addActionListener(event -> removeServer());
        stdioToggle.addActionListener(event -> selectTransport(McpTransportType.STDIO));
        httpToggle.addActionListener(event -> selectTransport(McpTransportType.STREAMABLE_HTTP));
        automaticBox.addActionListener(this::toggleAutomaticExecution);
        verifyButton.addActionListener(event -> verifySelected());
        replaceInvalidButton.addActionListener(event -> repairInvalidConfiguration());
        showSchemaButton.addActionListener(event -> showSelectedSchema());
        editorTabs.addChangeListener(event -> {
            if (!updating) {
                finishActiveEditing();
                refreshToolPresentation();
            }
        });
        toolTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                refreshToolDetails();
            }
        });
        List.of(nameField, modelIdField, endpointField, executableField).forEach(field ->
                field.getDocument().addDocumentListener(mutationDocumentListener()));
        enabledBox.addActionListener(event -> markDraftMutation());
        longRunningBox.addActionListener(event -> markDraftMutation());
    }

    private void loadInitialState() {
        McpConfigurationLoadResult result = manager.loadResult();
        McpConfiguration configuration = switch (result) {
            case McpConfigurationLoadResult.Missing missing -> missing.configuration();
            case McpConfigurationLoadResult.Valid valid -> valid.configuration();
            case McpConfigurationLoadResult.Invalid invalid -> {
                invalidBase = true;
                transientStatus = invalid.message();
                yield McpConfiguration.empty();
            }
        };
        cleanupStatus = manager.cleanupStatus();
        replaceConfiguration(configuration, null, false);
        replaceInvalidButton.setVisible(invalidBase);
        refreshFooter();
        refreshSelectionPresentation();
    }

    private void continueSave(SaveAction action) {
        if (action.result().isDone()) {
            return;
        }
        if (disposed) {
            completeSave(action, false, CLOSED_SAVE_ERROR);
            return;
        }
        CompletableFuture<Void> pending = publicationUiSettlement;
        if (!pending.isDone()) {
            pending.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> continueSave(action)));
            return;
        }
        observeStableManager(action, observation -> {
            if (publicationUiSettlement != pending) {
                continueSave(action);
                return;
            }
            continueSave(action, observation);
        });
    }

    private void continueSave(SaveAction action, StableManagerObservation observation) {
        if (disposed) {
            completeSave(action, false, CLOSED_SAVE_ERROR);
            return;
        }
        reconcileExternalRepair(observation, false);
        if (invalidBase && !invalidDraftDirty) {
            completeSave(action, true, "");
            return;
        }
        if (invalidBase && !invalidReplacementConfirmed) {
            completeSave(action, false, "Confirm replacement of the invalid MCP configuration first.");
            return;
        }
        if (!confirmCleartextEndpoints()) {
            completeSave(action, false, "Cleartext MCP endpoint was not accepted.");
            return;
        }
        McpConfiguration configuration = snapshotConfiguration();
        if (!validateForPresentation(configuration)) {
            completeSave(action, false, transientStatus);
            return;
        }
        if (canSkipPublication(configuration, observation)) {
            transientStatus = "MCP settings saved.";
            refreshFooter();
            completeSave(action, true, "");
            return;
        }
        McpConfigurationDraft draft = new McpConfigurationDraft(configuration, replacementSecrets);
        PublicationAttempt attempt = new PublicationAttempt(
                requestIdentity,
                draftRevision,
                selectedServerId(),
                configuration,
                invalidBase,
                submittedSecrets()
        );
        transientStatus = "Saving MCP settings…";
        refreshFooter();
        action.markSubmitted();
        submitPublication(draft, attempt, result -> {
            boolean applied = result.error() == null && result.applyResult() != null
                    && result.applyResult().outcome().applied();
            String message = applied ? "" : publicationFailureMessage(result);
            completeSave(action, applied, message);
        });
    }

    private void observeStableManager(Object token, java.util.function.Consumer<StableManagerObservation> consumer) {
        CompletableFuture<Void> barrier;
        try {
            barrier = manager.publicationsSettled();
        } catch (RuntimeException e) {
            SwingUtilities.invokeLater(() -> stableObservationFailed(token, consumer, e));
            return;
        }
        barrier.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) {
                stableObservationFailed(token, consumer, error);
                return;
            }
            long before = manager.generation();
            McpConfigurationLoadResult loadResult = manager.loadResult();
            String observedCleanup = manager.cleanupStatus();
            long after = manager.generation();
            if (before != after) {
                observeStableManager(token, consumer);
                return;
            }
            consumer.accept(new StableManagerObservation(after, loadResult, observedCleanup));
        }));
    }

    private void stableObservationFailed(
            Object token,
            java.util.function.Consumer<StableManagerObservation> consumer,
            Throwable error
    ) {
        String message = StringUtils.defaultIfBlank(error.getMessage(), "Could not observe MCP configuration state.");
        if (token instanceof SaveAction action) {
            completeSave(action, false, message);
        } else if (!disposed && token instanceof ActionToken actionToken
                && actionToken.request() == requestIdentity) {
            verificationRunning = false;
            transientStatus = message;
            refreshFooter();
            refreshActionStates();
        }
    }

    private void reconcileExternalRepair(StableManagerObservation observation, boolean standaloneRepair) {
        cleanupStatus = observation.cleanupStatus();
        if (!invalidBase || observation.loadResult() instanceof McpConfigurationLoadResult.Invalid) {
            refreshFooter();
            return;
        }
        invalidBase = false;
        invalidReplacementConfirmed = false;
        replaceInvalidButton.setVisible(false);
        if (!invalidDraftDirty && replacementSecrets.isEmpty()
                && observation.loadResult() instanceof McpConfigurationLoadResult.Valid valid) {
            replaceConfiguration(valid.configuration(), selectedServerId(), true);
            lastPanelApplied = new AppliedSnapshot(observation.generation(), valid.configuration());
        }
        if (standaloneRepair) {
            transientStatus = "MCP configuration was already repaired.";
        }
        refreshFooter();
    }

    private boolean canSkipPublication(
            McpConfiguration configuration,
            StableManagerObservation observation
    ) {
        return lastPanelApplied != null
                && lastPanelApplied.generation() == observation.generation()
                && lastPanelApplied.configuration().equals(configuration)
                && replacementSecrets.isEmpty()
                && StringUtils.isBlank(observation.cleanupStatus());
    }

    private void submitPublication(
            McpConfigurationDraft draft,
            PublicationAttempt attempt,
            java.util.function.Consumer<PublicationCompletion> completion
    ) {
        CompletableFuture<Void> settlement = new CompletableFuture<>();
        publicationUiSettlement = settlement;
        refreshActionStates();
        CompletableFuture<McpApplyResult> managerFuture;
        try {
            managerFuture = attempt.repair()
                    ? manager.replaceInvalidAndApply(draft)
                    : manager.saveAndApply(draft);
        } catch (RuntimeException e) {
            draft.clearSecrets();
            clearSubmittedSecrets(attempt.submittedSecrets());
            SwingUtilities.invokeLater(() -> finishPublication(
                    attempt,
                    settlement,
                    new PublicationCompletion(null, e),
                    completion
            ));
            return;
        }
        transferSubmittedSecrets(attempt.submittedSecrets());
        managerFuture.whenComplete((result, error) -> SwingUtilities.invokeLater(() -> finishPublication(
                attempt,
                settlement,
                new PublicationCompletion(result, error),
                completion
        )));
    }

    private void finishPublication(
            PublicationAttempt attempt,
            CompletableFuture<Void> settlement,
            PublicationCompletion result,
            java.util.function.Consumer<PublicationCompletion> completion
    ) {
        McpApplyResult applyResult = result.applyResult();
        boolean applied = result.error() == null && applyResult != null && applyResult.outcome().applied();
        if (!disposed && applied && manager.generation() != applyResult.generation()) {
            settleAdvancedPublication(attempt, settlement, result, completion);
            return;
        }
        if (!disposed && attempt.repair() && !applied) {
            settleRejectedRepair(attempt, settlement, result, completion);
            return;
        }
        finishPublication(attempt, settlement, result, completion, null);
    }

    private void settleRejectedRepair(
            PublicationAttempt attempt,
            CompletableFuture<Void> settlement,
            PublicationCompletion result,
            java.util.function.Consumer<PublicationCompletion> completion
    ) {
        CompletableFuture<Void> barrier;
        try {
            barrier = manager.publicationsSettled();
        } catch (RuntimeException e) {
            finishPublication(attempt, settlement, result, completion, e);
            return;
        }
        barrier.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) {
                finishPublication(attempt, settlement, result, completion, error);
                return;
            }
            long before = manager.generation();
            McpConfigurationLoadResult latest = manager.loadResult();
            String latestCleanup = manager.cleanupStatus();
            long after = manager.generation();
            if (before != after) {
                settleRejectedRepair(attempt, settlement, result, completion);
                return;
            }
            reconcileExternalRepair(new StableManagerObservation(after, latest, latestCleanup), false);
            finishPublication(attempt, settlement, result, completion, null);
        }));
    }

    private void settleAdvancedPublication(
            PublicationAttempt attempt,
            CompletableFuture<Void> settlement,
            PublicationCompletion result,
            java.util.function.Consumer<PublicationCompletion> completion
    ) {
        CompletableFuture<Void> barrier;
        try {
            barrier = manager.publicationsSettled();
        } catch (RuntimeException e) {
            finishPublication(attempt, settlement, result, completion, e);
            return;
        }
        barrier.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) {
                finishPublication(attempt, settlement, result, completion, error);
                return;
            }
            long before = manager.generation();
            McpConfigurationLoadResult latest = manager.loadResult();
            String latestCleanup = manager.cleanupStatus();
            long after = manager.generation();
            if (before != after) {
                settleAdvancedPublication(attempt, settlement, result, completion);
                return;
            }
            if (!disposed && latest instanceof McpConfigurationLoadResult.Valid valid) {
                cleanupStatus = latestCleanup;
                reconcileSecretReferences(valid.configuration());
            }
            finishPublication(attempt, settlement, result, completion, null);
        }));
    }

    private void finishPublication(
            PublicationAttempt attempt,
            CompletableFuture<Void> settlement,
            PublicationCompletion result,
            java.util.function.Consumer<PublicationCompletion> completion,
            Throwable observationError
    ) {
        PublicationCompletion callerResult = observationError == null
                ? result
                : new PublicationCompletion(result.applyResult(), observationError);
        try {
            McpApplyResult applyResult = result.applyResult();
            boolean applied = result.error() == null && applyResult != null && applyResult.outcome().applied();
            if (!disposed) {
                if (applied && attempt.repair()) {
                    invalidBase = false;
                    invalidReplacementConfirmed = false;
                    invalidDraftDirty = false;
                }
                cleanupStatus = manager.cleanupStatus();
                boolean verificationWasCancelled = transientStatus.contains(CANCELLED_STATUS);
                if (applied) {
                    replaceInvalidButton.setVisible(false);
                    if (manager.generation() == applyResult.generation()) {
                        boolean unchanged = attempt.request() == requestIdentity
                                && attempt.revision() == draftRevision;
                        if (unchanged) {
                            replaceConfiguration(applyResult.configuration(), attempt.selectedServerId(), true);
                        } else {
                            reconcileSecretReferences(applyResult.configuration());
                        }
                    }
                    lastPanelApplied = new AppliedSnapshot(applyResult.generation(), applyResult.configuration());
                    markOldToolSnapshotsStale(applyResult.generation());
                    transientStatus = observationError == null
                            ? "MCP configuration applied."
                            : StringUtils.defaultIfBlank(
                                    errorMessage(observationError),
                                    "MCP configuration applied, but its final state could not be observed."
                            );
                } else {
                    transientStatus = publicationFailureMessage(result);
                }
                if (verificationWasCancelled) {
                    transientStatus = "%s %s".formatted(transientStatus, CANCELLED_STATUS);
                }
                refreshFooter();
                refreshSelectionPresentation();
            }
        } finally {
            if (!disposed) {
                publicationFinishing = true;
                try {
                    refreshActionStates();
                } finally {
                    publicationFinishing = false;
                }
            }
            settlement.complete(null);
            completion.accept(callerResult);
        }
    }

    private void clearSubmittedSecrets(Map<String, char[]> submitted) {
        submitted.values().forEach(value -> fill(value, '\0'));
        submitted.clear();
    }

    private void transferSubmittedSecrets(Map<String, char[]> submitted) {
        submitted.forEach((rowId, submittedValue) -> {
            char[] current = replacementSecrets.get(rowId);
            if (current != null && Arrays.equals(current, submittedValue)) {
                replacementSecrets.remove(rowId);
                fill(current, '\0');
                fireCredentialStateChanged(rowId);
            }
            fill(submittedValue, '\0');
        });
    }

    private Map<String, char[]> submittedSecrets() {
        Map<String, char[]> submitted = new HashMap<>();
        replacementSecrets.forEach((rowId, value) -> submitted.put(rowId, copyOf(value, value.length)));
        return submitted;
    }

    private void completeSave(SaveAction action, boolean saved, String error) {
        if (action.result().isDone()) {
            return;
        }
        pendingSaveActions.remove(action);
        lastSaveError = saved ? "" : StringUtils.defaultIfBlank(error, "Could not save MCP settings.");
        if (!saved && !disposed) {
            transientStatus = lastSaveError;
            refreshFooter();
        }
        action.result().complete(saved);
    }

    private void verifySelected() {
        if (disposed || !publicationUiSettlement.isDone() || serverList.getSelectedValue() == null) {
            return;
        }
        finishActiveEditing();
        cancelVerification(false);
        long request = ++requestIdentity;
        long revision = draftRevision;
        String serverId = selectedServerId();
        verificationRunning = true;
        refreshActionStates();
        observeStableManager(new ActionToken(request), observation -> continueVerify(request, revision, serverId, observation));
    }

    private void continueVerify(
            long request,
            long revision,
            String serverId,
            StableManagerObservation observation
    ) {
        if (disposed || request != requestIdentity || !Objects.equals(serverId, selectedServerId())) {
            return;
        }
        reconcileExternalRepair(observation, false);
        if (invalidBase && !invalidReplacementConfirmed) {
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "The existing MCP configuration is invalid. Replace it with this draft?",
                    "Repair MCP Configuration",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (answer != JOptionPane.OK_OPTION) {
                verificationRunning = false;
                refreshActionStates();
                return;
            }
            invalidReplacementConfirmed = true;
        }
        if (!confirmCleartextEndpoints()) {
            verificationRunning = false;
            refreshActionStates();
            return;
        }
        McpConfiguration configuration = snapshotConfiguration();
        if (!validateForPresentation(configuration)) {
            verificationRunning = false;
            refreshActionStates();
            return;
        }
        McpServerConfiguration selected = serverById(serverId);
        if (selected == null) {
            verificationRunning = false;
            refreshActionStates();
            return;
        }
        AtomicBoolean cancellation = new AtomicBoolean();
        verifyCancellation.getAndSet(cancellation).set(true);
        transientStatus = "Verifying %s…".formatted(selected.displayName());
        refreshFooter();
        refreshActionStates();
        PublicationAttempt attempt = new PublicationAttempt(
                request,
                revision,
                serverId,
                configuration,
                invalidBase,
                submittedSecrets()
        );
        submitPublication(new McpConfigurationDraft(configuration, replacementSecrets), attempt, publication -> {
            McpApplyResult applyResult = publication.applyResult();
            if (publication.error() != null || applyResult == null || !applyResult.outcome().applied()
                    || cancellation.get()) {
                Throwable failure = publication.error() == null
                        ? new IllegalStateException(publicationFailureMessage(publication))
                        : publication.error();
                finishVerification(request, revision, serverId, null, failure, emptyMap());
                return;
            }
            manager.verifyAppliedAsync(applyResult, serverId, cancellation::get)
                    .thenApplyAsync(result -> new FormattedVerification(result, formatSchemas(result.tools())))
                    .whenComplete((formatted, error) -> SwingUtilities.invokeLater(() -> finishVerification(
                            request,
                            revision,
                            serverId,
                            formatted == null ? null : formatted.result(),
                            error,
                            formatted == null ? emptyMap() : formatted.schemas()
                    )));
        });
    }

    private void finishVerification(
            long request,
            long revision,
            String serverId,
            McpVerificationResult result,
            Throwable error,
            Map<String, String> schemas
    ) {
        if (disposed || request != requestIdentity || revision != draftRevision
                || !Objects.equals(serverId, selectedServerId())) {
            return;
        }
        verificationRunning = false;
        boolean currentGeneration = result != null
                && manager.generation() == result.applyResult().generation();
        if (error != null || result == null || !result.verified() || !currentGeneration) {
            ToolSnapshotState previous = toolStates.get(serverId);
            if (previous != null) {
                toolStates.put(serverId, new ToolSnapshotState(previous.generation(), true));
            }
            transientStatus = !currentGeneration && result != null && result.applyResult().outcome().applied()
                    ? "Verification result is stale because MCP settings changed."
                    : result == null
                            ? StringUtils.defaultIfBlank(errorMessage(error), "Verification failed.")
                            : StringUtils.defaultIfBlank(
                                    result.verificationError(),
                                    result.applyResult().message()
                            );
            refreshToolPresentation();
            refreshFooter();
            refreshActionStates();
            return;
        }
        lastTools.put(serverId, result.tools());
        formattedSchemas.put(serverId, schemas);
        toolStates.put(serverId, new ToolSnapshotState(result.applyResult().generation(), false));
        Set<String> knownNames = result.tools().stream().map(McpDiscoveredTool::name).collect(toSet());
        disabledTools.computeIfAbsent(serverId, ignored -> new LinkedHashSet<>()).retainAll(knownNames);
        transientStatus = "Verified %d tool(s).".formatted(result.tools().size());
        refreshToolPresentation();
        refreshFooter();
        refreshActionStates();
    }

    private void repairInvalidConfiguration() {
        if (disposed || !invalidBase || !publicationUiSettlement.isDone()) {
            return;
        }
        finishActiveEditing();
        cancelVerification(true);
        long request = ++requestIdentity;
        observeStableManager(new ActionToken(request), observation -> {
            if (disposed || request != requestIdentity) {
                return;
            }
            reconcileExternalRepair(observation, true);
            if (!invalidBase) {
                refreshActionStates();
                return;
            }
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "Replace the invalid MCP configuration with the current draft?",
                    "Repair MCP Configuration",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
            McpConfiguration configuration = snapshotConfiguration();
            if (!validateForPresentation(configuration)) {
                return;
            }
            invalidReplacementConfirmed = true;
            PublicationAttempt attempt = new PublicationAttempt(
                    request,
                    draftRevision,
                    selectedServerId(),
                    configuration,
                    true,
                    submittedSecrets()
            );
            submitPublication(new McpConfigurationDraft(configuration, replacementSecrets), attempt, ignored -> { });
        });
    }

    private boolean validateForPresentation(McpConfiguration configuration) {
        clearValidationOutlines();
        try {
            McpConfigurationValidator.validate(configuration);
            return true;
        } catch (McpConfigurationValidator.ValidationException e) {
            transientStatus = e.getMessage();
            revealValidationTarget(e);
        } catch (IllegalArgumentException e) {
            transientStatus = e.getMessage();
        }
        refreshFooter();
        return false;
    }

    private void revealValidationTarget(McpConfigurationValidator.ValidationException error) {
        if (StringUtils.isBlank(error.responsibleServerId())) {
            return;
        }
        McpServerConfiguration server = serverById(error.responsibleServerId());
        if (server == null) {
            return;
        }
        Component target = switch (error.category()) {
            case MODEL_ID -> modelIdField;
            case ENDPOINT -> server.transport() == McpTransportType.STREAMABLE_HTTP ? endpointField : null;
            case EXECUTABLE -> server.transport() == McpTransportType.STDIO ? executableField : null;
            case HTTP_HEADERS -> server.transport() == McpTransportType.STREAMABLE_HTTP ? headerEditor.table : null;
            case ENVIRONMENT -> server.transport() == McpTransportType.STDIO ? environmentEditor.table : null;
            case TOOLS -> toolTable;
            case GENERAL -> null;
        };
        if (target == null) {
            return;
        }
        int index = indexOfServer(server.id());
        updating = true;
        try {
            serverList.setSelectedIndex(index);
            editorTabs.setSelectedIndex(error.category() == McpConfigurationValidator.ValidationCategory.TOOLS ? 1 : 0);
        } finally {
            updating = false;
        }
        loadSelected();
        if (target instanceof JComponent component) {
            component.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
            component.requestFocusInWindow();
        }
    }

    private void clearValidationOutlines() {
        List.of(modelIdField, endpointField, executableField, headerEditor.table, environmentEditor.table, toolTable)
                .forEach(component -> component.putClientProperty(FlatClientProperties.OUTLINE, null));
    }

    private boolean confirmCleartextEndpoints() {
        return snapshotConfiguration().servers().stream()
                .filter(server -> server.transport() == McpTransportType.STREAMABLE_HTTP)
                .filter(server -> {
                    try {
                        URI endpoint = URI.create(server.endpoint());
                        String host = StringUtils.defaultString(endpoint.getHost());
                        return Strings.CI.equals("http", endpoint.getScheme())
                                && !(Strings.CI.equals("localhost", host)
                                || Strings.CS.equals("127.0.0.1", host));
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                })
                .allMatch(server -> JOptionPane.showConfirmDialog(
                        this,
                        "The MCP server named %s uses cleartext HTTP. Continue?".formatted(server.displayName()),
                        "Cleartext MCP Endpoint",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE
                ) == JOptionPane.OK_OPTION);
    }

    private void toggleAutomaticExecution(ActionEvent event) {
        if (updating) {
            return;
        }
        markDraftMutation();
        if (automaticBox.isSelected()) {
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "Chat4J is enabling automatic MCP tools for the server named %s. "
                            .formatted(nameField.getText())
                            + "Automatic tools can act with your user permissions. Enable only for trusted servers.",
                    "Enable Automatic MCP Tools?",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (answer != JOptionPane.OK_OPTION) {
                updating = true;
                try {
                    automaticBox.setSelected(false);
                } finally {
                    updating = false;
                }
            }
        }
    }

    private void selectTransport(McpTransportType requested) {
        if (updating || requested == editingTransport) {
            return;
        }
        updating = true;
        try {
            selectTransportToggle(editingTransport);
        } finally {
            updating = false;
        }
        finishActiveEditing();
        updating = true;
        try {
            selectTransportToggle(requested);
            transportCardsLayout.show(transportCards, requested.name());
            if (requested == McpTransportType.STREAMABLE_HTTP) {
                longRunningBox.setSelected(false);
            }
            editingTransport = requested;
        } finally {
            updating = false;
        }
        markDraftMutation();
        commitServer(editingServerId);
    }

    private void selectTransportToggle(McpTransportType transport) {
        stdioToggle.setSelected(transport == McpTransportType.STDIO);
        httpToggle.setSelected(transport == McpTransportType.STREAMABLE_HTTP);
    }

    private void addServer() {
        finishActiveEditing();
        String id = UUID.randomUUID().toString();
        var server = new McpServerConfiguration(
                id,
                "New Server",
                uniqueModelId(),
                true,
                false,
                McpTransportType.STDIO,
                "",
                "",
                emptyList(),
                emptyList(),
                emptyList(),
                false,
                emptySet()
        );
        serverModel.addElement(server);
        disabledTools.put(id, new LinkedHashSet<>());
        markDraftMutation();
        updating = true;
        try {
            serverList.setSelectedIndex(serverModel.size() - 1);
            editorTabs.setSelectedIndex(0);
        } finally {
            updating = false;
        }
        loadSelected();
        nameField.requestFocusInWindow();
    }

    private void removeServer() {
        argumentsEditor.stopEditing();
        int index = serverList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        McpServerConfiguration selected = serverModel.get(index);
        if (Objects.equals(selected.id(), editingServerId)) {
            headerEditor.discardVisiblePassword();
            environmentEditor.discardVisiblePassword();
            wipeReplacementRows(headerEditor.rowIds());
            wipeReplacementRows(environmentEditor.rowIds());
        }
        McpServerConfiguration removed = serverModel.remove(index);
        wipeServerReplacements(removed);
        disabledTools.remove(removed.id());
        lastTools.remove(removed.id());
        formattedSchemas.remove(removed.id());
        toolStates.remove(removed.id());
        editingServerId = null;
        markDraftMutation();
        if (serverModel.isEmpty()) {
            updating = true;
            try {
                serverList.clearSelection();
            } finally {
                updating = false;
            }
            clearEditor();
            emptyAddButton.requestFocusInWindow();
        } else {
            updating = true;
            try {
                serverList.setSelectedIndex(min(index, serverModel.size() - 1));
            } finally {
                updating = false;
            }
            loadSelected();
        }
        refreshSelectionPresentation();
    }

    private void wipeServerReplacements(McpServerConfiguration server) {
        wipeReplacementRows(concat(server.headers().stream(), server.environment().stream())
                .map(McpSecretReference::rowId)
                .toList());
    }

    private void wipeReplacementRows(List<String> rowIds) {
        rowIds.stream()
                .map(replacementSecrets::remove)
                .filter(Objects::nonNull)
                .forEach(value -> fill(value, '\0'));
    }

    private void selectSearchMatch() {
        if (updating) {
            return;
        }
        String query = searchField.getText().trim();
        if (StringUtils.isEmpty(query)) {
            return;
        }
        for (int index = 0; index < serverModel.size(); index++) {
            McpServerConfiguration server = serverModel.get(index);
            if (Strings.CI.contains(server.displayName(), query) || Strings.CI.contains(server.modelId(), query)) {
                serverList.setSelectedIndex(index);
                serverList.ensureIndexIsVisible(index);
                return;
            }
        }
    }

    private void loadSelected() {
        McpServerConfiguration server = serverList.getSelectedValue();
        if (server == null) {
            clearEditor();
            refreshSelectionPresentation();
            return;
        }
        updating = true;
        try {
            nameField.setText(server.name());
            modelIdField.setText(server.modelId());
            enabledBox.setSelected(server.enabled());
            automaticBox.setSelected(server.automatic());
            endpointField.setText(server.endpoint());
            executableField.setText(server.executable());
            argumentsEditor.load(server.arguments());
            headerEditor.load(server.headers());
            environmentEditor.load(server.environment());
            longRunningBox.setSelected(server.longRunning());
            editingServerId = server.id();
            editingTransport = server.transport();
            selectTransportToggle(editingTransport);
            transportCardsLayout.show(transportCards, editingTransport.name());
        } finally {
            updating = false;
        }
        refreshToolPresentation();
        refreshSelectionPresentation();
    }

    private void clearEditor() {
        updating = true;
        try {
            nameField.setText("");
            modelIdField.setText("");
            endpointField.setText("");
            executableField.setText("");
            enabledBox.setSelected(false);
            automaticBox.setSelected(false);
            longRunningBox.setSelected(false);
            argumentsEditor.load(emptyList());
            headerEditor.load(emptyList());
            environmentEditor.load(emptyList());
            editingServerId = null;
            toolModel.setTools("", emptyList(), false);
            toolDetails.setText("");
        } finally {
            updating = false;
        }
    }

    private void commitServer(String serverId) {
        if (updating || serverId == null) {
            return;
        }
        int index = indexOfServer(serverId);
        if (index < 0) {
            return;
        }
        McpServerConfiguration previous = serverModel.get(index);
        McpTransportType transport = editingTransport;
        var updated = new McpServerConfiguration(
                previous.id(),
                nameField.getText(),
                modelIdField.getText(),
                enabledBox.isSelected(),
                automaticBox.isSelected(),
                transport,
                endpointField.getText(),
                executableField.getText(),
                argumentsEditor.arguments(),
                headerEditor.rows(),
                environmentEditor.rows(),
                longRunningBox.isSelected(),
                disabledTools.getOrDefault(previous.id(), emptySet())
        );
        updating = true;
        try {
            serverModel.set(index, updated);
        } finally {
            updating = false;
        }
    }

    private void markDraftMutation() {
        if (updating || disposed) {
            return;
        }
        draftRevision++;
        invalidDraftDirty = invalidBase;
        clearValidationOutlines();
        cancelVerification(true);
        refreshActionStates();
    }

    private void cancelVerification(boolean mutation) {
        boolean running = verifyCancellation.get().compareAndSet(false, true);
        verificationRunning = false;
        requestIdentity++;
        if (mutation && running) {
            transientStatus = CANCELLED_STATUS;
            refreshFooter();
        }
    }

    private McpConfiguration snapshotConfiguration() {
        List<McpServerConfiguration> servers = new ArrayList<>();
        for (int index = 0; index < serverModel.size(); index++) {
            McpServerConfiguration server = serverModel.get(index);
            servers.add(new McpServerConfiguration(
                    server.id(),
                    server.name(),
                    server.modelId(),
                    server.enabled(),
                    server.automatic(),
                    server.transport(),
                    server.endpoint(),
                    server.executable(),
                    server.arguments(),
                    server.headers(),
                    server.environment(),
                    server.longRunning(),
                    disabledTools.getOrDefault(server.id(), emptySet())
            ));
        }
        return new McpConfiguration(McpConfiguration.CURRENT_VERSION, servers);
    }

    private void replaceConfiguration(McpConfiguration configuration, String selectedId, boolean preserveSelection) {
        String targetId = preserveSelection ? selectedId : null;
        clearReplacementSecrets();
        updating = true;
        try {
            serverModel.clear();
            disabledTools.clear();
            configuration.servers().forEach(server -> {
                serverModel.addElement(server);
                disabledTools.put(server.id(), new LinkedHashSet<>(server.disabledTools()));
            });
            int index = targetId == null ? -1 : indexOfServer(targetId);
            if (index < 0 && !serverModel.isEmpty()) {
                index = 0;
            }
            if (index >= 0) {
                serverList.setSelectedIndex(index);
            } else {
                serverList.clearSelection();
            }
        } finally {
            updating = false;
        }
        editingServerId = null;
        loadSelected();
    }

    private void reconcileSecretReferences(McpConfiguration applied) {
        finishActiveEditing();
        Map<String, McpServerConfiguration> appliedServers = applied.servers().stream()
                .collect(toMap(McpServerConfiguration::id, identity()));
        updating = true;
        try {
            for (int index = 0; index < serverModel.size(); index++) {
                McpServerConfiguration current = serverModel.get(index);
                McpServerConfiguration saved = appliedServers.get(current.id());
                if (saved != null) {
                    serverModel.set(index, withReconciledSecrets(current, saved));
                }
            }
        } finally {
            updating = false;
        }
        editingServerId = null;
        loadSelected();
    }

    private McpServerConfiguration withReconciledSecrets(
            McpServerConfiguration current,
            McpServerConfiguration applied
    ) {
        return new McpServerConfiguration(
                current.id(),
                current.name(),
                current.modelId(),
                current.enabled(),
                current.automatic(),
                current.transport(),
                current.endpoint(),
                current.executable(),
                current.arguments(),
                reconcileRows(current.headers(), applied.headers()),
                reconcileRows(current.environment(), applied.environment()),
                current.longRunning(),
                current.disabledTools()
        );
    }

    private List<McpSecretReference> reconcileRows(
            List<McpSecretReference> current,
            List<McpSecretReference> applied
    ) {
        Map<String, String> secretIds = applied.stream().collect(toMap(
                McpSecretReference::rowId,
                McpSecretReference::secretId
        ));
        return current.stream().map(row -> {
            String secretId = secretIds.get(row.rowId());
            if (replacementSecrets.containsKey(row.rowId())) {
                secretId = "";
            } else if (secretId == null) {
                secretId = "";
            }
            return new McpSecretReference(row.rowId(), row.key(), secretId);
        }).toList();
    }

    private void refreshSelectionPresentation() {
        boolean selected = serverList.getSelectedValue() != null;
        editorCardsLayout.show(editorCards, selected ? EDITOR_CARD : EMPTY_CARD);
        removeButton.setEnabled(selected);
        refreshActionStates();
    }

    private void refreshActionStates() {
        boolean selected = serverList.getSelectedValue() != null;
        boolean publicationIdle = publicationFinishing || publicationUiSettlement.isDone();
        verifyButton.setEnabled(!disposed && selected && publicationIdle && !verificationRunning);
        replaceInvalidButton.setEnabled(!disposed && invalidBase && publicationIdle);
        showSchemaButton.setEnabled(!disposed && selected && toolTable.getSelectedRow() >= 0);
        removeButton.setEnabled(!disposed && selected);
        headerEditor.refreshActionStates();
        environmentEditor.refreshActionStates();
        argumentsEditor.refreshActionStates();
    }

    private void refreshFooter() {
        if (disposed) {
            return;
        }
        String status = StringUtils.defaultString(transientStatus);
        if (StringUtils.isNotBlank(cleanupStatus)) {
            status = StringUtils.isBlank(status) ? cleanupStatus : "%s\n%s".formatted(status, cleanupStatus);
        }
        statusArea.setText(StringUtils.defaultIfBlank(status, " "));
        statusArea.setCaretPosition(0);
    }

    private void refreshToolPresentation() {
        String serverId = selectedServerId();
        if (serverId == null) {
            toolModel.setTools("", emptyList(), false);
            refreshToolDetails();
            return;
        }
        ToolSnapshotState state = toolStates.get(serverId);
        if (state != null && state.generation() != manager.generation() && !state.stale()) {
            state = new ToolSnapshotState(state.generation(), true);
            toolStates.put(serverId, state);
        }
        String selectedTool = toolModel.selectedRawName(toolTable.getSelectedRow());
        toolModel.setTools(serverId, lastTools.getOrDefault(serverId, emptyList()), state != null && state.stale());
        int index = toolModel.indexOf(selectedTool);
        if (index >= 0) {
            toolTable.setRowSelectionInterval(index, index);
        } else {
            toolTable.clearSelection();
        }
        refreshToolDetails();
    }

    private void refreshToolDetails() {
        McpDiscoveredTool tool = toolModel.toolAt(toolTable.getSelectedRow());
        if (tool == null) {
            toolDetailsHeading.setText("No tool selected");
            toolDetails.setText("");
        } else {
            toolDetailsHeading.setText(StringUtils.defaultIfBlank(tool.title(), tool.name()));
            toolDetails.setText("Protocol name: %s\n%s".formatted(
                    tool.name(),
                    StringUtils.defaultString(tool.description())
            ));
            toolDetails.setCaretPosition(0);
        }
        refreshActionStates();
    }

    private void markOldToolSnapshotsStale(long generation) {
        toolStates.replaceAll((serverId, state) -> state.generation() == generation
                ? state
                : new ToolSnapshotState(state.generation(), true));
        refreshToolPresentation();
    }

    private void showSelectedSchema() {
        finishActiveEditing();
        refreshToolPresentation();
        McpDiscoveredTool tool = toolModel.toolAt(toolTable.getSelectedRow());
        String serverId = selectedServerId();
        if (tool == null || serverId == null) {
            return;
        }
        if (activeSchemaDialog != null && activeSchemaDialog.isDisplayable()) {
            activeSchemaDialog.toFront();
            activeSchemaDialog.requestFocus();
            return;
        }
        String schema = formattedSchemas.getOrDefault(serverId, emptyMap())
                .getOrDefault(tool.name(), "Schema could not be displayed.");
        if (GraphicsEnvironment.isHeadless()) {
            transientStatus = "Schema dialog is unavailable in a headless environment.";
            refreshFooter();
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, "Input schema — %s".formatted(tool.name()), Dialog.ModalityType.DOCUMENT_MODAL);
        activeSchemaDialog = dialog;
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JTextArea content = new JTextArea(schema, 22, 72);
        content.setEditable(false);
        content.setLineWrap(false);
        content.setCaretPosition(0);
        content.getAccessibleContext().setAccessibleName("MCP tool input schema");
        content.getAccessibleContext().setAccessibleDescription(
                "Read-only JSON input schema for %s".formatted(tool.name())
        );
        JScrollPane scroll = new JScrollPane(content);
        JButton close = new JButton("Close");
        close.addActionListener(event -> dialog.dispose());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(close);
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(actions, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(
                event -> dialog.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                boolean tracked = activeSchemaDialog == dialog;
                if (tracked) {
                    activeSchemaDialog = null;
                }
                if (tracked && !disposed && showSchemaButton.isShowing()) {
                    showSchemaButton.requestFocusInWindow();
                }
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        clampDialog(dialog, owner);
        dialog.setVisible(true);
    }

    private void clampDialog(JDialog dialog, Window owner) {
        GraphicsConfiguration configuration = owner == null
                ? GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration()
                : owner.getGraphicsConfiguration();
        Rectangle usable = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int left = usable.x + insets.left;
        int top = usable.y + insets.top;
        int right = usable.x + usable.width - insets.right;
        int bottom = usable.y + usable.height - insets.bottom;
        int width = min(dialog.getWidth(), right - left);
        int height = min(dialog.getHeight(), bottom - top);
        int x = min(max(left, dialog.getX()), right - width);
        int y = min(max(top, dialog.getY()), bottom - height);
        dialog.setBounds(x, y, width, height);
    }

    private Map<String, String> formatSchemas(List<McpDiscoveredTool> tools) {
        Map<String, String> schemas = new HashMap<>();
        tools.forEach(tool -> {
            try {
                schemas.put(tool.name(), BoundedUtf8.presentation(
                        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tool.inputSchema()),
                        16_384,
                        65_536
                ));
            } catch (Exception e) {
                schemas.put(tool.name(), "Schema could not be displayed.");
            }
        });
        return Map.copyOf(schemas);
    }

    private void fireCredentialStateChanged(String rowId) {
        headerEditor.fireRowChanged(rowId);
        environmentEditor.fireRowChanged(rowId);
    }

    private void clearReplacementSecrets() {
        replacementSecrets.values().forEach(value -> fill(value, '\0'));
        replacementSecrets.clear();
    }

    private String publicationFailureMessage(PublicationCompletion completion) {
        if (completion.error() != null) {
            return StringUtils.defaultIfBlank(errorMessage(completion.error()), "Could not save MCP settings.");
        }
        if (completion.applyResult() == null) {
            return "Could not save MCP settings.";
        }
        if (completion.applyResult().outcome() == McpApplyOutcome.REJECTED_ORPHAN_CLEANUP_PENDING) {
            return "MCP configuration was not changed.";
        }
        return StringUtils.defaultIfBlank(
                completion.applyResult().message(),
                "MCP configuration was not changed."
        );
    }

    private String errorMessage(Throwable error) {
        Throwable unwrapped = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        return unwrapped == null ? "" : unwrapped.getMessage();
    }

    private String selectedServerId() {
        McpServerConfiguration selected = serverList.getSelectedValue();
        return selected == null ? null : selected.id();
    }

    private McpServerConfiguration serverById(String serverId) {
        int index = indexOfServer(serverId);
        return index < 0 ? null : serverModel.get(index);
    }

    private int indexOfServer(String serverId) {
        for (int index = 0; index < serverModel.size(); index++) {
            if (serverModel.get(index).id().equals(serverId)) {
                return index;
            }
        }
        return -1;
    }

    private String uniqueModelId() {
        Set<String> existing = new HashSet<>();
        for (int index = 0; index < serverModel.size(); index++) {
            existing.add(serverModel.get(index).modelId().toLowerCase(Locale.ROOT));
        }
        int suffix = serverModel.size() + 1;
        String candidate;
        do {
            candidate = "server_%d".formatted(suffix++);
        } while (existing.contains(candidate.toLowerCase(Locale.ROOT)));
        return candidate;
    }

    private static void installNestedWheelForwarding(Container root, JScrollPane outerScroll) {
        for (Component component : root.getComponents()) {
            if (component instanceof JScrollPane nestedScroll) {
                nestedScroll.addMouseWheelListener(event -> forwardWheelAtBoundary(event, nestedScroll, outerScroll));
            }
            if (component instanceof Container child) {
                installNestedWheelForwarding(child, outerScroll);
            }
        }
    }

    private static void forwardWheelAtBoundary(
            MouseWheelEvent event,
            JScrollPane nestedScroll,
            JScrollPane outerScroll
    ) {
        double rotation = event.getPreciseWheelRotation();
        if (rotation == 0) {
            return;
        }
        JScrollBar nestedBar = nestedScroll.getVerticalScrollBar();
        BoundedRangeModel model = nestedBar.getModel();
        boolean scrollingUp = rotation < 0;
        boolean atBoundary = scrollingUp
                ? model.getValue() <= model.getMinimum()
                : model.getValue() + model.getExtent() >= model.getMaximum();
        boolean noScrollableRange = model.getMaximum() - model.getMinimum() <= model.getExtent();
        if (nestedBar.isVisible() && !noScrollableRange && !atBoundary) {
            return;
        }
        Point point = SwingUtilities.convertPoint(nestedScroll, event.getPoint(), outerScroll);
        int wheelRotation = event.getWheelRotation() == 0
                ? (rotation < 0 ? -1 : 1)
                : event.getWheelRotation();
        MouseWheelEvent forwarded = new MouseWheelEvent(
                outerScroll,
                MouseWheelEvent.MOUSE_WHEEL,
                event.getWhen(),
                event.getModifiersEx(),
                point.x,
                point.y,
                event.getClickCount(),
                event.isPopupTrigger(),
                event.getScrollType(),
                event.getScrollAmount(),
                wheelRotation
        );
        outerScroll.dispatchEvent(forwarded);
        event.consume();
    }

    private static JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JComponent sectionHeading(String text) {
        JPanel heading = new JPanel(new BorderLayout(0, 3));
        JLabel label = new JLabel(text);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3");
        heading.add(label, BorderLayout.CENTER);
        heading.add(new JSeparator(), BorderLayout.SOUTH);
        heading.setAlignmentX(Component.CENTER_ALIGNMENT);
        return heading;
    }

    private static JPanel fieldPanel(String text, char mnemonic, JComponent field) {
        JPanel panel = new JPanel(new BorderLayout(8, 2));
        JLabel label = new JLabel(text);
        label.setDisplayedMnemonic(mnemonic);
        label.setLabelFor(field);
        panel.add(label, BorderLayout.WEST);
        panel.add(field, BorderLayout.CENTER);
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);
        return panel;
    }

    private static GridBagConstraints baseConstraints() {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(2, 2, 2, 6);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        return constraints;
    }

    private static void addFormRow(
            JPanel panel,
            GridBagConstraints constraints,
            int row,
            String text,
            char mnemonic,
            JComponent field
    ) {
        JLabel label = new JLabel(text);
        label.setDisplayedMnemonic(mnemonic);
        label.setLabelFor(field);
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0;
        panel.add(label, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(field, constraints);
    }

    private static JButton iconButton(String accessibleName, String resource) {
        JButton button = new JButton(loadIcon(resource, ICON_SIZE));
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.setToolTipText(accessibleName);
        button.getAccessibleContext().setAccessibleName(accessibleName);
        button.setPreferredSize(new Dimension(30, 30));
        return button;
    }

    private static Icon loadIcon(String resource, int size) {
        var url = McpPanel.class.getResource(resource);
        if (url == null) {
            return null;
        }
        FlatSVGIcon icon = new FlatSVGIcon(url).derive(size, size);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, original) ->
                component == null ? original : component.getForeground()));
        return icon;
    }

    private static JTextArea readOnlyWrappingArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFocusable(true);
        return area;
    }

    private static DocumentListener documentListener(Runnable action) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                action.run();
            }
        };
    }

    private DocumentListener mutationDocumentListener() {
        return documentListener(this::markDraftMutation);
    }

    private final class ArgumentTableEditor extends JPanel {
        private final ArgumentTableModel model = new ArgumentTableModel();
        private final JTable table = new JTable(model);
        private final JButton add = new JButton("Add");
        private final JButton remove = new JButton("Remove");
        private final JButton up = new JButton("Move Up");
        private final JButton down = new JButton("Move Down");
        private boolean loading;

        private ArgumentTableEditor() {
            setLayout(new BorderLayout(4, 4));
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setDefaultRenderer(String.class, new LiteralStringRenderer());
            table.getAccessibleContext().setAccessibleName("Ordered MCP arguments");
            table.setPreferredScrollableViewportSize(new Dimension(0, 92));
            JTextField editorField = new JTextField();
            editorField.getDocument().addDocumentListener(documentListener(() -> {
                if (!loading && table.isEditing()) {
                    markDraftMutation();
                }
            }));
            table.setDefaultEditor(String.class, new DefaultCellEditor(editorField));
            add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            List.of(add, remove, up, down).forEach(actions::add);
            add(actions, BorderLayout.SOUTH);
            add.addActionListener(event -> addArgument());
            remove.addActionListener(event -> removeArgument());
            up.addActionListener(event -> moveArgument(-1));
            down.addActionListener(event -> moveArgument(1));
            table.getSelectionModel().addListSelectionListener(event -> refreshActionStates());
        }

        private void load(List<String> values) {
            loading = true;
            try {
                stopEditing();
                model.setValues(values);
                table.clearSelection();
            } finally {
                loading = false;
            }
            refreshActionStates();
        }

        private List<String> arguments() {
            return model.values();
        }

        private void addArgument() {
            finishActiveEditing();
            int index = model.addValue("");
            table.setRowSelectionInterval(index, index);
            table.editCellAt(index, 0);
            Component editor = table.getEditorComponent();
            if (editor != null) {
                editor.requestFocusInWindow();
            }
            markDraftMutation();
            refreshActionStates();
        }

        private void removeArgument() {
            finishActiveEditing();
            int row = table.getSelectedRow();
            if (row < 0) {
                return;
            }
            model.remove(row);
            if (model.getRowCount() > 0) {
                int next = min(row, model.getRowCount() - 1);
                table.setRowSelectionInterval(next, next);
            }
            markDraftMutation();
            refreshActionStates();
        }

        private void moveArgument(int offset) {
            finishActiveEditing();
            int row = table.getSelectedRow();
            int target = row + offset;
            if (row < 0 || target < 0 || target >= model.getRowCount()) {
                return;
            }
            model.move(row, target);
            table.setRowSelectionInterval(target, target);
            markDraftMutation();
            refreshActionStates();
        }

        private void stopEditing() {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
        }

        private void refreshActionStates() {
            int row = table.getSelectedRow();
            boolean active = editingServerId != null && editingTransport == McpTransportType.STDIO && !disposed;
            add.setEnabled(active);
            remove.setEnabled(active && row >= 0);
            up.setEnabled(active && row > 0);
            down.setEnabled(active && row >= 0 && row < model.getRowCount() - 1);
        }

        private void disposeEditor() {
            if (table.isEditing()) {
                table.getCellEditor().cancelCellEditing();
            }
            model.setValues(emptyList());
        }
    }

    private final class CredentialRowsEditor extends JPanel {
        private final CredentialTableModel model = new CredentialTableModel();
        private final JTable table = new JTable(model);
        private final JTextField keyField = new JTextField();
        private final JPasswordField valueField = new JPasswordField();
        private final JButton add = new JButton("Add");
        private final JButton apply = new JButton("Apply");
        private final JButton remove = new JButton("Remove");
        private String editingCredentialRowId;
        private boolean loading;

        private CredentialRowsEditor(String accessibleName) {
            setLayout(new BorderLayout(4, 4));
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setDefaultRenderer(String.class, new CredentialRenderer());
            table.getAccessibleContext().setAccessibleName(accessibleName);
            table.getAccessibleContext().setAccessibleDescription(
                    "Name and credential reference state. Saved means an opaque reference exists and was not rechecked."
            );
            table.setPreferredScrollableViewportSize(new Dimension(0, 82));
            add(new JScrollPane(table), BorderLayout.CENTER);
            JPanel detail = new JPanel(new GridBagLayout());
            GridBagConstraints constraints = baseConstraints();
            addFormRow(detail, constraints, 0, "Name", 'N', keyField);
            valueField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Leave blank to retain current value");
            addFormRow(detail, constraints, 1, "New value", 'V', valueField);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            actions.add(add);
            actions.add(apply);
            actions.add(remove);
            JPanel editor = new JPanel(new BorderLayout(0, 4));
            editor.add(detail, BorderLayout.CENTER);
            editor.add(actions, BorderLayout.SOUTH);
            add(editor, BorderLayout.SOUTH);
            table.getSelectionModel().addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting() && !loading) {
                    finishActiveEditing();
                    loadSelectedRow();
                }
            });
            add.addActionListener(event -> addRow());
            apply.addActionListener(event -> commitDetail());
            remove.addActionListener(event -> removeRowDiscardingDetail());
            DocumentListener listener = documentListener(() -> {
                if (!loading) {
                    markDraftMutation();
                }
            });
            keyField.getDocument().addDocumentListener(listener);
            valueField.getDocument().addDocumentListener(listener);
        }

        private void load(List<McpSecretReference> source) {
            loading = true;
            try {
                model.setRows(source);
                if (!source.isEmpty()) {
                    table.setRowSelectionInterval(0, 0);
                } else {
                    table.clearSelection();
                }
            } finally {
                loading = false;
            }
            loadSelectedRow();
        }

        private List<McpSecretReference> rows() {
            return model.rows();
        }

        private void loadSelectedRow() {
            loading = true;
            try {
                McpSecretReference selected = model.rowAt(table.getSelectedRow());
                editingCredentialRowId = selected == null ? null : selected.rowId();
                keyField.setText(selected == null ? "" : selected.key());
                clearPasswordField();
            } finally {
                loading = false;
            }
            refreshActionStates();
        }

        private void commitDetail() {
            if (loading || editingCredentialRowId == null) {
                return;
            }
            int rowIndex = model.indexOf(editingCredentialRowId);
            if (rowIndex < 0) {
                return;
            }
            McpSecretReference previous = model.rowAt(rowIndex);
            char[] value = valueField.getPassword();
            boolean replacementChanged = value.length > 0
                    && !CharBuffer.wrap(value).chars().allMatch(Character::isWhitespace);
            boolean nameChanged = !Objects.equals(previous.key(), keyField.getText());
            try {
                if (replacementChanged) {
                    char[] old = replacementSecrets.put(previous.rowId(), copyOf(value, value.length));
                    if (old != null) {
                        fill(old, '\0');
                    }
                }
                loading = true;
                model.setRow(rowIndex, new McpSecretReference(
                        previous.rowId(),
                        keyField.getText(),
                        previous.secretId()
                ));
                clearPasswordField();
            } finally {
                loading = false;
                fill(value, '\0');
            }
            if (replacementChanged || nameChanged) {
                markDraftMutation();
            }
        }

        private void addRow() {
            finishActiveEditing();
            var row = new McpSecretReference(UUID.randomUUID().toString(), "", "");
            int index = model.addRow(row);
            loading = true;
            try {
                table.setRowSelectionInterval(index, index);
            } finally {
                loading = false;
            }
            loadSelectedRow();
            keyField.requestFocusInWindow();
            markDraftMutation();
        }

        private void removeRowDiscardingDetail() {
            int selected = table.getSelectedRow();
            McpSecretReference row = model.rowAt(selected);
            if (row == null) {
                return;
            }
            discardPasswordField();
            char[] replacement = replacementSecrets.remove(row.rowId());
            if (replacement != null) {
                fill(replacement, '\0');
            }
            loading = true;
            try {
                model.removeRow(selected);
                if (model.getRowCount() > 0) {
                    int next = min(selected, model.getRowCount() - 1);
                    table.setRowSelectionInterval(next, next);
                } else {
                    table.clearSelection();
                }
            } finally {
                loading = false;
            }
            loadSelectedRow();
            markDraftMutation();
            commitServer(editingServerId);
        }

        private void fireRowChanged(String rowId) {
            int index = model.indexOf(rowId);
            if (index >= 0) {
                model.fireTableRowsUpdated(index, index);
            }
        }

        private void refreshActionStates() {
            boolean selected = model.rowAt(table.getSelectedRow()) != null;
            boolean visibleTransport = this == headerEditor
                    ? editingTransport == McpTransportType.STREAMABLE_HTTP
                    : editingTransport == McpTransportType.STDIO;
            boolean active = !disposed && editingServerId != null && visibleTransport;
            add.setEnabled(active);
            keyField.setEnabled(active && selected);
            valueField.setEnabled(active && selected);
            apply.setEnabled(active && selected);
            remove.setEnabled(active && selected);
        }

        private void clearPasswordField() {
            char[] value = valueField.getPassword();
            fill(value, '\0');
            valueField.setText("");
        }

        private void discardPasswordField() {
            clearPasswordField();
        }

        private List<String> rowIds() {
            return model.rows().stream().map(McpSecretReference::rowId).toList();
        }

        private void discardVisiblePassword() {
            discardPasswordField();
        }

        private void disposeEditor() {
            discardPasswordField();
            keyField.setText("");
            model.setRows(emptyList());
            editingCredentialRowId = null;
        }
    }

    private static final class ArgumentTableModel extends AbstractTableModel {
        private final List<String> values = new ArrayList<>();

        @Override
        public int getRowCount() {
            return values.size();
        }

        @Override
        public int getColumnCount() {
            return 1;
        }

        @Override
        public String getColumnName(int column) {
            return "Argument";
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return values.get(rowIndex);
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            values.set(rowIndex, Objects.toString(value, ""));
            fireTableCellUpdated(rowIndex, columnIndex);
        }

        private void setValues(List<String> source) {
            values.clear();
            values.addAll(source);
            fireTableDataChanged();
        }

        private List<String> values() {
            return List.copyOf(values);
        }

        private int addValue(String value) {
            int index = values.size();
            values.add(value);
            fireTableRowsInserted(index, index);
            return index;
        }

        private void remove(int index) {
            values.remove(index);
            fireTableRowsDeleted(index, index);
        }

        private void move(int from, int to) {
            String value = values.remove(from);
            values.add(to, value);
            fireTableDataChanged();
        }
    }

    private final class CredentialTableModel extends AbstractTableModel {
        private final List<McpSecretReference> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Name" : "Credential reference state";
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            McpSecretReference row = rows.get(rowIndex);
            if (columnIndex == 0) {
                return row.key();
            }
            if (replacementSecrets.containsKey(row.rowId())) {
                return "New value entered";
            }
            return StringUtils.isNotBlank(row.secretId()) ? "Saved" : "Missing";
        }

        private void setRows(List<McpSecretReference> source) {
            rows.clear();
            rows.addAll(source);
            fireTableDataChanged();
        }

        private List<McpSecretReference> rows() {
            return List.copyOf(rows);
        }

        private McpSecretReference rowAt(int index) {
            return index < 0 || index >= rows.size() ? null : rows.get(index);
        }

        private int indexOf(String rowId) {
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).rowId().equals(rowId)) {
                    return index;
                }
            }
            return -1;
        }

        private void setRow(int index, McpSecretReference row) {
            rows.set(index, row);
            fireTableRowsUpdated(index, index);
        }

        private int addRow(McpSecretReference row) {
            int index = rows.size();
            rows.add(row);
            fireTableRowsInserted(index, index);
            return index;
        }

        private void removeRow(int index) {
            rows.remove(index);
            fireTableRowsDeleted(index, index);
        }
    }

    private final class ToolTableModel extends AbstractTableModel {
        private final List<McpDiscoveredTool> tools = new ArrayList<>();
        private String serverId = "";
        private boolean stale;

        @Override
        public int getRowCount() {
            return tools.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Enabled";
                case 1 -> "Tool";
                case 2 -> "Description";
                default -> "State";
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            McpDiscoveredTool tool = tools.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> !disabledTools.getOrDefault(serverId, emptySet()).contains(tool.name());
                case 1 -> tool.name();
                case 2 -> StringUtils.defaultString(tool.description());
                default -> stale ? "Stale" : "Current";
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != 0 || rowIndex < 0 || rowIndex >= tools.size()) {
                return;
            }
            String rawName = tools.get(rowIndex).name();
            Set<String> disabled = disabledTools.computeIfAbsent(serverId, ignored -> new LinkedHashSet<>());
            if (Boolean.TRUE.equals(value)) {
                disabled.remove(rawName);
            } else {
                disabled.add(rawName);
            }
            fireTableCellUpdated(rowIndex, columnIndex);
            markDraftMutation();
        }

        private void setTools(String serverId, List<McpDiscoveredTool> source, boolean stale) {
            this.serverId = serverId;
            this.stale = stale;
            tools.clear();
            tools.addAll(source);
            fireTableDataChanged();
        }

        private McpDiscoveredTool toolAt(int index) {
            return index < 0 || index >= tools.size() ? null : tools.get(index);
        }

        private String selectedRawName(int index) {
            McpDiscoveredTool tool = toolAt(index);
            return tool == null ? "" : tool.name();
        }

        private int indexOf(String rawName) {
            for (int index = 0; index < tools.size(); index++) {
                if (tools.get(index).name().equals(rawName)) {
                    return index;
                }
            }
            return -1;
        }
    }

    private static class LiteralStringRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean selected,
                boolean focus,
                int row,
                int column
        ) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            label.putClientProperty("html.disable", Boolean.TRUE);
            label.setIcon(null);
            label.setToolTipText(null);
            label.setText(Objects.toString(value, ""));
            label.getAccessibleContext().setAccessibleName(label.getText());
            label.getAccessibleContext().setAccessibleDescription(null);
            return label;
        }
    }

    private static final class CredentialRenderer extends LiteralStringRenderer {
        private static final String SAVED_DESCRIPTION =
                "An opaque saved credential reference exists and has not been rechecked.";

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean selected,
                boolean focus,
                int row,
                int column
        ) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (column == 1 && "Saved".equals(value)) {
                label.setToolTipText(SAVED_DESCRIPTION);
                label.getAccessibleContext().setAccessibleDescription(SAVED_DESCRIPTION);
            }
            return label;
        }
    }

    private static final class ToolRenderer extends LiteralStringRenderer {
        private final Icon hammer = loadIcon("/icons/settings/hammer.svg", ICON_SIZE);

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean selected,
                boolean focus,
                int row,
                int column
        ) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            label.setIcon(column == 1 ? hammer : null);
            label.getAccessibleContext().setAccessibleName(label.getText());
            return label;
        }
    }

    private static final class ServerRenderer extends JPanel implements ListCellRenderer<McpServerConfiguration> {
        private final JLabel name = new JLabel();
        private final JLabel detail = new JLabel();

        private ServerRenderer() {
            setLayout(new BorderLayout(0, 2));
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            name.putClientProperty("html.disable", Boolean.TRUE);
            detail.putClientProperty("html.disable", Boolean.TRUE);
            detail.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            add(name, BorderLayout.NORTH);
            add(detail, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends McpServerConfiguration> list,
                McpServerConfiguration value,
                int index,
                boolean selected,
                boolean focus
        ) {
            String state = value.enabled() ? "enabled" : "disabled";
            String transport = value.transport() == McpTransportType.STDIO ? "STDIO" : "HTTP";
            name.setText(value.displayName());
            detail.setText("%s · %s · %s".formatted(value.modelId(), transport, state));
            setOpaque(true);
            name.setOpaque(false);
            detail.setOpaque(false);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            detail.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            String accessible = "%s, %s, %s, %s".formatted(value.displayName(), value.modelId(), transport, state);
            getAccessibleContext().setAccessibleName(accessible);
            return this;
        }
    }

    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return max(16, visibleRect.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class VisibleCardPanel extends JPanel {
        private VisibleCardPanel(CardLayout layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredSize() {
            return List.of(getComponents()).stream()
                    .filter(Component::isVisible)
                    .map(Component::getPreferredSize)
                    .findFirst()
                    .orElseGet(super::getPreferredSize);
        }
    }

    private static final class FooterTextArea extends JTextArea {
        private FooterTextArea() {
            setEditable(false);
            setFocusable(true);
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            Dimension preferred = super.getPreferredScrollableViewportSize();
            FontMetrics metrics = getFontMetrics(getFont());
            Insets insets = getInsets();
            return new Dimension(preferred.width, metrics.getHeight() * 3 + insets.top + insets.bottom);
        }
    }

    private record StableManagerObservation(
            long generation,
            McpConfigurationLoadResult loadResult,
            String cleanupStatus
    ) {
    }

    private record AppliedSnapshot(long generation, McpConfiguration configuration) {
    }

    private static final class SaveAction {
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private boolean submitted;

        private CompletableFuture<Boolean> result() {
            return result;
        }

        private boolean submitted() {
            return submitted;
        }

        private void markSubmitted() {
            submitted = true;
        }
    }

    private record ActionToken(long request) {
    }

    private record PublicationAttempt(
            long request,
            long revision,
            String selectedServerId,
            McpConfiguration configuration,
            boolean repair,
            Map<String, char[]> submittedSecrets
    ) {
        @Override
        public String toString() {
            return "PublicationAttempt[request=%d, revision=%d, selectedServerId=%s, configuration=%s, repair=%s, "
                    .formatted(request, revision, selectedServerId, configuration, repair)
                    + "submittedSecrets=****]";
        }
    }

    private record PublicationCompletion(McpApplyResult applyResult, Throwable error) {
    }

    private record FormattedVerification(McpVerificationResult result, Map<String, String> schemas) {
    }

    private record ToolSnapshotState(long generation, boolean stale) {
    }
}
