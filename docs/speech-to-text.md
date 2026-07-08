# Speech to Text

Chat4J supports batch Speech to Text (STT): it records a temporary WAV file, then transcribes that file into the message composer.

Providers:

- **Groq**: cloud transcription using Groq-compatible Whisper endpoints.
- **ElevenLabs**: cloud transcription using ElevenLabs Scribe models.
- **Deepgram**: cloud transcription using Deepgram pre-recorded STT models.
- **Vosk**: local transcription using the bundled Vosk Java/native runtime and user-installed Vosk model folders.
- **Sphinx4**: local transcription using the bundled CMU Sphinx4 Java runtime and user-installed Sphinx4 acoustic/dictionary/language model folders.

Chat4J never auto-enables cloud STT. Selecting a provider is explicit in **Settings → Speech to Text**.

## Settings

Open **Settings → Speech to Text**.

- **Provider**: `Off`, `Groq`, `ElevenLabs`, `Deepgram`, `Vosk`, or `Sphinx4`.
- **Model**:
  - Groq defaults to `whisper-large-v3-turbo`.
  - ElevenLabs defaults to `scribe_v2` and can refresh available Scribe models from ElevenLabs.
  - Deepgram defaults to `nova-3` and can refresh batch-capable STT models from Deepgram.
  - Vosk shows only installed local models that are eligible for local transcription.
  - Sphinx4 shows only installed local models that pass structural and Sphinx4 recognizer-construction validation.
- **Local models directory**: base directory for local STT models. The default is under the app config directory at `stt/models`.
- **Max recording seconds**: valid range is 1–600 seconds.

## Groq credentials and base URL

Groq Speech to Text requires `GROQ_API_KEY` in the process environment. Chat4J does not store the API key.

Groq STT reuses the configured Groq provider base URL, defaulting to:

```text
https://api.groq.com/openai/v1
```

Custom Groq-compatible endpoints are supported when the configured URL is an absolute `http` or `https` URL with a host. Invalid, relative, hostless, or unsafe schemes are rejected before any request is sent.

## ElevenLabs credentials and models

ElevenLabs Speech to Text requires `ELEVENLABS_API_KEY` in the process environment. Chat4J does not store the API key.

ElevenLabs STT uses the official API base URL:

```text
https://api.elevenlabs.io
```

Model refresh calls:

```text
https://api.elevenlabs.io/v1/models
```

Chat4J filters the model list to batch-compatible Scribe speech-recognition models and falls back to bundled `scribe_v2` when no refreshed catalog is available. If a refresh fails, Chat4J preserves the previously cached ElevenLabs model catalog instead of replacing it with the fallback model.

Advanced ElevenLabs options such as language code, diarization, keyterms, entity detection/redaction, no-verbatim, and webhook transcription are not exposed yet.

## Deepgram credentials and models

Deepgram Speech to Text requires `DEEPGRAM_API_KEY` in the process environment. Chat4J does not store the API key.

Deepgram STT uses the official API base URL:

```text
https://api.deepgram.com
```

Batch transcription calls the pre-recorded endpoint:

```text
https://api.deepgram.com/v1/listen
```

Model refresh calls:

```text
https://api.deepgram.com/v1/models
```

Chat4J parses batch-capable STT models from the `stt` array, ignores non-STT families, and preserves the previously cached Deepgram model catalog if a refresh fails. The bundled fallback catalog contains `nova-3`, `nova-3-general`, and `nova-2-general`; the active Deepgram account/API may expose a different model set. Bare `nova-2` is not bundled unless Deepgram explicitly confirms it as a pre-recorded model ID.

Advanced Deepgram options such as smart formatting, diarization, language selection, punctuation, numerals, utterances, keywords, search/replace, alternatives, callbacks/webhooks, and live streaming are not exposed yet.

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

## Sphinx4 local transcription

Chat4J bundles the Sphinx4 Java runtime, but **does not bundle Sphinx4 acoustic models, dictionaries, or language models**. Users download catalog entries or add existing Sphinx4 model folders through Settings. The Sphinx4 catalog is a bundled curated snapshot of CMU Sphinx SourceForge language folders with recipe metadata for each bundled row; catalog refresh reloads that bundled snapshot, while model downloads fetch only the selected SourceForge files when the user starts a download.

The effective Sphinx4 model root is:

```text
<configured-stt-model-dir>/sphinx4
```

With default paths, that is under:

```text
<app-config>/stt/models/sphinx4
```

Sphinx4 model behavior:

- the model dropdown contains installed local models only;
- the local model table shows the full curated non-Archive language catalog plus imported folders;
- catalog rows are downloadable and include bundled recipe paths for acoustic model, dictionary, language model, required files, and sample rate;
- US English has a verified one-click recipe based on the Sphinx4 5prealpha SourceForge source package data resources;
- non-US rows use bundled SourceForge artifact metadata and recipe paths, then must still pass local Sphinx4 recognizer-construction validation before selection;
- the upstream Portuguese folder does not publish a language model, so Chat4J generates a simple unigram language model from the downloaded dictionary during installation;
- imported folders are copied into Chat4J's managed Sphinx4 root and must include safe metadata or an unambiguous acoustic model, pronunciation dictionary, language model, and sample-rate configuration;
- a model is selectable only after Sphinx4 can construct a recognizer for its acoustic/dictionary/language-model paths;
- quality and speed vary significantly and may be weaker than Vosk or cloud providers.

Sphinx4 supports older CMU Sphinx model formats. Some upstream assets are old, and model licenses vary by package. Chat4J does not bundle model license texts because models are imported or downloaded by the user.

## Recording UX

When Speech to Text is enabled and available, a microphone button appears in the input bar while normal composing is possible. During recording:

- existing typed text remains visible but read-only;
- composer mutation controls are hidden/blocked;
- the waveform/timer and stop-square are shown inline;
- clicking the stop-square finalizes the WAV and transcribes;
- pressing Escape cancels recording or an in-flight transcription. For Sphinx4, cancellation may wait for a blocking recognizer call to return before the transcription fully stops.

Successful transcripts are appended to the preserved raw composer text. Attachments and active skills are preserved.

Speech to Text is independent of chat-provider readiness: if STT is ready and the composer is otherwise editable, users can dictate into the composer even while the chat provider is missing or resolving. Sending remains disabled until the chat provider is ready.

## Privacy and errors

For Groq, finalized audio is sent to Groq for transcription.

For ElevenLabs, finalized audio is sent to ElevenLabs for transcription. Chat4J does not send ElevenLabs `enable_logging=false` or zero-retention flags in this implementation; retention and logging behavior are governed by the user's ElevenLabs account and API terms.

For Deepgram, finalized audio is sent to Deepgram for transcription. Chat4J does not claim local, offline, or zero-retention behavior for Deepgram; retention and logging behavior are governed by the user's Deepgram account and API terms.

For Vosk, transcription runs locally after the model is installed. Catalog refresh and model downloads contact the official Vosk/Alpha Cephei model host, but recorded audio is not uploaded for Vosk transcription.

For Sphinx4, transcription runs locally after the model is installed and validated. Sphinx4 catalog refresh reloads Chat4J's bundled curated snapshot; model downloads contact SourceForge/CMU Sphinx only after the user starts a download. Recorded audio is not uploaded for Sphinx4 transcription. If a Sphinx4 model requires audio conversion, Chat4J writes a temporary converted WAV under its STT temp directory and deletes it after transcription; stale converted files are cleaned on service startup.

Temp WAV files are created under Chat4J's app-controlled STT temp directory, cleaned up after success/failure/cancel, and stale STT-owned files older than 24 hours are removed on service startup. Vosk and Sphinx4 download/import partials use separate Chat4J-owned temp/staging paths and are cleaned conservatively after failures or crashes.

Chat4J avoids transcribing recordings shorter than 500 ms, oversized captures, or audio whose STT settings/model changed before transcription.
