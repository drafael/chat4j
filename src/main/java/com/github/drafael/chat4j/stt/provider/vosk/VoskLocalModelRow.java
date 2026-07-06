package com.github.drafael.chat4j.stt.provider.vosk;

import org.apache.commons.lang3.StringUtils;

public record VoskLocalModelRow(
        String id,
        String label,
        String language,
        String type,
        String sizeLabel,
        VoskModelCatalogEntry catalogEntry,
        VoskInstalledModel installedModel,
        boolean selected,
        boolean downloadable,
        boolean deleteable,
        boolean obsolete,
        String status
) {

    public boolean installed() {
        return installedModel != null;
    }

    public boolean selectable() {
        return installedModel != null && installedModel.eligible();
    }

    public String actionStatus() {
        return StringUtils.defaultIfBlank(status, installed() ? "Installed" : downloadable ? "Available" : "Unavailable");
    }
}
