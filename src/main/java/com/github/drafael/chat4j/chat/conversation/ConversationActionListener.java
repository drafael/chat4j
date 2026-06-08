package com.github.drafael.chat4j.chat.conversation;

@FunctionalInterface
public interface ConversationActionListener {
    void handle(String action, int messageIndex, String text);
}
