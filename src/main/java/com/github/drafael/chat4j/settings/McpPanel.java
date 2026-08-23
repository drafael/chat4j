package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.json.JsonCodec;
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
import com.github.drafael.chat4j.ui.components.ActionIcon;
import com.github.drafael.chat4j.ui.components.ActionToolbar;
import com.github.drafael.chat4j.ui.components.ListTableActionPanel;
import com.github.drafael.chat4j.ui.components.ToolbarPlacement;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
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
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.nio.CharBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
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
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.text.View;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static com.github.drafael.chat4j.util.ModalDialogSupport.showConfirmDialog;
import static java.lang.Math.ceil;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Arrays.fill;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;

public final class McpPanel extends JPanel implements AsyncPendingSettingsSaveParticipant {

    private static final JsonCodec JSON = JsonCodec.standard();
    private static final String EMPTY_CARD = "empty";
    private static final String EDITOR_CARD = "editor";
    private static final String CANCELLED_STATUS = "Verification cancelled because settings or selection changed.";
    private static final String CLOSED_SAVE_ERROR = "MCP settings save was cancelled because Settings closed.";
    private static final String CREDENTIAL_MASK = "••••••••";
    private static final int ICON_SIZE = 16;
    private static final int MAX_STATUS_ROWS = 3;
    private static final int IMPORT_TIMEOUT_MILLIS = 5_000;
    private static final CompletableFuture<ImportSettlement> NO_IMPORT_SETTLEMENT =
            CompletableFuture.completedFuture(new ImportSettlement(ImportOutcome.NO_IMPORT, ""));

    private final McpManager manager;
    private final DefaultListModel<McpServerConfiguration> serverModel = new DefaultListModel<>();
    private final JList<McpServerConfiguration> serverList = new JList<>(serverModel);
    private final JTextField searchField = new JTextField();
    private final JPopupMenu serverCreationMenu = new JPopupMenu();
    private final Action addServerAction = toolbarAction("New MCP server", () -> {
    });
    private final Action removeServerAction = toolbarAction("Remove selected MCP server", this::removeServer);
    private final JButton emptyAddButton = new JButton("Add server");
    private final JMenuItem importJsonItem = new JMenuItem("Import JSON from Clipboard");
    private final JTextField nameField = new JTextField();
    private final JCheckBox enabledBox = new JCheckBox("Enabled");
    private final JCheckBox automaticBox = new JCheckBox("Run tools automatically");
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
    private final Set<String> unstableModelIdServerIds = new HashSet<>();
    private final Set<SaveAction> pendingSaveActions = new HashSet<>();
    private final AtomicReference<AtomicBoolean> verifyCancellation = new AtomicReference<>(new AtomicBoolean(true));
    private final CompletableFuture<Void> disposalSignal = new CompletableFuture<>();
    private boolean updating;
    private boolean disposed;
    private boolean invalidBase;
    private boolean invalidReplacementConfirmed;
    private boolean invalidDraftDirty;
    private boolean verificationRunning;
    private boolean repairRunning;
    private boolean publicationFinishing;
    private String editingServerId;
    private long requestIdentity;
    private long draftRevision;
    private CompletableFuture<Void> publicationUiSettlement = CompletableFuture.completedFuture(null);
    private AppliedSnapshot lastPanelApplied;
    private String transientStatus = "";
    private String cleanupStatus = "";
    private String lastSaveError = "";
    private JDialog activeSchemaDialog;
    private ImportAction activeImport;
    private Thread lingeringImportWorker;

    public McpPanel(@NonNull McpManager manager) {
        this.manager = manager;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JMenuItem addStdioServerItem = new JMenuItem("Command-line (stdio)");
        addStdioServerItem.addActionListener(event -> addServer(McpTransportType.STDIO));
        serverCreationMenu.add(addStdioServerItem);
        JMenuItem addHttpServerItem = new JMenuItem("HTTP Server (http)");
        addHttpServerItem.addActionListener(event -> addServer(McpTransportType.STREAMABLE_HTTP));
        serverCreationMenu.add(addHttpServerItem);
        serverCreationMenu.addSeparator();
        importJsonItem.getAccessibleContext().setAccessibleName("Import JSON from Clipboard");
        importJsonItem.getAccessibleContext().setAccessibleDescription(
                "Import one MCP server as disabled from JSON or JSONC on the clipboard"
        );
        importJsonItem.addActionListener(event -> importFromSystemClipboard());
        serverCreationMenu.add(importJsonItem);
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
        ImportAction capturedImport = activeImport;
        CompletableFuture<ImportSettlement> importSettlement = capturedImport == null
                ? NO_IMPORT_SETTLEMENT
                : capturedImport.settlement();
        var action = new SaveAction(importSettlement);
        pendingSaveActions.add(action);
        lastSaveError = "";
        refreshActionStates();
        finishActiveEditing();
        if (capturedImport != null && activeImport == capturedImport) {
            capturedImport.markEditorsSettledBySave();
        }
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
        headerEditor.stopEditing();
        environmentEditor.stopEditing();
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
        disposalSignal.complete(null);
        verifyCancellation.get().set(true);
        verificationRunning = false;
        repairRunning = false;
        requestIdentity++;
        pendingSaveActions.stream()
                .filter(action -> !action.submitted())
                .toList()
                .forEach(action -> completeSave(action, false, CLOSED_SAVE_ERROR));
        disposeImport();
        argumentsEditor.disposeEditor();
        if (toolTable.isEditing()) {
            toolTable.getCellEditor().cancelCellEditing();
        }
        addServerAction.setEnabled(false);
        removeServerAction.setEnabled(false);
        emptyAddButton.setEnabled(false);
        importJsonItem.setEnabled(false);
        serverCreationMenu.setVisible(false);
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
        unstableModelIdServerIds.clear();
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
        var toolbar = new ActionToolbar();
        toolbar.addDropdownAction(addServerAction, ActionIcon.ADD, serverCreationMenu);
        toolbar.addIconAction(removeServerAction, ActionIcon.REMOVE);
        var actionPanel = new ListTableActionPanel(
                serverList,
                toolbar,
                ToolbarPlacement.BOTTOM,
                "No MCP servers"
        );
        actionPanel.scrollPane().setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        rail.add(actionPanel, BorderLayout.CENTER);
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
        constraints.gridx = 1;
        constraints.gridy = 1;
        constraints.weightx = 1;
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        options.add(enabledBox);
        options.add(automaticBox);
        header.add(options, constraints);
        nameField.getAccessibleContext().setAccessibleName("MCP server name");
        return header;
    }

    private JComponent createSettingsTab() {
        WidthTrackingPanel content = new WidthTrackingPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 8));
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
        detailsScroll.setPreferredSize(new Dimension(0, 140));
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
                "Validation, publication, verification, repair, import, and cleanup status"
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
        emptyAddButton.addActionListener(event -> showServerCreationMenu(emptyAddButton));
        automaticBox.addActionListener(event -> toggleAutomaticExecution());
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
        List.of(nameField, endpointField, executableField).forEach(field ->
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
        if (!action.importObserved()) {
            CompletableFuture<ImportSettlement> importSettlement = action.importSettlement();
            if (!importSettlement.isDone()) {
                importSettlement.whenComplete((ignored, error) ->
                        SwingUtilities.invokeLater(() -> continueSave(action)));
                return;
            }
            ImportSettlement settlement = importSettlement.getNow(NO_IMPORT_SETTLEMENT.join());
            action.markImportObserved();
            if (settlement.outcome() == ImportOutcome.FAILED
                    || settlement.outcome() == ImportOutcome.TIMED_OUT) {
                completeSave(action, false, settlement.diagnostic());
                return;
            }
            if (settlement.outcome() == ImportOutcome.DISPOSED) {
                completeSave(action, false, CLOSED_SAVE_ERROR);
                return;
            }
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
        if (invalidBase && !invalidDraftDirty && !invalidReplacementConfirmed) {
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
            stabilizeSavedModelIds(configuration);
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
                invalidBase,
                submittedSecrets()
        );
        transientStatus = "Saving MCP settings…";
        refreshFooter();
        submitPublication(draft, attempt, action::markSubmitted, result -> {
            boolean applied = result.error() == null && result.applyResult() != null
                    && result.applyResult().outcome().applied();
            if (applied) {
                stabilizeSavedModelIds(result.applyResult().configuration());
            }
            String message = applied ? "" : publicationFailureMessage(result);
            completeSave(action, applied, message);
        });
    }

    private void observeStableManager(Object token, java.util.function.Consumer<StableManagerObservation> consumer) {
        if (!observationNeeded(token)) {
            return;
        }
        CompletableFuture<Void> barrier;
        try {
            barrier = manager.publicationsSettled();
        } catch (RuntimeException e) {
            SwingUtilities.invokeLater(() -> stableObservationFailed(token, e));
            return;
        }
        barrier.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (!observationNeeded(token)) {
                return;
            }
            if (error != null) {
                stableObservationFailed(token, error);
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

    private boolean observationNeeded(Object token) {
        if (token instanceof SaveAction action) {
            return !action.result().isDone();
        }
        return !disposed && token instanceof ActionToken actionToken
                && actionToken.request() == requestIdentity;
    }

    private void stableObservationFailed(Object token, Throwable error) {
        String message = StringUtils.defaultIfBlank(error.getMessage(), "Could not observe MCP configuration state.");
        if (token instanceof SaveAction action) {
            completeSave(action, false, message);
        } else if (!disposed && token instanceof ActionToken actionToken
                && actionToken.request() == requestIdentity) {
            retireCurrentVerification();
            repairRunning = false;
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

    private void stabilizeSavedModelIds(McpConfiguration savedConfiguration) {
        if (unstableModelIdServerIds.isEmpty()) {
            return;
        }
        Map<String, McpServerConfiguration> savedServers = savedConfiguration.servers().stream()
                .collect(toMap(McpServerConfiguration::id, identity()));
        updating = true;
        try {
            for (int index = 0; index < serverModel.size(); index++) {
                McpServerConfiguration current = serverModel.get(index);
                McpServerConfiguration saved = savedServers.get(current.id());
                if (saved != null && unstableModelIdServerIds.contains(current.id())) {
                    serverModel.set(index, new McpServerConfiguration(
                            current.id(),
                            current.name(),
                            saved.modelId(),
                            current.enabled(),
                            current.automatic(),
                            current.transport(),
                            current.endpoint(),
                            current.executable(),
                            current.arguments(),
                            current.headers(),
                            current.environment(),
                            current.longRunning(),
                            current.disabledTools()
                    ));
                }
            }
        } finally {
            updating = false;
        }
        unstableModelIdServerIds.removeAll(savedServers.keySet());
    }

    private void submitPublication(
            McpConfigurationDraft draft,
            PublicationAttempt attempt,
            java.util.function.Consumer<PublicationCompletion> completion
    ) {
        submitPublication(draft, attempt, () -> {}, completion);
    }

    private void submitPublication(
            McpConfigurationDraft draft,
            PublicationAttempt attempt,
            Runnable markSubmitted,
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
            attempt.submittedSecrets().clear();
            SwingUtilities.invokeLater(() -> finishPublication(
                    attempt,
                    settlement,
                    new PublicationCompletion(null, e),
                    completion
            ));
            return;
        }
        markSubmitted.run();
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
        settleAfterStableObservation(
                attempt,
                settlement,
                result,
                completion,
                observation -> reconcileExternalRepair(observation, false)
        );
    }

    private void settleAdvancedPublication(
            PublicationAttempt attempt,
            CompletableFuture<Void> settlement,
            PublicationCompletion result,
            java.util.function.Consumer<PublicationCompletion> completion
    ) {
        settleAfterStableObservation(attempt, settlement, result, completion, observation -> {
            if (observation.loadResult() instanceof McpConfigurationLoadResult.Valid valid) {
                cleanupStatus = observation.cleanupStatus();
                reconcileSecretReferences(valid.configuration());
            }
        });
    }

    private void settleAfterStableObservation(
            PublicationAttempt attempt,
            CompletableFuture<Void> settlement,
            PublicationCompletion result,
            java.util.function.Consumer<PublicationCompletion> completion,
            java.util.function.Consumer<StableManagerObservation> reconciliation
    ) {
        if (disposed) {
            finishPublication(attempt, settlement, result, completion, null);
            return;
        }
        CompletableFuture<Void> barrier;
        try {
            barrier = manager.publicationsSettled();
        } catch (RuntimeException e) {
            finishPublication(attempt, settlement, result, completion, e);
            return;
        }
        barrier.applyToEither(disposalSignal, ignored -> null)
                .whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                    if (disposed) {
                        finishPublication(attempt, settlement, result, completion, null);
                        return;
                    }
                    if (error != null) {
                        finishPublication(attempt, settlement, result, completion, error);
                        return;
                    }
                    long before = manager.generation();
                    McpConfigurationLoadResult latest = manager.loadResult();
                    String latestCleanup = manager.cleanupStatus();
                    long after = manager.generation();
                    if (before != after) {
                        settleAfterStableObservation(
                                attempt,
                                settlement,
                                result,
                                completion,
                                reconciliation
                        );
                        return;
                    }
                    reconciliation.accept(new StableManagerObservation(after, latest, latestCleanup));
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
                boolean attemptCurrent = attempt.request() == requestIdentity && attempt.revision() == draftRevision;
                boolean verificationWasCancelled = CANCELLED_STATUS.equals(transientStatus);
                String publicationStatus;
                if (applied) {
                    replaceInvalidButton.setVisible(false);
                    if (manager.generation() == applyResult.generation()) {
                        if (attemptCurrent) {
                            replaceConfiguration(applyResult.configuration(), attempt.selectedServerId(), true);
                        } else {
                            reconcileSecretReferences(applyResult.configuration());
                        }
                    }
                    lastPanelApplied = new AppliedSnapshot(applyResult.generation(), applyResult.configuration());
                    markOldToolSnapshotsStale(applyResult.generation());
                    publicationStatus = observationError == null
                            ? "MCP configuration applied."
                            : StringUtils.defaultIfBlank(
                                    errorMessage(observationError),
                                    "MCP configuration applied, but its final state could not be observed."
                            );
                } else {
                    publicationStatus = publicationFailureMessage(callerResult);
                }
                if (attemptCurrent || verificationWasCancelled) {
                    transientStatus = verificationWasCancelled
                            ? "%s %s".formatted(publicationStatus, CANCELLED_STATUS)
                            : publicationStatus;
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

    private void transferSubmittedSecrets(Map<String, char[]> submitted) {
        submitted.forEach((rowId, submittedValue) -> {
            if (replacementSecrets.get(rowId) == submittedValue) {
                replacementSecrets.remove(rowId);
                fireCredentialStateChanged(rowId);
            }
            fill(submittedValue, '\0');
        });
        submitted.clear();
    }

    private Map<String, char[]> submittedSecrets() {
        return new HashMap<>(replacementSecrets);
    }

    private void completeSave(SaveAction action, boolean saved, String error) {
        if (action.result().isDone()) {
            return;
        }
        pendingSaveActions.remove(action);
        lastSaveError = saved ? "" : StringUtils.defaultIfBlank(error, "Could not save MCP settings.");
        try {
            if (!disposed) {
                if (!saved) {
                    transientStatus = lastSaveError;
                    refreshFooter();
                }
                refreshActionStates();
            }
        } finally {
            action.result().complete(saved);
        }
    }

    private void verifySelected() {
        if (disposed || verificationRunning || repairRunning || !publicationUiSettlement.isDone()
                || serverList.getSelectedValue() == null) {
            return;
        }
        finishActiveEditing();
        cancelVerification(false);
        long request = ++requestIdentity;
        long revision = draftRevision;
        String serverId = selectedServerId();
        AtomicBoolean cancellation = new AtomicBoolean();
        verifyCancellation.getAndSet(cancellation).set(true);
        verificationRunning = true;
        refreshActionStates();
        observeStableManager(new ActionToken(request), observation -> continueVerify(
                request,
                revision,
                serverId,
                cancellation,
                observation,
                VerifyPreflightPhase.INITIAL
        ));
    }

    private void continueVerify(
            long request,
            long revision,
            String serverId,
            AtomicBoolean cancellation,
            StableManagerObservation observation,
            VerifyPreflightPhase phase
    ) {
        if (!keepCurrentVerification(request, revision, serverId, cancellation)) {
            return;
        }
        reconcileExternalRepair(observation, false);
        if (!keepCurrentVerification(request, revision, serverId, cancellation)) {
            return;
        }
        if (invalidBase && !invalidReplacementConfirmed && phase == VerifyPreflightPhase.INITIAL) {
            int answer = showConfirmDialog(
                    this,
                    "The existing MCP configuration is invalid. Replace it with this draft?",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (!keepCurrentVerification(request, revision, serverId, cancellation)) {
                return;
            }
            if (answer != JOptionPane.OK_OPTION) {
                retireCurrentVerification();
                refreshActionStates();
                return;
            }
            invalidReplacementConfirmed = true;
            observeStableManager(new ActionToken(request), latest -> continueVerify(
                    request,
                    revision,
                    serverId,
                    cancellation,
                    latest,
                    VerifyPreflightPhase.REPAIR_CONFIRMED
            ));
            return;
        }
        if (phase != VerifyPreflightPhase.CONFIRMATIONS_COMPLETE) {
            boolean accepted = confirmCleartextEndpoints(
                    () -> isCurrentVerification(request, revision, serverId, cancellation)
            );
            if (!keepCurrentVerification(request, revision, serverId, cancellation)) {
                return;
            }
            if (!accepted) {
                retireCurrentVerification();
                refreshActionStates();
                return;
            }
            if (invalidBase) {
                observeStableManager(new ActionToken(request), latest -> continueVerify(
                        request,
                        revision,
                        serverId,
                        cancellation,
                        latest,
                        VerifyPreflightPhase.CONFIRMATIONS_COMPLETE
                ));
                return;
            }
        }
        McpConfiguration configuration = snapshotConfiguration();
        if (!validateForPresentation(configuration)) {
            retireCurrentVerification();
            refreshActionStates();
            return;
        }
        McpServerConfiguration selected = serverById(serverId);
        if (selected == null) {
            retireCurrentVerification();
            refreshActionStates();
            return;
        }
        transientStatus = "Verifying %s…".formatted(settingsDisplayName(selected));
        refreshFooter();
        refreshActionStates();
        PublicationAttempt attempt = new PublicationAttempt(
                request,
                revision,
                serverId,
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

    private boolean keepCurrentVerification(
            long request,
            long revision,
            String serverId,
            AtomicBoolean cancellation
    ) {
        if (isCurrentVerification(request, revision, serverId, cancellation)) {
            return true;
        }
        if (!disposed && request == requestIdentity && !cancellation.get()) {
            cancelVerification(true);
        }
        return false;
    }

    private boolean isCurrentVerification(
            long request,
            long revision,
            String serverId,
            AtomicBoolean cancellation
    ) {
        return !disposed
                && request == requestIdentity
                && revision == draftRevision
                && Objects.equals(serverId, selectedServerId())
                && !cancellation.get();
    }

    private void retireCurrentVerification() {
        verifyCancellation.get().set(true);
        verificationRunning = false;
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
        retireCurrentVerification();
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
        if (disposed || repairRunning || verificationRunning || !invalidBase || !publicationUiSettlement.isDone()) {
            return;
        }
        finishActiveEditing();
        cancelVerification(true);
        long request = ++requestIdentity;
        repairRunning = true;
        refreshActionStates();
        observeStableManager(new ActionToken(request), observation -> continueRepairInvalidConfiguration(
                request,
                observation,
                false
        ));
    }

    private void continueRepairInvalidConfiguration(
            long request,
            StableManagerObservation observation,
            boolean replacementConfirmed
    ) {
        if (disposed || request != requestIdentity) {
            return;
        }
        reconcileExternalRepair(observation, true);
        if (disposed || request != requestIdentity) {
            return;
        }
        if (!invalidBase) {
            repairRunning = false;
            refreshActionStates();
            return;
        }
        if (!replacementConfirmed) {
            int answer = showConfirmDialog(
                    this,
                    "Replace the invalid MCP configuration with the current draft?",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (disposed || request != requestIdentity || answer != JOptionPane.OK_OPTION) {
                repairRunning = false;
                refreshActionStates();
                return;
            }
            invalidReplacementConfirmed = true;
            observeStableManager(new ActionToken(request), latest -> continueRepairInvalidConfiguration(
                    request,
                    latest,
                    true
            ));
            return;
        }
        McpConfiguration configuration = snapshotConfiguration();
        if (!validateForPresentation(configuration)) {
            repairRunning = false;
            refreshActionStates();
            return;
        }
        PublicationAttempt attempt = new PublicationAttempt(
                request,
                draftRevision,
                selectedServerId(),
                true,
                submittedSecrets()
        );
        submitPublication(new McpConfigurationDraft(configuration, replacementSecrets), attempt, ignored -> {
            if (!disposed && request == requestIdentity) {
                repairRunning = false;
                refreshActionStates();
            }
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
            case MODEL_ID -> null;
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
        List.of(endpointField, executableField, headerEditor.table, environmentEditor.table, toolTable)
                .forEach(component -> component.putClientProperty(FlatClientProperties.OUTLINE, null));
    }

    private boolean confirmCleartextEndpoints() {
        return confirmCleartextEndpoints(() -> true);
    }

    private boolean confirmCleartextEndpoints(BooleanSupplier stillCurrent) {
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
                .allMatch(server -> stillCurrent.getAsBoolean()
                        && showConfirmDialog(
                                this,
                                "The MCP server named %s uses cleartext HTTP. Continue?"
                                        .formatted(settingsDisplayName(server)),
                                JOptionPane.OK_CANCEL_OPTION,
                                JOptionPane.WARNING_MESSAGE
                        ) == JOptionPane.OK_OPTION
                        && stillCurrent.getAsBoolean());
    }

    private void toggleAutomaticExecution() {
        if (updating) {
            return;
        }
        markDraftMutation();
        if (automaticBox.isSelected()) {
            int answer = showConfirmDialog(
                    this,
                    String.join(
                            " ",
                            "Chat4J is enabling automatic MCP tools for the server named %s.",
                            "Automatic tools can act with your user permissions. Enable only for trusted servers."
                    ).formatted(StringUtils.defaultIfBlank(nameField.getText(), "Unnamed server")),
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

    private void importFromSystemClipboard() {
        if (!canAdmitImport()) {
            return;
        }
        try {
            startClipboardImport(Toolkit.getDefaultToolkit().getSystemClipboard());
        } catch (RuntimeException e) {
            transientStatus = importDiagnostic(McpJsonImporter.ImportErrorReason.CLIPBOARD_UNAVAILABLE, "$");
            refreshFooter();
        }
    }

    private void startClipboardImport(Clipboard clipboard) {
        if (!canAdmitImport() || clipboard == null) {
            return;
        }
        var action = new ImportAction();
        Timer timer = new Timer(IMPORT_TIMEOUT_MILLIS, event -> timeoutImport(action));
        timer.setInitialDelay(IMPORT_TIMEOUT_MILLIS);
        timer.setRepeats(false);
        action.setTimer(timer);
        WorkerToken token = action.workerToken();
        WeakReference<McpPanel> owner = new WeakReference<>(this);
        Thread worker = Thread.ofVirtual()
                .name("chat4j-mcp-clipboard-import")
                .unstarted(() -> runImportWorker(clipboard, token, owner));
        action.setWorker(worker);
        activeImport = action;
        transientStatus = "Reading MCP configuration from clipboard…";
        refreshFooter();
        refreshActionStates();
        try {
            timer.start();
            worker.start();
        } catch (RuntimeException e) {
            timer.stop();
            token.fenced().set(true);
            activeImport = null;
            String diagnostic = importDiagnostic(McpJsonImporter.ImportErrorReason.CLIPBOARD_READ_FAILED, "$");
            transientStatus = diagnostic;
            refreshFooter();
            refreshActionStates();
            action.settlement().complete(new ImportSettlement(ImportOutcome.FAILED, diagnostic));
        }
    }

    private boolean canAdmitImport() {
        return !disposed
                && pendingSaveActions.isEmpty()
                && activeImport == null
                && (lingeringImportWorker == null || !lingeringImportWorker.isAlive());
    }

    private static void runImportWorker(Clipboard clipboard, WorkerToken token, WeakReference<McpPanel> owner) {
        WorkerCompletion completion = readClipboard(clipboard);
        Thread worker = Thread.currentThread();
        if (token.fenced().get()) {
            completion.close();
            completion = null;
        }
        if (owner.get() == null) {
            if (completion != null) {
                completion.close();
            }
            return;
        }
        WorkerCompletion delivered = completion;
        SwingUtilities.invokeLater(() -> {
            McpPanel panel = owner.get();
            if (panel == null) {
                if (delivered != null) {
                    delivered.close();
                }
                return;
            }
            panel.reconcileImportWorker(token, worker, delivered);
        });
    }

    private static WorkerCompletion readClipboard(Clipboard clipboard) {
        try {
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return WorkerCompletion.failure(new McpJsonImporter.ImportException(
                        McpJsonImporter.ImportErrorReason.CLIPBOARD_UNAVAILABLE,
                        "$",
                        -1,
                        -1
                ));
            }
            Object value = clipboard.getData(DataFlavor.stringFlavor);
            if (!(value instanceof String content)) {
                return WorkerCompletion.failure(new McpJsonImporter.ImportException(
                        McpJsonImporter.ImportErrorReason.CLIPBOARD_UNAVAILABLE,
                        "$",
                        -1,
                        -1
                ));
            }
            return WorkerCompletion.success(new McpJsonImporter().parse(content));
        } catch (McpJsonImporter.ImportException e) {
            return WorkerCompletion.failure(e);
        } catch (UnsupportedFlavorException e) {
            return WorkerCompletion.failure(new McpJsonImporter.ImportException(
                    McpJsonImporter.ImportErrorReason.CLIPBOARD_UNAVAILABLE,
                    "$",
                    -1,
                    -1
            ));
        } catch (IOException | RuntimeException e) {
            return WorkerCompletion.failure(new McpJsonImporter.ImportException(
                    McpJsonImporter.ImportErrorReason.CLIPBOARD_READ_FAILED,
                    "$",
                    -1,
                    -1
            ));
        }
    }

    private void reconcileImportWorker(WorkerToken token, Thread worker, WorkerCompletion completion) {
        if (lingeringImportWorker == worker) {
            lingeringImportWorker = null;
            refreshActionStates();
        }
        ImportAction action = activeImport;
        if (completion == null) {
            return;
        }
        if (disposed || action == null || action.workerToken() != token || token.fenced().get()) {
            completion.close();
            return;
        }
        action.timer().stop();
        token.fenced().set(true);
        String installDiagnostic = importDiagnostic(McpJsonImporter.ImportErrorReason.INSTALL_FAILED, "$.server");
        ImportSettlement settlement = new ImportSettlement(ImportOutcome.FAILED, installDiagnostic);
        try (completion) {
            if (completion.error() != null) {
                String diagnostic = completion.error().diagnostic();
                transientStatus = diagnostic;
                refreshFooter();
                settlement = new ImportSettlement(ImportOutcome.FAILED, diagnostic);
            } else {
                try {
                    settlement = applyImportedServer(action, completion.result());
                } catch (RuntimeException e) {
                    transientStatus = installDiagnostic;
                    refreshFooter();
                }
            }
        } finally {
            settleImportAction(action, settlement);
        }
    }

    private void timeoutImport(ImportAction action) {
        if (disposed || activeImport != action || action.workerToken().fenced().getAndSet(true)) {
            return;
        }
        action.timer().stop();
        Thread worker = action.worker();
        if (worker != null) {
            worker.interrupt();
            if (worker.isAlive()) {
                lingeringImportWorker = worker;
            }
        }
        String diagnostic = importDiagnostic(McpJsonImporter.ImportErrorReason.IMPORT_TIMED_OUT, "$");
        transientStatus = diagnostic;
        try {
            refreshFooter();
        } finally {
            settleImportAction(action, new ImportSettlement(ImportOutcome.TIMED_OUT, diagnostic));
        }
    }

    private void settleImportAction(ImportAction action, ImportSettlement settlement) {
        if (activeImport == action) {
            activeImport = null;
        }
        try {
            refreshActionStates();
        } finally {
            action.settlement().complete(settlement);
        }
    }

    private ImportSettlement applyImportedServer(ImportAction action, McpJsonImporter.ImportResult result) {
        if (disposed) {
            return new ImportSettlement(ImportOutcome.DISPOSED, CLOSED_SAVE_ERROR);
        }
        if (!action.editorsSettledBySave()) {
            finishActiveEditing();
        }

        long previousDraftRevision = draftRevision;
        boolean previousInvalidDraftDirty = invalidDraftDirty;
        boolean previousInvalidReplacementConfirmed = invalidReplacementConfirmed;
        String serverId = UUID.randomUUID().toString();
        List<McpJsonImporter.CredentialDescriptor> descriptors = result.credentialDescriptors();
        List<String> rowIds = descriptors.stream().map(ignored -> UUID.randomUUID().toString()).toList();
        List<McpSecretReference> rows = new ArrayList<>();
        for (int index = 0; index < descriptors.size(); index++) {
            rows.add(new McpSecretReference(rowIds.get(index), descriptors.get(index).key(), ""));
        }
        List<McpSecretReference> headers = result.transport() == McpTransportType.STREAMABLE_HTTP
                ? List.copyOf(rows)
                : emptyList();
        List<McpSecretReference> environment = result.transport() == McpTransportType.STDIO
                ? List.copyOf(rows)
                : emptyList();
        var server = new McpServerConfiguration(
                serverId,
                result.name(),
                uniqueModelId(result.name(), serverId),
                false,
                false,
                result.transport(),
                result.endpoint(),
                result.executable(),
                result.arguments(),
                headers,
                environment,
                false,
                emptySet()
        );
        try {
            McpConfigurationValidator.validate(new McpConfiguration(
                    McpConfiguration.CURRENT_VERSION,
                    List.of(server)
            ));
        } catch (McpConfigurationValidator.ValidationException e) {
            String diagnostic = validationImportDiagnostic(e.category());
            transientStatus = diagnostic;
            refreshFooter();
            return new ImportSettlement(ImportOutcome.FAILED, diagnostic);
        }

        List<McpJsonImporter.ImportedCredential> credentials = result.transferCredentials();
        Set<String> installedRows = new HashSet<>();
        boolean toolsInserted = false;
        boolean unstableInserted = false;
        boolean installed = false;
        String previousSelection = selectedServerId();
        int previousTab = editorTabs.getSelectedIndex();
        String previousTool = toolModel.selectedRawName(toolTable.getSelectedRow());
        try {
            serverModel.addElement(server);
            disabledTools.put(serverId, new LinkedHashSet<>());
            toolsInserted = true;
            unstableModelIdServerIds.add(serverId);
            unstableInserted = true;
            for (int index = 0; index < credentials.size(); index++) {
                McpJsonImporter.ImportedCredential credential = credentials.get(index);
                if (!credential.missing()) {
                    String rowId = rowIds.get(index);
                    if (replacementSecrets.containsKey(rowId)) {
                        throw new IllegalStateException("Credential row ID collision.");
                    }
                    replacementSecrets.put(rowId, credential.value());
                    installedRows.add(rowId);
                }
            }
            markDraftMutation(false);
            updating = true;
            try {
                serverList.setSelectedIndex(serverModel.size() - 1);
                editorTabs.setSelectedIndex(0);
            } finally {
                updating = false;
            }
            toolModel.setTools("", emptyList(), false);
            toolTable.clearSelection();
            loadSelected();
            nameField.requestFocusInWindow();
            transientStatus = importSuccessStatus(result);
            refreshFooter();
            installed = true;
            return new ImportSettlement(ImportOutcome.APPLIED, "");
        } catch (RuntimeException e) {
            draftRevision = previousDraftRevision;
            invalidDraftDirty = previousInvalidDraftDirty;
            invalidReplacementConfirmed = previousInvalidReplacementConfirmed;
            try {
                int serverIndex = indexOfServer(serverId);
                if (serverIndex >= 0) {
                    serverModel.remove(serverIndex);
                }
            } catch (RuntimeException ignored) {
                // DefaultListModel removes the element before notifying listeners, so rollback must continue.
            } finally {
                if (toolsInserted) {
                    disabledTools.remove(serverId);
                }
                if (unstableInserted) {
                    unstableModelIdServerIds.remove(serverId);
                }
            }
            updating = true;
            try {
                int previousIndex = previousSelection == null ? -1 : indexOfServer(previousSelection);
                if (previousIndex >= 0) {
                    serverList.setSelectedIndex(previousIndex);
                } else {
                    serverList.clearSelection();
                }
                editorTabs.setSelectedIndex(previousTab);
            } finally {
                updating = false;
            }
            editingServerId = null;
            loadSelected();
            int previousToolIndex = toolModel.indexOf(previousTool);
            if (previousToolIndex >= 0) {
                toolTable.setRowSelectionInterval(previousToolIndex, previousToolIndex);
            }
            String diagnostic = importDiagnostic(McpJsonImporter.ImportErrorReason.INSTALL_FAILED, "$.server");
            transientStatus = diagnostic;
            refreshFooter();
            return new ImportSettlement(ImportOutcome.FAILED, diagnostic);
        } finally {
            if (!installed) {
                for (int index = 0; index < credentials.size(); index++) {
                    McpJsonImporter.ImportedCredential credential = credentials.get(index);
                    String rowId = rowIds.get(index);
                    if (installedRows.contains(rowId)) {
                        char[] removed = replacementSecrets.remove(rowId);
                        if (removed != null) {
                            fill(removed, '\0');
                        }
                    } else {
                        credential.wipe();
                    }
                }
            }
        }
    }

    private String importSuccessStatus(McpJsonImporter.ImportResult result) {
        String presentedName = BoundedUtf8.presentation(result.name(), 80, 320);
        int missing = result.missingCredentialCount();
        String missingText = switch (missing) {
            case 0 -> "";
            case 1 -> " Review its settings and enter 1 missing credential before saving.";
            default -> " Review its settings and enter %d missing credentials before saving.".formatted(missing);
        };
        String warnings = result.warnings().stream()
                .map(McpJsonImporter.ImportWarning::message)
                .collect(joining(" "));
        String warningText = StringUtils.isBlank(warnings) ? "" : " %s".formatted(warnings);
        return "Imported “%s” as disabled.%s%s".formatted(presentedName, missingText, warningText);
    }

    private String validationImportDiagnostic(McpConfigurationValidator.ValidationCategory category) {
        McpJsonImporter.ImportErrorReason reason = switch (category) {
            case MODEL_ID -> McpJsonImporter.ImportErrorReason.GENERATED_MODEL_ID_INVALID;
            case ENDPOINT -> McpJsonImporter.ImportErrorReason.IMPORTED_ENDPOINT_INVALID;
            case EXECUTABLE -> McpJsonImporter.ImportErrorReason.IMPORTED_EXECUTABLE_INVALID;
            case HTTP_HEADERS -> McpJsonImporter.ImportErrorReason.IMPORTED_HEADERS_INVALID;
            case ENVIRONMENT -> McpJsonImporter.ImportErrorReason.IMPORTED_ENVIRONMENT_INVALID;
            case TOOLS -> McpJsonImporter.ImportErrorReason.IMPORTED_TOOLS_INVALID;
            case GENERAL -> McpJsonImporter.ImportErrorReason.IMPORTED_SERVER_INVALID;
        };
        String path = switch (category) {
            case MODEL_ID, GENERAL -> "$.server";
            case ENDPOINT -> "$.server.endpoint";
            case EXECUTABLE -> "$.server.executable";
            case HTTP_HEADERS -> "$.server.headers";
            case ENVIRONMENT -> "$.server.environment";
            case TOOLS -> "$.server.tools";
        };
        return importDiagnostic(reason, path);
    }

    private static String importDiagnostic(McpJsonImporter.ImportErrorReason reason, String path) {
        return "%s Path: %s".formatted(reason.message(), path);
    }

    private void disposeImport() {
        ImportAction action = activeImport;
        activeImport = null;
        if (action != null) {
            action.timer().stop();
            action.workerToken().fenced().set(true);
            Thread worker = action.worker();
            if (worker != null) {
                worker.interrupt();
            }
            action.settlement().complete(new ImportSettlement(ImportOutcome.DISPOSED, CLOSED_SAVE_ERROR));
        }
        lingeringImportWorker = null;
    }

    private void showServerCreationMenu(Component invoker) {
        if (!disposed && invoker.isShowing()) {
            SwingUtilities.updateComponentTreeUI(serverCreationMenu);
            serverCreationMenu.show(invoker, 0, invoker.getHeight());
        }
    }

    private void addServer(McpTransportType transport) {
        if (disposed) {
            return;
        }
        finishActiveEditing();
        String id = UUID.randomUUID().toString();
        var server = new McpServerConfiguration(
                id,
                "New Server",
                uniqueModelId("New Server", id),
                true,
                false,
                transport,
                "",
                "",
                emptyList(),
                emptyList(),
                emptyList(),
                false,
                emptySet()
        );
        serverModel.addElement(server);
        unstableModelIdServerIds.add(id);
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
        headerEditor.settleEditingForRemoval();
        environmentEditor.settleEditingForRemoval();
        finishActiveEditing();
        int index = serverList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        McpServerConfiguration removed;
        updating = true;
        try {
            removed = serverModel.remove(index);
            if (serverModel.isEmpty()) {
                serverList.clearSelection();
            } else {
                serverList.setSelectedIndex(min(index, serverModel.size() - 1));
            }
        } finally {
            updating = false;
        }
        wipeServerReplacements(removed);
        disabledTools.remove(removed.id());
        lastTools.remove(removed.id());
        formattedSchemas.remove(removed.id());
        toolStates.remove(removed.id());
        unstableModelIdServerIds.remove(removed.id());
        toolModel.setTools("", emptyList(), false);
        toolTable.clearSelection();
        editingServerId = null;
        markDraftMutation();
        if (serverModel.isEmpty()) {
            clearEditor();
            emptyAddButton.requestFocusInWindow();
        } else {
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
            if (Strings.CI.contains(settingsDisplayName(server), query)) {
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
            enabledBox.setSelected(server.enabled());
            automaticBox.setSelected(server.automatic());
            endpointField.setText(server.endpoint());
            executableField.setText(server.executable());
            argumentsEditor.load(server.arguments());
            headerEditor.load(server.headers());
            environmentEditor.load(server.environment());
            longRunningBox.setSelected(server.longRunning());
            editingServerId = server.id();
            transportCardsLayout.show(transportCards, server.transport().name());
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
        var updated = new McpServerConfiguration(
                previous.id(),
                nameField.getText(),
                unstableModelIdServerIds.contains(previous.id())
                        ? uniqueModelId(nameField.getText(), previous.id())
                        : previous.modelId(),
                enabledBox.isSelected(),
                automaticBox.isSelected(),
                previous.transport(),
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
        markDraftMutation(true);
    }

    private void markDraftMutation(boolean reportCancellation) {
        if (updating || disposed) {
            return;
        }
        draftRevision++;
        invalidDraftDirty = invalidBase;
        if (invalidBase) {
            invalidReplacementConfirmed = false;
        }
        clearValidationOutlines();
        cancelVerification(reportCancellation);
        refreshActionStates();
    }

    private void cancelVerification(boolean mutation) {
        boolean running = verifyCancellation.get().compareAndSet(false, true);
        verificationRunning = false;
        repairRunning = false;
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
        refreshActionStates();
    }

    private void refreshActionStates() {
        boolean selected = serverList.getSelectedValue() != null;
        boolean publicationIdle = publicationFinishing || publicationUiSettlement.isDone();
        boolean actionIdle = !verificationRunning && !repairRunning;
        verifyButton.setEnabled(!disposed && selected && publicationIdle && actionIdle);
        replaceInvalidButton.setEnabled(!disposed && invalidBase && publicationIdle && actionIdle);
        showSchemaButton.setEnabled(!disposed && selected && toolTable.getSelectedRow() >= 0);
        removeServerAction.setEnabled(!disposed && selected);
        importJsonItem.setEnabled(canAdmitImport());
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
        Font selectedCodeFont = UIManager.getFont("monospaced.font");
        content.setFont(selectedCodeFont == null
                ? new Font(Font.MONOSPACED, Font.PLAIN, content.getFont().getSize())
                : selectedCodeFont);
        content.setCaretPosition(0);
        content.getAccessibleContext().setAccessibleName("MCP tool input schema");
        content.getAccessibleContext().setAccessibleDescription(
                "Read-only JSON input schema for %s".formatted(tool.name())
        );
        JScrollPane scroll = new JScrollPane(content);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
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
                schemas.put(tool.name(), BoundedUtf8.multilinePresentation(
                        JSON.writePrettyString(tool.inputSchema()),
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

    private boolean editingTransportIs(McpTransportType transport) {
        McpServerConfiguration server = serverById(editingServerId);
        return server != null && server.transport() == transport;
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

    private static String settingsDisplayName(McpServerConfiguration server) {
        return StringUtils.defaultIfBlank(server.name(), "Unnamed server");
    }

    private String uniqueModelId(String name, String serverId) {
        String normalized = Normalizer.normalize(StringUtils.defaultString(name), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_");
        String base = StringUtils.defaultIfBlank(StringUtils.strip(normalized, "_"), "server");
        Set<String> existing = new HashSet<>();
        for (int index = 0; index < serverModel.size(); index++) {
            McpServerConfiguration server = serverModel.get(index);
            if (!server.id().equals(serverId)) {
                existing.add(server.modelId().toLowerCase(Locale.ROOT));
            }
        }
        String candidate = StringUtils.left(base, 48);
        int suffix = 2;
        while (existing.contains(candidate.toLowerCase(Locale.ROOT))) {
            String suffixText = "_%d".formatted(suffix++);
            candidate = "%s%s".formatted(StringUtils.left(base, 48 - suffixText.length()), suffixText);
        }
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
        JPanel heading = new JPanel(new BorderLayout());
        JLabel label = new JLabel(text);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "h4");
        heading.add(label, BorderLayout.CENTER);
        heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
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

    private static Action toolbarAction(String name, Runnable callback) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent event) {
                callback.run();
            }
        };
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
        private final Action addAction = toolbarAction("Add argument", this::addArgument);
        private final Action removeAction = toolbarAction("Remove selected argument", this::removeArgument);
        private final Action moveUpAction = toolbarAction("Move selected argument up", () -> moveArgument(-1));
        private final Action moveDownAction = toolbarAction("Move selected argument down", () -> moveArgument(1));
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
            var toolbar = new ActionToolbar();
            toolbar.addIconAction(addAction, ActionIcon.ADD);
            toolbar.addIconAction(removeAction, ActionIcon.REMOVE);
            toolbar.addIconAction(moveUpAction, ActionIcon.MOVE_UP);
            toolbar.addIconAction(moveDownAction, ActionIcon.MOVE_DOWN);
            add(new ListTableActionPanel(
                    table,
                    toolbar,
                    ToolbarPlacement.TOP,
                    "No arguments"
            ), BorderLayout.CENTER);
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
            boolean active = !disposed && editingTransportIs(McpTransportType.STDIO);
            addAction.setEnabled(active);
            removeAction.setEnabled(active && row >= 0);
            moveUpAction.setEnabled(active && row > 0);
            moveDownAction.setEnabled(active && row >= 0 && row < model.getRowCount() - 1);
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
        private final CredentialPasswordCellEditor valueEditor;
        private final Action addAction;
        private final Action removeAction;
        private boolean loading;

        private CredentialRowsEditor(String accessibleName) {
            valueEditor = new CredentialPasswordCellEditor("%s value".formatted(accessibleName));
            addAction = toolbarAction("Add a row to %s".formatted(accessibleName), this::addRow);
            removeAction = toolbarAction(
                    "Remove the selected row from %s".formatted(accessibleName),
                    this::removeRow
            );
            JTextField nameEditorField = new JTextField();
            setLayout(new BorderLayout(4, 4));
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.getColumnModel().getColumn(0).setCellRenderer(new LiteralStringRenderer());
            table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(nameEditorField));
            table.getColumnModel().getColumn(1).setCellRenderer(new CredentialValueRenderer());
            table.getColumnModel().getColumn(1).setCellEditor(valueEditor);
            table.getAccessibleContext().setAccessibleName(accessibleName);
            table.getAccessibleContext().setAccessibleDescription(
                    "Editable credential names and masked values. A fixed mask means a credential is available."
            );
            nameEditorField.getAccessibleContext().setAccessibleName("%s name".formatted(accessibleName));
            nameEditorField.getDocument().addDocumentListener(documentListener(() -> {
                if (!loading && table.isEditing() && table.getEditingColumn() == 0) {
                    markDraftMutation();
                }
            }));
            table.setPreferredScrollableViewportSize(new Dimension(0, 82));
            var toolbar = new ActionToolbar();
            toolbar.addIconAction(addAction, ActionIcon.ADD);
            toolbar.addIconAction(removeAction, ActionIcon.REMOVE);
            add(new ListTableActionPanel(
                    table,
                    toolbar,
                    ToolbarPlacement.TOP,
                    "No rows"
            ), BorderLayout.CENTER);
            table.getSelectionModel().addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting() && !loading) {
                    stopEditing();
                }
                refreshActionStates();
            });
        }

        private void load(List<McpSecretReference> source) {
            loading = true;
            try {
                cancelEditing();
                model.setRows(source);
                if (!source.isEmpty()) {
                    table.setRowSelectionInterval(0, 0);
                } else {
                    table.clearSelection();
                }
            } finally {
                loading = false;
            }
            refreshActionStates();
        }

        private List<McpSecretReference> rows() {
            return model.rows();
        }

        private void addRow() {
            finishActiveEditing();
            var row = new McpSecretReference(UUID.randomUUID().toString(), "", "");
            int index = model.addRow(row);
            table.setRowSelectionInterval(index, index);
            table.editCellAt(index, 0);
            Component editor = table.getEditorComponent();
            if (editor != null) {
                editor.requestFocusInWindow();
            }
            markDraftMutation();
            refreshActionStates();
        }

        private void removeRow() {
            settleEditingForRemoval();
            int selected = table.getSelectedRow();
            McpSecretReference row = model.rowAt(selected);
            if (row == null) {
                return;
            }
            char[] replacement = replacementSecrets.remove(row.rowId());
            if (replacement != null) {
                fill(replacement, '\0');
            }
            model.removeRow(selected);
            if (model.getRowCount() > 0) {
                int next = min(selected, model.getRowCount() - 1);
                table.setRowSelectionInterval(next, next);
            } else {
                table.clearSelection();
            }
            markDraftMutation();
            commitServer(editingServerId);
            refreshActionStates();
        }

        private void settleEditingForRemoval() {
            if (!table.isEditing()) {
                return;
            }
            if (table.getEditingColumn() == 1) {
                table.getCellEditor().cancelCellEditing();
            } else {
                table.getCellEditor().stopCellEditing();
            }
        }

        private void stopEditing() {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
        }

        private void cancelEditing() {
            if (table.isEditing()) {
                table.getCellEditor().cancelCellEditing();
            }
        }

        private void fireRowChanged(String rowId) {
            int index = model.indexOf(rowId);
            if (index >= 0) {
                model.fireTableRowsUpdated(index, index);
            }
        }

        private void refreshActionStates() {
            boolean visibleTransport = this == headerEditor
                    ? editingTransportIs(McpTransportType.STREAMABLE_HTTP)
                    : editingTransportIs(McpTransportType.STDIO);
            boolean active = !disposed && visibleTransport;
            table.setEnabled(active);
            addAction.setEnabled(active);
            removeAction.setEnabled(active && model.rowAt(table.getSelectedRow()) != null);
        }

        private void disposeEditor() {
            cancelEditing();
            valueEditor.clearPasswordField();
            model.setRows(emptyList());
            table.clearSelection();
        }

        private final class CredentialPasswordCellEditor extends AbstractCellEditor implements TableCellEditor {
            private final JPasswordField field = new JPasswordField();
            private String editingRowId;
            private boolean clearing;

            private CredentialPasswordCellEditor(String accessibleName) {
                field.putClientProperty(
                        FlatClientProperties.PLACEHOLDER_TEXT,
                        "Leave blank to retain the current value"
                );
                field.getAccessibleContext().setAccessibleName(accessibleName);
                field.getDocument().addDocumentListener(documentListener(() -> {
                    if (!clearing && !loading && table.isEditing() && table.getEditingColumn() == 1) {
                        markDraftMutation();
                    }
                }));
                field.addActionListener(event -> stopCellEditing());
                field.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "cancelCredentialEditing");
                field.getActionMap().put("cancelCredentialEditing", new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        cancelCellEditing();
                    }
                });
            }

            @Override
            public Component getTableCellEditorComponent(
                    JTable editorTable,
                    Object value,
                    boolean selected,
                    int row,
                    int column
            ) {
                clearPasswordField();
                McpSecretReference credential = model.rowAt(row);
                editingRowId = credential == null ? null : credential.rowId();
                boolean available = credential != null && (replacementSecrets.containsKey(credential.rowId())
                        || StringUtils.isNotBlank(credential.secretId()));
                String guidance = available
                        ? "Leave blank to retain the current value"
                        : "Enter a credential value; this server cannot be saved until one is entered.";
                field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, guidance);
                field.getAccessibleContext().setAccessibleDescription(guidance);
                return field;
            }

            @Override
            public Object getCellEditorValue() {
                return "";
            }

            @Override
            public boolean stopCellEditing() {
                char[] value = field.getPassword();
                boolean replacementChanged = value.length > 0
                        && !CharBuffer.wrap(value).chars().allMatch(Character::isWhitespace);
                boolean transferred = false;
                try {
                    if (replacementChanged && model.indexOf(editingRowId) >= 0) {
                        char[] previous = replacementSecrets.put(editingRowId, value);
                        transferred = true;
                        if (previous != null) {
                            fill(previous, '\0');
                        }
                        fireRowChanged(editingRowId);
                    }
                    return super.stopCellEditing();
                } finally {
                    if (!transferred) {
                        fill(value, '\0');
                    }
                    clearPasswordField();
                    editingRowId = null;
                }
            }

            @Override
            public void cancelCellEditing() {
                clearPasswordField();
                editingRowId = null;
                super.cancelCellEditing();
            }

            private void clearPasswordField() {
                clearing = true;
                try {
                    char[] value = field.getPassword();
                    fill(value, '\0');
                    field.setText("");
                } finally {
                    clearing = false;
                }
            }
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
            return column == 0 ? "Name" : "Value";
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
            McpSecretReference row = rows.get(rowIndex);
            if (columnIndex == 0) {
                return row.key();
            }
            boolean available = replacementSecrets.containsKey(row.rowId())
                    || StringUtils.isNotBlank(row.secretId());
            return available ? CREDENTIAL_MASK : "";
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != 0 || rowIndex < 0 || rowIndex >= rows.size()) {
                return;
            }
            McpSecretReference previous = rows.get(rowIndex);
            String name = Objects.toString(value, "");
            if (Objects.equals(previous.key(), name)) {
                return;
            }
            rows.set(rowIndex, new McpSecretReference(previous.rowId(), name, previous.secretId()));
            fireTableCellUpdated(rowIndex, columnIndex);
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
            if (rowId == null) {
                return -1;
            }
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).rowId().equals(rowId)) {
                    return index;
                }
            }
            return -1;
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

    private static final class CredentialValueRenderer extends LiteralStringRenderer {
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
            boolean available = StringUtils.isNotEmpty(Objects.toString(value, ""));
            String state = available ? "credential available" : "credential missing";
            String name = Objects.toString(table.getValueAt(row, 0), "");
            label.setText(available ? CREDENTIAL_MASK : "");
            label.setToolTipText(available
                    ? "Credential available. Edit to replace it."
                    : "No credential value. Edit to add one.");
            label.getAccessibleContext().setAccessibleName(
                    "%s, %s".formatted(StringUtils.defaultIfBlank(name, "Unnamed credential"), state)
            );
            label.getAccessibleContext().setAccessibleDescription(label.getToolTipText());
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
            name.setText(settingsDisplayName(value));
            detail.setText("%s · %s".formatted(transport, state));
            setOpaque(true);
            name.setOpaque(false);
            detail.setOpaque(false);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            name.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            detail.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            String accessible = "%s, %s, %s".formatted(settingsDisplayName(value), transport, state);
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
            View root = getUI().getRootView(this);
            root.setSize(availableTextWidth(preferred, insets), Integer.MAX_VALUE);
            int visualRows = max(1, (int) ceil(root.getPreferredSpan(View.Y_AXIS) / metrics.getHeight()));
            int visibleRows = min(MAX_STATUS_ROWS, visualRows);
            return new Dimension(preferred.width, metrics.getHeight() * visibleRows + insets.top + insets.bottom);
        }

        private int availableTextWidth(Dimension preferred, Insets textInsets) {
            int horizontalInsets = textInsets.left + textInsets.right;
            if (getParent() instanceof JViewport viewport && viewport.getExtentSize().width > 0) {
                return max(1, viewport.getExtentSize().width - horizontalInsets);
            }
            if (getWidth() > 0) {
                return max(1, getWidth() - horizontalInsets);
            }
            for (Container ancestor = getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                if (ancestor instanceof JComponent component) {
                    Insets insets = component.getInsets();
                    horizontalInsets += insets.left + insets.right;
                }
                if (ancestor.getWidth() > 0) {
                    return max(1, ancestor.getWidth() - horizontalInsets);
                }
            }
            return max(1, preferred.width - horizontalInsets);
        }
    }

    private record StableManagerObservation(
            long generation,
            McpConfigurationLoadResult loadResult,
            String cleanupStatus
    ) {
    }

    private enum VerifyPreflightPhase {
        INITIAL,
        REPAIR_CONFIRMED,
        CONFIRMATIONS_COMPLETE
    }

    private record AppliedSnapshot(long generation, McpConfiguration configuration) {
    }

    private enum ImportOutcome {
        NO_IMPORT,
        APPLIED,
        FAILED,
        TIMED_OUT,
        DISPOSED
    }

    private record ImportSettlement(ImportOutcome outcome, String diagnostic) {
    }

    private record WorkerToken(AtomicBoolean fenced) {
        private WorkerToken() {
            this(new AtomicBoolean());
        }
    }

    private static final class ImportAction {
        private final WorkerToken workerToken = new WorkerToken();
        private final CompletableFuture<ImportSettlement> settlement = new CompletableFuture<>();
        private Timer timer;
        private Thread worker;
        private boolean editorsSettledBySave;

        private WorkerToken workerToken() {
            return workerToken;
        }

        private CompletableFuture<ImportSettlement> settlement() {
            return settlement;
        }

        private Timer timer() {
            return timer;
        }

        private void setTimer(Timer timer) {
            this.timer = timer;
        }

        private Thread worker() {
            return worker;
        }

        private void setWorker(Thread worker) {
            this.worker = worker;
        }

        private boolean editorsSettledBySave() {
            return editorsSettledBySave;
        }

        private void markEditorsSettledBySave() {
            editorsSettledBySave = true;
        }
    }

    private static final class WorkerCompletion implements AutoCloseable {
        private final McpJsonImporter.ImportResult result;
        private final McpJsonImporter.ImportException error;

        private WorkerCompletion(
                McpJsonImporter.ImportResult result,
                McpJsonImporter.ImportException error
        ) {
            this.result = result;
            this.error = error;
        }

        private static WorkerCompletion success(McpJsonImporter.ImportResult result) {
            return new WorkerCompletion(result, null);
        }

        private static WorkerCompletion failure(McpJsonImporter.ImportException error) {
            return new WorkerCompletion(null, error);
        }

        private McpJsonImporter.ImportResult result() {
            return result;
        }

        private McpJsonImporter.ImportException error() {
            return error;
        }

        @Override
        public void close() {
            if (result != null) {
                result.close();
            }
        }
    }

    private static final class SaveAction {
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private final CompletableFuture<ImportSettlement> importSettlement;
        private boolean importObserved;
        private boolean submitted;

        private SaveAction(CompletableFuture<ImportSettlement> importSettlement) {
            this.importSettlement = importSettlement;
        }

        private CompletableFuture<Boolean> result() {
            return result;
        }

        private CompletableFuture<ImportSettlement> importSettlement() {
            return importSettlement;
        }

        private boolean importObserved() {
            return importObserved;
        }

        private void markImportObserved() {
            importObserved = true;
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
            boolean repair,
            Map<String, char[]> submittedSecrets
    ) {
        @Override
        public String toString() {
            return "PublicationAttempt[request=%d, revision=%d, selectedServerId=%s, repair=%s, submittedSecrets=****]"
                    .formatted(request, revision, selectedServerId, repair);
        }
    }

    private record PublicationCompletion(McpApplyResult applyResult, Throwable error) {
    }

    private record FormattedVerification(McpVerificationResult result, Map<String, String> schemas) {
    }

    private record ToolSnapshotState(long generation, boolean stale) {
    }
}
