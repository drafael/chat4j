package com.github.drafael.chat4j.provider.api.content;

import org.apache.commons.lang3.StringUtils;

public record GeneratedImagePart(AttachmentRef attachmentRef, Integer width, Integer height, String altText) implements ContentPart {

    public GeneratedImagePart {
        attachmentRef = attachmentRef == null ? new AttachmentRef(null, null, null, null, 0L, null) : attachmentRef;
        altText = StringUtils.defaultIfBlank(altText, "Generated image");
    }

    @Override
    public String asTextProjection() {
        String dimensions = width != null && height != null
                ? ", %dx%d".formatted(width, height)
                : "";
        String size = attachmentRef.sizeBytes() > 0
                ? ", %d bytes".formatted(attachmentRef.sizeBytes())
                : "";
        return "[Generated image: %s%s%s]".formatted(altText, dimensions, size);
    }
}
