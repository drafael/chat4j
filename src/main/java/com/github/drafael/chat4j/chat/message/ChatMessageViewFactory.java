package com.github.drafael.chat4j.chat.message;

import com.github.drafael.chat4j.provider.api.Role;

public final class ChatMessageViewFactory {

    private final MessageContentViewProvider contentViewProvider;
    private final MessageHtmlRenderer messageHtmlRenderer;

    public ChatMessageViewFactory() {
        this(ChatWebViewEngine.JEDITOR_PANE);
    }

    public ChatMessageViewFactory(ChatWebViewEngine engine) {
        this(providerFor(engine), new MessageHtmlRenderer());
    }

    ChatMessageViewFactory(MessageContentViewProvider contentViewProvider, MessageHtmlRenderer messageHtmlRenderer) {
        this.contentViewProvider = contentViewProvider;
        this.messageHtmlRenderer = messageHtmlRenderer;
    }

    public ChatMessageView create(Role role) {
        return new MessageBubble(role, contentViewProvider, messageHtmlRenderer);
    }

    private static MessageContentViewProvider providerFor(ChatWebViewEngine engine) {
        return JEditorPaneMessageContentView::new;
    }
}
