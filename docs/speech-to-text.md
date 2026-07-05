# Speech to Text

Chat4J includes a Speech to Text foundation with Groq batch transcription as the first provider.

## Settings

Open **Settings → Speech to Text**.

- **Provider**: `Off` or `Groq`.
- **Model**: defaults to `whisper-large-v3-turbo`.
- **Local models directory**: reserved for future local providers and defaults to the app config directory under `stt/models`.
- **Max recording seconds**: valid range is 1–600 seconds.

Chat4J never auto-enables cloud Speech to Text. Selecting Groq is allowed even when credentials are missing so the UI can explain what is required.

## Groq credentials and base URL

Groq Speech to Text requires `GROQ_API_KEY` in the process environment. Chat4J does not store the API key.

Groq STT reuses the configured Groq provider base URL, defaulting to:

```text
https://api.groq.com/openai/v1
```

Custom Groq-compatible endpoints are supported when the configured URL is an absolute `http` or `https` URL with a host. Invalid, relative, hostless, or unsafe schemes are rejected before any request is sent.

## Recording UX

When Speech to Text is enabled and available, a microphone button appears in the input bar while normal composing is possible. During recording:

- existing typed text remains visible but read-only
- composer mutation controls are hidden/blocked
- the waveform/timer and stop-square are shown inline
- clicking the stop-square finalizes the WAV and transcribes
- pressing Escape cancels recording or an in-flight transcription

Successful transcripts are appended to the preserved raw composer text. Attachments and active skills are preserved.

## Privacy and errors

For Groq, finalized audio is sent to Groq for transcription. Temp WAV files are created under Chat4J's app-controlled STT temp directory, cleaned up after success/failure/cancel, and stale STT-owned files older than 24 hours are removed on service startup.

Chat4J avoids uploading recordings shorter than 500 ms, oversized captures, or audio whose STT settings changed before upload.

## Future providers

The first milestone includes local model directory/downloader extension points, but no local/native STT dependencies. Future providers can add on-device model download and transcription implementations without changing the input-bar UX.
