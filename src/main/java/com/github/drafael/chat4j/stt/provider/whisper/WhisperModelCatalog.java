package com.github.drafael.chat4j.stt.provider.whisper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.stream.Collectors.toMap;

public final class WhisperModelCatalog {

    private static final String OFFICIAL_REPO = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";
    private static final String TINYDIARIZE_REPO = "https://huggingface.co/akashmjn/tinydiarize-whisper.cpp/resolve/main/";

    private static final List<WhisperModelCatalogEntry> ENTRIES = List.of(
            entry("tiny", "75 MiB", 77691713L, "bd577a113a864445d4c299885e0cb97d4ba92b5f"),
            entry("tiny.en", "75 MiB", 77704715L, "c78c86eb1a8faa21b369bcd33207cc90d64ae9df"),
            entry("tiny-q5_1", "31 MiB", 32152673L, "2827a03e495b1ed3048ef28a6a4620537db4ee51"),
            entry("tiny.en-q5_1", "31 MiB", 32166155L, "3fb92ec865cbbc769f08137f22470d6b66e071b6"),
            entry("tiny-q8_0", "42 MiB", 43537433L, "19e8118f6652a650569f5a949d962154e01571d9"),
            entry("base", "142 MiB", 147951465L, "465707469ff3a37a2b9b8d8f89f2f99de7299dac"),
            entry("base.en", "142 MiB", 147964211L, "137c40403d78fd54d454da0f9bd998f78703390c"),
            entry("base-q5_1", "57 MiB", 59707625L, "a3733eda680ef76256db5fc5dd9de8629e62c5e7"),
            entry("base.en-q5_1", "57 MiB", 59721011L, "d26d7ce5a1b6e57bea5d0431b9c20ae49423c94a"),
            entry("base-q8_0", "78 MiB", 81768585L, "7bb89bb49ed6955013b166f1b6a6c04584a20fbe"),
            entry("small", "466 MiB", 487601967L, "55356645c2b361a969dfd0ef2c5a50d530afd8d5"),
            entry("small.en", "466 MiB", 487614201L, "db8a495a91d927739e50b3fc1cc4c6b8f6c2d022"),
            entry("small.en-tdrz", "465 MiB", 487614184L, "b6c6e7e89af1a35c08e6de56b66ca6a02a2fdfa1"),
            entry("small-q5_1", "181 MiB", 190085487L, "6fe57ddcfdd1c6b07cdcc73aaf620810ce5fc771"),
            entry("small.en-q5_1", "181 MiB", 190098681L, "20f54878d608f94e4a8ee3ae56016571d47cba34"),
            entry("small-q8_0", "252 MiB", 264464607L, "bcad8a2083f4e53d648d586b7dbc0cd673d8afad"),
            entry("medium", "1.5 GiB", 1533763059L, "fd9727b6e1217c2f614f9b698455c4ffd82463b4"),
            entry("medium.en", "1.5 GiB", 1533774781L, "8c30f0e44ce9560643ebd10bbe50cd20eafd3723"),
            entry("medium-q5_0", "514 MiB", 539212467L, "7718d4c1ec62ca96998f058114db98236937490e"),
            entry("medium.en-q5_0", "514 MiB", 539225533L, "bb3b5281bddd61605d6fc76bc5b92d8f20284c3b"),
            entry("medium-q8_0", "785 MiB", 823369779L, "e66645948aff4bebbec71b3485c576f3d63af5d6"),
            entry("large-v1", "2.9 GiB", 3094623691L, "b1caaf735c4cc1429223d5a74f0f4d0b9b59a299"),
            entry("large-v2", "2.9 GiB", 3094623691L, "0f4c8e34f21cf1a914c59d8b3ce882345ad349d6"),
            entry("large-v2-q5_0", "1.1 GiB", 1080732091L, "00e39f2196344e901b3a2bd5814807a769bd1630"),
            entry("large-v2-q8_0", "1.5 GiB", 1656129691L, "da97d6ca8f8ffbeeb5fd147f79010eeea194ba38"),
            entry("large-v3", "2.9 GiB", 3095033483L, "ad82bf6a9043ceed055076d0fd39f5f186ff8062"),
            entry("large-v3-q5_0", "1.1 GiB", 1081140203L, "e6e2ed78495d403bef4b7cff42ef4aaadcfea8de"),
            entry("large-v3-turbo", "1.5 GiB", 1624555275L, "4af2b29d7ec73d781377bfd1758ca957a807e941"),
            entry("large-v3-turbo-q5_0", "547 MiB", 574041195L, "e050f7970618a659205450ad97eb95a18d69c9ee"),
            entry("large-v3-turbo-q8_0", "834 MiB", 874188075L, "01bf15bedffe9f39d65c1b6ff9b687ea91f59e0e")
    );

    private static final Map<String, WhisperModelCatalogEntry> BY_ID = ENTRIES.stream().collect(toMap(WhisperModelCatalogEntry::id, entry -> entry));

    private WhisperModelCatalog() {
    }

    public static List<WhisperModelCatalogEntry> entries() {
        return ENTRIES;
    }

    public static Optional<WhisperModelCatalogEntry> find(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    private static WhisperModelCatalogEntry entry(String id, String sizeLabel, long sizeBytes, String sha1) {
        boolean tinydiarize = id.contains("tdrz");
        String expectedFileName = "ggml-%s.bin".formatted(id);
        String description = description(id);
        String label = "Whisper %s".formatted(id);
        URI uri = URI.create((tinydiarize ? TINYDIARIZE_REPO : OFFICIAL_REPO) + expectedFileName);
        return new WhisperModelCatalogEntry(
                id,
                label,
                description,
                sizeLabel,
                uri,
                sizeBytes,
                sha1,
                expectedFileName,
                id.contains(".en"),
                id.contains("-q"),
                tinydiarize
        );
    }

    private static String description(String id) {
        if (id.contains("tdrz")) {
            return "Tinydiarize Whisper.cpp model; Chat4J v1 returns plain transcript text.";
        }
        String family = id.split("[.-]")[0];
        String language = id.contains(".en") ? "English-only" : "multilingual";
        String quantized = id.contains("-q") ? " quantized" : "";
        return "%s%s %s Whisper.cpp ggml model.".formatted(family, quantized, language);
    }
}
