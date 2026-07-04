# System Text-to-Speech Provider Plan

## Goal

Implement a default `System` Text-to-Speech provider that uses the operating system's local TTS engine through subprocess backends, while fitting the existing Chat4J TTS architecture.

This is a full implementation, not an MVP. Do not skip backend voice discovery, cancellation, safe process handling, settings edge cases, or tests.

## Decisions

- Use **Option A**: process-based provider that writes OS-generated audio to a temp file, then returns `TextToSpeechAudio` for existing playback.
- Use **System** as the provider/user-facing name.
- Use backend names based on actual mechanisms:
  - `MacOsSayBackend`
  - `WindowsSapiBackend`
  - `LinuxEspeakBackend`
- Provider id: `system`.
- Display name: `System`.
- Add `SettingsKeys.TTS_PROVIDER_SYSTEM = "system"` rather than scattering the string.
- Make `System` the implicit default provider when no saved TTS provider setting exists and the system backend is available.
- Respect explicit `off` if the user already selected it.
- Do not persist the implicit default from `TextToSpeechSettings.resolve()`; resolving settings should remain side-effect free. Persist `system` only when the user explicitly selects/saves it.

## Architecture

Add these classes under `src/main/java/com/github/drafael/chat4j/tts`:

- `SystemTextToSpeechProvider`
- `SystemTtsBackend`
- `MacOsSayBackend`
- `WindowsSapiBackend`
- `LinuxEspeakBackend`
- `SystemTtsProcessRunner` or similar injectable command runner
- Optional helper value types such as `SystemTtsCommandResult`, `SystemTtsAudioResult`, or `SystemTtsBackendDescriptor` if they keep command execution/test assertions clean.

`SystemTextToSpeechProvider` implements the existing `TextToSpeechProvider` interface. It selects one OS backend based on the current OS and tool availability, exposes one model, exposes a default voice plus discovered voices, and delegates synthesis to the selected backend.

`SystemTextToSpeechProvider` should be non-null even when no backend is available. It should report `available() == false` and provide a concise unavailable reason. This keeps the settings UI able to show `System` as an unavailable provider instead of silently hiding it.

Register it first in `TextToSpeechProviderRegistry.createDefault()` without ever putting `null` into `List.of(...)` or `List.copyOf(...)`:

```java
return new TextToSpeechProviderRegistry(List.of(
    SystemTextToSpeechProvider.createDefault(),
    new GroqTextToSpeechProvider(transport),
    new ElevenLabsTextToSpeechProvider(transport)
));
```

Because the System provider is always constructed, tests can assert it appears first without depending on the CI machine having `say`, PowerShell/SAPI, or `espeak-ng` available.

## Provider contract updates

The current architecture hardcodes response formats in both `TextToSpeechService` and `TextToSpeechPanel`:

- Groq => `wav`
- every other provider => `mp3`

That will be wrong for System TTS. Add a provider-level default response format method to `TextToSpeechProvider`, for example:

```java
default String defaultResponseFormat() {
    return "mp3";
}
```

Then override:

- Groq: `wav`
- ElevenLabs: `mp3`
- System: backend-specific `wav` or `aiff`

Update both read-aloud and preview code to use `selection.provider().defaultResponseFormat()` instead of provider-id branching.

Also add provider-level user-facing availability text so the UI and service do not duplicate env-var branching. Prefer a method on `TextToSpeechProvider`, for example:

```java
default String unavailableMessage() {
    return StringUtils.isBlank(requiredEnvVar())
            ? "%s is not available.".formatted(displayName())
            : "%s requires %s.".formatted(displayName(), requiredEnvVar());
}

default String availableMessage() {
    return StringUtils.isBlank(requiredEnvVar())
            ? "Using %s.".formatted(displayName())
            : "Using %s with env var %s.".formatted(displayName(), requiredEnvVar());
}
```

Override these in System to return OS/backend-specific messages such as `Uses your operating system text-to-speech engine.` and `Install espeak-ng to use System Text to Speech on Linux.`. Then update both `TextToSpeechService` and `TextToSpeechPanel` to call these provider methods instead of formatting `requires ...` directly.

## Provider behavior

Provider values:

- id: `system`
- display name: `System`
- required env var: `null`, not an empty string
- default model: `system` / `System TTS`
- default voice: `system-default` / `System Default`
- max input characters: `0`; all backends use input files so long text should not require provider chunking

Override `available()` so it checks cached backend availability instead of env-var credentials. Returning `null` from `requiredEnvVar()` is safe with the current default credential logic, but System must still override `available()` for clarity and to report backend availability rather than credentials.

`apiKey()` must never be called by the System provider.

`fetchModels()` returns the single bundled model.

`bundledVoices()` and `fetchVoices()` must always include `System Default` as the first item. Discovered voices are appended after it and de-duplicated by id. This prevents `TextToSpeechPanel.saveFirstVoiceWhenSelectionIsUnavailable(...)` from replacing the OS default voice just because a discovered catalog arrived.

`voicesForModel(...)` for System should also enforce `System Default` as the first item regardless of what came from `TextToSpeechCatalogStore`. This covers stale/older cached voice catalogs that might not contain `system-default`, because `TextToSpeechCatalogStore.merged(...)` currently appends the selected item after cached primary items rather than prepending fallback voices.

`fetchVoices()` delegates to the backend. If discovery fails but synthesis can use the OS default voice, return `System Default` plus no discovered voices rather than failing the provider.

`synthesize()` delegates to the backend, reads temp audio bytes, validates the output file exists and is non-empty, and returns `TextToSpeechAudio` with the correct format/content type.

Voice handling rules:

- `system-default`, blank, unknown, or no-longer-installed voices should fall back to the OS default voice unless the backend requires strict voice selection.
- When using the default voice, omit backend voice arguments/options rather than passing `system-default` to OS tools.
- If a selected discovered voice is still installed, pass the backend's voice id/name exactly as discovered.

## Backend selection and command environment

Backend detection should be cached and cheap after provider construction. Avoid running expensive voice discovery inside `available()` because `available()` is called repeatedly by settings and read-aloud availability checks.

`TextToSpeechProviderRegistry.createDefault()` is used while building `TextToSpeechService` and `TextToSpeechPanel`, so provider construction may happen on the Swing EDT. Do not run voice discovery or long capability probes during construction. Use cheap OS/executable checks at construction time; if an external capability probe is truly needed, bound it to a very short timeout and make it injectable/testable. Expensive discovery belongs in `fetchVoices()`/catalog refresh, which already runs off the EDT.

Use OS detection that is already consistent with the project style, or `System.getProperty("os.name")` normalized in one helper. Keep the helper injectable/testable so unit tests can exercise all OS branches on any machine. Normalize provider ids and OS strings with `Locale.ROOT`, not the default locale.

Command lookup rules:

- macOS: use absolute `/usr/bin/say` and require it to be executable.
- Windows: prefer `powershell.exe` on Windows because `System.Speech` is a Windows/.NET Framework API; mark the backend available only after a lightweight, tightly timed probe confirms `Add-Type -AssemblyName System.Speech` works. Allow `pwsh` only if the same probe succeeds.
- Linux: locate `espeak-ng` from `PATH`, using `CredentialResolver.mergedEnvironment()` so a packaged app can see shell-loaded PATH entries consistently with the rest of Chat4J.

Implement command lookup explicitly, e.g. `findExecutableOnPath(command, environment)`, and pass absolute executable paths to `ProcessBuilder` where possible. This is more deterministic and testable than relying on Java/OS PATH lookup semantics.

Subprocesses should receive a merged environment from `CredentialResolver.mergedEnvironment()` where practical. This matters for packaged desktop launches where `System.getenv()` may not include the user's shell PATH.

## Backend behavior

### macOS: `MacOsSayBackend`

- Detect `/usr/bin/say`.
- Discover voices with:

```text
/usr/bin/say -v ?
```

- Parse each non-blank output line into voice id/name plus optional locale/sample text. The voice id passed back to `say -v` should be the exact voice name emitted by `say`; preserve names with spaces if present.
- Synthesize to temp audio using an input file, not shell-interpolated text:

```text
/usr/bin/say -v <voice> -o <output-file> --input-file=<input-file>
```

or equivalently:

```text
/usr/bin/say -v <voice> -o <output-file> -f <input-file>
```

- Omit `-v <voice>` when the selected voice is `system-default`.
- Use `.aiff` output by default. Return content type `audio/aiff` and format `aiff`.
- Java Sound supports AIFF through `AudioSystem`; if a target runtime fails to play AIFF, switch the backend to `--file-format=WAVE`/`.wav` and document the reason in the implementation comments.

### Windows: `WindowsSapiBackend`

- Detect Windows and a working PowerShell host.
- Prefer `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass`.
- Use .NET `System.Speech.Synthesis.SpeechSynthesizer`.
- Discover voices with `GetInstalledVoices()`, include only enabled voices, and emit machine-readable data from PowerShell, preferably JSON via `ConvertTo-Json`.
- Normalize both single-object and array JSON results because `ConvertTo-Json` can emit an object rather than an array when exactly one voice is returned.
- Use SAPI voice name as the voice id. Labels may include culture/gender/age metadata if available.
- Synthesize WAV to temp file with `SetOutputToWaveFile()` and `Speak()`.
- Pass input/output paths and selected voice as script parameters or write a temp `.ps1` script. Do not interpolate user text into the command line.
- Read the text from a UTF-8 temp input file inside PowerShell using `[System.IO.File]::ReadAllText($InputPath, [System.Text.Encoding]::UTF8)` rather than relying on `Get-Content` defaults, which differ across Windows PowerShell and PowerShell 7.
- Force machine-readable stdout to UTF-8, e.g. set `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` before `ConvertTo-Json`, and decode process stdout as UTF-8 in Java. Voice names can be localized/non-ASCII.
- Omit `SelectVoice(...)` when the selected voice is `system-default`.
- Return content type `audio/wav` and format `wav`.

### Linux: `LinuxEspeakBackend`

- Detect `espeak-ng` on `PATH`.
- Discover voices with:

```text
espeak-ng --voices
```

- Parse the table output into stable voice ids suitable for `-v`, with human-readable labels that include language when useful.
- Synthesize WAV from a temp input file:

```text
espeak-ng -v <voice> -w <output-file> -f <input-file>
```

- Omit `-v <voice>` when the selected voice is `system-default`.
- If the parser cannot understand any voice rows but `espeak-ng` synthesis works with the default voice, still expose `System Default`; do not skip Linux synthesis.
- Return content type `audio/wav` and format `wav`.

## Default settings behavior

Update `TextToSpeechSettings.resolve()` so missing provider setting is not treated the same as explicit `off`.

`SettingsRepository` already exposes `Optional<String> get(String key)`, so use that rather than adding a key-existence API.

Desired behavior:

1. If `chat4j.tts.provider` is missing:
   - choose `system` when the provider exists and is available;
   - otherwise choose `off`.
2. If `chat4j.tts.provider` exists but is blank or whitespace:
   - treat it like missing for robustness and choose available `system`, otherwise `off`.
3. If the setting is explicitly `off`, respect it.
4. If the setting names a known provider, use it even if it is currently unavailable; the selection should remain enabled but unavailable so the UI/service can explain the problem.
5. If the setting names an unknown provider, fallback to `off`.

Do not write any setting from `resolve()` while applying this default. However, if the settings panel is showing the implicit System default and the user explicitly changes a System model/voice selection, save `chat4j.tts.provider=system` before saving the model/voice. A customization is an explicit user choice and should not remain indistinguishable from a never-configured first run.

Selection examples:

- no setting + System available => `Selection(system, ..., available=true)`
- no setting + System unavailable => `Selection.off()`
- saved `off` + System available => `Selection.off()`
- saved `system` + System unavailable => `Selection(system, ..., available=false)`
- saved unknown provider => `Selection.off()`

## Settings UI behavior

Update the Text to Speech section hint in `TextToSpeechPanel`; it currently says read aloud uses env-var configured providers, which will be false for System.

Update `TextToSpeechPanel` in every place that assumes unavailable TTS providers are unavailable because of an env var:

- `ProviderOption.of(...)` label formatting
- rejected selection status in `onProviderSelected()`
- helper text in `updateControlAvailability(...)`
- preview error/status formatting if selection is unavailable

For API-key providers, keep the current behavior:

```text
Groq (requires GROQ_API_KEY)
Using Groq with env var GROQ_API_KEY.
Groq requires GROQ_API_KEY.
```

For System provider:

- available helper text: `Uses your operating system text-to-speech engine.`
- unavailable label/status/helper: `System Text to Speech is not available on this computer.` or a backend-specific message such as `Install espeak-ng to use System Text to Speech on Linux.`

Model combo should show one item: `System TTS`.

Voice combo should show `System Default` plus discovered voices. `System Default` should remain selectable even after catalog refreshes.

Preview must use `selection.provider().defaultResponseFormat()` rather than hardcoding Groq=`wav`, other=`mp3`.

## Read-aloud service behavior

Update `TextToSpeechService` in every place that assumes unavailable TTS providers are unavailable because of an env var:

- When selected System is unavailable, report the System unavailable message, not `System requires null.`
- Synthesis should use `selection.provider().defaultResponseFormat()` rather than hardcoding Groq=`wav`, other=`mp3`.
- Existing stale-request cancellation and active-message toggling should remain unchanged.

Because the System backend may be executing a subprocess when `Future.cancel(true)` is called, command runner waits must be interruptible and must destroy the process on interruption.

## Process safety

- Use `ProcessBuilder` argument lists; do not concatenate shell commands.
- Use temp input and output files for all backends.
- Write temp input as UTF-8.
- Create temp files with restrictive defaults from `Files.createTempFile(...)` and delete them in `finally`.
- Enforce bounded timeouts, but do not use a fixed short synthesis timeout for all input sizes. Use a base timeout plus a per-character or per-chunk allowance, with shorter fixed timeouts for command probes and voice discovery. Long assistant messages can legitimately take more than 30 seconds to synthesize locally.
- On interruption/cancellation, destroy the process and then forcibly destroy if it does not exit promptly.
- Capture stdout/stderr concurrently or use bounded readers so a verbose process cannot deadlock on a full pipe.
- Limit/sanitize stderr/stdout in thrown exception messages.
- Never include full input text, API keys, generated audio bytes, or full command output in logs/errors.
- Validate command exit code and output file size before returning audio.
- Preserve the thread interrupt flag after catching `InterruptedException`.

## Audio/playback considerations

`JavaSoundAudioPlaybackService` treats anything that is not MP3 as Java Sound audio, so WAV and AIFF can both flow through the existing playback path. `TextToSpeechAudio` currently detects MP3/WAV from content type but not AIFF; that is acceptable because AIFF only needs to avoid the MP3 path. Still pass `format="aiff"` and `contentType="audio/aiff"` from macOS for clarity.

No direct backend playback should be introduced. All providers, including System, should continue returning `TextToSpeechAudio` and let `AudioPlaybackService` handle playback/stop semantics.

## Documentation updates

Update `docs/text-to-speech.md`:

- Add `System` to supported providers with env var `none`.
- Document that first-run default is System when a backend is available, otherwise Off.
- Document macOS/Windows/Linux backends and Linux's `espeak-ng` dependency.
- Update persisted provider values to include `system`.
- Explain that System uses local OS tools and does not send text to a cloud provider.

## Testing plan

Add/update unit tests for:

- `TextToSpeechProviderRegistry.createDefault()` includes non-null `System` first regardless of host backend availability.
- Missing provider setting resolves to `system` when a test System provider is available.
- Missing provider setting resolves to `off` when System is unavailable or absent.
- Blank provider setting follows the same default behavior as missing.
- Explicit `off` remains respected even when System is available.
- Saved `system` remains selected but unavailable when the backend disappears.
- Missing/blank env var does not make System unavailable.
- Unknown provider setting falls back to `off`.
- `TextToSpeechService` and `TextToSpeechPanel` use provider `defaultResponseFormat()`.
- `TextToSpeechService` and `TextToSpeechPanel` use provider availability-message methods and never render `requires null` or `requires ` for System.
- Text to Speech settings section hint no longer says all TTS providers are env-var configured.
- System provider construction does not run slow voice discovery or long process probes on the EDT.
- Command lookup uses injectable explicit PATH resolution and absolute executable paths where possible.
- Windows voice JSON parsing handles both single-object and array `ConvertTo-Json` output.
- System provider always includes `System Default` as the first voice, including when stale cached catalogs are passed through `voicesForModel(...)`.
- Provider id and OS normalization use `Locale.ROOT`.
- `system-default` causes each backend to omit voice-selection arguments.
- macOS backend builds expected `say` commands for default and explicit voices.
- macOS voice parser handles normal `say -v ?` rows and voice names conservatively.
- Windows backend builds expected PowerShell/SAPI command/script for default and explicit voices.
- Windows voice parser handles JSON output and filters disabled voices.
- Linux backend builds expected `espeak-ng` commands for default and explicit voices.
- Linux voice parser handles representative `espeak-ng --voices` table output.
- Command runner reads output bytes into `TextToSpeechAudio` with the expected content type/format.
- Process failure, timeout, interruption, missing output file, and empty output file yield concise safe errors and clean up temp files.
- Settings panel catalog refresh preserves `System Default` and does not auto-save the first discovered voice over it.
- Changing model/voice while the implicit System default is active persists `chat4j.tts.provider=system`.

Use injectable OS detection, command lookup, process runner, and temp-file/audio result fixtures so tests never invoke real OS TTS.

## Implementation order

1. Add constants/default-format/unavailable-message contract changes needed by all providers.
2. Add `SystemTtsBackend` and process runner abstractions with test seams for OS, command lookup, environment, and temp files.
3. Implement backend detection, voice discovery, and synthesis for macOS, Windows, and Linux.
4. Add `SystemTextToSpeechProvider` with bundled model/default voice behavior and backend-specific format/unavailable messages.
5. Register provider first in `TextToSpeechProviderRegistry` as a non-null provider.
6. Update `TextToSpeechSettings` default resolution to prefer available `system` only when unset/blank, with no resolve-time persistence.
7. Update `TextToSpeechService` unavailable-message and response-format handling.
8. Update `TextToSpeechPanel` provider labels, helper text, unavailable selection messages, catalog preservation, and preview format handling.
9. Update `docs/text-to-speech.md` with System provider behavior.
10. Add tests.
11. Run targeted Maven tests, then broader test suite if practical.
