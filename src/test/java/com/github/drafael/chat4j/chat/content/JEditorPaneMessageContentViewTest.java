package com.github.drafael.chat4j.chat.content;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class JEditorPaneMessageContentViewTest {

    @Test
    @DisplayName("Swing content view creates a non-editable HTML pane")
    void constructor_whenCreated_configuresEditorPaneForHtmlDisplay() {
        JEditorPaneMessageContentView subject = new JEditorPaneMessageContentView(Role.ASSISTANT, () -> Integer.MAX_VALUE);

        JEditorPane pane = subject.editorPane();

        assertThat(pane.isEditable()).isFalse();
        assertThat(pane.isOpaque()).isFalse();
        assertThat(pane.getContentType()).isEqualTo("text/html");
        assertThat(pane.getClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES)).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("Swing content view installs popup menus and key bindings")
    void installKeyBinding_whenActionRegistered_invokesActionThroughEditorPane() {
        JEditorPaneMessageContentView subject = new JEditorPaneMessageContentView(Role.ASSISTANT, () -> Integer.MAX_VALUE);
        JPopupMenu popupMenu = new JPopupMenu();
        KeyStroke keyStroke = KeyStroke.getKeyStroke("shift meta A");
        AtomicBoolean invoked = new AtomicBoolean(false);

        subject.setContextMenu(popupMenu);
        subject.installKeyBinding(keyStroke, "selectConversation", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                invoked.set(true);
            }
        });

        JEditorPane pane = subject.editorPane();
        pane.getActionMap().get("selectConversation").actionPerformed(new ActionEvent(pane, ActionEvent.ACTION_PERFORMED, "selectConversation"));

        assertThat(pane.getComponentPopupMenu()).isSameAs(popupMenu);
        assertThat(pane.getInputMap(JComponent.WHEN_FOCUSED).get(keyStroke)).isEqualTo("selectConversation");
        assertThat(invoked).isTrue();
    }

    @Test
    @DisplayName("Swing content view writes HTML and exposes rendered snapshots")
    void setHtml_whenCalled_updatesEditorTextAndSnapshots() {
        JEditorPaneMessageContentView subject = new JEditorPaneMessageContentView(Role.ASSISTANT, () -> Integer.MAX_VALUE);

        subject.setHtml("<html><body>Hello</body></html>");

        assertThat(subject.editorPane().getText()).contains("Hello");
        assertThat(subject.htmlSnapshot()).contains("Hello");
        assertThat(subject.textSnapshot()).contains("Hello");
    }

    @Test
    @DisplayName("User content view wraps long code lines within the configured width")
    void getPreferredSize_whenUserMessageContainsWideCode_staysWithinConfiguredWidth() {
        int maxWidth = 260;
        JEditorPaneMessageContentView subject = new JEditorPaneMessageContentView(Role.USER, () -> maxWidth);
        MessageHtmlRenderer renderer = new MessageHtmlRenderer();
        String message = """
                review the code snippet: ```@Getter
                @RequiredArgsConstructor
                public enum ErrorCode {
                INVALID_REQUEST(\"GEN_001\", \"Invalid request\", \"Check request parameters\", HttpStatus.BAD_REQUEST),
                DEVICE_NOT_FOUND(\"GEN_002\", \"Device not found\", \"Ensure device is registered for this user\", HttpStatus.NOT_FOUND),
                VERSION_NOT_FOUND(\"GEN_003\", \"Version not found\", \"Ensure app version exists in versions table\", HttpStatus.BAD_REQUEST);
                private final String userAction;
                }```
                """;

        subject.setHtml(renderer.render(Role.USER, RenderMode.PREVIEW, message, false));
        Dimension preferredSize = subject.editorPane().getPreferredSize();

        assertThat(preferredSize.width).isLessThanOrEqualTo(maxWidth);
        assertThat(preferredSize.height).isPositive();
    }

    @Test
    @DisplayName("User content view can break long words instead of expanding horizontally")
    void getPreferredSize_whenUserMessageContainsLongToken_staysWithinConfiguredWidth() {
        int maxWidth = 180;
        String html = new MessageHtmlRenderer()
                .render(Role.USER, RenderMode.PREVIEW, "token-" + "x".repeat(300), false);
        JEditorPaneMessageContentView subject = new JEditorPaneMessageContentView(Role.USER, () -> maxWidth);
        JEditorPaneMessageContentView wideSubject = new JEditorPaneMessageContentView(Role.USER, () -> 800);

        subject.setHtml(html);
        wideSubject.setHtml(html);
        Dimension preferredSize = subject.editorPane().getPreferredSize();
        Dimension widePreferredSize = wideSubject.editorPane().getPreferredSize();

        assertThat(preferredSize.width).isLessThanOrEqualTo(maxWidth);
        assertThat(preferredSize.height).isGreaterThan(widePreferredSize.height);
    }

    @Test
    @DisplayName("Markdown content view can break long source lines instead of expanding horizontally")
    void getPreferredSize_whenMarkdownModeContainsLongToken_staysWithinConfiguredWidth() {
        int maxWidth = 220;
        String html = new MessageHtmlRenderer()
                .render(Role.USER, RenderMode.MARKDOWN, "```java\n" + "x".repeat(300) + "\n```", false);
        JEditorPaneMessageContentView subject = new JEditorPaneMessageContentView(Role.USER, () -> maxWidth);

        subject.setHtml(html);
        Dimension preferredSize = subject.editorPane().getPreferredSize();

        assertThat(preferredSize.width).isLessThanOrEqualTo(maxWidth);
        assertThat(preferredSize.height).isPositive();
    }

    @Test
    @DisplayName("Markdown mode keeps explicit response line breaks")
    void getPreferredSize_whenMarkdownModeUsesLineBreaks_preservesFormatting() {
        int maxWidth = 1_000;
        String markdown = """
                ## Code Review

                ### Overall Assessment
                - **Strength**
                ```java
                String value;
                ```
                """;
        MessageHtmlRenderer renderer = new MessageHtmlRenderer();
        JEditorPaneMessageContentView lineBreakSubject = new JEditorPaneMessageContentView(Role.USER, () -> maxWidth);
        JEditorPaneMessageContentView singleLineSubject = new JEditorPaneMessageContentView(Role.USER, () -> maxWidth);

        lineBreakSubject.setHtml(renderer.render(Role.USER, RenderMode.MARKDOWN, markdown, false));
        singleLineSubject.setHtml(renderer.render(Role.USER, RenderMode.MARKDOWN, markdown.replace('\n', ' '), false));

        assertThat(lineBreakSubject.htmlSnapshot()).contains("<pre>");
        assertThat(lineBreakSubject.editorPane().getPreferredSize().height)
                .isGreaterThan(singleLineSubject.editorPane().getPreferredSize().height);
    }

    @Test
    @DisplayName("Swing content view marks itself disposed and clears popup menu")
    void dispose_whenCalled_marksDisposedAndClearsPopupMenu() {
        JEditorPaneMessageContentView subject = new JEditorPaneMessageContentView(Role.ASSISTANT, () -> Integer.MAX_VALUE);
        subject.setContextMenu(new JPopupMenu());

        subject.dispose();

        assertThat(subject.isDisposed()).isTrue();
        assertThat(subject.editorPane().getComponentPopupMenu()).isNull();
    }
}
