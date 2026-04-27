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
import java.util.concurrent.atomic.AtomicReference;

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
        assertThat(subject.getReasoningLevel()).isEqualTo(ReasoningLevel.EXTRA_HIGH);

        subject.setThinkingAvailable(true);

        assertThat(thinkingButton.isVisible()).isTrue();
        assertThat(subject.isThinkingEnabled()).isTrue();
        assertThat(subject.getReasoningLevel()).isEqualTo(ReasoningLevel.EXTRA_HIGH);
    }

    @Test
    @DisplayName("Agent toggle visibility follows tool capability and disables mode when unavailable")
    void setAgentModeAvailable_whenCapabilityChanges_updatesToggleVisibilityAndState() throws Exception {
        InputBar subject = new InputBar();
        JToggleButton agentModeButton = readAgentModeButton(subject);

        subject.setAgentModeAvailable(true);
        subject.setAgentProjectRoot(Files.createTempDirectory("chat4j-agent-project"));
        subject.setAgentModeEnabled(true);

        assertThat(agentModeButton.isVisible()).isTrue();
        assertThat(subject.isAgentModeEnabled()).isTrue();

        subject.setAgentModeAvailable(false);

        assertThat(agentModeButton.isVisible()).isFalse();
        assertThat(subject.isAgentModeEnabled()).isFalse();

        subject.setAgentModeAvailable(true);

        assertThat(agentModeButton.isVisible()).isTrue();
        assertThat(subject.isAgentModeEnabled()).isTrue();
    }

    @Test
    @DisplayName("Enabling agent mode requires selecting a project folder")
    void agentModeButtonClick_whenFolderSelectionCancelled_keepsModeDisabledAndShowsValidation() throws Exception {
        InputBar subject = new InputBar();
        JToggleButton agentModeButton = readAgentModeButton(subject);
        JLabel validationLabel = readValidationLabel(subject);

        subject.setAgentModeAvailable(true);
        subject.setProjectRootChooserForTests(parent -> Optional.empty());

        SwingUtilities.invokeAndWait(agentModeButton::doClick);

        assertThat(subject.isAgentModeEnabled()).isFalse();
        assertThat(subject.getAgentProjectRoot()).isNull();
        assertThat(validationLabel.isVisible()).isTrue();
        assertThat(validationLabel.getText()).contains("Select a project folder");
    }

    @Test
    @DisplayName("Selected folder indicator is hidden when agent mode is off")
    void setAgentProjectRoot_whenAgentModeDisabled_hidesSelectedFolderIndicator() throws Exception {
        InputBar subject = new InputBar();
        JButton projectRootButton = readProjectRootButton(subject);
        Path projectRoot = Files.createTempDirectory("chat4j-agent-project-hidden");

        subject.setAgentModeAvailable(true);
        subject.setAgentProjectRoot(projectRoot);
        subject.setAgentModeEnabled(false);

        assertThat(projectRootButton.isVisible()).isFalse();
    }

    @Test
    @DisplayName("Selecting project folder enables agent mode and shows root indicator")
    void agentModeButtonClick_whenFolderSelected_enablesModeAndShowsProjectRoot() throws Exception {
        InputBar subject = new InputBar();
        JToggleButton agentModeButton = readAgentModeButton(subject);
        JButton projectRootButton = readProjectRootButton(subject);
        Path projectRoot = Files.createTempDirectory("chat4j-agent-project");
        AtomicReference<Path> notifiedRoot = new AtomicReference<>();

        subject.addAgentProjectRootListener(notifiedRoot::set);
        subject.setAgentModeAvailable(true);
        subject.setProjectRootChooserForTests(parent -> Optional.of(projectRoot));

        SwingUtilities.invokeAndWait(agentModeButton::doClick);

        assertThat(subject.isAgentModeEnabled()).isTrue();
        assertThat(subject.getAgentProjectRoot()).isEqualTo(projectRoot.normalize());
        assertThat(projectRootButton.isVisible()).isTrue();
        assertThat(projectRootButton.getText()).startsWith(projectRoot.getFileName().toString().substring(0, 8));
        assertThat(projectRootButton.getToolTipText()).contains(projectRoot.toAbsolutePath().toString());
        assertThat(notifiedRoot.get()).isEqualTo(projectRoot.normalize());
    }

    @Test
    @DisplayName("Selected folder button width is capped to half of input bar and text is truncated")
    void setAgentProjectRoot_whenFolderNameIsLong_capsButtonWidthAndTrimsLabel() throws Exception {
        InputBar subject = new InputBar();
        JButton projectRootButton = readProjectRootButton(subject);
        Path longNamedRoot = Files.createTempDirectory("chat4j-agent-project-name-is-intentionally-very-long-for-ui-width-test-");

        SwingUtilities.invokeAndWait(() -> {
            subject.setSize(600, 220);
            subject.doLayout();
        });

        subject.setAgentModeAvailable(true);
        subject.setAgentProjectRoot(longNamedRoot);
        subject.setAgentModeEnabled(true);

        assertThat(projectRootButton.getPreferredSize().width).isLessThanOrEqualTo(300);
        assertThat(projectRootButton.getText()).endsWith("…");
    }

    @Test
    @DisplayName("Clicking selected folder button reopens chooser and updates project root")
    void projectRootButtonClick_whenAgentModeEnabled_updatesSelectedProjectRoot() throws Exception {
        InputBar subject = new InputBar();
        JButton projectRootButton = readProjectRootButton(subject);
        Path firstRoot = Files.createTempDirectory("chat4j-agent-project-first");
        Path secondRoot = Files.createTempDirectory("chat4j-agent-project-second");

        subject.setAgentModeAvailable(true);
        subject.setProjectRootChooserForTests(parent -> Optional.of(secondRoot));
        subject.setAgentProjectRoot(firstRoot);
        subject.setAgentModeEnabled(true);

        SwingUtilities.invokeAndWait(projectRootButton::doClick);

        assertThat(subject.getAgentProjectRoot()).isEqualTo(secondRoot.normalize());
        assertThat(projectRootButton.getText()).startsWith(secondRoot.getFileName().toString().substring(0, 8));
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

    private JButton readProjectRootButton(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("projectRootButton");
        field.setAccessible(true);
        return (JButton) field.get(inputBar);
    }

    private JButton readThinkingButton(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("thinkingButton");
        field.setAccessible(true);
        return (JButton) field.get(inputBar);
    }

    private JToggleButton readAgentModeButton(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("agentModeButton");
        field.setAccessible(true);
        return (JToggleButton) field.get(inputBar);
    }
}
