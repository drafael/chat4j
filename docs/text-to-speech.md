# Text to Speech / Read Aloud

Chat4J supports a `Read aloud` action for assistant messages. The feature resolves the selected Text to Speech provider, synthesizes speech off the Swing EDT, and plays the resulting audio locally.

## Runtime model

TTS lives in `com.github.drafael.chat4j.tts` and is intentionally separate from chat providers.

Key classes:

- `TextToSpeechProvider` — provider metadata, credential availability, catalog fetch, and synthesis contract.
- `TextToSpeechProviderRegistry` — TTS provider registry. Do not reuse the chat provider registry or chat model filters; chat filtering excludes TTS model IDs.
- `TextToSpeechSettings` — selected provider plus provider-specific model and voice settings.
- `TextToSpeechCatalogStore` — cached model/voice catalogs in settings with bundled fallback values.
- `TextToSpeechService` — async synthesis, stale-request cancellation, active message tracking, status/error callbacks, and playback handoff.
- `AudioPlaybackService` / `JavaSoundAudioPlaybackService` — WAV/PCM playback through Java Sound and MP3 playback through JLayer.

`MainFrame` creates the default service from `SettingsRepository` and passes it into `ChatPanel`. Test/no-op paths use `TextToSpeechService.disabled()`.

## Credentials

Credentials are environment-variable only. Chat4J never stores TTS API keys in settings.

Supported providers:

| Provider | Env var | Default format | Notes |
| --- | --- | --- | --- |
| Groq | `GROQ_API_KEY` | WAV | Uses Orpheus models, Hannah as the default English voice, model-scoped English/Arabic voice lists, 200-character chunking, and WAV RIFF normalization during playback. |
| ElevenLabs | `ELEVENLABS_API_KEY` | MP3 | Uses ElevenLabs model/voice catalogs when available and stores selected voice IDs separately from labels. |

Provider availability must use `CredentialResolver`, not direct `System.getenv`, so macOS shell-environment loading continues to work.

## Settings

Settings are file-backed through `SettingsRepository`; no database table is involved.

Persisted keys:

```text
chat4j.tts.provider                         # off | groq | elevenlabs
chat4j.tts.<provider>.model.id
chat4j.tts.<provider>.model.label
chat4j.tts.<provider>.voice.id
chat4j.tts.<provider>.voice.label
chat4j.tts.catalog.<provider>.models
chat4j.tts.catalog.<provider>.voices
chat4j.tts.catalog.<provider>.updatedAt
```

Catalog values are JSON arrays of catalog items with `id`, `label`, and optional `description`. Saved selections are preserved even when a refreshed catalog omits them.

Settings UI behavior:

1. Load cached catalogs immediately.
2. Add bundled defaults when cache is empty or stale.
3. Preserve saved model/voice selections.
4. Refresh catalogs in the background when credentials are available.
5. Update Swing controls only on the EDT and ignore stale refresh results.
6. Keep unavailable providers visible with helper text naming the required env var.

No API key fields should be added to the UI.

## Chat UI behavior

Read aloud appears only when all conditions are true:

- selected TTS provider is not `Off`,
- provider credentials are available,
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
