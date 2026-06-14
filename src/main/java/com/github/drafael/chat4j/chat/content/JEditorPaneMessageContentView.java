package com.github.drafael.chat4j.chat.content;

import com.github.drafael.chat4j.provider.api.Role;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.InlineView;
import javax.swing.text.html.ParagraphView;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntSupplier;

public final class JEditorPaneMessageContentView implements MessageContentView {

    private final JEditorPane editorPane;
    private boolean disposed;

    public JEditorPaneMessageContentView(Role role, IntSupplier maxContentWidthSupplier) {
        editorPane = new WrappingEditorPane(role, maxContentWidthSupplier);
        editorPane.setEditable(false);
        editorPane.setOpaque(false);
        editorPane.setContentType("text/html");
        editorPane.setEditorKit(new WrappingHtmlEditorKit());
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

            if (openAttachmentLink(event)) {
                return;
            }

            String link = event.getURL() != null ? event.getURL().toExternalForm() : event.getDescription();
            if (StringUtils.isBlank(link)) {
                return;
            }

            ExternalLinkSupport.openExternalLink(link);
        });
    }

    private boolean openAttachmentLink(HyperlinkEvent event) {
        String attachmentPath = sourceElementAttribute(event.getSourceElement(), "data-attachment-path");
        if (StringUtils.isBlank(attachmentPath) && isGeneratedImageLink(event)) {
            attachmentPath = filePathFromEventUrl(event);
        }
        if (StringUtils.isBlank(attachmentPath)) {
            return false;
        }
        openLocalAttachment(attachmentPath);
        return true;
    }

    private boolean isGeneratedImageLink(HyperlinkEvent event) {
        String className = sourceElementAttribute(event.getSourceElement(), "class");
        return StringUtils.contains(className, "generated-image-wrap");
    }

    private String filePathFromEventUrl(HyperlinkEvent event) {
        if (event.getURL() == null || !"file".equalsIgnoreCase(event.getURL().getProtocol())) {
            return "";
        }
        try {
            return Path.of(URI.create(event.getURL().toExternalForm())).toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String sourceElementAttribute(Element element, String attributeName) {
        Element current = element;
        while (current != null) {
            AttributeSet attributes = current.getAttributes();
            Object directValue = attributes.getAttribute(attributeName);
            if (directValue != null) {
                return directValue.toString();
            }
            Object anchorValue = attributes.getAttribute(HTML.Tag.A);
            if (anchorValue instanceof AttributeSet anchorAttributes) {
                Object value = anchorAttributes.getAttribute(attributeName);
                if (value != null) {
                    return value.toString();
                }
            }
            current = current.getParentElement();
        }
        return "";
    }

    private void openLocalAttachment(String attachmentPath) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return;
        }
        try {
            Path path = Path.of(attachmentPath);
            if (Files.isRegularFile(path)) {
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (Exception ignored) {
        }
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
                Dimension preferredSize = super.getPreferredSize();
                return new Dimension(Math.min(maxContentWidth, preferredSize.width), preferredSize.height);
            }

            Container parent = getParent();
            if (parent != null && parent.getWidth() > 0) {
                setSize(parent.getWidth(), Short.MAX_VALUE);
            }
            return super.getPreferredSize();
        }
    }

    private static final class WrappingHtmlEditorKit extends HTMLEditorKit {

        private final ViewFactory viewFactory = new HTMLFactory() {
            @Override
            public View create(Element element) {
                View view = super.create(element);
                if (view instanceof InlineView && isContentElement(element)) {
                    return new WrappingInlineView(element);
                }
                if (view instanceof ParagraphView) {
                    return new WrappingParagraphView(element);
                }
                return view;
            }

            private boolean isContentElement(Element element) {
                Object name = element.getAttributes().getAttribute(StyleConstants.NameAttribute);
                return HTML.Tag.CONTENT.equals(name);
            }
        };

        @Override
        public ViewFactory getViewFactory() {
            return viewFactory;
        }
    }

    private static final class WrappingInlineView extends InlineView {

        private WrappingInlineView(Element element) {
            super(element);
        }

        @Override
        public int getBreakWeight(int axis, float pos, float len) {
            if (axis == View.X_AXIS) {
                return GoodBreakWeight;
            }
            return super.getBreakWeight(axis, pos, len);
        }

        @Override
        public View breakView(int axis, int p0, float pos, float len) {
            if (axis != View.X_AXIS) {
                return super.breakView(axis, p0, pos, len);
            }

            checkPainter();
            int p1 = getGlyphPainter().getBoundedPosition(this, p0, pos, len);
            if (p1 <= p0) {
                p1 = Math.min(p0 + 1, getEndOffset());
            }
            if (p0 == getStartOffset() && p1 == getEndOffset()) {
                return this;
            }
            return createFragment(p0, p1);
        }

        @Override
        public float getMinimumSpan(int axis) {
            if (axis == View.X_AXIS) {
                return 0;
            }
            return super.getMinimumSpan(axis);
        }
    }

    private static final class WrappingParagraphView extends ParagraphView {

        private WrappingParagraphView(Element element) {
            super(element);
        }

        @Override
        protected SizeRequirements calculateMinorAxisRequirements(int axis, SizeRequirements requirements) {
            SizeRequirements resolvedRequirements = super.calculateMinorAxisRequirements(axis, requirements);
            if (axis == View.X_AXIS) {
                resolvedRequirements.minimum = 0;
            }
            return resolvedRequirements;
        }
    }
}
