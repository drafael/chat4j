package com.github.drafael.chat4j.chat.model;

import com.github.drafael.chat4j.util.Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSelectorButtonTest {

    @Test
    @DisplayName("Provider icon size remains compact and independent from provider text height")
    void providerIconSize_whenCalled_returnsCompactScaledSize() throws Exception {
        Method method = ModelSelectorButton.class.getDeclaredMethod("providerIconSize", FontMetrics.class);
        method.setAccessible(true);

        int size = (int) method.invoke(null, new Object[]{null});

        assertThat(size).isEqualTo(Fonts.scale(14));
    }

    @Test
    @DisplayName("Tooltip shows provider before model on one line")
    void setSelection_whenCalled_showsProviderBeforeModelInTooltip() {
        var subject = new ModelSelectorButton();

        subject.setSelection("Anthropic", "claude-sonnet-4-6");

        assertThat(subject.getToolTipText()).isEqualTo("Anthropic claude-sonnet-4-6");
    }

    @Test
    @DisplayName("Preferred width can grow up to sixty percent of the title bar")
    void getPreferredSize_whenModelNameIsLong_capsAtSixtyPercentOfTitleBar() {
        var subject = new ModelSelectorButton();
        subject.setSelection(
                "LM Studio",
                "gemma-4-e4b-claude-abliterated-super-extra-long-experimental-model-name"
        );

        JPanel titleBar = new JPanel(new BorderLayout());
        JPanel centerPanel = new JPanel();
        titleBar.setSize(new Dimension(1000, 32));
        centerPanel.setSize(new Dimension(900, 32));
        centerPanel.add(subject);
        titleBar.add(centerPanel, BorderLayout.CENTER);

        assertThat(subject.getPreferredSize().width).isEqualTo(600);
    }
}
