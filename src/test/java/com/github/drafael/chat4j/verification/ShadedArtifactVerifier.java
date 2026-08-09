package com.github.drafael.chat4j.verification;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.BooleanSupplier;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

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

        verifyOpenHtmlToPdfIsReplaceable(artifact);
        URL[] artifactUrl = {artifact.toUri().toURL()};
        try (var classLoader = new URLClassLoader(artifactUrl, ClassLoader.getPlatformClassLoader())) {
            verifyWebpService(classLoader);
            verifyMcpJacksonServices(classLoader);
            verifyPackagedPdfExport(classLoader);
        }
        System.out.println("Isolated shaded WebP, MCP Jackson, and PDF export verification passed");
    }

    private static void verifyOpenHtmlToPdfIsReplaceable(Path artifact) throws Exception {
        try (var archive = new JarFile(artifact.toFile())) {
            if (archive.getEntry("com/openhtmltopdf/pdfboxout/PdfRendererBuilder.class") != null) {
                throw new IllegalStateException("OpenHTMLtoPDF must remain outside the shaded application JAR");
            }
            String classPath = archive.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            List<String> replaceableLibraries = classPath == null
                    ? List.of()
                    : List.of(classPath.split("\\s+"));
            if (replaceableLibraries.stream().noneMatch(name -> name.startsWith("openhtmltopdf-core-"))
                    || replaceableLibraries.stream().noneMatch(name -> name.startsWith("openhtmltopdf-pdfbox-"))
            ) {
                throw new IllegalStateException("OpenHTMLtoPDF sibling JARs are missing from the application class path");
            }
            Path directory = artifact.getParent();
            if (replaceableLibraries.stream().map(directory::resolve).anyMatch(path -> !Files.isRegularFile(path))) {
                throw new IllegalStateException("A replaceable OpenHTMLtoPDF sibling JAR is missing");
            }
        }
    }

    private static void verifyPackagedPdfExport(ClassLoader classLoader) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(classLoader);
        Path output = Files.createTempFile("chat4j-packaged-pdf-", ".pdf");
        try {
            Class<?> documentType = Class.forName(
                    "com.github.drafael.chat4j.chat.export.pdf.ConversationPdfDocument",
                    true,
                    classLoader
            );
            Object document = documentType.getConstructor(
                    String.class,
                    String.class,
                    String.class,
                    LocalDateTime.class,
                    Instant.class,
                    List.class
            ).newInstance("Packaged PDF smoke test", "", "", LocalDateTime.now(), Instant.now(), List.of());
            Class<?> exporterType = Class.forName(
                    "com.github.drafael.chat4j.chat.export.pdf.OpenHtmlConversationPdfExporter",
                    true,
                    classLoader
            );
            Object exporter = exporterType.getConstructor().newInstance();
            exporterType.getMethod("export", documentType, Path.class, BooleanSupplier.class)
                    .invoke(exporter, document, output, (BooleanSupplier) () -> false);
            byte[] signature = Files.readAllBytes(output);
            if (signature.length < 4
                    || signature[0] != '%'
                    || signature[1] != 'P'
                    || signature[2] != 'D'
                    || signature[3] != 'F'
            ) {
                throw new IllegalStateException("Packaged PDF exporter did not create a PDF");
            }
        } finally {
            Files.deleteIfExists(output);
            thread.setContextClassLoader(previous);
        }
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
