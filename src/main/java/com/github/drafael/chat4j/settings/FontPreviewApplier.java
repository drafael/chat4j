package com.github.drafael.chat4j.settings;

public class FontPreviewApplier {

    public void applyAppFont(String family, int size) {
        AppearancePanel.applyAppFont(family, size);
    }

    public void applyCodeFont(String family) {
        AppearancePanel.applyCodeFont(family);
    }
}
