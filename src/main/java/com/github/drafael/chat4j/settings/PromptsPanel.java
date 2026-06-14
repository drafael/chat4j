package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.prompts.PromptCatalogRepo;
import com.github.drafael.chat4j.prompts.PromptTemplate;
import com.github.drafael.chat4j.prompts.PromptVariable;
import com.github.drafael.chat4j.prompts.PromptVariableType;
import com.github.drafael.chat4j.util.Fonts;
import com.github.drafael.chat4j.util.ModalDialogSupport;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

public class PromptsPanel extends JPanel {

    private final PromptCatalogRepo promptCatalogRepo;
    private final DefaultListModel<PromptTemplate> promptListModel = new DefaultListModel<>();
    private final JList<PromptTemplate> promptList = new JList<>(promptListModel);
    private final JTextField titleField = new JTextField();
    private final JTextField idField = new JTextField();
    private final JTextArea promptArea = new JTextArea(10, 48);
    private JScrollPane promptScrollPane;
    private final VariableTableModel variableTableModel = new VariableTableModel();
    private final JTable variableTable = new JTable(variableTableModel);
    private final JLabel statusLabel = new JLabel(" ");
    private boolean loadingSelection;
    private boolean dirty;
    private String lastSaveError = "";
    private int currentEditingIndex = -1;

    public PromptsPanel(@NonNull SettingsRepository settingsRepo) {
        this.promptCatalogRepo = new PromptCatalogRepo(settingsRepo);
        setLayout(new BorderLayout());
        add(createHeader(), BorderLayout.NORTH);
        add(createBody(), BorderLayout.CENTER);
        installDirtyTracking();
        reloadPrompts();
    }

    private JComponent createHeader() {
        JLabel title = new JLabel("Prompts");
        Fonts.apply(title, Font.BOLD, Fonts.SIZE_SUBTITLE);
        title.setBorder(new EmptyBorder(12, 18, 10, 18));
        return title;
    }

    private JComponent createBody() {
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createPromptListPanel(), createEditorPanel());
        splitPane.setDividerLocation(260);
        splitPane.setResizeWeight(0);
        splitPane.setDividerSize(1);
        splitPane.setBorder(null);
        return splitPane;
    }

    private JComponent createPromptListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        promptList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        promptList.setCellRenderer(new PromptListRenderer());
        promptList.setFixedCellHeight(34);
        promptList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadSelectedPrompt();
            }
        });
        panel.add(new JScrollPane(promptList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton addButton = new JButton("+");
        JButton removeButton = new JButton("−");
        JButton resetButton = new JButton("Reset to Built-ins");
        addButton.addActionListener(e -> addPrompt());
        removeButton.addActionListener(e -> removeSelectedPrompt());
        resetButton.addActionListener(e -> resetToBuiltIns());
        buttons.add(addButton);
        buttons.add(removeButton);
        buttons.add(resetButton);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent createEditorPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(14, 18, 14, 18));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 0, 6, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        addRow(form, gbc, row++, "Title", titleField);
        addRow(form, gbc, row++, "ID", idField);

        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptScrollPane = new JScrollPane(promptArea);
        promptScrollPane.setPreferredSize(new Dimension(0, 220));
        promptScrollPane.setMinimumSize(new Dimension(0, 180));
        addRow(form, gbc, row++, "Prompt", promptScrollPane);

        JPanel variableToolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton addVariableButton = new JButton("+ New Variable");
        JButton removeVariableButton = new JButton("Remove Variable");
        addVariableButton.addActionListener(e -> variableTableModel.addVariable());
        removeVariableButton.addActionListener(e -> removeSelectedVariable());
        variableToolbar.add(addVariableButton);
        variableToolbar.add(removeVariableButton);

        JPanel variablesPanel = new JPanel(new BorderLayout(0, 4));
        variablesPanel.add(variableToolbar, BorderLayout.NORTH);
        variablesPanel.add(new JScrollPane(variableTable), BorderLayout.CENTER);
        variableTable.setFillsViewportHeight(true);
        variableTable.putClientProperty("terminateEditOnFocusLost", true);

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        form.add(variablesPanel, gbc);

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusPanel.add(statusLabel, BorderLayout.CENTER);

        panel.add(form, BorderLayout.CENTER);
        panel.add(statusPanel, BorderLayout.SOUTH);
        return panel;
    }

    private void installDirtyTracking() {
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                markDirty();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                markDirty();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                markDirty();
            }
        };
        titleField.getDocument().addDocumentListener(listener);
        idField.getDocument().addDocumentListener(listener);
        promptArea.getDocument().addDocumentListener(listener);
    }

    private void markDirty() {
        if (loadingSelection) {
            return;
        }
        dirty = true;
        setStatus("Unsaved changes", false);
    }

    private void addRow(JPanel form, GridBagConstraints gbc, int row, String label, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.weighty = field == promptScrollPane ? 0.35 : 0;
        gbc.fill = field == promptScrollPane ? GridBagConstraints.VERTICAL : GridBagConstraints.HORIZONTAL;
        form.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = field == promptScrollPane ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
        form.add(field, gbc);
    }

    public boolean savePendingChanges() {
        if (!persistCurrentEditorToModel()) {
            return false;
        }
        if (!dirty) {
            return true;
        }
        return saveCatalogFromList();
    }

    public String lastSaveError() {
        return lastSaveError;
    }

    private void reloadPrompts() {
        currentEditingIndex = -1;
        loadingSelection = true;
        promptListModel.clear();
        promptCatalogRepo.load().forEach(promptListModel::addElement);
        loadingSelection = false;
        dirty = false;
        if (!promptListModel.isEmpty()) {
            promptList.setSelectedIndex(0);
        }
    }

    private void loadSelectedPrompt() {
        if (loadingSelection) {
            return;
        }
        int previousEditingIndex = currentEditingIndex;
        if (!persistCurrentEditorToModel()) {
            restoreSelection(previousEditingIndex);
            return;
        }
        PromptTemplate selected = promptList.getSelectedValue();
        if (selected == null) {
            currentEditingIndex = -1;
            return;
        }
        loadingSelection = true;
        titleField.setText(selected.title());
        idField.setText(selected.id());
        promptArea.setText(selected.prompt());
        variableTableModel.setVariables(selected.variables());
        currentEditingIndex = promptList.getSelectedIndex();
        loadingSelection = false;
    }

    private void addPrompt() {
        if (!persistCurrentEditorToModel()) {
            return;
        }
        String title = "New Prompt";
        PromptTemplate prompt = new PromptTemplate(
                uniqueId(slug(title)),
                title,
                "@{{text}}",
                PromptTemplate.DEFAULT_MODEL,
                List.of(PromptVariable.input("text"))
        );
        promptListModel.addElement(prompt);
        dirty = true;
        promptList.setSelectedValue(prompt, true);
    }

    private void restoreSelection(int index) {
        if (index < 0 || index >= promptListModel.size()) {
            return;
        }
        loadingSelection = true;
        promptList.setSelectedIndex(index);
        loadingSelection = false;
    }

    private void removeSelectedPrompt() {
        if (!persistCurrentEditorToModel()) {
            return;
        }
        int index = promptList.getSelectedIndex();
        if (index < 0) {
            return;
        }
        promptListModel.remove(index);
        currentEditingIndex = -1;
        dirty = true;
        if (!promptListModel.isEmpty()) {
            promptList.setSelectedIndex(Math.min(index, promptListModel.size() - 1));
        } else {
            setStatus("Unsaved changes", false);
        }
    }

    private boolean persistCurrentEditorToModel() {
        if (loadingSelection || currentEditingIndex < 0 || currentEditingIndex >= promptListModel.size()) {
            return true;
        }
        if (!stopTableEditing()) {
            lastSaveError = "Finish editing the selected variable before saving prompts.";
            setStatus(lastSaveError, true);
            return false;
        }
        PromptTemplate prompt = new PromptTemplate(
                idField.getText(),
                titleField.getText(),
                promptArea.getText(),
                PromptTemplate.DEFAULT_MODEL,
                variableTableModel.toVariables()
        );
        if (!prompt.equals(promptListModel.get(currentEditingIndex))) {
            promptListModel.set(currentEditingIndex, prompt);
            dirty = true;
        }
        return true;
    }

    private boolean stopTableEditing() {
        return !variableTable.isEditing() || variableTable.getCellEditor().stopCellEditing();
    }

    private boolean saveCatalogFromList() {
        try {
            List<PromptTemplate> prompts = IntStream.range(0, promptListModel.size())
                    .mapToObj(promptListModel::get)
                    .toList();
            promptCatalogRepo.save(prompts);
            dirty = false;
            lastSaveError = "";
            setStatus("Saved", false);
            return true;
        } catch (Exception e) {
            lastSaveError = e.getMessage();
            setStatus(lastSaveError, true);
            return false;
        }
    }

    private void resetToBuiltIns() {
        if (!confirmResetToBuiltIns()) {
            return;
        }
        promptCatalogRepo.resetToBuiltIns();
        reloadPrompts();
        dirty = false;
        setStatus("Reset to built-ins", false);
    }

    private boolean confirmResetToBuiltIns() {
        JOptionPane pane = new JOptionPane(
                "<html><div style='width:260px'>Replace all custom prompts with the built-in prompt list?</div></html>",
                JOptionPane.WARNING_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION
        );
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        dialog.setContentPane(pane);
        pane.addPropertyChangeListener(event -> {
            if (dialog.isVisible()
                    && event.getSource() == pane
                    && JOptionPane.VALUE_PROPERTY.equals(event.getPropertyName())) {
                dialog.setVisible(false);
            }
        });
        ModalDialogSupport.prepareCompactModal(dialog, this);
        dialog.setVisible(true);
        dialog.dispose();

        Object value = pane.getValue();
        return value instanceof Integer selectedValue && selectedValue == JOptionPane.OK_OPTION;
    }

    private void removeSelectedVariable() {
        int row = variableTable.getSelectedRow();
        if (row >= 0) {
            variableTableModel.removeVariable(variableTable.convertRowIndexToModel(row));
        }
    }

    private String uniqueId(String base) {
        String normalizedBase = StringUtils.defaultIfBlank(base, "prompt");
        List<String> ids = IntStream.range(0, promptListModel.size())
                .mapToObj(index -> promptListModel.get(index).id())
                .toList();
        if (!ids.contains(normalizedBase)) {
            return normalizedBase;
        }
        int suffix = 2;
        while (ids.contains("%s-%d".formatted(normalizedBase, suffix))) {
            suffix++;
        }
        return "%s-%d".formatted(normalizedBase, suffix);
    }

    private String slug(String value) {
        String slug = StringUtils.defaultString(value).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return StringUtils.defaultIfBlank(slug, "prompt");
    }

    private void setStatus(String message, boolean error) {
        statusLabel.setText(StringUtils.defaultString(message));
        statusLabel.setForeground(error ? new Color(200, 50, 50) : UIManager.getColor("Label.disabledForeground"));
    }

    private static final class PromptListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof PromptTemplate promptTemplate) {
                label.setText(promptTemplate.title());
            }
            label.setBorder(new EmptyBorder(5, 10, 5, 10));
            return label;
        }
    }

    private final class VariableTableModel extends AbstractTableModel {
        private static final List<String> COLUMNS = List.of("Name", "Type", "Options");
        private final List<MutableVariable> variables = new ArrayList<>();

        @Override
        public int getRowCount() {
            return variables.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.size();
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS.get(column);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            MutableVariable variable = variables.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> variable.name;
                case 1 -> variable.type.name();
                case 2 -> String.join(", ", variable.options);
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            MutableVariable variable = variables.get(rowIndex);
            String text = StringUtils.defaultString(value == null ? null : value.toString()).trim();
            switch (columnIndex) {
                case 0 -> variable.name = text;
                case 1 -> variable.type = parseType(text);
                case 2 -> variable.options = parseOptions(text);
                default -> {
                }
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
            markDirty();
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        void setVariables(List<PromptVariable> promptVariables) {
            variables.clear();
            promptVariables.forEach(variable -> variables.add(new MutableVariable(variable)));
            fireTableDataChanged();
        }

        void addVariable() {
            variables.add(new MutableVariable("name", PromptVariableType.INPUT, emptyList(), ""));
            fireTableRowsInserted(variables.size() - 1, variables.size() - 1);
            markDirty();
        }

        void removeVariable(int row) {
            variables.remove(row);
            fireTableRowsDeleted(row, row);
            markDirty();
        }

        List<PromptVariable> toVariables() {
            return variables.stream()
                    .map(variable -> new PromptVariable(variable.name, variable.type, variable.options, variable.defaultValue))
                    .toList();
        }

        private PromptVariableType parseType(String value) {
            try {
                return PromptVariableType.valueOf(StringUtils.defaultIfBlank(value, "INPUT").trim().toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return PromptVariableType.INPUT;
            }
        }

        private List<String> parseOptions(String value) {
            if (StringUtils.isBlank(value)) {
                return emptyList();
            }
            return Arrays.stream(value.split(","))
                    .map(StringUtils::trimToEmpty)
                    .filter(StringUtils::isNotBlank)
                    .toList();
        }
    }

    private static final class MutableVariable {
        private String name;
        private PromptVariableType type;
        private List<String> options;
        private String defaultValue;

        private MutableVariable(PromptVariable variable) {
            this(variable.name(), variable.type(), variable.options(), variable.defaultValue());
        }

        private MutableVariable(String name, PromptVariableType type, List<String> options, String defaultValue) {
            this.name = name;
            this.type = type;
            this.options = new ArrayList<>(options);
            this.defaultValue = defaultValue;
        }
    }
}
