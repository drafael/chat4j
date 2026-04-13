package com.github.drafael.chat4j.provider.api.content;

public record FilePart(AttachmentRef attachmentRef) implements ContentPart {

    public FilePart {
        attachmentRef = attachmentRef == null ? new AttachmentRef(null, null, null, null, 0L, null) : attachmentRef;
    }

    @Override
    public String asTextProjection() {
        String size = attachmentRef.sizeBytes() > 0
                ? ", %d bytes".formatted(attachmentRef.sizeBytes())
                : "";
        return "[File attached: %s%s]".formatted(attachmentRef.originalName(), size);
    }
}
