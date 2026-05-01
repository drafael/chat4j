package com.github.drafael.chat4j;

import com.github.drafael.chat4j.prompts.PromptTemplate;
import com.github.drafael.chat4j.prompts.PromptVariable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PromptVariablesDialogTest {

    private final PromptVariablesDialog subject = new PromptVariablesDialog();

    @Test
    @DisplayName("Dialog content builds fields and collects inserted values")
    void createContent_whenVariablesProvided_buildsFieldsAndCollectsValues() {
        PromptVariable topic = PromptVariable.input("topic");
        PromptVariable tone = PromptVariable.select("tone", List.of("friendly", "formal"));
        PromptTemplate template = new PromptTemplate("id", "Draft Reply", "Write {{topic}}", "default", List.of(topic, tone));
        var cancelled = new AtomicBoolean();
        var insertedValues = new AtomicReference<Map<String, String>>();

        PromptVariablesDialog.DialogContent content = subject.createContent(
                template,
                List.of(topic, tone),
                () -> cancelled.set(true),
                insertedValues::set
        );

        JScrollPane topicField = (JScrollPane) content.fields().get(topic);
        JTextArea topicTextArea = (JTextArea) topicField.getViewport().getView();
        topicTextArea.setText("release notes");
        JComboBox<?> toneField = (JComboBox<?>) content.fields().get(tone);
        toneField.setSelectedItem("formal");

        content.insertButton().doClick();
        content.cancelButton().doClick();

        assertThat(content.panel().getComponentCount()).isGreaterThan(0);
        assertThat(insertedValues).hasValue(Map.of("topic", "release notes", "tone", "formal"));
        assertThat(cancelled).isTrue();
    }

    @Test
    @DisplayName("Read field handles unknown components as empty text")
    void readField_whenComponentUnsupported_returnsEmptyText() {
        assertThat(subject.readField(new JPanel())).isEmpty();
    }
}
