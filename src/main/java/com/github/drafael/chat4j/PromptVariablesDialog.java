package com.github.drafael.chat4j;

import com.github.drafael.chat4j.prompts.PromptTemplate;
import com.github.drafael.chat4j.prompts.PromptVariable;
import com.github.drafael.chat4j.prompts.PromptVariableType;
import com.github.drafael.chat4j.util.Fonts;
import lombok.NonNull;
import org.apache.commons.lang3.ObjectUtils;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class PromptVariablesDialog {

    public Optional<Map<String, String>> show(
            @NonNull Component parent,
            @NonNull PromptTemplate promptTemplate,
            @NonNull List<PromptVariable> variables
    ) {
        JDialog dialog = new JDialog(
                SwingUtilities.getWindowAncestor(parent),
                promptTemplate.title(),
                Dialog.ModalityType.APPLICATION_MODAL
        );
        dialog.setUndecorated(true);
        dialog.setLayout(new BorderLayout());
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        dialog.getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        });

        AtomicReference<Optional<Map<String, String>>> result = new AtomicReference<>(Optional.empty());
        DialogContent content = createContent(
                promptTemplate,
                variables,
                dialog::dispose,
                collectedValues -> {
                    result.set(Optional.of(collectedValues));
                    dialog.dispose();
                }
        );

        dialog.add(content.panel(), BorderLayout.CENTER);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return result.get();
    }

    DialogContent createContent(
            @NonNull PromptTemplate promptTemplate,
            @NonNull List<PromptVariable> variables,
            @NonNull Runnable cancelAction,
            @NonNull CollectedValuesHandler insertAction
    ) {
        JPanel content = new JPanel(new GridBagLayout());
        Color borderColor = ObjectUtils.firstNonNull(UIManager.getColor("Component.borderColor"), Color.GRAY);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 1),
                BorderFactory.createEmptyBorder(14, 14, 12, 14)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 0, 5, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel(promptTemplate.title());
        Fonts.apply(title, Font.BOLD, Fonts.SIZE_SUBTITLE);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        content.add(title, gbc);

        Map<PromptVariable, JComponent> fields = new LinkedHashMap<>();
        int[] row = {1};
        variables.forEach(variable -> {
            gbc.gridy = row[0];
            gbc.gridwidth = 1;
            gbc.gridx = 0;
            gbc.weightx = 0;
            content.add(new JLabel(variable.name()), gbc);

            JComponent field = createField(variable);
            fields.put(variable, field);

            gbc.gridx = 1;
            gbc.weightx = 1;
            content.add(field, gbc);
            row[0]++;
        });

        JButton cancelButton = new JButton("Cancel");
        JButton insertButton = new JButton("Insert");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancelButton);
        buttons.add(insertButton);
        gbc.gridx = 0;
        gbc.gridy = row[0];
        gbc.gridwidth = 2;
        content.add(buttons, gbc);

        cancelButton.addActionListener(e -> cancelAction.run());
        insertButton.addActionListener(e -> insertAction.accept(collectValues(fields)));

        return new DialogContent(content, fields, cancelButton, insertButton);
    }

    private JComponent createField(PromptVariable variable) {
        if (variable.type() == PromptVariableType.SELECT) {
            return new JComboBox<>(variable.options().toArray(String[]::new));
        }

        JTextArea textArea = new JTextArea(4, 36);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        return new JScrollPane(textArea);
    }

    private Map<String, String> collectValues(Map<PromptVariable, JComponent> fields) {
        Map<String, String> collected = new LinkedHashMap<>();
        fields.forEach((variable, field) -> collected.put(variable.name(), readField(field)));
        return collected;
    }

    String readField(@NonNull JComponent field) {
        if (field instanceof JComboBox<?> comboBox) {
            Object selected = comboBox.getSelectedItem();
            return selected == null ? "" : selected.toString();
        }
        if (field instanceof JScrollPane scrollPane && scrollPane.getViewport().getView() instanceof JTextArea textArea) {
            return textArea.getText();
        }
        return "";
    }

    @FunctionalInterface
    interface CollectedValuesHandler {
        void accept(Map<String, String> values);
    }

    static final class DialogContent {
        private final JPanel panel;
        private final Map<PromptVariable, JComponent> fields;
        private final JButton cancelButton;
        private final JButton insertButton;

        private DialogContent(
                JPanel panel,
                Map<PromptVariable, JComponent> fields,
                JButton cancelButton,
                JButton insertButton
        ) {
            this.panel = panel;
            this.fields = fields;
            this.cancelButton = cancelButton;
            this.insertButton = insertButton;
        }

        JPanel panel() {
            return panel;
        }

        Map<PromptVariable, JComponent> fields() {
            return fields;
        }

        JButton cancelButton() {
            return cancelButton;
        }

        JButton insertButton() {
            return insertButton;
        }
    }
}
