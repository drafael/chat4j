package com.github.drafael.chat4j.stt.provider.sphinx4;

import org.apache.commons.lang3.StringUtils;

public record Sphinx4LocalModelRow(
        String id,
        String label,
        String language,
        String type,
        String sizeLabel,
        Sphinx4ModelCatalogEntry catalogEntry,
        Sphinx4InstalledModel installedModel,
        boolean selected,
        boolean downloadable,
        boolean deleteable,
        String status
) {

    public boolean installed() {
        return installedModel != null;
    }

    public boolean selectable() {
        return installedModel != null && installedModel.selectable();
    }

    public String actionStatus() {
        if (StringUtils.isNotBlank(status)) {
            return status;
        }
        if (downloadable) {
            return "Available to download";
        }
        if (installed()) {
            return installedModel.validationMessage();
        }
        return "Catalog only";
    }
}
