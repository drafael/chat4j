package com.github.drafael.chat4j.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.chat.render.BoundedUtf8;
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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

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

    private final McpManager manager;
    private final DefaultListModel<McpServerConfiguration> serverModel = new DefaultListModel<>();
    private final JList<McpServerConfiguration> serverList = new JList<>(serverModel);
    private final JTextField searchField = new JTextField();
    private final JTextField nameField = new JTextField();
    private final JTextField modelIdField = new JTextField();
    private final JCheckBox enabledBox = new JCheckBox("Enabled");
    private final JCheckBox automaticBox = new JCheckBox("Run tools automatically");
    private final JComboBox<McpTransportType> transportBox = new JComboBox<>(McpTransportType.values());
    private final JTextField endpointField = new JTextField();
    private final JTextField executableField = new JTextField();
    private final ArgumentListEditor argumentsEditor = new ArgumentListEditor();
    private final CredentialRowsEditor headerEditor = new CredentialRowsEditor("HTTP headers");
    private final CredentialRowsEditor environmentEditor = new CredentialRowsEditor("Environment variables");
    private final JCheckBox longRunningBox = new JCheckBox("Long-running");
    private final JButton verifyButton = new JButton("Verify / View Tools");
    private final JButton replaceInvalidButton = new JButton("Replace / Recreate invalid configuration");
    private final JLabel statusLabel = new JLabel(" ");
    private final JPanel toolCards = new JPanel();
    private final CardLayout transportCards = new CardLayout();
    private final JPanel transportFields = new JPanel(transportCards);
    private final Map<String, List<McpDiscoveredTool>> lastTools = new HashMap<>();
    private final Map<String, Map<String, String>> formattedSchemas = new HashMap<>();
    private final Map<String, Set<String>> disabledTools = new HashMap<>();
    private final Map<String, char[]> replacementSecrets = new HashMap<>();
    private final AtomicReference<AtomicBoolean> verifyCancellation =
            new AtomicReference<>(new AtomicBoolean());
    private boolean updating;
    private String editingServerId;
    private boolean disposed;
    private boolean invalidBase;
    private boolean invalidReplacementConfirmed;
    private boolean invalidDraftDirty;
    private long requestIdentity;
    private long draftRevision;
    private CompletableFuture<Void> verificationUiSettlement = CompletableFuture.completedFuture(null);
    private String lastSaveError = "";

    public McpPanel(@NonNull McpManager manager) {
        this.manager = manager;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(createContent(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        loadInitialState();
        bindListeners();
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
        if (!verificationUiSettlement.isDone()) {
            return saveAfterVerificationSettlement();
        }
        commitEditor();
        if (invalidBase && !invalidDraftDirty) {
            return CompletableFuture.completedFuture(true);
        }
        if (invalidBase && !invalidReplacementConfirmed) {
            lastSaveError = "Confirm replacement of the invalid MCP configuration first.";
            return CompletableFuture.completedFuture(false);
        }
        if (!confirmCleartextEndpoints()) {
            lastSaveError = "Cleartext MCP endpoint was not accepted.";
            return CompletableFuture.completedFuture(false);
        }
        try {
            McpConfigurationValidator.validate(snapshotConfiguration());
        } catch (IllegalArgumentException e) {
            lastSaveError = e.getMessage();
            statusLabel.setText(lastSaveError);
            return CompletableFuture.completedFuture(false);
        }
        McpConfigurationDraft draft = snapshotDraft();
        CompletableFuture<McpApplyResult> publication = invalidBase
                ? manager.replaceInvalidAndApply(draft)
                : manager.saveAndApply(draft);
        clearReplacementSecrets();
        CompletableFuture<Boolean> saved = new CompletableFuture<>();
        publication.whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
            if (disposed) {
                saved.complete(error == null && result != null && result.outcome().applied());
                return;
            }
            if (error != null) {
                lastSaveError = StringUtils.defaultIfBlank(error.getMessage(), "Could not save MCP settings.");
                saved.complete(false);
                return;
            }
            lastSaveError = result.message();
            if (StringUtils.isNotBlank(result.message())) {
                statusLabel.setText(result.message());
            }
            if (result.outcome().applied()) {
                invalidBase = false;
                invalidReplacementConfirmed = false;
                invalidDraftDirty = false;
                replaceInvalidButton.setVisible(false);
                applySavedConfiguration(result.configuration());
                saved.complete(true);
                return;
            }
            saved.complete(false);
        }));
        return saved;
    }

    @Override
    public String lastSaveError() {
        return lastSaveError;
    }

    @Override
    public String settingsSectionName() {
        return "MCP";
    }

    public void disposePanel() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::disposePanel);
            return;
        }
        disposed = true;
        verifyCancellation.get().set(true);
        requestIdentity++;
        argumentsEditor.disposeEditor();
        replacementSecrets.values().forEach(value -> fill(value, '\0'));
        replacementSecrets.clear();
        headerEditor.disposeEditor();
        environmentEditor.disposeEditor();
    }

    private JSplitPane createContent() {
        JPanel left = new JPanel(new BorderLayout(6, 6));
        searchField.putClientProperty("JTextField.placeholderText", "Search servers");
        left.add(searchField, BorderLayout.NORTH);
        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverList.setCellRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value.displayName());
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        left.add(new JScrollPane(serverList), BorderLayout.CENTER);
        JPanel serverActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addButton = new JButton("+");
        JButton removeButton = new JButton("−");
        addButton.addActionListener(event -> addServer());
        removeButton.addActionListener(event -> removeServer());
        serverActions.add(addButton);
        serverActions.add(removeButton);
        left.add(serverActions, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(8, 8));
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(field("Name", nameField));
        form.add(field("Model ID", modelIdField));
        form.add(enabledBox);
        form.add(automaticBox);
        form.add(field("Transport", transportBox));

        JPanel http = new JPanel(new BorderLayout(4, 4));
        JPanel httpContent = new JPanel();
        httpContent.setLayout(new BoxLayout(httpContent, BoxLayout.Y_AXIS));
        httpContent.add(field("Streamable HTTP endpoint", endpointField));
        httpContent.add(headerEditor);
        httpContent.add(new JLabel("Credentials are encrypted; URL queries are stored as plaintext."));
        http.add(httpContent, BorderLayout.CENTER);
        JPanel stdio = new JPanel();
        stdio.setLayout(new BoxLayout(stdio, BoxLayout.Y_AXIS));
        stdio.add(field("Executable", executableField));
        stdio.add(new JLabel("Arguments remain ordered; commands are launched without a shell."));
        stdio.add(argumentsEditor);
        stdio.add(environmentEditor);
        stdio.add(longRunningBox);
        transportFields.add(stdio, McpTransportType.STDIO.name());
        transportFields.add(http, McpTransportType.STREAMABLE_HTTP.name());
        form.add(transportFields);
        form.add(Box.createVerticalStrut(8));
        JPanel verificationActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        verificationActions.add(verifyButton);
        verificationActions.add(replaceInvalidButton);
        form.add(verificationActions);
        right.add(new JScrollPane(form), BorderLayout.NORTH);
        toolCards.setLayout(new BoxLayout(toolCards, BoxLayout.Y_AXIS));
        right.add(new JScrollPane(toolCards), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(230);
        split.setResizeWeight(0);
        split.setPreferredSize(new Dimension(760, 520));
        return split;
    }

    private JPanel field(String label, Component component) {
        JPanel panel = new JPanel(new BorderLayout(8, 2));
        panel.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        JLabel fieldLabel = new JLabel(label);
        fieldLabel.setPreferredSize(new Dimension(150, fieldLabel.getPreferredSize().height));
        panel.add(fieldLabel, BorderLayout.WEST);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private void loadInitialState() {
        McpConfigurationLoadResult result = manager.loadResult();
        McpConfiguration configuration = switch (result) {
            case McpConfigurationLoadResult.Missing missing -> missing.configuration();
            case McpConfigurationLoadResult.Valid valid -> valid.configuration();
            case McpConfigurationLoadResult.Invalid invalid -> {
                invalidBase = true;
                statusLabel.setText(invalid.message());
                yield McpConfiguration.empty();
            }
        };
        replaceInvalidButton.setVisible(invalidBase);
        configuration.servers().forEach(server -> {
            serverModel.addElement(server);
            disabledTools.put(server.id(), new LinkedHashSet<>(server.disabledTools()));
        });
        if (!serverModel.isEmpty()) {
            serverList.setSelectedIndex(0);
            loadSelected();
        } else {
            clearEditor();
        }
        if (!invalidBase && StringUtils.isNotBlank(manager.cleanupStatus())) {
            statusLabel.setText(manager.cleanupStatus());
        }
    }

    private void bindListeners() {
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                selectSearchMatch();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                selectSearchMatch();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                selectSearchMatch();
            }
        });
        serverList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updating) {
                verifyCancellation.get().set(true);
                requestIdentity++;
                verifyButton.setEnabled(true);
                loadSelected();
            }
        });
        transportBox.addActionListener(event -> {
            McpTransportType transport = (McpTransportType) transportBox.getSelectedItem();
            if (transport != null) {
                transportCards.show(transportFields, transport.name());
                if (!updating && transport == McpTransportType.STREAMABLE_HTTP) {
                    longRunningBox.setSelected(false);
                }
            }
        });
        automaticBox.addActionListener(event -> {
            if (!updating && automaticBox.isSelected()) {
                int answer = JOptionPane.showConfirmDialog(
                        this,
                        "Automatic MCP tools can act with your user permissions. Enable only for trusted servers.",
                        "Enable Automatic MCP Tools?",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );
                if (answer != JOptionPane.OK_OPTION) {
                    automaticBox.setSelected(false);
                }
            }
        });
        verifyButton.addActionListener(event -> verifySelected());
        replaceInvalidButton.addActionListener(event -> replaceInvalidConfiguration());
        bindMutationListeners();
    }

    private void bindMutationListeners() {
        List.of(nameField, modelIdField, endpointField, executableField).forEach(field ->
                field.getDocument().addDocumentListener(mutationDocumentListener()));
        enabledBox.addActionListener(event -> markDraftMutation());
        automaticBox.addActionListener(event -> markDraftMutation());
        transportBox.addActionListener(event -> markDraftMutation());
        longRunningBox.addActionListener(event -> markDraftMutation());
        argumentsEditor.bindMutationListeners();
        headerEditor.bindMutationListeners();
        environmentEditor.bindMutationListeners();
    }

    private DocumentListener mutationDocumentListener() {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                markDraftMutation();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                markDraftMutation();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                markDraftMutation();
            }
        };
    }

    private void markDraftMutation() {
        if (updating || disposed) {
            return;
        }
        draftRevision++;
        requestIdentity++;
        verifyCancellation.get().set(true);
        verifyButton.setEnabled(true);
        invalidDraftDirty = invalidBase;
    }

    private boolean confirmCleartextEndpoints() {
        return snapshotConfiguration().servers().stream()
                .filter(server -> server.transport() == McpTransportType.STREAMABLE_HTTP)
                .filter(server -> {
                    try {
                        URI endpoint = URI.create(server.endpoint());
                        String host = StringUtils.defaultString(endpoint.getHost());
                        return Strings.CI.equals("http", endpoint.getScheme())
                                && !(Strings.CI.equals("localhost", host) || Strings.CS.equals("127.0.0.1", host)
                                || Strings.CS.equals("::1", host));
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                })
                .allMatch(server -> JOptionPane.showConfirmDialog(
                        this,
                        "%s uses cleartext HTTP. Continue?".formatted(server.displayName()),
                        "Cleartext MCP Endpoint",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE
                ) == JOptionPane.OK_OPTION);
    }

    private void selectSearchMatch() {
        String query = searchField.getText().trim();
        if (StringUtils.isEmpty(query)) {
            return;
        }
        for (int index = 0; index < serverModel.size(); index++) {
            McpServerConfiguration server = serverModel.get(index);
            if (Strings.CI.contains(server.displayName(), query)
                    || Strings.CI.contains(server.modelId(), query)) {
                serverList.setSelectedIndex(index);
                serverList.ensureIndexIsVisible(index);
                return;
            }
        }
    }

    private void addServer() {
        commitEditor();
        String id = UUID.randomUUID().toString();
        McpServerConfiguration server = new McpServerConfiguration(
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
        markDraftMutation();
        invalidDraftDirty = invalidBase;
        disabledTools.put(id, new LinkedHashSet<>());
        serverList.setSelectedIndex(serverModel.size() - 1);
    }

    private void removeServer() {
        commitEditor();
        int index = serverList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        McpServerConfiguration removed = serverModel.remove(index);
        markDraftMutation();
        concat(removed.headers().stream(), removed.environment().stream())
                .map(McpSecretReference::rowId)
                .map(replacementSecrets::remove)
                .filter(Objects::nonNull)
                .forEach(value -> fill(value, '\0'));
        invalidDraftDirty = invalidBase;
        disabledTools.remove(removed.id());
        lastTools.remove(removed.id());
        formattedSchemas.remove(removed.id());
        if (!serverModel.isEmpty()) {
            serverList.setSelectedIndex(min(index, serverModel.size() - 1));
        } else {
            clearEditor();
        }
    }

    private void loadSelected() {
        loadSelected(true);
    }

    private void loadSelected(boolean refreshToolCards) {
        commitEditor();
        McpServerConfiguration server = serverList.getSelectedValue();
        if (server == null) {
            clearEditor();
            return;
        }
        updating = true;
        try {
            nameField.setText(server.name());
            modelIdField.setText(server.modelId());
            enabledBox.setSelected(server.enabled());
            automaticBox.setSelected(server.automatic());
            transportBox.setSelectedItem(server.transport());
            endpointField.setText(server.endpoint());
            executableField.setText(server.executable());
            argumentsEditor.load(server.arguments());
            longRunningBox.setSelected(server.longRunning());
            headerEditor.load(server.headers());
            environmentEditor.load(server.environment());
            editingServerId = server.id();
            if (refreshToolCards) {
                showTools(server.id(), false);
            }
        } finally {
            updating = false;
        }
    }

    private void commitEditor() {
        if (updating || editingServerId == null) {
            return;
        }
        int index = indexOfServer(editingServerId);
        if (index < 0) {
            return;
        }
        McpServerConfiguration previous = serverModel.get(index);
        McpServerConfiguration updated = new McpServerConfiguration(
                previous.id(),
                nameField.getText(),
                modelIdField.getText(),
                enabledBox.isSelected(),
                automaticBox.isSelected(),
                (McpTransportType) transportBox.getSelectedItem(),
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

    private int indexOfServer(String serverId) {
        for (int index = 0; index < serverModel.size(); index++) {
            if (serverModel.get(index).id().equals(serverId)) {
                return index;
            }
        }
        return -1;
    }

    private void replaceInvalidConfiguration() {
        if (!invalidBase) {
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
        invalidReplacementConfirmed = true;
        invalidDraftDirty = true;
        savePendingChangesAsync().whenComplete((saved, error) -> SwingUtilities.invokeLater(() -> {
            if (!disposed && (error != null || !Boolean.TRUE.equals(saved))) {
                statusLabel.setText(error == null
                        ? StringUtils.defaultIfBlank(lastSaveError, "Could not repair MCP configuration.")
                        : "Could not repair MCP configuration.");
            }
        }));
    }

    private void verifySelected() {
        if (!verificationUiSettlement.isDone()) {
            CompletableFuture<Void> pending = verificationUiSettlement;
            pending.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
                if (!disposed) {
                    verifySelected();
                }
            }));
            return;
        }
        commitEditor();
        McpServerConfiguration server = serverList.getSelectedValue();
        if (server == null) {
            return;
        }
        if (invalidBase && !invalidReplacementConfirmed) {
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "The existing MCP configuration is invalid. Replace it with this draft?",
                    "Repair MCP Configuration",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
            invalidReplacementConfirmed = true;
        }
        if (!confirmCleartextEndpoints()) {
            return;
        }
        try {
            McpConfigurationValidator.validate(snapshotConfiguration());
        } catch (IllegalArgumentException e) {
            statusLabel.setText(e.getMessage());
            return;
        }
        McpConfigurationDraft draft = snapshotDraft();
        long request = ++requestIdentity;
        long requestedRevision = draftRevision;
        AtomicBoolean cancellation = new AtomicBoolean();
        verifyCancellation.getAndSet(cancellation).set(true);
        verifyButton.setEnabled(false);
        statusLabel.setText("Verifying %s…".formatted(server.displayName()));
        CompletableFuture<McpApplyResult> publication = invalidBase
                ? manager.replaceInvalidAndApply(draft)
                : manager.saveAndApply(draft);
        CompletableFuture<Void> uiSettlement = new CompletableFuture<>();
        verificationUiSettlement = uiSettlement;
        publication.whenComplete((applyResult, publicationError) -> SwingUtilities.invokeLater(() -> {
            try {
                if (!disposed && publicationError == null && applyResult != null
                        && applyResult.outcome().applied() && manager.generation() == applyResult.generation()) {
                    boolean staleDraft = request != requestIdentity || requestedRevision != draftRevision;
                    boolean sameSelection = serverList.getSelectedValue() != null
                            && server.id().equals(serverList.getSelectedValue().id());
                    reconcileAppliedConfiguration(applyResult.configuration(), staleDraft || !sameSelection);
                }
            } finally {
                uiSettlement.complete(null);
            }
        }));
        CompletableFuture<McpVerificationResult> verification = publication.thenCompose(applyResult ->
                uiSettlement.thenCompose(ignored -> manager.verifyAppliedAsync(
                        applyResult,
                        server.id(),
                        cancellation::get
                )));
        clearReplacementSecrets();
        verification.whenComplete((result, error) -> {
            Map<String, String> schemas = result != null && result.verified()
                    ? formatSchemas(result.tools())
                    : emptyMap();
            SwingUtilities.invokeLater(() -> {
                if (disposed) {
                    return;
                }
                boolean currentGeneration = result != null
                        && manager.generation() == result.applyResult().generation();
                boolean staleDraft = request != requestIdentity || requestedRevision != draftRevision;
                boolean sameSelection = serverList.getSelectedValue() != null
                        && server.id().equals(serverList.getSelectedValue().id());
                if (result != null && result.applyResult().outcome().applied() && currentGeneration) {
                    reconcileAppliedConfiguration(result.applyResult().configuration(), staleDraft || !sameSelection);
                }
                if (request != requestIdentity || staleDraft || !sameSelection) {
                    return;
                }
                verifyButton.setEnabled(true);
                if (!currentGeneration && result != null && result.applyResult().outcome().applied()) {
                    statusLabel.setText("Verification result is stale because MCP settings changed.");
                    showTools(server.id(), true);
                    return;
                }
                if (error != null || result == null || !result.verified()) {
                    statusLabel.setText(error != null
                            ? StringUtils.defaultIfBlank(error.getMessage(), "Verification failed.")
                            : result == null
                                    ? "Verification failed."
                                    : StringUtils.defaultIfBlank(
                                            result.verificationError(),
                                            result.applyResult().message()
                                    ));
                    showTools(server.id(), true);
                    return;
                }
                lastTools.put(server.id(), result.tools());
                formattedSchemas.put(server.id(), schemas);
                Set<String> known = result.tools().stream().map(McpDiscoveredTool::name)
                        .collect(toSet());
                disabledTools.computeIfAbsent(server.id(), ignored -> new LinkedHashSet<>()).retainAll(known);
                statusLabel.setText("Verified %d tool(s).".formatted(result.tools().size()));
                showTools(server.id(), false);
            });
        });
    }

    private CompletableFuture<Boolean> saveAfterVerificationSettlement() {
        CompletableFuture<Void> pending = verificationUiSettlement;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        pending.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (disposed) {
                result.complete(false);
                return;
            }
            savePendingChangesAsync().whenComplete((saved, saveError) -> {
                if (saveError == null) {
                    result.complete(saved);
                } else {
                    result.completeExceptionally(saveError);
                }
            });
        }));
        return result;
    }

    private void showTools(String serverId, boolean stale) {
        toolCards.removeAll();
        List<McpDiscoveredTool> tools = lastTools.getOrDefault(serverId, emptyList());
        if (tools.isEmpty()) {
            toolCards.add(new JLabel("No verified tools yet."));
        }
        tools.forEach(tool -> {
            JPanel card = new JPanel(new BorderLayout(4, 4));
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createEtchedBorder(),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)
            ));
            JCheckBox enabled = new JCheckBox(
                    "%s%s".formatted(StringUtils.defaultIfBlank(tool.title(), tool.name()), stale ? " (stale)" : ""),
                    !disabledTools.getOrDefault(serverId, emptySet()).contains(tool.name())
            );
            enabled.addActionListener(event -> {
                Set<String> disabled = disabledTools.computeIfAbsent(serverId, ignored -> new LinkedHashSet<>());
                if (enabled.isSelected()) {
                    disabled.remove(tool.name());
                } else {
                    disabled.add(tool.name());
                }
                markDraftMutation();
            });
            card.add(enabled, BorderLayout.NORTH);
            JTextArea details = new JTextArea("%s\n%s".formatted(tool.name(), tool.description()), 3, 30);
            details.setEditable(false);
            details.setLineWrap(true);
            details.setWrapStyleWord(true);
            card.add(details, BorderLayout.CENTER);
            JButton schemaButton = new JButton("Show input schema");
            JTextArea schema = new JTextArea(6, 30);
            schema.setEditable(false);
            schema.setVisible(false);
            schemaButton.addActionListener(event -> {
                if (StringUtils.isEmpty(schema.getText())) {
                    schema.setText(formattedSchemas
                            .getOrDefault(serverId, emptyMap())
                            .getOrDefault(tool.name(), "Schema could not be displayed."));
                }
                schema.setVisible(!schema.isVisible());
                schemaButton.setText(schema.isVisible() ? "Hide input schema" : "Show input schema");
                card.revalidate();
            });
            JPanel schemaPanel = new JPanel(new BorderLayout());
            schemaPanel.add(schemaButton, BorderLayout.NORTH);
            schemaPanel.add(schema, BorderLayout.CENTER);
            card.add(schemaPanel, BorderLayout.SOUTH);
            toolCards.add(card);
        });
        toolCards.revalidate();
        toolCards.repaint();
    }

    private Map<String, String> formatSchemas(List<McpDiscoveredTool> tools) {
        Map<String, String> result = new HashMap<>();
        tools.forEach(tool -> {
            try {
                result.put(tool.name(), BoundedUtf8.presentation(
                        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tool.inputSchema()),
                        16_384,
                        65_536
                ));
            } catch (Exception e) {
                result.put(tool.name(), "Schema could not be displayed.");
            }
        });
        return Map.copyOf(result);
    }

    private McpConfigurationDraft snapshotDraft() {
        commitEditor();
        return new McpConfigurationDraft(snapshotConfiguration(), replacementSecrets);
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

    private void clearReplacementSecrets() {
        replacementSecrets.values().forEach(value -> fill(value, '\0'));
        replacementSecrets.clear();
    }

    private void reconcileAppliedConfiguration(McpConfiguration configuration, boolean preserveDraft) {
        invalidBase = false;
        invalidReplacementConfirmed = false;
        invalidDraftDirty = false;
        replaceInvalidButton.setVisible(false);
        if (!preserveDraft) {
            applySavedConfiguration(configuration);
            return;
        }
        commitEditor();
        Map<String, McpServerConfiguration> published = configuration.servers().stream()
                .collect(toMap(McpServerConfiguration::id, identity()));
        updating = true;
        try {
            for (int index = 0; index < serverModel.size(); index++) {
                McpServerConfiguration current = serverModel.get(index);
                McpServerConfiguration applied = published.get(current.id());
                if (applied != null) {
                    serverModel.set(index, withReconciledSecrets(current, applied));
                }
            }
        } finally {
            updating = false;
        }
        editingServerId = null;
        loadSelected(false);
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
        return current.stream()
                .map(row -> {
                    String appliedSecretId = secretIds.get(row.rowId());
                    if (appliedSecretId == null) {
                        return row;
                    }
                    String reconciledSecretId = replacementSecrets.containsKey(row.rowId())
                            ? ""
                            : appliedSecretId;
                    return new McpSecretReference(row.rowId(), row.key(), reconciledSecretId);
                })
                .toList();
    }

    private void applySavedConfiguration(McpConfiguration configuration) {
        String selectedId = serverList.getSelectedValue() == null ? null : serverList.getSelectedValue().id();
        clearReplacementSecrets();
        updating = true;
        try {
            serverModel.clear();
            disabledTools.clear();
            configuration.servers().forEach(server -> {
                serverModel.addElement(server);
                disabledTools.put(server.id(), new LinkedHashSet<>(server.disabledTools()));
            });
            int selectedIndex = selectedId == null ? -1 : indexOfServer(selectedId);
            if (selectedIndex < 0 && !serverModel.isEmpty()) {
                selectedIndex = 0;
            }
            if (selectedIndex >= 0) {
                serverList.setSelectedIndex(selectedIndex);
            }
        } finally {
            updating = false;
        }
        editingServerId = null;
        loadSelected();
    }

    private void clearEditor() {
        updating = true;
        try {
            nameField.setText("");
            modelIdField.setText("");
            endpointField.setText("");
            executableField.setText("");
            argumentsEditor.load(emptyList());
            enabledBox.setSelected(false);
            automaticBox.setSelected(false);
            headerEditor.load(emptyList());
            environmentEditor.load(emptyList());
            editingServerId = null;
            toolCards.removeAll();
        } finally {
            updating = false;
        }
    }

    private final class ArgumentListEditor extends JPanel {
        private final DefaultListModel<String> arguments = new DefaultListModel<>();
        private final JList<String> argumentList = new JList<>(arguments);
        private final JTextField argumentField = new JTextField();
        private boolean loading;

        private ArgumentListEditor() {
            setLayout(new BorderLayout(4, 4));
            setBorder(BorderFactory.createTitledBorder("Ordered arguments"));
            argumentList.setVisibleRowCount(4);
            add(new JScrollPane(argumentList), BorderLayout.CENTER);
            add(argumentField, BorderLayout.NORTH);
            JButton add = new JButton("Add");
            JButton replace = new JButton("Replace");
            JButton remove = new JButton("Remove");
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            actions.add(add);
            actions.add(replace);
            actions.add(remove);
            add(actions, BorderLayout.SOUTH);
            argumentList.addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting() && argumentList.getSelectedIndex() >= 0) {
                    loading = true;
                    try {
                        argumentField.setText(arguments.get(argumentList.getSelectedIndex()));
                    } finally {
                        loading = false;
                    }
                }
            });
            add.addActionListener(event -> {
                arguments.addElement(argumentField.getText());
                argumentList.setSelectedIndex(arguments.size() - 1);
                markDraftMutation();
            });
            replace.addActionListener(event -> {
                int index = argumentList.getSelectedIndex();
                if (index >= 0) {
                    arguments.set(index, argumentField.getText());
                    markDraftMutation();
                }
            });
            remove.addActionListener(event -> {
                int index = argumentList.getSelectedIndex();
                if (index >= 0) {
                    arguments.remove(index);
                    markDraftMutation();
                }
            });
        }

        private void bindMutationListeners() {
            argumentField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    markArgumentMutation();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    markArgumentMutation();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    markArgumentMutation();
                }
            });
        }

        private void markArgumentMutation() {
            if (!loading) {
                markDraftMutation();
            }
        }

        private void load(List<String> source) {
            loading = true;
            try {
                arguments.clear();
                source.forEach(arguments::addElement);
                argumentField.setText("");
            } finally {
                loading = false;
            }
        }

        private List<String> arguments() {
            List<String> result = new ArrayList<>();
            for (int index = 0; index < arguments.size(); index++) {
                result.add(arguments.get(index));
            }
            return List.copyOf(result);
        }

        private void disposeEditor() {
            argumentField.setText("");
            arguments.clear();
        }
    }

    private final class CredentialRowsEditor extends JPanel {
        private final DefaultListModel<McpSecretReference> rows = new DefaultListModel<>();
        private final JList<McpSecretReference> rowList = new JList<>(rows);
        private final JTextField keyField = new JTextField();
        private final JPasswordField valueField = new JPasswordField();
        private boolean loading;

        private CredentialRowsEditor(String title) {
            setLayout(new BorderLayout(4, 4));
            setBorder(BorderFactory.createTitledBorder(title));
            rowList.setVisibleRowCount(3);
            rowList.setCellRenderer((list, value, index, selected, focus) -> {
                JLabel label = new JLabel("%s  ••••••••".formatted(value.key()));
                label.setOpaque(true);
                label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
                label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
                return label;
            });
            add(new JScrollPane(rowList), BorderLayout.CENTER);

            JPanel editor = new JPanel(new GridLayout(2, 2, 4, 4));
            editor.add(new JLabel("Name"));
            editor.add(keyField);
            editor.add(new JLabel("New value"));
            valueField.putClientProperty("JTextField.placeholderText", "Leave blank to retain saved value");
            editor.add(valueField);
            add(editor, BorderLayout.NORTH);

            JButton add = new JButton("Add");
            JButton apply = new JButton("Apply");
            JButton remove = new JButton("Remove/Clear");
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            actions.add(add);
            actions.add(apply);
            actions.add(remove);
            add(actions, BorderLayout.SOUTH);

            rowList.addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting()) {
                    loadSelectedRow();
                }
            });
            add.addActionListener(event -> {
                McpSecretReference row = new McpSecretReference(UUID.randomUUID().toString(), "", "");
                rows.addElement(row);
                rowList.setSelectedIndex(rows.size() - 1);
                keyField.requestFocusInWindow();
                markDraftMutation();
            });
            apply.addActionListener(event -> applySelectedRow());
            remove.addActionListener(event -> removeSelectedRow());
        }

        private void bindMutationListeners() {
            DocumentListener listener = new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    markCredentialMutation();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    markCredentialMutation();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    markCredentialMutation();
                }
            };
            keyField.getDocument().addDocumentListener(listener);
            valueField.getDocument().addDocumentListener(listener);
        }

        private void markCredentialMutation() {
            if (!loading) {
                markDraftMutation();
            }
        }

        private void load(List<McpSecretReference> source) {
            loading = true;
            try {
                rows.clear();
                source.forEach(rows::addElement);
                if (!rows.isEmpty()) {
                    rowList.setSelectedIndex(0);
                } else {
                    keyField.setText("");
                    valueField.setText("");
                }
            } finally {
                loading = false;
            }
            loadSelectedRow();
        }

        private List<McpSecretReference> rows() {
            applySelectedRow();
            List<McpSecretReference> result = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                result.add(rows.get(index));
            }
            return List.copyOf(result);
        }

        private void loadSelectedRow() {
            if (loading) {
                return;
            }
            loading = true;
            try {
                McpSecretReference row = rowList.getSelectedValue();
                keyField.setText(row == null ? "" : row.key());
                valueField.setText("");
            } finally {
                loading = false;
            }
        }

        private void applySelectedRow() {
            int index = rowList.getSelectedIndex();
            if (loading || index < 0) {
                return;
            }
            McpSecretReference previous = rows.get(index);
            char[] value = valueField.getPassword();
            String key = keyField.getText();
            boolean replacementChanged = value.length > 0
                    && !CharBuffer.wrap(value).chars().allMatch(Character::isWhitespace);
            boolean changed = replacementChanged || !previous.key().equals(key);
            try {
                if (replacementChanged) {
                    char[] old = replacementSecrets.put(previous.rowId(), copyOf(value, value.length));
                    if (old != null) {
                        fill(old, '\0');
                    }
                }
                loading = true;
                rows.set(index, new McpSecretReference(previous.rowId(), key, previous.secretId()));
                valueField.setText("");
            } finally {
                loading = false;
                fill(value, '\0');
            }
            if (changed) {
                markDraftMutation();
            }
        }

        private void removeSelectedRow() {
            int index = rowList.getSelectedIndex();
            if (index < 0) {
                return;
            }
            McpSecretReference removed = rows.remove(index);
            markDraftMutation();
            char[] replacement = replacementSecrets.remove(removed.rowId());
            if (replacement != null) {
                fill(replacement, '\0');
            }
            if (!rows.isEmpty()) {
                rowList.setSelectedIndex(min(index, rows.size() - 1));
            } else {
                keyField.setText("");
                valueField.setText("");
            }
        }

        private void disposeEditor() {
            char[] value = valueField.getPassword();
            fill(value, '\0');
            valueField.setText("");
            rows.clear();
        }
    }

    private String uniqueModelId() {
        int suffix = serverModel.size() + 1;
        Set<String> existing = new HashSet<>();
        for (int index = 0; index < serverModel.size(); index++) {
            existing.add(serverModel.get(index).modelId());
        }
        String candidate;
        do {
            candidate = "server_%d".formatted(suffix++);
        } while (existing.contains(candidate));
        return candidate;
    }
}
