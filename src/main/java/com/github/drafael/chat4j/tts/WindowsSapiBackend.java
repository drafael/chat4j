package com.github.drafael.chat4j.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

class WindowsSapiBackend implements SystemTtsBackend {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(8);
    private static final String POWERSHELL_EXE = "powershell.exe";
    private static final Path POWERSHELL_RELATIVE_PATH = Path.of("System32", "WindowsPowerShell", "v1.0", POWERSHELL_EXE);
    private static final String VOICE_DISCOVERY_SCRIPT = """
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Speech
            $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
            try {
              $voices = $synth.GetInstalledVoices() | Where-Object { $_.Enabled } | ForEach-Object {
                $info = $_.VoiceInfo
                [PSCustomObject]@{
                  Name = $info.Name
                  Culture = $info.Culture.Name
                  Gender = $info.Gender.ToString()
                  Age = $info.Age.ToString()
                }
              }
              $voices | ConvertTo-Json -Compress
            } finally {
              $synth.Dispose()
            }
            """;
    private static final String SYNTHESIS_SCRIPT = """
            param($InputPath, $OutputPath, $VoiceName)
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Speech
            $text = [System.IO.File]::ReadAllText($InputPath, [System.Text.Encoding]::UTF8)
            $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
            try {
              if (-not [string]::IsNullOrWhiteSpace($VoiceName)) {
                $synth.SelectVoice($VoiceName)
              }
              $synth.SetOutputToWaveFile($OutputPath)
              $synth.Speak($text)
            } finally {
              $synth.Dispose()
            }
            """;

    private final SystemTtsProcessRunner runner;
    private final Map<String, String> environment;
    private final Path executable;
    private final boolean available;

    WindowsSapiBackend(
            SystemTtsProcessRunner runner,
            SystemTtsExecutableLocator locator,
            Map<String, String> environment
    ) {
        this.runner = runner;
        this.environment = environment;
        this.executable = findPowerShell(locator, environment).orElse(null);
        this.available = executable != null;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String unavailableMessage() {
        return "System Text to Speech is not available on this Windows computer.";
    }

    @Override
    public String availableMessage() {
        return "Uses your Windows system text-to-speech engine.";
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
        SystemTtsCommandResult result = runner.run(command(VOICE_DISCOVERY_SCRIPT), environment, DISCOVERY_TIMEOUT);
        if (!result.successful()) {
            throw new IllegalStateException("Voice discovery failed: %s".formatted(result.safeErrorText()));
        }
        return parseVoices(result.stdout());
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
            List<String> command = command(SYNTHESIS_SCRIPT, input.toString(), output.toString(), voiceArgument(request.voiceId()));
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

    private static Optional<Path> findPowerShell(SystemTtsExecutableLocator locator, Map<String, String> environment) {
        return locator.findOnPath(POWERSHELL_EXE, environment)
                .or(() -> windowsDirectory(environment)
                        .map(directory -> directory.resolve(POWERSHELL_RELATIVE_PATH))
                        .filter(Files::isExecutable));
    }

    private static Optional<Path> windowsDirectory(Map<String, String> environment) {
        if (environment == null) {
            return Optional.empty();
        }
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .filter(entry -> isWindowsDirectoryKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(StringUtils::isNotBlank)
                .map(WindowsSapiBackend::safePath)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<Path> safePath(String path) {
        try {
            return Optional.of(Path.of(path));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean isWindowsDirectoryKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return "systemroot".equals(normalized) || "windir".equals(normalized);
    }

    List<String> command(String script, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("-NoProfile");
        command.add("-NonInteractive");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-Command");
        command.add("& { %s }".formatted(script));
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    static List<TextToSpeechCatalogItem> parseVoices(String json) throws Exception {
        if (StringUtils.isBlank(json)) {
            return emptyList();
        }
        JsonNode root = OBJECT_MAPPER.readTree(json);
        List<JsonNode> nodes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(nodes::add);
        } else {
            nodes.add(root);
        }
        return nodes.stream()
                .map(WindowsSapiBackend::voiceItem)
                .filter(item -> item != null)
                .toList();
    }

    private static TextToSpeechCatalogItem voiceItem(JsonNode voice) {
        String name = voice.path("Name").asText("");
        if (StringUtils.isBlank(name)) {
            return null;
        }
        String culture = voice.path("Culture").asText("");
        String gender = voice.path("Gender").asText("");
        String age = voice.path("Age").asText("");
        String description = StringUtils.normalizeSpace(String.join(" ", List.of(culture, gender, age)));
        return new TextToSpeechCatalogItem(name, name, description);
    }

    private static String voiceArgument(String voiceId) {
        return SystemTextToSpeechProvider.isDefaultVoice(voiceId) ? "" : StringUtils.trimToEmpty(voiceId);
    }
}
