package com.github.drafael.chat4j.persistence.catalog;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import lombok.NonNull;

/** A bounded UTF-8 snapshot payload. Its contents must never appear in diagnostics. */
public final class CatalogPayload {

    public static final int MAX_BYTES = 8 * 1024 * 1024;

    private final String value;
    private final byte[] bytes;

    private CatalogPayload(String value, byte[] bytes) {
        this.value = value;
        this.bytes = bytes.clone();
    }

    public static CatalogPayload of(@NonNull String value) {
        try {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(value));
            if (encoded.remaining() > MAX_BYTES) {
                throw new IllegalArgumentException("Catalog payload exceeds 8 MiB");
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return new CatalogPayload(value, bytes);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Catalog payload is not valid UTF-8", e);
        }
    }

    public String value() {
        return value;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public int byteLength() {
        return bytes.length;
    }

    @Override
    public String toString() {
        return "CatalogPayload[byteLength=%d]".formatted(bytes.length);
    }
}
