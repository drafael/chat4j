package com.github.drafael.chat4j.verification;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.ServiceLoader;

public final class ShadedArtifactVerifier {

    private ShadedArtifactVerifier() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the shaded artifact path");
        }
        Path artifact = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalStateException("Shaded artifact does not exist: %s".formatted(artifact));
        }

        URL[] artifactUrl = {artifact.toUri().toURL()};
        try (var classLoader = new URLClassLoader(artifactUrl, ClassLoader.getPlatformClassLoader())) {
            verifyWebpService(classLoader);
            verifyMcpJacksonServices(classLoader);
        }
        System.out.println("Isolated shaded WebP and MCP Jackson service verification passed");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void verifyMcpJacksonServices(ClassLoader classLoader) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(classLoader);
        try {
            verifyMcpJacksonServicesWithContext(classLoader);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void verifyMcpJacksonServicesWithContext(ClassLoader classLoader) throws Exception {
        Class<?> mapperSupplier = Class.forName(
                "io.modelcontextprotocol.json.McpJsonMapperSupplier",
                true,
                classLoader
        );
        Object mapper = ServiceLoader.load((Class<Object>) (Class<?>) mapperSupplier, classLoader).findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP Jackson mapper service is missing"));
        Object mapperInstance = mapperSupplier.getMethod("get").invoke(mapper);
        if (!mapperInstance.getClass().getName().contains("JacksonMcpJsonMapper")) {
            throw new IllegalStateException("MCP Jackson mapper service resolved the wrong implementation");
        }

        Class<?> validatorSupplier = Class.forName(
                "io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier",
                true,
                classLoader
        );
        Object validator = ServiceLoader.load((Class<Object>) (Class<?>) validatorSupplier, classLoader).findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP JSON schema validator service is missing"));
        Object validatorInstance = validatorSupplier.getMethod("get").invoke(validator);
        if (!validatorInstance.getClass().getName().contains("DefaultJsonSchemaValidator")) {
            throw new IllegalStateException("MCP JSON schema validator service resolved the wrong implementation");
        }
    }

    private static void verifyWebpService(ClassLoader classLoader) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(classLoader);
        try {
            ImageIO.scanForPlugins();
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("webp");
            if (!readers.hasNext()) {
                throw new IllegalStateException("The shaded artifact did not expose a WebP ImageIO reader");
            }
            ImageReader reader = readers.next();
            try {
                if (reader.getClass().getClassLoader() != classLoader) {
                    throw new IllegalStateException("The WebP reader was not loaded from the isolated shaded artifact");
                }
            } finally {
                reader.dispose();
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
