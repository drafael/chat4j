package com.github.drafael.chat4j.stt.provider.vosk;

import com.github.drafael.chat4j.persistence.catalog.CatalogGroup;
import com.github.drafael.chat4j.persistence.catalog.CatalogPayload;
import com.github.drafael.chat4j.persistence.catalog.CatalogSnapshotStore;
import com.github.drafael.chat4j.persistence.catalog.SpeechCatalogKeySchema;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

public class VoskModelCatalogCache {

    private final CatalogSnapshotStore snapshots;

    public VoskModelCatalogCache(@NonNull SettingsRepository settingsRepo) {
        this(CatalogSnapshotStore.forSettings(settingsRepo));
    }

    public VoskModelCatalogCache(@NonNull CatalogSnapshotStore snapshots) {
        this.snapshots = snapshots;
    }

    public Optional<String> rawJson() {
        CatalogGroup group = SpeechCatalogKeySchema.voskRawJson();
        return snapshots.read(group).payload(group.slots().getFirst()).map(CatalogPayload::value);
    }

    public boolean saveRawJson(String json) {
        return snapshots.save(SpeechCatalogKeySchema.voskRawJson(), List.of(CatalogPayload.of(json)));
    }
}
