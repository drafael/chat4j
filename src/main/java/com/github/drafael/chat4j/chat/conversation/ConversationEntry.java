package com.github.drafael.chat4j.chat.conversation;

import com.github.drafael.chat4j.provider.api.Role;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

import static java.util.Collections.emptyList;

public record ConversationEntry(
        ConversationEntryKind kind,
        Role role,
        String title,
        String text,
        boolean collapsed,
        int messageIndex,
        List<ConversationAttachment> attachments
) {
    public ConversationEntry {
        kind = kind == null ? ConversationEntryKind.MESSAGE : kind;
        role = role == null ? Role.ASSISTANT : role;
        title = StringUtils.defaultString(title);
        text = StringUtils.defaultString(text);
        attachments = attachments == null ? emptyList() : List.copyOf(attachments);
    }

    @Override
    public String toString() {
        return "ConversationEntry[kind=%s, role=%s, title=%s, text=<masked>, collapsed=%s, messageIndex=%d, attachments=%d]"
                .formatted(kind, role, title, collapsed, messageIndex, attachments.size());
    }

    public static ConversationEntry message(Role role, String text, int messageIndex) {
        return message(role, text, messageIndex, emptyList());
    }

    public static ConversationEntry message(Role role, String text, int messageIndex, List<ConversationAttachment> attachments) {
        return new ConversationEntry(ConversationEntryKind.MESSAGE, role, "", StringUtils.defaultString(text), false, messageIndex, attachments);
    }

    public static ConversationEntry activity(String title, String text, boolean collapsed) {
        return new ConversationEntry(
                ConversationEntryKind.ACTIVITY,
                Role.ASSISTANT,
                title,
                StringUtils.defaultString(text),
                collapsed,
                -1,
                emptyList()
        );
    }
}
