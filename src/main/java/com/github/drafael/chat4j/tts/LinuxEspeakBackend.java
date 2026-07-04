package com.github.drafael.chat4j.tts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

class LinuxEspeakBackend implements SystemTtsBackend {

    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(5);
    private static final String ESPEAK_NG = "espeak-ng";

    private final SystemTtsProcessRunner runner;
    private final Map<String, String> environment;
    private final Path executable;

    LinuxEspeakBackend(
            SystemTtsProcessRunner runner,
            SystemTtsExecutableLocator locator,
            Map<String, String> environment
    ) {
        this.runner = runner;
        this.environment = environment;
        this.executable = locator.findOnPath(ESPEAK_NG, environment).orElse(null);
    }

    @Override
    public boolean available() {
        return executable != null;
    }

    @Override
    public String unavailableMessage() {
        return "Install espeak-ng to use System Text to Speech on Linux.";
    }

    @Override
    public String availableMessage() {
        return "Uses espeak-ng for system text-to-speech on Linux.";
    }

    @Override
    public String defaultResponseFormat() {
        return "wav";
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchVoices() throws Exception {
        if (!available()) {
            return emptyList();
        }
        SystemTtsCommandResult result = runner.run(List.of(executable.toString(), "--voices"), environment, DISCOVERY_TIMEOUT);
        if (!result.successful()) {
            throw new IllegalStateException("Voice discovery failed: %s".formatted(result.safeErrorText()));
        }
        return result.stdout().lines()
                .map(LinuxEspeakBackend::parseVoiceLine)
                .filter(item -> item != null)
                .toList();
    }

    @Override
    public TextToSpeechAudio synthesize(TextToSpeechRequest request) throws Exception {
        if (!available()) {
            throw new IllegalStateException(unavailableMessage());
        }
        Path tempDir = Files.createTempDirectory("chat4j-system-tts");
        Path input = tempDir.resolve("input.txt");
        Path output = tempDir.resolve("speech.wav");
        try {
            Files.writeString(input, request.text(), StandardCharsets.UTF_8);
            List<String> command = synthesisCommand(input, output, request.voiceId());
            SystemTtsCommandResult result = runner.run(command, environment, SystemTtsTimeouts.synthesisTimeout(request.text()));
            if (!result.successful()) {
                throw new IllegalStateException("System Text to Speech failed: %s".formatted(result.safeErrorText()));
            }
            byte[] bytes = SystemTtsFiles.readNonEmpty(output);
            return new TextToSpeechAudio(bytes, "audio/wav", "wav");
        } finally {
            SystemTtsFiles.deleteIfExists(input);
            SystemTtsFiles.deleteIfExists(output);
            SystemTtsFiles.deleteIfExists(tempDir);
        }
    }

    List<String> synthesisCommand(Path input, Path output, String voiceId) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        if (!SystemTextToSpeechProvider.isDefaultVoice(voiceId)) {
            command.add("-v");
            command.add(voiceId);
        }
        command.add("-w");
        command.add(output.toString());
        command.add("-f");
        command.add(input.toString());
        return List.copyOf(command);
    }

    static TextToSpeechCatalogItem parseVoiceLine(String line) {
        String normalized = StringUtils.normalizeSpace(line);
        if (StringUtils.isBlank(normalized) || normalized.startsWith("Pty ")) {
            return null;
        }
        String[] parts = normalized.split(" ");
        if (parts.length < 4 || !StringUtils.isNumeric(parts[0])) {
            return null;
        }
        String language = parts[1];
        String gender = parts[2];
        String voiceName = parts[3].replace('_', ' ');
        String label = "%s (%s)".formatted(voiceName, language);
        return new TextToSpeechCatalogItem(language, label, gender);
    }
}
