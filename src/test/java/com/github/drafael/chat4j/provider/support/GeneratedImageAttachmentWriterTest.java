package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedImageAttachmentWriterTest {

    @Test
    @DisplayName("Generated images use UUID-only managed storage names")
    void write_whenImageIsValid_usesUuidStorageName(@TempDir Path tempDir) throws Exception {
        Path attachmentRoot = tempDir.resolve("attachments");
        Files.createDirectories(attachmentRoot);
        var authority = new ProviderAttachmentSupport(attachmentRoot);
        var subject = new GeneratedImageAttachmentWriter(authority);
        byte[] png = pngBytes();

        var result = subject.write(png, "image/png");
        Path storedPath = Path.of(result.storagePath());

        assertThat(storedPath).startsWith(attachmentRoot);
        assertThat(storedPath.getFileName().toString()).isEqualTo(UUID.fromString(
                storedPath.getFileName().toString()
        ).toString());
        assertThat(result.originalName()).startsWith("generated-image-").endsWith(".png");
        assertThat(Files.readAllBytes(storedPath)).isEqualTo(png);
        assertThat(authority.resolve(result, true)).isPresent();
        try (var files = Files.list(storedPath.getParent())) {
            assertThat(files).allMatch(path -> !path.getFileName().toString().endsWith(".part"));
        }
    }

    @Test
    @DisplayName("TwelveMonkeys WebP reader is registered through ImageIO services")
    void imageIo_whenWebpPluginIsPresent_discoversReader() {
        assertThat(ImageIO.getImageReadersByMIMEType("image/webp").hasNext()).isTrue();
    }

    @Test
    @DisplayName("A UUID collision preserves the pre-existing destination and retries safely")
    void write_whenFinalNameCollides_preservesExistingDestination(@TempDir Path tempDir) throws Exception {
        Path attachmentRoot = tempDir.resolve("attachments");
        Files.createDirectories(attachmentRoot);
        var authority = new ProviderAttachmentSupport(attachmentRoot);
        UUID collisionId = UUID.randomUUID();
        UUID publishedId = UUID.randomUUID();
        Path dayDirectory = attachmentRoot.resolve(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
        Files.createDirectories(dayDirectory);
        Path existing = dayDirectory.resolve(collisionId.toString());
        byte[] existingBytes = "existing".getBytes();
        Files.write(existing, existingBytes);
        var ids = List.of(collisionId, publishedId).iterator();
        var subject = new GeneratedImageAttachmentWriter(authority, ids::next);

        AttachmentRef result = subject.write(pngBytes(), "image/png");

        assertThat(Files.readAllBytes(existing)).isEqualTo(existingBytes);
        assertThat(Path.of(result.storagePath()).getFileName().toString()).isEqualTo(publishedId.toString());
        assertThat(Files.exists(dayDirectory.resolve("%s.part".formatted(collisionId)))).isFalse();
    }

    @Test
    @DisplayName("Discard removes a substituted symlink instead of its managed target")
    void discard_whenGeneratedLeafBecomesSymlink_preservesTarget(@TempDir Path tempDir) throws Exception {
        Path attachmentRoot = Files.createDirectories(tempDir.resolve("attachments"));
        var authority = new ProviderAttachmentSupport(attachmentRoot);
        var subject = new GeneratedImageAttachmentWriter(authority);
        AttachmentRef published = subject.write(pngBytes(), "image/png");
        Path publishedPath = Path.of(published.storagePath());
        Path target = Files.writeString(publishedPath.resolveSibling("target"), "keep");
        Files.delete(publishedPath);
        try {
            Files.createSymbolicLink(publishedPath, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }

        subject.discard(published);

        assertThat(publishedPath).doesNotExist();
        assertThat(target).exists().hasContent("keep");
    }

    @Test
    @DisplayName("Truncated generated images are rejected before publication")
    void write_whenImageCannotBeDecoded_rejectsImage(@TempDir Path tempDir) throws Exception {
        Path attachmentRoot = tempDir.resolve("attachments");
        Files.createDirectories(attachmentRoot);
        var subject = new GeneratedImageAttachmentWriter(new ProviderAttachmentSupport(attachmentRoot));
        byte[] png = pngBytes();
        assertThat(png.length).isGreaterThan(41);
        byte[] truncated = Arrays.copyOf(png, 41);

        assertThatThrownBy(() -> subject.write(truncated, "image/png"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Generated image data could not be decoded.");
    }

    @Test
    @DisplayName("Every frame of a generated GIF must satisfy the dimension limit")
    void write_whenLaterGifFrameIsOversized_rejectsImage(@TempDir Path tempDir) throws Exception {
        Path attachmentRoot = tempDir.resolve("attachments");
        Files.createDirectories(attachmentRoot);
        var subject = new GeneratedImageAttachmentWriter(new ProviderAttachmentSupport(attachmentRoot));
        byte[] gif = animatedGifBytes(1, 16_385);

        assertThatThrownBy(() -> subject.write(gif, "image/gif"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Generated image dimensions exceeded the managed image limit.");
    }

    @Test
    @DisplayName("A symlinked day directory cannot redirect generated image publication")
    void write_whenDayDirectoryIsSymlink_rejectsPublication(@TempDir Path tempDir) throws Exception {
        Path attachmentRoot = Files.createDirectories(tempDir.resolve("attachments"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path dayDirectory = attachmentRoot.resolve(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
        try {
            Files.createSymbolicLink(dayDirectory, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }
        var subject = new GeneratedImageAttachmentWriter(new ProviderAttachmentSupport(attachmentRoot));

        assertThatThrownBy(() -> subject.write(pngBytes(), "image/png"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Could not prepare managed image storage.");
        try (var outsideFiles = Files.list(outside)) {
            assertThat(outsideFiles).isEmpty();
        }
    }

    @Test
    @DisplayName("Publication failures do not expose the managed attachment path")
    void write_whenPublicationFails_returnsPathSafeError(@TempDir Path tempDir) throws Exception {
        Path attachmentRoot = tempDir.resolve("private-attachments");
        Files.createDirectories(attachmentRoot);
        Path dayPath = attachmentRoot.resolve(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
        Files.writeString(dayPath, "not a directory");
        var authority = new ProviderAttachmentSupport(attachmentRoot);
        var subject = new GeneratedImageAttachmentWriter(authority);

        assertThatThrownBy(() -> subject.write(pngBytes(), "image/png"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("Could not prepare managed image storage.")
                .hasMessageNotContaining(attachmentRoot.toString())
                .hasMessageNotContaining(dayPath.toString())
                .hasNoCause();
    }

    private static byte[] animatedGifBytes(int firstFrameWidth, int secondFrameWidth) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.prepareWriteSequence(null);
            writer.writeToSequence(new IIOImage(
                    new BufferedImage(firstFrameWidth, 1, BufferedImage.TYPE_INT_ARGB),
                    null,
                    null
            ), null);
            writer.writeToSequence(new IIOImage(
                    new BufferedImage(secondFrameWidth, 1, BufferedImage.TYPE_INT_ARGB),
                    null,
                    null
            ), null);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private static byte[] pngBytes() throws Exception {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
