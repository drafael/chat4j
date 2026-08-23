package com.github.drafael.chat4j.json;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonCodecTest {

    private final JsonCodec subject = JsonCodec.standard();

    @Test
    @DisplayName("Known records, aliases, arrays, and additive fields decode from UTF-8 JSON")
    void read_whenPayloadUsesAliasAndUnknownField_decodesTypedRecords() {
        String json = "[{\"display_name\":\"Grüße\",\"future\":true}]";

        Item[] result = subject.read(json.getBytes(StandardCharsets.UTF_8), Item[].class);

        assertThat(result).containsExactly(new Item("Grüße"));
    }

    @Test
    @DisplayName("Compact and pretty serialization both preserve record values")
    void writeString_whenPrettyAndCompactRequested_preservesValues() {
        var value = new Item("voice");

        assertThat(subject.writeString(value)).isEqualTo("{\"name\":\"voice\"}");
        assertThat(subject.writePrettyString(value)).contains("\n").contains("\"name\" : \"voice\"");
        assertThat(subject.writePrettyBytes(value)).isEqualTo(subject.writePrettyString(value).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Malformed payload diagnostics do not include source content")
    void read_whenPayloadIsMalformed_throwsPayloadFreeException() {
        String secretPayload = "{\"token\":\"secret-value\"";

        assertThatThrownBy(() -> subject.read(secretPayload, Item.class))
                .isInstanceOf(JsonCodec.JsonCodecException.class)
                .hasMessage("JSON could not be decoded.")
                .hasMessageNotContaining("secret-value");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Item(@JsonAlias("display_name") String name) {
    }
}
