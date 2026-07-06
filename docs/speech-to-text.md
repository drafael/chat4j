# Speech to Text

Chat4J supports batch Speech to Text (STT): it records a temporary WAV file, then transcribes that file into the message composer.

Providers:

- **Groq**: cloud transcription using Groq-compatible Whisper endpoints.
- **Vosk**: local transcription using the bundled Vosk Java/native runtime and user-installed Vosk model folders.

Chat4J never auto-enables cloud STT. Selecting a provider is explicit in **Settings → Speech to Text**.

## Settings

Open **Settings → Speech to Text**.

- **Provider**: `Off`, `Groq`, or `Vosk`.
- **Model**:
  - Groq defaults to `whisper-large-v3-turbo`.
  - Vosk shows only installed local models that are eligible for local transcription.
- **Local models directory**: base directory for local STT models. The default is under the app config directory at `stt/models`.
- **Max recording seconds**: valid range is 1–600 seconds.

## Groq credentials and base URL

Groq Speech to Text requires `GROQ_API_KEY` in the process environment. Chat4J does not store the API key.

Groq STT reuses the configured Groq provider base URL, defaulting to:

```text
https://api.groq.com/openai/v1
```

Custom Groq-compatible endpoints are supported when the configured URL is an absolute `http` or `https` URL with a host. Invalid, relative, hostless, or unsafe schemes are rejected before any request is sent.

## Vosk local transcription

Chat4J bundles the Vosk Java/native runtime, but **does not bundle speech models**. Users download models through Settings or add an existing unzipped Vosk model folder.

Vosk model metadata comes from the official JSON catalog:

```text
https://alphacephei.com/vosk/models/model-list.json
```

Chat4J caches successful catalog refreshes and includes a small bundled fallback catalog snapshot for first-run/offline display. The fallback contains metadata only, not model ZIPs.

The effective Vosk model root is:

```text
<configured-stt-model-dir>/vosk
```

With default paths, that is under:

```text
<app-config>/stt/models/vosk
```

Vosk model behavior:

- the model dropdown contains installed local models only;
- downloadable catalog entries stay in the local model table until installed;
- obsolete installed speech-recognition models remain visible with a warning;
- non-transcription Vosk catalog entries such as `tts` and `spk` are excluded;
- custom local model folders can be added by placing them under the Vosk root or using **Add existing model folder…**;
- large models can require substantial disk space and memory.

For official catalog downloads, Chat4J validates HTTPS URLs under `alphacephei.com/vosk/models/`, streams ZIP files to disk, checks the catalog MD5 for integrity, and rejects unsafe ZIP paths. MD5 is an integrity check from the official catalog, not a cryptographic trust guarantee.

Model licenses vary. Chat4J does not bundle model license texts because models are downloaded or imported by the user. See the official Vosk model page for model details and licenses:

```text
https://alphacephei.com/vosk/models
```

## Recording UX

When Speech to Text is enabled and available, a microphone button appears in the input bar while normal composing is possible. During recording:

- existing typed text remains visible but read-only;
- composer mutation controls are hidden/blocked;
- the waveform/timer and stop-square are shown inline;
- clicking the stop-square finalizes the WAV and transcribes;
- pressing Escape cancels recording or an in-flight transcription.

Successful transcripts are appended to the preserved raw composer text. Attachments and active skills are preserved.

Speech to Text is independent of chat-provider readiness: if STT is ready and the composer is otherwise editable, users can dictate into the composer even while the chat provider is missing or resolving. Sending remains disabled until the chat provider is ready.

## Privacy and errors

For Groq, finalized audio is sent to Groq for transcription.

For Vosk, transcription runs locally after the model is installed. Catalog refresh and model downloads contact the official Vosk/Alpha Cephei model host, but recorded audio is not uploaded for Vosk transcription.

Temp WAV files are created under Chat4J's app-controlled STT temp directory, cleaned up after success/failure/cancel, and stale STT-owned files older than 24 hours are removed on service startup. Vosk download ZIPs and partial install folders use separate Chat4J-owned temp/staging paths and are cleaned conservatively after failures or crashes.

Chat4J avoids transcribing recordings shorter than 500 ms, oversized captures, or audio whose STT settings/model changed before transcription.
