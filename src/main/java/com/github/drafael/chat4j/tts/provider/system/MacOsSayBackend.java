package com.github.drafael.chat4j.tts.provider.system;

import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

class MacOsSayBackend implements SystemTtsBackend {

    private static final Path SAY_PATH = Path.of("/usr/bin/say");
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern VOICE_LINE = Pattern.compile("^(.+?)\\s+([a-zA-Z]{2,3}(?:[_-][a-zA-Z0-9]+)*)\\s+#?\\s*(.*)$");

    private final SystemTtsProcessRunner runner;
    private final Map<String, String> environment;
    private final boolean available;

    MacOsSayBackend(SystemTtsProcessRunner runner, Map<String, String> environment) {
        this.runner = runner;
        this.environment = environment;
        this.available = Files.isExecutable(SAY_PATH);
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String unavailableMessage() {
        return "System Text to Speech is not available on this Mac.";
    }

    @Override
    public String availableMessage() {
        return "Uses your Mac's system text-to-speech engine.";
    }

    @Override
    public String defaultResponseFormat() {
        return "aiff";
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchVoices() throws Exception {
        if (!available()) {
            return emptyList();
        }
        SystemTtsCommandResult result = runner.run(List.of(SAY_PATH.toString(), "-v", "?"), environment, DISCOVERY_TIMEOUT);
        if (!result.successful()) {
            throw new IllegalStateException("Voice discovery failed: %s".formatted(result.safeErrorText()));
        }
        return result.stdout().lines()
                .map(MacOsSayBackend::parseVoiceLine)
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
        Path output = tempDir.resolve("speech.aiff");
        try {
            Files.writeString(input, request.text(), StandardCharsets.UTF_8);
            List<String> command = synthesisCommand(input, output, request.voiceId());
            SystemTtsCommandResult result = runner.run(command, environment, SystemTtsTimeouts.synthesisTimeout(request.text()));
            if (!result.successful()) {
                throw new IllegalStateException("System Text to Speech failed: %s".formatted(result.safeErrorText()));
            }
            byte[] bytes = SystemTtsFiles.readNonEmpty(output);
            return new TextToSpeechAudio(bytes, "audio/aiff", "aiff");
        } finally {
            SystemTtsFiles.deleteIfExists(input);
            SystemTtsFiles.deleteIfExists(output);
            SystemTtsFiles.deleteIfExists(tempDir);
        }
    }

    List<String> synthesisCommand(Path input, Path output, String voiceId) {
        List<String> command = new ArrayList<>();
        command.add(SAY_PATH.toString());
        if (!SystemTextToSpeechProvider.isDefaultVoice(voiceId)) {
            command.add("-v");
            command.add(voiceId);
        }
        command.add("-o");
        command.add(output.toString());
        command.add("--file-format=AIFF");
        command.add("--data-format=BEI16");
        command.add("-f");
        command.add(input.toString());
        return List.copyOf(command);
    }

    static TextToSpeechCatalogItem parseVoiceLine(String line) {
        String normalized = StringUtils.trimToEmpty(line);
        if (normalized.isBlank()) {
            return null;
        }
        var matcher = VOICE_LINE.matcher(normalized);
        if (!matcher.matches()) {
            return TextToSpeechCatalogItem.of(normalized, normalized);
        }
        String voice = matcher.group(1).trim();
        String locale = matcher.group(2).trim();
        String sample = matcher.group(3).trim();
        String description = StringUtils.defaultIfBlank(sample, locale);
        return new TextToSpeechCatalogItem(voice, voice, description);
    }
}
