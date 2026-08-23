package com.github.drafael.chat4j.http;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;

public final class HttpBodyTestSupport {
    private HttpBodyTestSupport() {
    }

    public static byte[] bytes(HttpBody body) throws Exception {
        return switch (body) {
            case HttpBody.Empty ignored -> new byte[0];
            case HttpBody.Bytes bytes -> bytes.value();
            case HttpBody.File file -> Files.readAllBytes(file.path());
            case HttpBody.Composite composite -> {
                var output = new ByteArrayOutputStream();
                for (HttpBody part : composite.parts()) {
                    output.write(bytes(part));
                }
                yield output.toByteArray();
            }
        };
    }
}
