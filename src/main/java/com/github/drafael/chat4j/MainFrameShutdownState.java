package com.github.drafael.chat4j;

public class MainFrameShutdownState {

    private boolean shutdownInProgress;

    public boolean shutdownInProgress() {
        return shutdownInProgress;
    }

    public void setShutdownInProgress(boolean shutdownInProgress) {
        this.shutdownInProgress = shutdownInProgress;
    }
}
