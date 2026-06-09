package com.github.drafael.chat4j.chat.conversation.webview.jcef;

import me.friwi.jcefmaven.EnumProgress;
import org.apache.commons.lang3.StringUtils;

public record JcefInitializationProgress(String stage, String message, int percent, boolean determinate) {

    public JcefInitializationProgress {
        stage = StringUtils.defaultIfBlank(stage, "Preparing Chromium");
        message = StringUtils.defaultIfBlank(message, stage);
        percent = Math.clamp(percent, 0, 100);
    }

    public static JcefInitializationProgress from(EnumProgress stage, float progress) {
        boolean determinate = progress != EnumProgress.NO_ESTIMATION;
        int percent = determinate ? Math.round(progress) : 0;
        String message = messageFor(stage);
        return new JcefInitializationProgress(displayName(stage), message, percent, determinate);
    }

    private static String displayName(EnumProgress stage) {
        return switch (stage) {
            case LOCATING -> "Locating";
            case DOWNLOADING -> "Downloading";
            case EXTRACTING -> "Extracting";
            case INSTALL -> "Installing";
            case INITIALIZING -> "Starting";
            case INITIALIZED -> "Ready";
        };
    }

    private static String messageFor(EnumProgress stage) {
        return switch (stage) {
            case LOCATING -> "Locating Chromium bundle…";
            case DOWNLOADING -> "Downloading Chromium bundle…";
            case EXTRACTING -> "Extracting Chromium bundle…";
            case INSTALL -> "Installing Chromium bundle…";
            case INITIALIZING -> "Starting Chromium…";
            case INITIALIZED -> "Chromium ready";
        };
    }
}
