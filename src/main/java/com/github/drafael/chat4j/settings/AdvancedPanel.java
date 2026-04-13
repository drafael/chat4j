package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsRepo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class AdvancedPanel extends JPanel {

    private static final String SECTION_MARKDOWN = "Markdown";

    public AdvancedPanel(SettingsRepo settingsRepo) {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(0, 0, 0, 0));

        DefaultListModel<String> sectionModel = new DefaultListModel<>();
        sectionModel.addElement(SECTION_MARKDOWN);

        JList<String> sectionList = new JList<>(sectionModel);
        sectionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sectionList.setFixedCellHeight(42);
        sectionList.setBorder(new EmptyBorder(12, 10, 12, 10));

        Color dividerColor = UIManager.getColor("Separator.foreground");
        if (dividerColor == null) {
            dividerColor = new Color(210, 210, 210);
        }

        JPanel listContainer = new JPanel(new BorderLayout());
        listContainer.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, dividerColor));
        listContainer.setPreferredSize(new Dimension(180, 0));
        listContainer.add(sectionList, BorderLayout.CENTER);

        CardLayout cardLayout = new CardLayout();
        JPanel cardPanel = new JPanel(cardLayout);
        cardPanel.add(new MarkdownAdvancedPanel(settingsRepo), SECTION_MARKDOWN);

        sectionList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                String selectedSection = sectionList.getSelectedValue();
                if (selectedSection != null) {
                    cardLayout.show(cardPanel, selectedSection);
                }
            }
        });

        sectionList.setSelectedIndex(0);

        add(listContainer, BorderLayout.WEST);
        add(cardPanel, BorderLayout.CENTER);
    }
}
