package com.github.drafael.chat4j.settings;

import java.util.concurrent.CompletableFuture;

public interface AsyncPendingSettingsSaveParticipant extends PendingSettingsSaveParticipant {

    CompletableFuture<Boolean> savePendingChangesAsync();

    @Override
    default boolean savePendingChanges() {
        try {
            return savePendingChangesAsync().get();
        } catch (Exception e) {
            return false;
        }
    }
}
