package com.github.drafael.chat4j.stt.provider.whisper;

import org.apache.commons.lang3.StringUtils;

public record WhisperLocalModelRow(
        String id,
        String label,
        String typeLanguage,
        String sizeLabel,
        WhisperModelCatalogEntry catalogEntry,
        WhisperInstalledModel installedModel,
        boolean selected,
        boolean downloadable,
        boolean deleteable,
        String status
) {

    public boolean installed() {
        return installedModel != null && installedModel.ready();
    }

    public boolean selectable() {
        return installedModel != null && installedModel.eligible();
    }

    public String actionStatus() {
        return StringUtils.defaultIfBlank(status, installed() ? "Installed" : downloadable ? "Available to download" : "Unavailable");
    }
}
