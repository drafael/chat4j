package com.github.drafael.chat4j.settings;

import java.util.concurrent.CompletableFuture;

public interface AsyncPendingSettingsSaveParticipant {

    CompletableFuture<Boolean> savePendingChangesAsync();

    String lastSaveError();

    default String settingsSectionName() {
        return "Settings";
    }
}
