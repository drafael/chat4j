package com.github.drafael.chat4j.stt.provider.sphinx4;

import org.apache.commons.lang3.StringUtils;

public record Sphinx4ModelArtifact(
        String artifactId,
        String role,
        String url,
        long expectedSizeBytes,
        long expectedUncompressedBytes,
        String sha256,
        String archiveFormat,
        String extractTo,
        String targetPath,
        boolean stripTopLevelDirectory,
        boolean required
) {

    public boolean requiredArtifact() {
        return required;
    }

    public boolean archive() {
        String format = StringUtils.trimToEmpty(archiveFormat);
        return "zip".equals(format) || "tar.gz".equals(format) || "tgz".equals(format) || "tar.xz".equals(format);
    }

    public boolean rawGzip() {
        return "raw-gzip".equals(StringUtils.trimToEmpty(archiveFormat));
    }

    public boolean rawFile() {
        return "raw-file".equals(StringUtils.trimToEmpty(archiveFormat));
    }
}
