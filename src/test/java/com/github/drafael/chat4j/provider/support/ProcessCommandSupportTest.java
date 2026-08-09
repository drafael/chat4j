package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessCommandSupportTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Applying an environment replaces inherited values and resolves the executable from that snapshot")
    void applyEnvironment_whenSnapshotProvided_appliesExactSnapshotAndResolvesCommand() throws Exception {
        Path executable = Files.writeString(tempDir.resolve("chat4j-tool"), "test");
        executable.toFile().setExecutable(true);
        Map<String, String> environment = Map.of(
                "Path",
                tempDir.toString(),
                "CHAT4J_FLAG",
                "enabled"
        );
        ProcessBuilder processBuilder = new ProcessBuilder("chat4j-tool", "--version");

        ProcessCommandSupport.applyEnvironment(processBuilder, environment);

        assertThat(processBuilder.environment()).containsExactlyInAnyOrderEntriesOf(environment);
        assertThat(processBuilder.command()).containsExactly(executable.toString(), "--version");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("Direct executable discovery returns an absolute executable found on PATH")
    void findDirectExecutable_whenExecutableIsOnPath_returnsAbsolutePath() throws Exception {
        Path executable = Files.writeString(tempDir.resolve("chat4j-direct-tool"), "test");
        executable.toFile().setExecutable(true);

        assertThat(ProcessCommandSupport.findDirectExecutable(
                "chat4j-direct-tool",
                Map.of("PATH", tempDir.toString())
        )).contains(executable.toAbsolutePath().normalize().toString());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Direct Windows discovery skips shell launchers in favor of executables")
    void findDirectExecutable_whenCommandAndExecutableExist_returnsExecutable() throws Exception {
        Files.writeString(tempDir.resolve("chat4j-direct-tool.cmd"), "@echo off");
        Path executable = Files.writeString(tempDir.resolve("chat4j-direct-tool.exe"), "test");

        assertThat(ProcessCommandSupport.findDirectExecutable(
                "chat4j-direct-tool",
                Map.of("Path", tempDir.toString(), "PathExt", ".CMD;.EXE")
        )).contains(executable.toAbsolutePath().normalize().toString());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows executable discovery reads case-variant PathExt entries")
    void resolveCommand_whenWindowsPathExtUsesMixedCase_resolvesCommandFile() throws Exception {
        Path executable = Files.writeString(tempDir.resolve("chat4j-tool.cmd"), "@echo off");

        List<String> resolved = ProcessCommandSupport.resolveCommand(
                List.of("chat4j-tool"),
                Map.of("Path", tempDir.toString(), "PathExt", ".CMD;.EXE")
        );

        assertThat(resolved).containsExactly(executable.toString());
    }

    @Test
    @DisplayName("Commands containing a path component are preserved")
    void resolveCommand_whenExecutableAlreadyContainsPath_preservesCommand() {
        List<String> command = List.of("./chat4j-tool", "--version");

        List<String> resolved = ProcessCommandSupport.resolveCommand(command, Map.of("PATH", tempDir.toString()));

        assertThat(resolved).isSameAs(command);
    }

    @Test
    @DisplayName("Missing PATH leaves a bare executable unchanged")
    void resolveCommand_whenPathMissing_preservesCommand() {
        List<String> command = List.of("chat4j-tool");

        List<String> resolved = ProcessCommandSupport.resolveCommand(command, Map.of());

        assertThat(resolved).isSameAs(command);
    }
}
