package com.github.drafael.chat4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StoragePathsTest {

    @Test
    @DisplayName("Default paths use XDG_CONFIG_HOME when the environment variable is configured")
    void defaultPaths_whenXdgConfigHomeIsConfigured_usesXdgConfigHomeDirectory() {
        var xdgConfigHome = System.getenv("XDG_CONFIG_HOME");
        var subject = StoragePaths.defaultPaths();

        assertThat(xdgConfigHome).isNotBlank();
        assertThat(subject.appConfigDirectory())
                .isEqualTo(Path.of(xdgConfigHome).resolve("chat4j"));
        assertThat(subject.databaseDirectory())
                .isEqualTo(Path.of(xdgConfigHome).resolve("chat4j").resolve("data"));
    }
}
