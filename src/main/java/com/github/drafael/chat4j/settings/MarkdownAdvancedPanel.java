package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsRepo;

import javax.swing.*;
import java.awt.*;

public class MarkdownAdvancedPanel extends AbstractSettingsPanel {

    static final String KEY_LATEX_ENABLED = "markdown.latex.enabled";
    static final String KEY_LATEX_SINGLE_DOLLAR = "markdown.latex.singleDollar";
    static final String KEY_LATEX_BRACKET_DELIMITERS = "markdown.latex.bracketDelimiters";

    public MarkdownAdvancedPanel(SettingsRepo settingsRepo) {
        super(settingsRepo);

        JPanel form = createFormPanel("Markdown");
        GridBagConstraints gbc = createFormConstraints();

        int row = 0;

        JCheckBox latexEnabled = new JCheckBox("Enable math formula rendering");
        JCheckBox singleDollarEnabled = new JCheckBox("Render single dollar math ($...$)");
        JCheckBox bracketDelimitersEnabled = new JCheckBox("Enable \\(...\\) and \\[...\\] delimiters");

        JPanel latexOptionsPanel = new JPanel();
        latexOptionsPanel.setOpaque(false);
        latexOptionsPanel.setLayout(new BoxLayout(latexOptionsPanel, BoxLayout.Y_AXIS));
        latexOptionsPanel.add(latexEnabled);
        latexOptionsPanel.add(Box.createVerticalStrut(6));
        latexOptionsPanel.add(singleDollarEnabled);
        latexOptionsPanel.add(Box.createVerticalStrut(6));
        latexOptionsPanel.add(bracketDelimitersEnabled);

        addRow(form, gbc, row++, "LaTeX", latexOptionsPanel);

        bindCheckBox(
                latexEnabled,
                KEY_LATEX_ENABLED,
                true,
                enabled -> {
                    updateDependentToggleState(singleDollarEnabled, bracketDelimitersEnabled, enabled);
                    setStatusInfo(STATUS_SAVED);
                }
        );
        bindCheckBox(singleDollarEnabled, KEY_LATEX_SINGLE_DOLLAR, true, null);
        bindCheckBox(bracketDelimitersEnabled, KEY_LATEX_BRACKET_DELIMITERS, true, null);

        updateDependentToggleState(singleDollarEnabled, bracketDelimitersEnabled, latexEnabled.isSelected());
        addVerticalSpacer(form, gbc, row);
    }

    private void updateDependentToggleState(
            JCheckBox singleDollarEnabled,
            JCheckBox bracketDelimitersEnabled,
            boolean latexEnabled
    ) {
        singleDollarEnabled.setEnabled(latexEnabled);
        bracketDelimitersEnabled.setEnabled(latexEnabled);
    }
}
