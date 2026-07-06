package com.github.drafael.chat4j.stt.provider.vosk;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class VoskModelValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Official small model layout without words or phones text is valid")
    void validate_whenOfficialSmallLayoutOmitsOptionalFiles_acceptsModel() throws Exception {
        Path root = tempDir.resolve("vosk");
        Path model = root.resolve("vosk-model-small-en-us-0.15");
        Files.createDirectories(model.resolve("am"));
        Files.createDirectories(model.resolve("conf"));
        Files.createDirectories(model.resolve("graph"));
        Files.writeString(model.resolve("am").resolve("final.mdl"), "model");
        Files.writeString(model.resolve("conf").resolve("model.conf"), "conf");
        Files.writeString(model.resolve("conf").resolve("mfcc.conf"), "mfcc");
        Files.writeString(model.resolve("graph").resolve("HCLr.fst"), "hclr");
        Files.writeString(model.resolve("graph").resolve("Gr.fst"), "gr");

        var subject = new VoskModelValidator();

        var result = subject.validate(model, root);

        assertThat(result.status()).isEqualTo(VoskValidationStatus.VALID);
    }

    @Test
    @DisplayName("Unsafe catalog names are rejected before path derivation")
    void safeModelName_whenNameContainsTraversal_rejects() {
        var subject = new VoskModelValidator();

        assertThat(subject.safeModelName("../vosk-model")).isFalse();
        assertThat(subject.safeModelName("vosk/model")).isFalse();
        assertThat(subject.safeModelName("vosk-model-small-en-us-0.15")).isTrue();
    }
}
