package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.ContentPart;

import javax.swing.*;
import java.util.List;

import static java.util.Collections.emptyList;

public interface ChatMessageView {

    JComponent component();

    Role getRole();

    void appendText(String token);

    void setText(String text);

    default void appendPart(ContentPart part) {
    }

    default void setContentParts(List<ContentPart> parts) {
    }

    default List<ContentPart> contentPartsSnapshot() {
        return emptyList();
    }

    String getFullText();

    void setRenderMode(RenderMode renderMode);

    void setMaxContentWidth(int maxContentWidth);

    String contentHtmlSnapshot();

    String contentTextSnapshot();

    void setContextMenu(JPopupMenu popupMenu);

    void installKeyBinding(KeyStroke keyStroke, String actionName, Action action);

    void selectAllContent();

    boolean hasContentSelection();

    void copySelectedContent();

    void requestContentFocus();

    void dispose();

    boolean isDisposed();
}
