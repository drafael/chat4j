package com.github.drafael.chat4j.chat.composer;

import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.web.WebSearchMode;
import com.github.drafael.chat4j.web.WebSearchOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.plaf.basic.BasicListUI;
import java.awt.Dimension;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    @DisplayName("Command center button fires listener using toolbar presentation")
    void commandCenterButtonClick_whenListenerRegistered_notifiesListener() throws Exception {
        InputBar subject = new InputBar();
        JButton commandCenterButton = readCommandCenterButton(subject);
        AtomicBoolean notified = new AtomicBoolean(false);

        subject.addCommandCenterListener(e -> notified.set(true));
        SwingUtilities.invokeAndWait(commandCenterButton::doClick);

        assertThat(notified).isTrue();
        assertThat(commandCenterButton.getToolTipText()).contains("Command center");
        assertThat(commandCenterButton.getToolTipText()).contains("P");
        assertThat(commandCenterButton.getPreferredSize()).isEqualTo(new Dimension(26, 26));

        subject.setEnabled(false);
        assertThat(commandCenterButton.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Clear chat button fires listener using toolbar presentation")
    void clearChatButtonClick_whenListenerRegistered_notifiesListener() throws Exception {
        InputBar subject = new InputBar();
        JButton clearChatButton = readClearChatButton(subject);
        AtomicBoolean notified = new AtomicBoolean(false);

        assertThat(subject.isClearChatVisible()).isFalse();

        subject.addClearChatListener(e -> notified.set(true));
        subject.setClearChatVisible(true);
        SwingUtilities.invokeAndWait(clearChatButton::doClick);

        assertThat(notified).isTrue();
        assertThat(subject.isClearChatVisible()).isTrue();

        subject.setEnabled(false);
        assertThat(subject.isClearChatVisible()).isFalse();

        subject.setEnabled(true);
        assertThat(subject.isClearChatVisible()).isTrue();
        assertThat(clearChatButton.getToolTipText()).isEqualTo("Clear chat");
        assertThat(clearChatButton.getPreferredSize()).isEqualTo(new Dimension(26, 26));
    }

    @Test
    @DisplayName("Send action still reaches ChatPanel validation when composer is not sendable")
    void fireSend_whenComposerNotSendableNotRecording_notifiesListener() throws Exception {
        InputBar subject = new InputBar();
        AtomicBoolean notified = new AtomicBoolean(false);

        subject.setText("hello");
        subject.setProviderReady(false);
        subject.addSendListener(e -> notified.set(true));

        invokeFireSend(subject);

        assertThat(notified).isTrue();
    }

    @Test
    @DisplayName("Send action is blocked with inline feedback while recording")
    void fireSend_whenRecording_blocksSendAndShowsValidation() throws Exception {
        InputBar subject = new InputBar();
        AtomicBoolean notified = new AtomicBoolean(false);

        subject.addSendListener(e -> notified.set(true));
        subject.showRecordingState();

        invokeFireSend(subject);

        assertThat(notified).isFalse();
        assertThat(readValidationLabel(subject).getText()).isEqualTo("Finish or cancel transcription before sending.");
    }

    @Test
    @DisplayName("Preparing speech state uses cancel control instead of stop-transcribe")
    void recordingPanelButton_whenPreparing_invokesCancelListener() throws Exception {
        AtomicBoolean stopInvoked = new AtomicBoolean(false);
        AtomicBoolean cancelInvoked = new AtomicBoolean(false);
        var subject = new InputRecordingPanel(e -> stopInvoked.set(true), e -> cancelInvoked.set(true));
        JButton stopButton = readStopButton(subject);
        JLabel statusLabel = readStatusLabel(subject);

        subject.startPreparing();
        SwingUtilities.invokeAndWait(stopButton::doClick);

        assertThat(stopInvoked).isFalse();
        assertThat(cancelInvoked).isTrue();
        assertThat(statusLabel.getText()).isEqualTo("Preparing speech model...");
        assertThat(stopButton.getToolTipText()).isEqualTo("Cancel speech preparation");
        assertThat(stopButton.getAccessibleContext().getAccessibleName()).isEqualTo("Cancel speech preparation");
    }

    @Test
    @DisplayName("Recording panel control cancels while transcribing")
    void recordingPanelButton_whenTranscribing_invokesCancelListener() throws Exception {
        AtomicBoolean stopInvoked = new AtomicBoolean(false);
        AtomicBoolean cancelInvoked = new AtomicBoolean(false);
        var subject = new InputRecordingPanel(e -> stopInvoked.set(true), e -> cancelInvoked.set(true));
        JButton stopButton = readStopButton(subject);

        subject.startRecording();
        SwingUtilities.invokeAndWait(stopButton::doClick);

        assertThat(stopInvoked).isTrue();
        assertThat(cancelInvoked).isFalse();
        assertThat(stopButton.getToolTipText()).isEqualTo("Stop recording and transcribe");

        stopInvoked.set(false);
        subject.setTranscribing();
        SwingUtilities.invokeAndWait(stopButton::doClick);

        assertThat(stopInvoked).isFalse();
        assertThat(cancelInvoked).isTrue();
        assertThat(stopButton.getToolTipText()).isEqualTo("Cancel transcription");
        assertThat(stopButton.getAccessibleContext().getAccessibleName()).isEqualTo("Cancel transcription");
    }

    @Test
    @DisplayName("Agent mode request shows validation when unavailable")
    void requestAgentModeEnabled_whenUnavailable_showsValidation() throws Exception {
        InputBar subject = new InputBar();

        SwingUtilities.invokeAndWait(() -> subject.requestAgentModeEnabled(true));

        assertThat(subject.isAgentModeEnabled()).isFalse();
        assertThat(readValidationLabel(subject).getText()).isEqualTo("Agent Mode is not available for the selected model.");
    }

    @Test
    @DisplayName("Web search request shows validation when unavailable")
    void requestWebSearchEnabled_whenUnavailable_showsValidation() throws Exception {
        InputBar subject = new InputBar();

        SwingUtilities.invokeAndWait(() -> subject.requestWebSearchEnabled(true));

        assertThat(subject.isWebSearchEnabled()).isFalse();
        assertThat(readValidationLabel(subject).getText()).isEqualTo("Web Search is not available for the selected model.");
    }

    @Test
    @DisplayName("Web search button click toggles enabled state and notifies listener")
    void webSearchButtonClick_whenAvailable_togglesEnabledState() throws Exception {
        InputBar subject = new InputBar();
        JToggleButton webSearchButton = readWebSearchButton(subject);
        AtomicReference<Boolean> notified = new AtomicReference<>();

        subject.addWebSearchEnabledListener(notified::set);
        subject.setWebSearchOptions(List.of(new WebSearchOption("native", "Native", WebSearchMode.NATIVE, true)), "native");

        SwingUtilities.invokeAndWait(webSearchButton::doClick);

        assertThat(subject.isWebSearchEnabled()).isTrue();
        assertThat(webSearchButton.isSelected()).isTrue();
        assertThat(webSearchButton.isContentAreaFilled()).isFalse();
        assertThat(webSearchButton.isOpaque()).isFalse();
        assertThat(notified.get()).isTrue();

        SwingUtilities.invokeAndWait(webSearchButton::doClick);

        assertThat(subject.isWebSearchEnabled()).isFalse();
        assertThat(webSearchButton.isSelected()).isFalse();
        assertThat(webSearchButton.isContentAreaFilled()).isFalse();
        assertThat(notified.get()).isFalse();
    }

    @Test
    @DisplayName("Default-enabled web search can be toggled off from the input button")
    void webSearchButtonClick_whenDefaultEnabled_togglesOff() throws Exception {
        InputBar subject = new InputBar();
        JToggleButton webSearchButton = readWebSearchButton(subject);
        AtomicReference<Boolean> notified = new AtomicReference<>();

        subject.addWebSearchEnabledListener(notified::set);
        subject.setWebSearchLockedEnabled(true);
        subject.setWebSearchOptions(List.of(new WebSearchOption("native", "Native", WebSearchMode.NATIVE, true)), "native");

        assertThat(subject.isWebSearchEnabled()).isTrue();

        SwingUtilities.invokeAndWait(webSearchButton::doClick);

        assertThat(subject.isWebSearchEnabled()).isFalse();
        assertThat(webSearchButton.isSelected()).isFalse();
        assertThat(webSearchButton.getToolTipText()).contains("click to toggle");
        assertThat(notified.get()).isFalse();
    }

    @Test
    @DisplayName("Persisted web search state is restored after options become available")
    void setWebSearchOptions_whenPersistedStateAppliedFirst_restoresEnabledToggle() throws Exception {
        AtomicReference<InputBar> subjectRef = new AtomicReference<>();
        AtomicReference<JToggleButton> buttonRef = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            try {
                InputBar subject = new InputBar();
                subject.setWebSearchOptionId("perplexity");
                subject.setWebSearchEnabled(true);
                subject.setWebSearchOptions(List.of(
                        new WebSearchOption("native", "Native", WebSearchMode.NATIVE, true),
                        new WebSearchOption("perplexity", "Perplexity", WebSearchMode.EXTERNAL, true)
                ), "native");
                subjectRef.set(subject);
                buttonRef.set(readWebSearchButton(subject));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        AtomicReference<Boolean> webSearchEnabled = new AtomicReference<>();
        AtomicReference<String> webSearchOptionId = new AtomicReference<>();
        AtomicReference<Boolean> buttonSelected = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            webSearchEnabled.set(subjectRef.get().isWebSearchEnabled());
            webSearchOptionId.set(subjectRef.get().getWebSearchOptionId());
            buttonSelected.set(buttonRef.get().isSelected());
        });

        assertThat(webSearchEnabled.get()).isTrue();
        assertThat(webSearchOptionId.get()).isEqualTo("perplexity");
        assertThat(buttonSelected.get()).isTrue();
    }

    @Test
    @DisplayName("Web search button keeps selected state while input bar is disabled")
    void setEnabled_whenWebSearchEnabled_displaysSelectedState() throws Exception {
        AtomicReference<InputBar> subjectRef = new AtomicReference<>();
        AtomicReference<JToggleButton> buttonRef = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            try {
                InputBar subject = new InputBar();
                subject.setWebSearchOptions(List.of(new WebSearchOption("native", "Native", WebSearchMode.NATIVE, true)), "native");
                subject.setWebSearchEnabled(true);
                subject.setEnabled(false);
                subjectRef.set(subject);
                buttonRef.set(readWebSearchButton(subject));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        AtomicReference<Boolean> webSearchEnabled = new AtomicReference<>();
        AtomicReference<Boolean> buttonSelected = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            webSearchEnabled.set(subjectRef.get().isWebSearchEnabled());
            buttonSelected.set(buttonRef.get().isSelected());
        });

        assertThat(webSearchEnabled.get()).isTrue();
        assertThat(buttonSelected.get()).isTrue();
    }

    @Test
    @DisplayName("Web search context menu omits enabled item and separators")
    void rebuildWebSearchMenu_whenOptionsAvailable_omitsToggleItemAndSeparators() throws Exception {
        InputBar subject = new InputBar();
        JPopupMenu webSearchMenu = readWebSearchMenu(subject);

        subject.setWebSearchOptions(List.of(
                new WebSearchOption("native", "Native", WebSearchMode.NATIVE, true),
                new WebSearchOption("perplexity", "Perplexity", WebSearchMode.EXTERNAL, true)
        ), "native");

        assertThat(webSearchMenu.getComponentCount()).isEqualTo(2);
        assertThat(webSearchMenu.getComponent(0)).isInstanceOf(JMenu.class);
        assertThat(((JMenu) webSearchMenu.getComponent(0)).getText()).isEqualTo("Search with");
        assertThat(webSearchMenu.getComponent(1)).isInstanceOf(JMenu.class);
        assertThat(((JMenu) webSearchMenu.getComponent(1)).getText()).isEqualTo("Browse top");
        assertThat(List.of(webSearchMenu.getComponents()))
                .noneMatch(component -> component instanceof JSeparator)
                .noneMatch(component -> component instanceof JCheckBoxMenuItem);
    }

    @Test
    @DisplayName("Block YAML skill descriptions are flattened for popup display")
    void parseSkillFile_whenDescriptionUsesBlockScalar_returnsReadableDescription() throws Exception {
        InputBar subject = new InputBar();
        Path skillDir = Files.createTempDirectory("chat4j-skill-block-description");
        Path skillFile = skillDir.resolve("SKILL.md");
        Files.writeString(skillFile, """
                ---
                name: humanizer
                description: |
                  Remove signs of AI-generated writing from text. Use when editing or reviewing
                  text to make it sound more natural and human-written.
                allowed-tools:
                  - Read
                ---
                # Humanizer
                """);

        Optional<?> result = invokeParseSkillFile(subject, skillFile);

        assertThat(result).isPresent();
        assertThat(readSkillDescription(result.orElseThrow()))
                .isEqualTo("Remove signs of AI-generated writing from text. Use when editing or reviewing text to make it sound more natural and human-written.");
    }

    @Test
    @DisplayName("Theme refresh updates detached skills popup components")
    void updateUI_whenThemeChanges_refreshesDetachedSlashPopupComponents() throws Exception {
        InputBar subject = new InputBar();
        JList<?> slashSuggestionsList = readSlashSuggestionsList(subject);
        slashSuggestionsList.setUI(new SentinelListUi());

        subject.updateUI();

        assertThat(slashSuggestionsList.getUI()).isNotInstanceOf(SentinelListUi.class);
    }

    @Test
    @DisplayName("Input popups use heavyweight windows so they appear above native WebView")
    void configurePopupMenus_whenInputBarCreated_disablesLightweightPopups() throws Exception {
        InputBar subject = new InputBar();

        subject.setWebSearchOptions(List.of(new WebSearchOption("native", "Native", WebSearchMode.NATIVE, true)), "native");

        assertThat(readSlashPopupMenu(subject).isLightWeightPopupEnabled()).isFalse();
        assertThat(readReasoningLevelMenu(subject).isLightWeightPopupEnabled()).isFalse();
        assertThat(readWebSearchMenu(subject).isLightWeightPopupEnabled()).isFalse();
        assertThat(readWebSearchSubmenus(subject))
                .extracting(menu -> menu.getPopupMenu().isLightWeightPopupEnabled())
                .containsOnly(false);
        assertThat(readInputTextArea(subject).getComponentPopupMenu().isLightWeightPopupEnabled()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private Optional<?> invokeToComposerAttachment(InputBar inputBar, Path path) throws Exception {
        Method method = InputBar.class.getDeclaredMethod("toComposerAttachment", Path.class);
        method.setAccessible(true);
        return (Optional<?>) method.invoke(inputBar, path);
    }

    @SuppressWarnings("unchecked")
    private Optional<?> invokeParseSkillFile(InputBar inputBar, Path path) throws Exception {
        Method method = InputBar.class.getDeclaredMethod("parseSkillFile", Path.class);
        method.setAccessible(true);
        return (Optional<?>) method.invoke(inputBar, path);
    }

    private void invokeFireSend(InputBar inputBar) throws Exception {
        Method method = InputBar.class.getDeclaredMethod("fireSend");
        method.setAccessible(true);
        method.invoke(inputBar);
    }

    private JButton readStopButton(InputRecordingPanel panel) throws Exception {
        Field field = InputRecordingPanel.class.getDeclaredField("stopButton");
        field.setAccessible(true);
        return (JButton) field.get(panel);
    }

    private JLabel readStatusLabel(InputRecordingPanel panel) throws Exception {
        Field field = InputRecordingPanel.class.getDeclaredField("statusLabel");
        field.setAccessible(true);
        return (JLabel) field.get(panel);
    }

    private String readSkillDescription(Object skillCommand) throws Exception {
        Method method = skillCommand.getClass().getDeclaredMethod("description");
        method.setAccessible(true);
        return (String) method.invoke(skillCommand);
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

    private JButton readClearChatButton(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("clearChatButton");
        field.setAccessible(true);
        return (JButton) field.get(inputBar);
    }

    private JButton readCommandCenterButton(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("commandCenterButton");
        field.setAccessible(true);
        return (JButton) field.get(inputBar);
    }

    private JToggleButton readWebSearchButton(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("webSearchButton");
        field.setAccessible(true);
        return (JToggleButton) field.get(inputBar);
    }

    private JPopupMenu readWebSearchMenu(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("webSearchMenu");
        field.setAccessible(true);
        return (JPopupMenu) field.get(inputBar);
    }

    private JPopupMenu readReasoningLevelMenu(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("reasoningLevelMenu");
        field.setAccessible(true);
        return (JPopupMenu) field.get(inputBar);
    }

    private JPopupMenu readSlashPopupMenu(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("slashPopupMenu");
        field.setAccessible(true);
        return (JPopupMenu) field.get(inputBar);
    }

    private List<JMenu> readWebSearchSubmenus(InputBar inputBar) throws Exception {
        return List.of(readWebSearchMenu(inputBar).getComponents()).stream()
                .filter(JMenu.class::isInstance)
                .map(JMenu.class::cast)
                .toList();
    }

    private JList<?> readSlashSuggestionsList(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("slashSuggestionsList");
        field.setAccessible(true);
        return (JList<?>) field.get(inputBar);
    }

    private JTextArea readInputTextArea(InputBar inputBar) throws Exception {
        Field field = InputBar.class.getDeclaredField("textArea");
        field.setAccessible(true);
        return (JTextArea) field.get(inputBar);
    }

    private static class SentinelListUi extends BasicListUI {
    }
}
