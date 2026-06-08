package com.github.drafael.chat4j.chat.content;

import javax.swing.*;

public interface MessageContentView {

    JComponent component();

    void setHtml(String html);

    String htmlSnapshot();

    String textSnapshot();

    void setContextMenu(JPopupMenu popupMenu);

    void installKeyBinding(KeyStroke keyStroke, String actionName, Action action);

    void selectAll();

    boolean hasSelection();

    void copySelection();

    void requestContentFocus();

    void invalidateLayout();

    void dispose();

    boolean isDisposed();
}
