package com.github.drafael.chat4j.persistence.catalog;

import com.github.drafael.chat4j.persistence.settings.SettingsKeySlugs;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

/** The sole constructor of speech snapshot keys and filename namespaces. */
public final class SpeechCatalogKeySchema {

    private static final int MAX_RAW_ID_BYTES = 4096;
    private static final int MAX_SLUG_LENGTH = 96;
    private static final String PROVIDER_SLUG_PATTERN = "[a-z0-9]+(?:-[a-z0-9]+)*";
    private static final Pattern ASCII_SLUG = Pattern.compile(PROVIDER_SLUG_PATTERN);
    private static final Pattern STT_REFERENCE_KEY = Pattern.compile(
            "chat4j\\.stt\\.catalog\\.(%s)\\.modelsFile".formatted(PROVIDER_SLUG_PATTERN)
    );
    private static final Pattern TTS_REFERENCE_KEY = Pattern.compile(
            "chat4j\\.tts\\.catalog\\.(%s)\\.(models|voices)File".formatted(PROVIDER_SLUG_PATTERN)
    );

    private SpeechCatalogKeySchema() {
    }

    public static CatalogGroup sttModels(String providerId) {
        String slug = providerSlug(providerId);
        Validate.isTrue(!"vosk".equals(slug), "Vosk raw catalog must use its reserved snapshot slot");
        return new CatalogGroup(
                List.of(new SnapshotSlot("chat4j.stt.catalog.%s.modelsFile".formatted(slug), "stt-%s-models-".formatted(slug))),
                "chat4j.stt.catalog.%s.updatedAt".formatted(slug)
        );
    }

    public static CatalogGroup tts(String providerId) {
        String slug = providerSlug(providerId);
        return new CatalogGroup(
                List.of(
                        new SnapshotSlot("chat4j.tts.catalog.%s.modelsFile".formatted(slug), "tts-%s-models-".formatted(slug)),
                        new SnapshotSlot("chat4j.tts.catalog.%s.voicesFile".formatted(slug), "tts-%s-voices-".formatted(slug))
                ),
                "chat4j.tts.catalog.%s.updatedAt".formatted(slug)
        );
    }

    public static CatalogGroup voskRawJson() {
        return new CatalogGroup(
                List.of(new SnapshotSlot("chat4j.stt.catalog.vosk.rawJsonFile", "stt-vosk-raw-json-")),
                "chat4j.stt.catalog.vosk.rawJson.updatedAt"
        );
    }

    static Optional<CatalogGroup> groupForReferenceKey(String key) {
        if ("chat4j.stt.catalog.vosk.rawJsonFile".equals(key)) {
            return Optional.of(voskRawJson());
        }
        Matcher stt = STT_REFERENCE_KEY.matcher(key);
        if (stt.matches() && isCanonicalSlug(stt.group(1)) && !"vosk".equals(stt.group(1))) {
            return Optional.of(sttModels(stt.group(1)));
        }
        Matcher tts = TTS_REFERENCE_KEY.matcher(key);
        return tts.matches() && isCanonicalSlug(tts.group(1))
                ? Optional.of(tts(tts.group(1)))
                : Optional.empty();
    }

    static Optional<SnapshotSlot> slotForReferenceKey(String key) {
        return groupForReferenceKey(key).flatMap(group -> group.slots().stream()
                .filter(slot -> slot.referenceKey().equals(key))
                .findFirst());
    }

    public static void validateUniqueProviderSlugs(@NonNull List<String> providerIds) {
        Set<String> slugs = new HashSet<>();
        providerIds.stream()
                .map(SpeechCatalogKeySchema::providerSlug)
                .forEach(slug -> Validate.isTrue(slugs.add(slug), "provider catalog slugs must be unique"));
    }

    public static String providerSlug(String providerId) {
        Validate.notBlank(providerId, "providerId should not be blank");
        Validate.isTrue(strictUtf8Length(providerId) <= MAX_RAW_ID_BYTES, "providerId exceeds 4096 UTF-8 bytes");
        String slug = SettingsKeySlugs.providerSlug(providerId);
        Validate.isTrue(isCanonicalSlug(slug),
                "providerId does not produce a valid catalog slug");
        Validate.isTrue(!"unknown".equals(slug) || "unknown".equals(providerId.trim().toLowerCase(Locale.ROOT)),
                "providerId must contain letters or numbers");
        return slug;
    }

    private static boolean isCanonicalSlug(String value) {
        return value.length() <= MAX_SLUG_LENGTH && ASCII_SLUG.matcher(value).matches();
    }

    private static int strictUtf8Length(String value) {
        try {
            return StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value))
                    .remaining();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("providerId is not valid UTF-8", e);
        }
    }
}
