package com.github.drafael.chat4j.provider.api.content;

public record ImagePart(AttachmentRef attachmentRef, Integer width, Integer height) implements ContentPart {

    public ImagePart {
        attachmentRef = attachmentRef == null ? new AttachmentRef(null, null, null, null, 0L, null) : attachmentRef;
    }

    @Override
    public String asTextProjection() {
        String dimensions = width != null && height != null
                ? ", %dx%d".formatted(width, height)
                : "";
        String size = attachmentRef.sizeBytes() > 0
                ? ", %d bytes".formatted(attachmentRef.sizeBytes())
                : "";
        return "[Image attached: %s%s%s]".formatted(attachmentRef.originalName(), dimensions, size);
    }
}
