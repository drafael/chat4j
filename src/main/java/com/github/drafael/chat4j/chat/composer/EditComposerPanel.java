package com.github.drafael.chat4j.chat.composer;

import com.formdev.flatlaf.FlatClientProperties;
import com.github.drafael.chat4j.util.Fonts;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

public final class EditComposerPanel extends JPanel {
    private final JComponent composer;

    public EditComposerPanel(JComponent composer, Runnable saveOnlyAction, Runnable saveAndRegenerateAction, Runnable cancelAction) {
        this.composer = composer;
        setOpaque(false);
        setLayout(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        add(createHeader(saveOnlyAction, saveAndRegenerateAction, cancelAction), BorderLayout.NORTH);
        add(composer, BorderLayout.CENTER);
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelEditing");
        getActionMap().put("cancelEditing", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancelAction.run();
            }
        });
    }

    private JComponent createHeader(Runnable saveOnlyAction, Runnable saveAndRegenerateAction, Runnable cancelAction) {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(4, 16, 0, 16));

        JLabel title = new JLabel("✎ Editing message");
        Fonts.apply(title, Font.PLAIN, Fonts.SIZE_BODY);
        header.add(title, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        JButton saveAndRegenerateButton = new JButton("Save & regenerate");
        saveAndRegenerateButton.putClientProperty("JButton.buttonType", "roundRect");
        saveAndRegenerateButton.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:10");
        saveAndRegenerateButton.setFocusable(false);
        saveAndRegenerateButton.addActionListener(e -> saveAndRegenerateAction.run());
        actions.add(saveAndRegenerateButton);

        JButton saveOnlyButton = new JButton("Save only");
        saveOnlyButton.putClientProperty("JButton.buttonType", "roundRect");
        saveOnlyButton.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:10");
        saveOnlyButton.setFocusable(false);
        saveOnlyButton.addActionListener(e -> saveOnlyAction.run());
        actions.add(saveOnlyButton);

        JButton cancelButton = new JButton("×");
        cancelButton.putClientProperty("JButton.buttonType", "borderless");
        cancelButton.setFocusable(false);
        cancelButton.setToolTipText("Cancel editing");
        cancelButton.addActionListener(e -> cancelAction.run());
        actions.add(cancelButton);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        Dimension composerPreferred = composer.getPreferredSize();
        return new Dimension(Math.max(preferred.width, composerPreferred.width), preferred.height);
    }
}
