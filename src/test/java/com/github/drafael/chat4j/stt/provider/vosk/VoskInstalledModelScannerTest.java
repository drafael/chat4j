package com.github.drafael.chat4j.stt.provider.vosk;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class VoskInstalledModelScannerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Custom model IDs are disambiguated when folder slugs collide")
    void scan_whenCustomFolderSlugsCollide_assignsDistinctStableIds() throws Exception {
        Path root = tempDir.resolve("vosk");
        createValidModel(root.resolve("my model"));
        createValidModel(root.resolve("my@model"));
        var subject = new VoskInstalledModelScanner(new VoskModelValidator());

        var firstScan = subject.scan(root, emptyList());
        var secondScan = subject.scan(root, emptyList());

        assertThat(firstScan)
                .extracting(VoskInstalledModel::id)
                .containsExactlyInAnyOrderElementsOf(secondScan.stream().map(VoskInstalledModel::id).toList())
                .hasSize(2)
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).startsWith("local:my-model-"));
    }

    private void createValidModel(Path model) throws Exception {
        Files.createDirectories(model.resolve("am"));
        Files.createDirectories(model.resolve("conf"));
        Files.createDirectories(model.resolve("graph"));
        Files.writeString(model.resolve("am").resolve("final.mdl"), "model");
        Files.writeString(model.resolve("conf").resolve("model.conf"), "conf");
        Files.writeString(model.resolve("conf").resolve("mfcc.conf"), "mfcc");
        Files.writeString(model.resolve("graph").resolve("HCLG.fst"), "graph");
    }
}
