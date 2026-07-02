package com.github.drafael.chat4j.chat.conversation;

import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
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
        List<ConversationAttachment> attachments,
        List<ContentPart> parts,
        MessageMeta meta
) {
    public ConversationEntry {
        kind = kind == null ? ConversationEntryKind.MESSAGE : kind;
        role = role == null ? Role.ASSISTANT : role;
        title = StringUtils.defaultString(title);
        text = StringUtils.defaultString(text);
        attachments = attachments == null ? emptyList() : List.copyOf(attachments);
        parts = parts == null ? emptyList() : List.copyOf(parts);
        meta = meta == null ? MessageMeta.empty() : meta;
    }

    public ConversationEntry(
            ConversationEntryKind kind,
            Role role,
            String title,
            String text,
            boolean collapsed,
            int messageIndex,
            List<ConversationAttachment> attachments
    ) {
        this(kind, role, title, text, collapsed, messageIndex, attachments, emptyList(), MessageMeta.empty());
    }

    @Override
    public String toString() {
        return "ConversationEntry[kind=%s, role=%s, title=%s, text=<masked>, collapsed=%s, messageIndex=%d, attachments=%d, parts=%d, citations=%d]"
                .formatted(kind, role, title, collapsed, messageIndex, attachments.size(), parts.size(), meta.citations().size());
    }

    public static ConversationEntry message(Role role, String text, int messageIndex) {
        return message(role, text, messageIndex, emptyList(), emptyList());
    }

    public static ConversationEntry message(Role role, String text, int messageIndex, List<ConversationAttachment> attachments) {
        return message(role, text, messageIndex, attachments, emptyList());
    }

    public static ConversationEntry message(
            Role role,
            String text,
            int messageIndex,
            List<ConversationAttachment> attachments,
            List<ContentPart> parts
    ) {
        return message(role, text, messageIndex, attachments, parts, MessageMeta.empty());
    }

    public static ConversationEntry message(
            Role role,
            String text,
            int messageIndex,
            List<ConversationAttachment> attachments,
            List<ContentPart> parts,
            MessageMeta meta
    ) {
        return new ConversationEntry(
                ConversationEntryKind.MESSAGE,
                role,
                "",
                StringUtils.defaultString(text),
                false,
                messageIndex,
                attachments,
                parts,
                meta
        );
    }

    public static ConversationEntry activity(String title, String text, boolean collapsed) {
        return new ConversationEntry(
                ConversationEntryKind.ACTIVITY,
                Role.ASSISTANT,
                title,
                StringUtils.defaultString(text),
                collapsed,
                -1,
                emptyList(),
                emptyList(),
                MessageMeta.empty()
        );
    }
}
