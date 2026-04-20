package com.github.drafael.chat4j;

public class MainFrameModelMenuState {

    private boolean modelsMenuDirty;
    private String lastMenuSelectedModelKey;

    public MainFrameModelMenuState() {
        this(true, null);
    }

    MainFrameModelMenuState(boolean modelsMenuDirty, String lastMenuSelectedModelKey) {
        this.modelsMenuDirty = modelsMenuDirty;
        this.lastMenuSelectedModelKey = lastMenuSelectedModelKey;
    }

    public boolean modelsMenuDirty() {
        return modelsMenuDirty;
    }

    public String lastMenuSelectedModelKey() {
        return lastMenuSelectedModelKey;
    }

    public void setModelsMenuDirty(boolean modelsMenuDirty) {
        this.modelsMenuDirty = modelsMenuDirty;
    }

    public void setLastMenuSelectedModelKey(String lastMenuSelectedModelKey) {
        this.lastMenuSelectedModelKey = lastMenuSelectedModelKey;
    }

    public void markModelsMenuDirty() {
        modelsMenuDirty = true;
    }
}
