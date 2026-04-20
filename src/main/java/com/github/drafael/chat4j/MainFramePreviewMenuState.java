package com.github.drafael.chat4j;

import javax.swing.JCheckBoxMenuItem;

public class MainFramePreviewMenuState {

    private JCheckBoxMenuItem togglePreviewMenuItem;
    private boolean syncingPreviewMenuSelection;

    public JCheckBoxMenuItem togglePreviewMenuItem() {
        return togglePreviewMenuItem;
    }

    public boolean syncingPreviewMenuSelection() {
        return syncingPreviewMenuSelection;
    }

    public void setTogglePreviewMenuItem(JCheckBoxMenuItem togglePreviewMenuItem) {
        this.togglePreviewMenuItem = togglePreviewMenuItem;
    }

    public void setSyncingPreviewMenuSelection(boolean syncingPreviewMenuSelection) {
        this.syncingPreviewMenuSelection = syncingPreviewMenuSelection;
    }
}
