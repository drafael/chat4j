package com.github.drafael.chat4j.stt.provider.sphinx4;

import java.util.List;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

public record Sphinx4ModelCatalogEntry(
        String id,
        String label,
        String language,
        String description,
        boolean catalogOnly,
        boolean downloadable,
        boolean verifiedDownload,
        long sizeBytes,
        int sampleRateHz,
        String licenseName,
        String licenseUrl,
        String sourceNotes,
        List<Sphinx4ModelArtifact> artifacts,
        Sphinx4ModelRecipe recipe
) {

    public Sphinx4ModelCatalogEntry {
        artifacts = artifacts == null ? emptyList() : List.copyOf(artifacts);
    }

    public boolean hasVerifiedDownload() {
        return verifiedDownload && downloadable && !catalogOnly && !artifacts.isEmpty();
    }

    public boolean canDownload() {
        return hasVerifiedDownload() || downloadable;
    }

    public String displayLanguage() {
        return StringUtils.defaultIfBlank(language, "Unknown");
    }

    public String displaySize() {
        if (sizeBytes <= 0) {
            return "";
        }
        double mib = sizeBytes / 1024.0 / 1024.0;
        return "%.1f MB".formatted(mib);
    }
}
