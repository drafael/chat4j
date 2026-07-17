# Text to Speech / Read Aloud

Chat4J supports a `Read aloud` action for assistant messages. The feature resolves the selected Text to Speech provider, synthesizes speech off the Swing EDT, and plays the resulting audio locally. On first run, Chat4J selects the local `System` provider when an operating-system backend is available; otherwise Text to Speech remains `Off`.

## Runtime model

TTS lives in `com.github.drafael.chat4j.tts` and is intentionally separate from chat providers.

Key classes:

- `TextToSpeechProvider` — provider metadata, credential availability, catalog fetch, and synthesis contract.
- `TextToSpeechProviderRegistry` — TTS provider registry. Do not reuse the chat provider registry or chat model filters; chat filtering excludes TTS model IDs.
- `TextToSpeechSettings` — selected provider plus provider-specific model and voice settings.
- `TextToSpeechCatalogStore` — snapshot-backed model/voice catalogs with bundled fallback values.
- `TextToSpeechService` — async synthesis, stale-request cancellation, active message tracking, status/error callbacks, and playback handoff.
- `AudioPlaybackService` / `JavaSoundAudioPlaybackService` — WAV/PCM playback through Java Sound and MP3 playback through JLayer.

`MainFrame` creates the default service from `SettingsRepository` and passes it into `ChatPanel`. Test/no-op paths use `TextToSpeechService.disabled()`.

## Credentials

Cloud TTS credentials can be saved in Chat4J's encrypted local token vault from Settings or supplied by process/shell environment variables. Saved tokens take precedence over environment variables. The vault is app-local convenience encryption, not OS keychain storage.

Supported providers:

| Provider | Env var | Default format | Notes |
| --- | --- | --- | --- |
| System | none | WAV/AIFF | Uses local OS text-to-speech tools and does not send text to a cloud provider. macOS uses `/usr/bin/say`, Windows uses SAPI through PowerShell `System.Speech`, and Linux uses `espeak-ng` when installed. |
| Deepgram | `DEEPGRAM_API_KEY` | WAV | Uses Aura TTS voice models, Thalia as the bundled default voice, 140-character chunking, and WAV RIFF normalization during playback. |
| Groq | `GROQ_API_KEY` | WAV | Uses Orpheus models, Hannah as the default English voice, model-scoped English/Arabic voice lists, 200-character chunking, and WAV RIFF normalization during playback. |
| ElevenLabs | `ELEVENLABS_API_KEY` | MP3 | Uses ElevenLabs model/voice catalogs when available and stores selected voice IDs separately from labels. |

Provider availability must use `CredentialResolver`, not direct `System.getenv`, so macOS shell-environment loading continues to work. System provider subprocesses also use the merged shell environment so packaged desktop launches can find tools such as `espeak-ng` on the user's PATH.

## Settings

Settings are file-backed through `SettingsRepository`; no database table is involved.

Persisted keys:

```text
chat4j.tts.provider                         # off | system | deepgram | groq | elevenlabs
chat4j.tts.<provider>.model.id
chat4j.tts.<provider>.model.label
chat4j.tts.<provider>.voice.id
chat4j.tts.<provider>.voice.label
chat4j.tts.catalog.<provider>.modelsFile
chat4j.tts.catalog.<provider>.voicesFile
chat4j.tts.catalog.<provider>.updatedAt
```

Catalog settings contain snapshot basenames under `<app-config>/cache`. The referenced files contain JSON arrays of catalog items with `id`, `label`, and optional `description`. Saved selections are preserved even when a refreshed catalog omits them.

Settings UI behavior:

1. Load cached catalogs immediately.
2. Add bundled defaults when cached snapshots are unavailable or empty.
3. Preserve saved model/voice selections.
4. Refresh catalogs in the background when credentials are available.
5. Update Swing controls only on the EDT and ignore stale refresh results.
6. Keep unavailable providers visible with provider-specific helper text.

Missing or blank provider settings resolve to available `System`, otherwise `Off`. Explicit `off` is respected. Saved unavailable cloud providers remain selected so the UI can explain what is missing and provide an API token field for configuration.

## System provider

The System provider is always present in the settings list. It is available when Chat4J detects a supported local backend:

- macOS: `/usr/bin/say`
- Windows: PowerShell with `System.Speech.Synthesis.SpeechSynthesizer`
- Linux: `espeak-ng` on `PATH`

The System provider exposes one model (`System TTS`) and always keeps `System Default` as the first voice. Discovered OS voices appear after the default. If a saved OS voice disappears, synthesis falls back to the system default voice.

System synthesis is process-based but never shell-interpolates user text. Backends use `ProcessBuilder` argument lists, UTF-8 temp input files, temp audio output files, bounded timeouts, concise sanitized errors, and `finally` cleanup. Interrupting playback/read-aloud destroys the active subprocess and preserves the thread interrupt flag. macOS returns AIFF from `say`; Windows and Linux return WAV.

## Chat UI behavior

Read aloud appears only when all conditions are true:

- selected TTS provider is not `Off`,
- provider is available (credentials for cloud providers, local backend for System),
- message is from the assistant,
- speakable text is non-blank.

Swing transcript:

- assistant action bar includes `Read aloud` / `Stop` as a toggle,
- assistant context menu includes the same action,
- user messages never show Read aloud.

WebView transcript:

- `TranscriptRenderSnapshot` carries read-aloud availability and active-message state,
- shared transcript rendering emits action buttons and context-menu actions,
- `transcript-actions.js` dispatches `read-aloud` through the existing browser callback path.

`ChatPanel` stops TTS when conversations are cleared, history is loaded, settings are reloaded, or the panel is removed.

## Playback and cancellation

- New Read aloud requests stop current playback first.
- Clicking the active message again stops playback instead of starting a duplicate request.
- Synthesis and playback never block the EDT.
- Stale synthesis results are ignored after stop/new request/dispose.
- Providers only return `TextToSpeechAudio`; playback remains centralized in `AudioPlaybackService`.
- User-facing errors are concise and must never include API keys, request bodies, generated audio bytes, or full provider response bodies.

## Testing expectations

Tests must not make real provider calls. Provider transports and playback are injectable.

Coverage should include:

- provider-specific settings persistence,
- catalog encode/decode and malformed-cache fallback,
- provider response parsing and safe error extraction,
- Groq chunking/voice filtering/WAV normalization,
- ElevenLabs MP3 synthesis request shape,
- playback stop/toggle/stale-result behavior,
- Swing and WebView read-aloud visibility/action dispatch,
- Settings UI catalog refresh and unavailable-provider behavior.
