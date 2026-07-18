package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.concurrent.CopyOnWriteArrayList;

public class ApiTokenFieldRegistry {

    private final CopyOnWriteArrayList<ApiTokenFieldPanel> fields = new CopyOnWriteArrayList<>();

    public void register(ApiTokenFieldPanel field) {
        fields.addIfAbsent(field);
    }

    public void unregister(ApiTokenFieldPanel field) {
        fields.remove(field);
    }

    public String conflictMessage(ApiTokenFieldPanel field) {
        if (field == null || !field.dirty()) {
            return "";
        }
        return fields.stream()
                .filter(other -> other != field)
                .filter(ApiTokenFieldPanel::dirty)
                .filter(other -> Strings.CS.equals(other.canonicalTokenId(), field.canonicalTokenId()))
                .filter(field::hasDifferentPendingValue)
                .findFirst()
                .map(other -> "Another settings tab has unsaved changes for %s. Save or clear one value first."
                        .formatted(field.canonicalTokenId()))
                .orElse("");
    }

    public String conflictMessage() {
        return fields.stream()
                .filter(ApiTokenFieldPanel::dirty)
                .map(this::conflictMessage)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }

    public void broadcastCredentialChanging(String canonicalTokenId) {
        fields.stream()
                .filter(field -> Strings.CS.equals(field.canonicalTokenId(), canonicalTokenId))
                .forEach(ApiTokenFieldPanel::prepareForCredentialChange);
    }

    public void broadcastAllCredentialsChanging() {
        fields.forEach(ApiTokenFieldPanel::prepareForCredentialChange);
    }

    public void broadcastSaved(ApiTokenFieldPanel source, String canonicalTokenId) {
        fields.stream()
                .filter(field -> field != source)
                .filter(field -> Strings.CS.equals(field.canonicalTokenId(), canonicalTokenId))
                .filter(field -> !field.dirty())
                .forEach(ApiTokenFieldPanel::reloadAfterPeerCredentialChanged);
    }

    public void broadcastVaultRecreated(ApiTokenFieldPanel source) {
        fields.stream()
                .filter(field -> field != source)
                .filter(field -> !field.dirty())
                .forEach(ApiTokenFieldPanel::reloadAfterPeerCredentialChanged);
    }
}
