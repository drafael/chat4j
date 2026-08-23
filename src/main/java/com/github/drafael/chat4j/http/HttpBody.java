package com.github.drafael.chat4j.http;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import lombok.NonNull;

public sealed interface HttpBody permits HttpBody.Empty, HttpBody.Bytes, HttpBody.File, HttpBody.Composite {

    static Empty empty() {
        return new Empty();
    }

    static Bytes bytes(byte[] value) {
        return new Bytes(value);
    }

    static Bytes utf8(String value) {
        return bytes(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    static File file(@NonNull Path path) {
        return new File(path);
    }

    static Composite composite(@NonNull List<HttpBody> parts) {
        return new Composite(parts);
    }

    record Empty() implements HttpBody {
    }

    record Bytes(byte[] value) implements HttpBody {
        public Bytes {
            value = value == null ? new byte[0] : value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public String toString() {
            return "HttpBody.Bytes[value=<masked:%d>]".formatted(value.length);
        }
    }

    record File(@NonNull Path path) implements HttpBody {
        @Override
        public String toString() {
            return "HttpBody.File[path=<masked>]";
        }
    }

    record Composite(List<HttpBody> parts) implements HttpBody {
        public Composite {
            parts = parts == null ? List.of() : List.copyOf(parts);
        }

        @Override
        public String toString() {
            return "HttpBody.Composite[parts=<masked:%d>]".formatted(parts.size());
        }
    }
}
