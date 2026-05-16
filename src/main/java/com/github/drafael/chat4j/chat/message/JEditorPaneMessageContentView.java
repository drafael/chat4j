package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.provider.api.Role;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.function.IntSupplier;

final class JEditorPaneMessageContentView implements MessageContentView {

    private final JEditorPane editorPane;
    private boolean disposed;

    JEditorPaneMessageContentView(Role role, IntSupplier maxContentWidthSupplier) {
        editorPane = new WrappingEditorPane(role, maxContentWidthSupplier);
        editorPane.setEditable(false);
        editorPane.setOpaque(false);
        editorPane.setContentType("text/html");
        editorPane.setEditorKit(new HTMLEditorKit());
        editorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        if (editorPane.getCaret() instanceof DefaultCaret caret) {
            caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }
        configureHyperlinkSupport();
    }

    @Override
    public JComponent component() {
        return editorPane;
    }

    @Override
    public void setHtml(String html) {
        editorPane.setText(StringUtils.defaultString(html));
    }

    @Override
    public String htmlSnapshot() {
        return editorPane.getText();
    }

    @Override
    public String textSnapshot() {
        try {
            var document = editorPane.getDocument();
            return document.getText(0, document.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    @Override
    public void setContextMenu(JPopupMenu popupMenu) {
        editorPane.setComponentPopupMenu(popupMenu);
    }

    @Override
    public void installKeyBinding(KeyStroke keyStroke, String actionName, Action action) {
        editorPane.getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, actionName);
        editorPane.getActionMap().put(actionName, action);
    }

    @Override
    public void selectAll() {
        editorPane.selectAll();
    }

    @Override
    public boolean hasSelection() {
        return editorPane.getSelectionStart() != editorPane.getSelectionEnd();
    }

    @Override
    public void copySelection() {
        Action action = editorPane.getActionMap().get(DefaultEditorKit.copyAction);
        if (action == null) {
            return;
        }

        ActionEvent forwarded = new ActionEvent(editorPane, ActionEvent.ACTION_PERFORMED, DefaultEditorKit.copyAction);
        action.actionPerformed(forwarded);
    }

    @Override
    public void requestContentFocus() {
        editorPane.requestFocusInWindow();
    }

    @Override
    public void invalidateLayout() {
        editorPane.invalidate();
    }

    @Override
    public void dispose() {
        disposed = true;
        editorPane.setComponentPopupMenu(null);
    }

    @Override
    public boolean isDisposed() {
        return disposed;
    }

    JEditorPane editorPane() {
        return editorPane;
    }

    private void configureHyperlinkSupport() {
        editorPane.addHyperlinkListener(event -> {
            if (event.getEventType() != HyperlinkEvent.EventType.ACTIVATED) {
                return;
            }

            String link = event.getURL() != null ? event.getURL().toExternalForm() : event.getDescription();
            if (StringUtils.isBlank(link)) {
                return;
            }

            ExternalLinkSupport.openExternalLink(link);
        });
    }

    private static final class WrappingEditorPane extends JEditorPane {

        private final Role role;
        private final IntSupplier maxContentWidthSupplier;

        private WrappingEditorPane(Role role, IntSupplier maxContentWidthSupplier) {
            this.role = role;
            this.maxContentWidthSupplier = maxContentWidthSupplier;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public Dimension getPreferredSize() {
            int maxContentWidth = maxContentWidthSupplier.getAsInt();
            if (role == Role.USER && maxContentWidth > 0 && maxContentWidth != Integer.MAX_VALUE) {
                setSize(maxContentWidth, Short.MAX_VALUE);
                return super.getPreferredSize();
            }

            Container parent = getParent();
            if (parent != null && parent.getWidth() > 0) {
                setSize(parent.getWidth(), Short.MAX_VALUE);
            }
            return super.getPreferredSize();
        }
    }
}
