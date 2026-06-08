package com.github.drafael.chat4j.chat.conversation;

import org.apache.commons.lang3.StringUtils;

public record ConversationAttachment(String storagePath, String originalName, String mimeType, long sizeBytes, boolean image) {
    public ConversationAttachment {
        storagePath = StringUtils.defaultString(storagePath);
        originalName = StringUtils.defaultIfBlank(originalName, "Attachment");
        mimeType = StringUtils.defaultString(mimeType);
        sizeBytes = Math.max(0L, sizeBytes);
    }

    @Override
    public String toString() {
        return "ConversationAttachment[storagePath=<masked>, originalName=%s, mimeType=%s, sizeBytes=%d, image=%s]"
                .formatted(originalName, mimeType, sizeBytes, image);
    }
}
