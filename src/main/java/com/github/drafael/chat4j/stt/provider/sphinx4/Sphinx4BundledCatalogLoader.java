package com.github.drafael.chat4j.stt.provider.sphinx4;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import java.io.InputStream;
import java.util.List;

public class Sphinx4BundledCatalogLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String RESOURCE = "/stt/sphinx4/model-catalog.json";

    public List<Sphinx4ModelCatalogEntry> load() throws Exception {
        try (InputStream input = Sphinx4BundledCatalogLoader.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new SpeechToTextException("Bundled Sphinx4 catalog is missing.");
            }
            List<Sphinx4ModelCatalogEntry> entries = OBJECT_MAPPER.readValue(input, new TypeReference<>() {
            });
            return List.copyOf(entries);
        }
    }
}
