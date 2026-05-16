package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Role;

import javax.swing.*;

public interface ChatMessageView {

    JComponent component();

    Role getRole();

    void appendText(String token);

    void setText(String text);

    String getFullText();

    void setAssistantRenderMode(AssistantRenderMode assistantRenderMode);

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
