package com.github.drafael.chat4j.persistence.catalog;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

/** Removes obsolete inline speech payload pointers without inspecting their values. */
@Slf4j
@RequiredArgsConstructor
public final class ObsoleteSpeechCatalogSettingsCleanup {

    private static final Pattern STT = Pattern.compile("chat4j\\.stt\\.catalog\\.([a-z0-9-]+)\\.models");
    private static final Pattern TTS = Pattern.compile("chat4j\\.tts\\.catalog\\.([a-z0-9-]+)\\.(models|voices)");
    private static final String VOSK_RAW_JSON = "chat4j.stt.catalog.vosk.rawJson";
    private static final int ENTRY_LIMIT = 100_000;

    @NonNull
    private final SettingsRepository settings;

    public void cleanup() {
        try {
            settings.updatePrefixBatch("chat4j.", ENTRY_LIMIT, batch -> {
                Set<String> sttProviders = new HashSet<>();
                Set<String> ttsProviders = new HashSet<>();
                boolean vosk = batch.values().containsKey(VOSK_RAW_JSON);
                batch.values().keySet().forEach(key -> classify(key, sttProviders, ttsProviders));
                batch.values().keySet().stream()
                        .filter(this::isObsoleteInlineKey)
                        .forEach(batch.updates()::remove);
                sttProviders.forEach(provider -> batch.updates().remove("chat4j.stt.catalog.%s.updatedAt".formatted(provider)));
                ttsProviders.forEach(provider -> batch.updates().remove("chat4j.tts.catalog.%s.updatedAt".formatted(provider)));
                if (vosk) {
                    batch.updates().remove("chat4j.stt.catalog.vosk.rawJson.updatedAt");
                }
            });
        } catch (RuntimeException e) {
            log.warn("Obsolete speech catalog cleanup skipped: {}", ExceptionUtils.getMessage(e));
        }
    }

    private void classify(String key, Set<String> sttProviders, Set<String> ttsProviders) {
        Matcher stt = STT.matcher(key);
        if (stt.matches()) {
            sttProviders.add(stt.group(1));
        }
        Matcher tts = TTS.matcher(key);
        if (tts.matches()) {
            ttsProviders.add(tts.group(1));
        }
    }

    private boolean isObsoleteInlineKey(String key) {
        return STT.matcher(key).matches() || TTS.matcher(key).matches() || VOSK_RAW_JSON.equals(key);
    }
}
