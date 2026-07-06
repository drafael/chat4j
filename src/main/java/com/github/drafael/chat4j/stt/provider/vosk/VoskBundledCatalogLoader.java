package com.github.drafael.chat4j.stt.provider.vosk;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static java.util.Collections.emptyList;

public class VoskBundledCatalogLoader {

    private static final String RESOURCE = "/stt/vosk/model-list-fallback.json";
    private final VoskModelCatalogClient parser;

    public VoskBundledCatalogLoader(VoskModelCatalogClient parser) {
        this.parser = parser;
    }

    public List<VoskModelCatalogEntry> load() {
        try (InputStream input = VoskBundledCatalogLoader.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return emptyList();
            }
            return parser.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return emptyList();
        }
    }

}
