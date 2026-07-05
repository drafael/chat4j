package com.github.drafael.chat4j.settings;

public interface PendingSettingsSaveParticipant {

    boolean savePendingChanges();

    String lastSaveError();

    default String settingsSectionName() {
        return "Settings";
    }
}
