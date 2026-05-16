package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.provider.api.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.*;
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
    @DisplayName("Swing content view marks itself disposed and clears popup menu")
    void dispose_whenCalled_marksDisposedAndClearsPopupMenu() {
        JEditorPaneMessageContentView subject = new JEditorPaneMessageContentView(Role.ASSISTANT, () -> Integer.MAX_VALUE);
        subject.setContextMenu(new JPopupMenu());

        subject.dispose();

        assertThat(subject.isDisposed()).isTrue();
        assertThat(subject.editorPane().getComponentPopupMenu()).isNull();
    }
}
