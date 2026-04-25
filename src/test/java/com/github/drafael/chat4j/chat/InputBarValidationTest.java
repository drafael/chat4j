package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InputBarValidationTest {

    @Test
    @DisplayName("Executable attachment is rejected and inline validation is shown")
    void toComposerAttachment_whenExecutableExtensionProvided_rejectsAttachmentAndShowsValidationMessage() throws Exception {
        InputBar subject = new InputBar();
        Path executable = Files.createTempFile("chat4j-validation", ".exe");

        Optional<?> result = invokeToComposerAttachment(subject, executable);
        JLabel validationLabel = readValidationLabel(subject);

        assertThat(result).isEmpty();
        assertThat(validationLabel.isVisible()).isTrue();
        assertThat(validationLabel.getText()).contains("Unsupported file type");

        Files.deleteIfExists(executable);
    }

    @Test
    @DisplayName("Text attachment with allowed extension is accepted")
    void toComposerAttachment_whenMarkdownFileProvided_acceptsAttachment() throws Exception {
        InputBar subject = new InputBar();
        Path markdown = Files.createTempFile("chat4j-validation", ".md");
        Files.writeString(markdown, "# hello");

        Optional<?> result = invokeToComposerAttachment(subject, markdown);
        JLabel validationLabel = readValidationLabel(subject);

        assertThat(result).isPresent();
        assertThat(validationLabel.isVisible()).isFalse();

        Files.deleteIfExists(markdown);
    }

    @Test
    @DisplayName("Thinking toggle visibility follows model thinking capability")
    void setThinkingAvailable_whenCapabilityChanges_updatesToggleVisibilityAndState() throws Exception {
        InputBar subject = new InputBar();
        JButton thinkingButton = readThinkingButton(subject);

        subject.setThinkingAvailable(true);
        subject.setReasoningLevel(ReasoningLevel.EXTRA_HIGH);

        assertThat(thinkingButton.isVisible()).isTrue();
        assertThat(subject.isThinkingEnabled()).isTrue();
        assertThat(subject.getReasoningLevel()).isEqualTo(ReasoningLevel.EXTRA_HIGH);

        subject.setThinkingAvailable(false);

        assertThat(thinkingButton.isVisible()).isFalse();
        assertThat(subject.isThinkingEnabled()).isFalse();
        assertThat(subject.getReasoningLevel()).isEqualTo(ReasoningLevel.OFF);
    }

    @SuppressWarnings("unchecked")
    private Optional<?> invokeToComposerAttachment(InputBar inputBar, Path path) throws Exception {
        Method method = InputBar.class.getDeclaredMethod("toComposerAttachment", Path.class);
        method.setAccessible(true);
        return (Optional<?>) method.invoke(inputBar, path);
    }

    private JLabel readValidationLabel(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("validationLabel");
        field.setAccessible(true);
        return (JLabel) field.get(inputBar);
    }

    private JButton readThinkingButton(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("thinkingButton");
        field.setAccessible(true);
        return (JButton) field.get(inputBar);
    }
}
