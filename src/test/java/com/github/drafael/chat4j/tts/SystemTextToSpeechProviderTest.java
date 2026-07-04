package com.github.drafael.chat4j.tts;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemTextToSpeechProviderTest {

    @Test
    @DisplayName("Default registry includes System provider first")
    void createDefault_always_includesSystemProviderFirst() {
        var subject = TextToSpeechProviderRegistry.createDefault();

        assertThat(subject.providers()).isNotEmpty();
        assertThat(subject.providers().getFirst().id()).isEqualTo("system");
    }

    @Test
    @DisplayName("System voices always keep System Default first")
    void voicesForModel_staleCachedVoices_keepsSystemDefaultFirst() {
        var subject = new SystemTextToSpeechProvider(new FakeBackend(true));

        List<TextToSpeechCatalogItem> voices = subject.voicesForModel(
                SystemTextToSpeechProvider.DEFAULT_MODEL,
                List.of(TextToSpeechCatalogItem.of("stale", "Stale Voice"))
        );

        assertThat(voices).extracting(TextToSpeechCatalogItem::id).containsExactly("system-default", "stale");
    }

    @Test
    @DisplayName("System voice discovery falls back to System Default")
    void fetchVoices_discoveryFails_returnsSystemDefault() throws Exception {
        var subject = new SystemTextToSpeechProvider(new FailingVoiceDiscoveryBackend());

        List<TextToSpeechCatalogItem> voices = subject.fetchVoices();

        assertThat(voices).extracting(TextToSpeechCatalogItem::id).containsExactly("system-default");
    }

    @Test
    @DisplayName("System provider exposes backend availability messages")
    void availabilityMessages_backendMessages_usesBackendMessages() {
        var subject = new SystemTextToSpeechProvider(new FakeBackend(false));

        assertThat(subject.unavailableMessage()).isEqualTo("System backend missing.");
        assertThat(subject.availableMessage()).isEqualTo("System backend available.");
        assertThat(subject.unavailableLabel()).isEqualTo("System (unavailable)");
    }

    @Test
    @DisplayName("System synthesis falls back to default voice when selected voice is stale")
    void synthesize_staleVoice_fallsBackToDefaultVoice() throws Exception {
        var backend = new FakeBackend(true);
        var subject = new SystemTextToSpeechProvider(backend);

        subject.synthesize(new TextToSpeechRequest("system", "system", "missing", "hello", "wav"));

        assertThat(backend.requests).extracting(TextToSpeechRequest::voiceId).containsExactly("missing", "system-default");
    }

    @Test
    @DisplayName("System synthesis does not retry non-voice failures")
    void synthesize_nonVoiceFailure_doesNotRetry() {
        var backend = new FakeBackend(true);
        var subject = new SystemTextToSpeechProvider(backend);

        assertThatThrownBy(() -> subject.synthesize(new TextToSpeechRequest("system", "system", "broken", "hello", "wav")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Output failed");

        assertThat(backend.requests).extracting(TextToSpeechRequest::voiceId).containsExactly("broken");
    }

    @Test
    @DisplayName("macOS backend omits voice argument for System Default")
    void macOsSynthesisCommand_defaultVoice_omitsVoiceArgument() {
        var subject = new MacOsSayBackend(new RecordingRunner(), Map.of());

        List<String> command = subject.synthesisCommand(Path.of("input.txt"), Path.of("output.aiff"), "system-default");

        assertThat(command).doesNotContain("-v");
        assertThat(command).contains("-o", "output.aiff", "--file-format=AIFF", "--data-format=BEI16", "-f", "input.txt");
    }

    @Test
    @DisplayName("macOS voice parser handles standard say output")
    void parseVoiceLine_standardSayOutput_parsesVoice() {
        TextToSpeechCatalogItem item = MacOsSayBackend.parseVoiceLine("Samantha            en_US    # Hello, my name is Samantha.");

        assertThat(item.id()).isEqualTo("Samantha");
        assertThat(item.label()).isEqualTo("Samantha");
        assertThat(item.description()).isEqualTo("Hello, my name is Samantha.");
    }

    @Test
    @DisplayName("macOS voice parser handles numeric locale variants")
    void parseVoiceLine_numericLocale_parsesVoice() {
        TextToSpeechCatalogItem item = MacOsSayBackend.parseVoiceLine("Majed               ar_001   # مرحبًا! اسمي ماجد.");

        assertThat(item.id()).isEqualTo("Majed");
        assertThat(item.label()).isEqualTo("Majed");
        assertThat(item.description()).isEqualTo("مرحبًا! اسمي ماجد.");
    }

    @Test
    @DisplayName("Linux backend omits voice argument for System Default")
    void linuxSynthesisCommand_defaultVoice_omitsVoiceArgument() {
        var subject = new LinuxEspeakBackend(new RecordingRunner(), new FixedLocator(Path.of("/usr/bin/espeak-ng")), Map.of("PATH", "/usr/bin"));

        List<String> command = subject.synthesisCommand(Path.of("input.txt"), Path.of("output.wav"), "system-default");

        assertThat(command).doesNotContain("-v");
        assertThat(command).contains("-w", "output.wav", "-f", "input.txt");
    }

    @Test
    @DisplayName("Linux voice parser handles espeak-ng table rows")
    void parseVoiceLine_espeakTableRow_parsesVoice() {
        TextToSpeechCatalogItem item = LinuxEspeakBackend.parseVoiceLine(" 5  en-us          M  English_(America)   gmw/en-US");

        assertThat(item.id()).isEqualTo("en-us");
        assertThat(item.label()).isEqualTo("English (America) (en-us)");
        assertThat(item.description()).isEqualTo("M");
    }

    @Test
    @DisplayName("Executable locator reads PATH case-insensitively")
    void findOnPath_windowsPathKey_findsExecutable() throws Exception {
        var subject = new SystemTtsExecutableLocator();
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        String command = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";

        Optional<Path> executable = subject.findOnPath(command, Map.of("Path", javaBin.toString()));

        assertThat(executable).contains(javaBin.resolve(command));
    }

    @Test
    @DisplayName("Executable locator searches all PATH-like values in stable order")
    void findOnPath_duplicatePathKeys_searchesAllValues() {
        var subject = new SystemTtsExecutableLocator();
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        String command = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", javaBin.getParent().toString());
        environment.put("PaTh", javaBin.toString());

        Optional<Path> executable = subject.findOnPath(command, environment);

        assertThat(executable).contains(javaBin.resolve(command));
    }

    @Test
    @DisplayName("Executable locator skips malformed PATH entries")
    void findOnPath_malformedPathEntry_skipsEntry() {
        var subject = new SystemTtsExecutableLocator();
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        String command = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";

        Optional<Path> executable = subject.findOnPath(command, Map.of("PATH", "bad\u0000entry%s%s".formatted(File.pathSeparator, javaBin)));

        assertThat(executable).contains(javaBin.resolve(command));
    }

    @Test
    @DisplayName("Executable locator unwraps quoted PATH entries")
    void findOnPath_quotedPathEntry_findsExecutable() {
        var subject = new SystemTtsExecutableLocator();
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        String command = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";

        Optional<Path> executable = subject.findOnPath(command, Map.of("PATH", "\"%s\"".formatted(javaBin)));

        assertThat(executable).contains(javaBin.resolve(command));
    }

    @Test
    @DisplayName("Windows backend skips malformed SystemRoot")
    void windowsConstructor_malformedSystemRoot_remainsUnavailable() {
        var subject = new WindowsSapiBackend(new RecordingRunner(), new EmptyLocator(), Map.of("SystemRoot", "bad\u0000root"));

        assertThat(subject.available()).isFalse();
    }

    @Test
    @DisplayName("Windows backend falls back to SystemRoot PowerShell")
    void windowsConstructor_systemRootPowerShell_usesKnownPowerShellPath() throws Exception {
        Path windowsDirectory = Files.createTempDirectory("chat4j-windows-root");
        Path powershell = windowsDirectory.resolve(Path.of("System32", "WindowsPowerShell", "v1.0", "powershell.exe"));
        Files.createDirectories(powershell.getParent());
        Files.writeString(powershell, "");
        powershell.toFile().setExecutable(true);
        var subject = new WindowsSapiBackend(new RecordingRunner(), new EmptyLocator(), Map.of("SystemRoot", windowsDirectory.toString()));

        List<String> command = subject.command("'ok'");

        assertThat(command.getFirst()).isEqualTo(powershell.toString());
    }

    @Test
    @DisplayName("Windows backend construction does not run PowerShell probes")
    void windowsConstructor_withExecutable_doesNotRunProbe() {
        var runner = new RecordingRunner();

        new WindowsSapiBackend(runner, new FixedLocator(Path.of("powershell.exe")), Map.of("PATH", ""));

        assertThat(runner.commands).isEmpty();
    }

    @Test
    @DisplayName("System temp cleanup removes non-empty directories")
    void deleteIfExists_nonEmptyDirectory_removesDirectory() throws Exception {
        Path directory = Files.createTempDirectory("chat4j-delete-test");
        Files.writeString(directory.resolve("extra.tmp"), "extra");

        SystemTtsFiles.deleteIfExists(directory);

        assertThat(directory).doesNotExist();
    }

    @Test
    @DisplayName("Windows command wraps scripts in an invokable script block")
    void windowsCommand_withArguments_wrapsScriptBlock() {
        var subject = new WindowsSapiBackend(new RecordingRunner(), new FixedLocator(Path.of("powershell.exe")), Map.of("PATH", ""));

        List<String> command = subject.command("param($Value) $Value", "hello");

        assertThat(command).contains("-Command", "& { param($Value) $Value }", "hello");
    }

    @Test
    @DisplayName("Windows voice parser handles single-object JSON")
    void parseVoices_singleObjectJson_parsesVoice() throws Exception {
        List<TextToSpeechCatalogItem> voices = WindowsSapiBackend.parseVoices("""
                {"Name":"Microsoft Zira Desktop","Culture":"en-US","Gender":"Female","Age":"Adult"}
                """);

        assertThat(voices).extracting(TextToSpeechCatalogItem::id).containsExactly("Microsoft Zira Desktop");
        assertThat(voices.getFirst().description()).contains("en-US", "Female", "Adult");
    }

    @Test
    @DisplayName("Windows voice parser handles array JSON")
    void parseVoices_arrayJson_parsesVoices() throws Exception {
        List<TextToSpeechCatalogItem> voices = WindowsSapiBackend.parseVoices("""
                [{"Name":"Voice One","Culture":"en-US","Gender":"Female","Age":"Adult"},{"Name":"Voice Two","Culture":"en-GB","Gender":"Male","Age":"Adult"}]
                """);

        assertThat(voices).extracting(TextToSpeechCatalogItem::id).containsExactly("Voice One", "Voice Two");
    }

    private static final class FakeBackend implements SystemTtsBackend {
        private final boolean available;
        private final List<TextToSpeechRequest> requests = new ArrayList<>();

        private FakeBackend(boolean available) {
            this.available = available;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String unavailableMessage() {
            return "System backend missing.";
        }

        @Override
        public String availableMessage() {
            return "System backend available.";
        }

        @Override
        public String defaultResponseFormat() {
            return "wav";
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchVoices() {
            return List.of(TextToSpeechCatalogItem.of("installed", "Installed Voice"));
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            requests.add(request);
            if ("missing".equals(request.voiceId())) {
                throw new IllegalStateException("Voice missing");
            }
            if ("broken".equals(request.voiceId())) {
                throw new IllegalStateException("Output failed");
            }
            return new TextToSpeechAudio(new byte[]{1}, "audio/wav", "wav");
        }
    }

    private static final class FailingVoiceDiscoveryBackend implements SystemTtsBackend {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String unavailableMessage() {
            return "System backend missing.";
        }

        @Override
        public String availableMessage() {
            return "System backend available.";
        }

        @Override
        public String defaultResponseFormat() {
            return "wav";
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchVoices() {
            throw new IllegalStateException("discovery failed");
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            return new TextToSpeechAudio(new byte[]{1}, "audio/wav", "wav");
        }
    }

    private static final class RecordingRunner extends SystemTtsProcessRunner {
        private final List<List<String>> commands = new ArrayList<>();

        @Override
        SystemTtsCommandResult run(List<String> command, Map<String, String> environment, Duration timeout) {
            commands.add(command);
            return new SystemTtsCommandResult(0, "", "");
        }
    }

    private static final class EmptyLocator extends SystemTtsExecutableLocator {
        @Override
        Optional<Path> findOnPath(String command, Map<String, String> environment) {
            return Optional.empty();
        }
    }

    private static final class FixedLocator extends SystemTtsExecutableLocator {
        private final Path path;

        private FixedLocator(Path path) {
            this.path = path;
        }

        @Override
        Optional<Path> findOnPath(String command, Map<String, String> environment) {
            return Optional.of(path);
        }
    }
}
