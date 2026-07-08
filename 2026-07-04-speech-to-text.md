# Speech-to-Text Foundation + Groq Plan

## Goal

Prepare Chat4J for Speech-to-Text (STT) with a clean provider architecture, local model directory/downloader extension points, settings UI, and one working end-to-end provider: Groq batch transcription. Keep the first milestone batch-only and avoid unused local/native dependencies until the first local provider is implemented.

## Decisions

- First milestone: STT foundation + one working provider.
- First provider: Groq batch transcription.
- Recording UX: inline in the existing input bar, not a modal.
- Recording widget: Codex-inspired waveform/timer, themed with current Swing/FlatLaf colors.
- During recording: existing typed text remains visible but read-only; normal action buttons are hidden; only a stop-square button is shown.
- Stop-square stops recording and transcribes. Escape cancels/discards recording, and cancels any in-flight transcription before text is inserted.
- Add a first-class send/up-arrow button to `InputBar`; today sending is keyboard/listener based only. The send/up-arrow is hidden during recording and shown/enabled only when the composer is truly sendable: normal compose mode is active, text/attachments/active skills are present, the conversation is not loading/preparing/streaming/editing/STT-active, and the selected chat provider is resolved/non-null. This preserves existing attachment-only sends and also covers text inserted by transcription while avoiding a visible send affordance that only fails later in `ChatPanel.onSend()`.
- STT settings are a top-level Settings section for now.
- First settings provider list: `Off` and `Groq` only. Future providers should not be shown as disabled placeholders yet.
- Add local model directory/downloader interfaces now, but no actual model downloads or local STT dependencies yet.
- Use `stt` subpackages even though `tts` is currently flatter; TTS can be refactored later.

## Package Layout

```text
com.github.drafael.chat4j.stt
  SpeechToTextService
  SpeechToTextSettings
  SpeechToTextSettingsSnapshot
  SpeechToTextProviderRegistry

com.github.drafael.chat4j.stt.provider
  SpeechToTextProvider
  SpeechToTextProviderContext
  SpeechToTextRequest
  SpeechToTextResult
  SpeechToTextCatalogItem
  SpeechToTextCatalogStore
  SttHttpTransport / SttHttpRequest / SttHttpResponse
  JavaNetSttHttpTransport

com.github.drafael.chat4j.stt.provider.groq
  GroqSpeechToTextProvider
  GroqSpeechToTextModels

com.github.drafael.chat4j.stt.audio
  MicrophoneAudioCapture
  AudioCaptureSession
  CapturedAudio
  AudioLevelListener
  WavFileWriter

com.github.drafael.chat4j.stt.model
  SpeechToTextModelDirectory
  SpeechToTextModelDescriptor
  SpeechToTextModelDownloader
  NoOpSpeechToTextModelDownloader

com.github.drafael.chat4j.stt.error
  SpeechToTextException
```

Future providers can live under `stt.provider.vosk`, `stt.provider.whispercpp`, `stt.provider.sphinx4`, `stt.provider.deepgram`, `stt.provider.assemblyai`, `stt.provider.elevenlabs`, `stt.provider.googlecloud`, etc.

Swing/UI classes should follow existing UI package conventions instead of living in the core STT/provider domain: `settings.SpeechToTextPanel`, `chat.composer.InputRecordingPanel`, `chat.composer.RecordingWaveformTimeline`, and a small STT UI/controller layer such as `chat.composer.stt.ComposerSttState` / `InputBarSttController` plus `chat.ChatPanelSttCoordinator` (or equivalent). Use those coordinator/state classes to centralize lifecycle gates, popup hiding, shortcut routing, and presentation recomputation rather than spreading ad hoc STT booleans across already-large `InputBar`, `ChatPanel`, and `MainFrame` methods.

## Settings

Add settings keys:

```text
chat4j.stt.provider                         # off | groq
chat4j.stt.<provider>.model.id
chat4j.stt.<provider>.model.label
chat4j.stt.catalog.<provider>.models
chat4j.stt.catalog.<provider>.updatedAt
chat4j.stt.models.dir
chat4j.stt.recording.maxDurationSeconds        # optional override; default 600
```

Provider contract behavior:

- Keep STT in its own provider registry/catalog path. Do not reuse the chat `ProviderRegistry`, chat model filters, or chat capability model list as the source of STT models; only reuse the configured Groq base URL setting.
- Mirror the useful TTS provider contract pieces: provider id/display name, required env var, bundled/default models, `available()`, `availableMessage()`, `unavailableLabel()`, `unavailableMessage()`, catalog fetch, and `transcribe(...)`, but do not copy TTS's no-context `fetchModels()` shape. Define `fetchModels(SpeechToTextProviderContext context)` or equivalent so catalog refresh receives the normalized base URL/endpoint, credential source, transport/cancellation handle, and provider id without providers reading `SettingsRepository` directly.
- Use provider-level availability messages in `SpeechToTextPanel`, `SpeechToTextService`, and `InputBar` status/errors so UI code does not hard-code environment-variable wording.

Settings behavior:

- Missing/blank provider resolves to `off`.
- Saved `groq` remains selected even if unavailable so the UI can explain the missing key.
- The settings UI must allow selecting and saving `Groq` even when `GROQ_API_KEY` is missing. In that state, mark it unavailable, show the provider helper text, keep the mic hidden, and never auto-start cloud STT.
- Unknown provider falls back to `off`.
- Groq availability uses `CredentialResolver` and `GROQ_API_KEY` in production, but STT settings/provider code should depend on an injectable credential-source/test seam so tests can force `GROQ_API_KEY` present or missing independent of the process environment. This avoids CI/developer machines with real `GROQ_API_KEY` values making missing-key tests non-deterministic.
- STT Groq availability is controlled by STT settings and credentials, not by the chat provider enabled/disabled flag. Reuse the chat Groq base URL setting only; do not hide STT Groq just because the chat provider is disabled. Never resolve the STT Groq base URL through `ProviderRegistry.availableProviders()` or any path that filters disabled/uncredentialed chat providers. Resolve the STT Groq base URL either by reading `SettingsKeys.providerBaseUrlKey("Groq")` directly, or by finding Groq in `ProviderRegistry.allProviders()` and passing that `ProviderDef` to `ProviderRuntimeSettingsResolver.resolve(...)`; use only the returned `baseUrl` and ignore its `enabled` value for STT availability. Do not use `ProviderRegistry.allProviders()` by itself for the base URL because it returns catalog defaults, not runtime overrides.
- Do not auto-enable cloud STT just because credentials exist.
- Add a top-level `Speech to Text` settings section next to `Text to Speech`.
- Settings panel fields: Provider, Model, local models directory, Browse, Refresh catalogs, max recording duration, and status/helper text.
- Provider helper text should make cloud/local privacy clear. For Groq, say that recorded audio is sent to Groq for transcription; future local providers can say transcription stays on-device.
- Thread the app `StoragePaths`/default STT model directory into settings and services. `SettingsRepository` currently stores only the settings file path and should not be used to rediscover app storage implicitly.
- Default Groq model: `whisper-large-v3-turbo` unless implementation research finds a better current default.
- Max recording duration setting defaults to 600 seconds, is user-visible in the STT panel, and has a valid range of 1-600 seconds inclusive. UI edits outside that range (`0`, negative, nonnumeric, or `>600`) are rejected with a settings status error and are not persisted. Persisted invalid values resolve to the default 600 seconds. Values above 600 seconds must not bypass the 10-minute app/provider safety cap.
- STT settings UI reads/writes should not introduce repeated blocking filesystem work on the EDT. Load persisted STT settings/catalogs into a snapshot before wiring high-frequency UI listeners, save provider/model/model-directory/duration changes through an async batched settings-write path, marshal status updates back to Swing, and expose/participate in a generalized SettingsDialog pending-save hook so closing the modal dialog cannot race ahead of an in-flight STT save before `MainFrame.openSettings()` applies reload callbacks. Values that are correlated within one user action or refresh result (for example provider + selected model id/label, or catalog JSON + catalog `updatedAt`) must be persisted as one off-EDT settings snapshot/batch operation, adding a `SettingsRepository` batch API if needed, so a mid-save failure cannot leave an internally inconsistent STT selection/catalog. STT async/pending saves must use a result-returning save API rather than inherited `AbstractSettingsPanel.writeSetting(...)` / `removeSetting(...)` / `bindTextField(...)`, because those helpers write synchronously and hide failures behind a status label; pending-save failures must propagate to `SettingsDialog`. If generalized beyond `PromptsPanel`, the dialog error copy must no longer say only "Prompt settings" for STT save failures.
- Catalog UI must load cached models immediately, merge bundled defaults when cache is empty/malformed/stale, preserve the saved selected model even when refresh omits it, refresh in the background only when the provider is available, update Swing controls only on the EDT, and ignore stale refresh results. Define catalog staleness explicitly for this milestone: treat Groq STT catalogs as stale after 24 hours based on `chat4j.stt.catalog.<provider>.updatedAt`; malformed/missing timestamps count as stale but should not break cached/bundled model display.
- Catalog refresh must use the same configured Groq base URL validation/normalization as transcription, call `/models`, include the same `Authorization: Bearer <GROQ_API_KEY>` credential resolution, filter to STT-capable model IDs, ignore stale refresh results, and keep bundled defaults if refresh fails or returns no STT models. Do not issue catalog refresh HTTP requests while Groq is unavailable or the API key is missing.

## Local Model Directory Foundation

Default model directory should be under the existing app config directory from `StoragePaths`, e.g.:

```text
<app-config-dir>/stt/models
```

Suggested layout:

```text
stt/
  models/
    vosk/<model-id>/
    whisper.cpp/<model-id>/
    sphinx4/<model-id>/
  temp/
```

For this milestone, implement directory resolution and downloader interfaces only. Add `StoragePaths.sttModelsDirectory()` as `appConfigDirectory().resolve("stt").resolve("models")` and `StoragePaths.sttTempDirectory()` as `appConfigDirectory().resolve("stt").resolve("temp")`, and pass those default paths through `AppServices`/`MainFrame`/`SettingsDialog`/`SpeechToTextService` instead of calling `StoragePaths.defaultPaths()` from deep UI or provider code. Validate saved model-directory overrides: support only `~` and `~/...` expansion to `user.home`, resolve relative paths with `Path.toAbsolutePath().normalize()`, normalize absolute paths with `normalize()`, persist/use the normalized absolute path, create the directory when saving or first using it, reject file paths/non-writable directories with a clear settings status error, and never silently fall back to another directory after the user saved an explicit override. Do not use the existing synchronous text-field binding for model directory validation/creation; directory filesystem work and the settings write that follows it should run off the EDT, then status/control updates must marshal back to Swing. Do not add Vosk, whisper.cpp JNI, or Sphinx dependencies yet.

## Audio Capture

Use `javax.sound.sampled.TargetDataLine` and produce Groq-ready WAV:

- preferred output: 16 kHz, mono, signed 16-bit PCM, little-endian WAV
- try direct 16 kHz mono little-endian capture first
- if the microphone/mixer does not support that format, try fallback PCM capture formats in this order, opening only formats supported by the target line and requiring `AudioSystem.isConversionSupported(targetFormat, sourceFormat)` before using the conversion path: 48 kHz mono signed 16-bit little-endian, 44.1 kHz mono signed 16-bit little-endian, 48 kHz stereo signed 16-bit little-endian, 44.1 kHz stereo signed 16-bit little-endian, then the same sample-rate/channel combinations with signed 16-bit big-endian only if Java Sound can convert them to the target format. Stereo sources must be downmixed by the conversion pipeline to mono target output.
- fail clearly only after direct capture and the explicit conversion options above are unavailable

Capture runs off the EDT. It writes to a temp WAV file and publishes RMS/peak levels for the recording widget. No streaming queue is needed in the first milestone. Treat captured audio as sensitive: create temp WAVs in an app-controlled STT temp directory with owner-only permissions where the platform supports it, delete stale abandoned STT temp files on service startup, never use transcript text or user content in temp filenames, and upload only after the WAV header has been finalized successfully. Stale cleanup must be narrowly scoped: for this milestone, delete only regular files in `StoragePaths.sttTempDirectory()` matching an STT-owned filename prefix/suffix and older than 24 hours; skip files marked/locked by an active capture session where supported, never delete fresh files that may belong to another process/session, and do not recurse or follow/delete symlinks.

Rules:

- No mic access, audio/temp-file IO, model-directory IO, catalog refresh IO, HTTP, or model work on Swing EDT. File-backed settings should be read into snapshots and refreshed deliberately, not queried from high-frequency document/change listeners.
- UI updates are marshaled to the EDT, but audio-level callbacks should be coalesced/throttled (for example ~30 FPS) so microphone chunks cannot flood Swing's event queue.
- Track recording/transcription sessions with monotonically increasing request IDs so late capture/transcription callbacks are ignored after explicit cancel, shutdown/dispose, or any forced lifecycle replacement path. User-initiated settings reloads and conversation switches should normally be blocked while STT is active by the deterministic action policy, but stale callback guards must still protect against direct/test-only invocations and late background completions.
- Stop/cancel is idempotent.
- Capture cancellation must close/stop the `TargetDataLine` so a blocking `read(...)` wakes promptly, then clean up the temp WAV and preserve the thread interrupt flag when interrupted.
- Cancel must also abort an in-flight transcription request when the user presses Escape after recording has stopped but before Groq returns.
- Temp WAV files are deleted after transcription/cancel/failure.
- Empty or too-short recordings should not call Groq. For the first milestone, treat recordings shorter than 500 ms of finalized audio as empty/too short and show `No speech was recorded.` without uploading; avoid aggressive RMS silence rejection until there is a tested calibration path.
- Enforce bounded recordings to avoid runaway temp files/uploads. For the first Groq milestone, use conservative exact caps: effective recording duration limit from validated `chat4j.stt.recording.maxDurationSeconds` (default 600 seconds, capped at 600), max captured WAV 24 MiB, and max provider upload 25 MiB unless fresh implementation-time Groq docs prove a lower limit. When the configured duration limit is reached, stop/finalize capture cleanly, show the user that the duration limit was reached, then proceed through too-short and size checks before transcription. Refuse uploads that exceed the provider limit with a safe error. The WAV/upload size caps are post-finalization safety guards; a compliant 16 kHz mono 16-bit 10-minute WAV is below those thresholds, so the duration cap normally fires first in real capture.

## Input Bar UX

Normal state:

```text
existing typed text

[paperclip] [commands] [thinking] [web] ...                 [mic] [send-if-sendable]
```

Recording state:

```text
existing typed text

        ···········||||||||||||····|||||············   0:07   [■]
```

Implementation shape:

- Refactor `InputBar` so the current local `actionsRow`, right-side cancel panel, and composer bottom panel become fields that can be toggled by recording state. The recording panel must be added/removed or shown/hidden through fields, not trapped in constructor-local Swing variables.
- Add a separate mic button and a separate send/up-arrow button; do not reuse `cancelGenerationButton`, which is already wired to assistant generation cancellation.
- The mic button is visible/enabled only when STT settings resolve to an enabled and available provider, the input is in normal compose mode, the conversation is not loading/preparing/streaming, and recording/transcription is idle. If STT is `Off` or unavailable, hide the mic rather than presenting a broken action.
- The send/up-arrow belongs to normal compose mode. Hide it while `EditComposerPanel` is active so edit mode continues to use its explicit `Save only` / `Save & regenerate` header actions rather than adding a second ambiguous send affordance.
- Recompute mic and send/up-arrow visibility whenever document text changes, attachments/skills change, STT settings reload, edit mode toggles, conversation loading/busy state changes, selected provider/model changes, provider resolution starts/succeeds/fails, provider refresh invalidates the selected provider, or recording/transcription state changes. `ChatPanel.applyResolvedProvider(...)` and `applyProviderResolutionFailure(...)` (or their extracted coordinator equivalents) must update `InputBar` presentation on the EDT. These recomputes must use in-memory STT and chat-provider readiness snapshots; do not call file-backed `SettingsRepository` or provider catalog refresh from document listeners or other high-frequency EDT paths.
- Centralize composer enabled/read-only/recording state instead of scattering raw `InputBar.setEnabled(true/false)` effects. Existing `ChatPanel` paths for preparing, streaming, cancellation, conversation loading, provider refresh, and late `SwingUtilities.invokeLater` callbacks must update busy/edit/STT state flags and then recompute presentation, so a stale busy-state callback cannot re-enable mutation, show normal actions, or hide the recording panel while recording/transcribing is active.
- Add `InputRecordingPanel` to the composer bottom area.
- Add `RecordingWaveformTimeline` custom component.
- Keep the text area visible, but set it read-only while recording/transcribing. Do not use `InputBar.setEnabled(false)`, because that disables the text area and applies disabled styling.
- On recording start, hide any visible slash popup and suppress slash suggestions while recording/transcribing. Also hide any visible `PromptCommandCenter` popup and block `MainFrame.openCommandCenter()`/global command-center shortcuts while STT is active; Escape handling for recording/transcription must win over command-center dismissal and `handleSlashPopupKey(...)`. Install STT Escape cancellation at the recording panel/InputBar root pane or `MainFrame` level, not only on the text area's `KeyListener`, so it preempts slash popup, `PromptCommandCenter`, model popup, chat search, and focused stop/waveform controls. Any detached web-search/reasoning/context popup menus, model selector popup, and chat search popup should also be hidden so no popup steals Escape/focus or mutates conversation/composer/provider-derived state while the normal action row is hidden.
- Temporarily disable composer mutation while recording/transcribing: hide the action row, prevent `requestAttachmentPicker()`/`openAttachmentPicker()`, guard file-drop imports on both `textArea` and `composerShell`, avoid accepting pasted/dropped text into the read-only recording state, prevent drag-out MOVE/cut/export paths from deleting selected text while the text area is read-only, and make existing attachment/skill chips immutable by disabling or hiding their remove buttons until recording/transcription exits.
- Add an explicit composer mutation gate such as `InputBar.isComposerMutable()` / `isRecordingOrTranscribing()`. Public mutation methods (`setText`, `setComposerState`, `clear`, attachment/skill/project-root/web-search/agent-mode request methods, prompt insertion helpers) must either reject/defer user-initiated mutations while recording/transcribing or be reserved for controlled lifecycle restoration paths. Do not rely on `InputBar.isEnabled()`, because the plan keeps the text area visually enabled/read-only.
- External mutation entry points must check the same gate: MainFrame command center and prompt-template insertion, empty-state quick actions (`setText`, agent mode, attachment picker, web search), menu/shortcut actions, model-selector actions/shortcuts that can change provider-derived composer capabilities, bubble/context-menu edit actions, WebView transcript actions, and any other code that mutates the composer without using the visible action row.
- Hide or disable the agent project-root row while recording/transcribing so the project chooser cannot mutate composer state while the action row is hidden.
- Hide the normal action row while recording/transcribing.
- Add accessible names/tooltips for mic, send/up-arrow, stop-square, recording timer, waveform/level indicator, and transcribing status. Icon-only controls must remain keyboard reachable in normal mode, and Escape/stop behavior must be discoverable through tooltips or status text. Do not blindly reuse the existing `configureInputIconButton(...)` focus policy for mic/send/stop; current input toolbar buttons call `setFocusable(false)`, but the new STT controls need explicit keyboard focus or alternate documented key bindings.
- Hide mic and send/up-arrow during recording.
- Do not allow starting STT while `ChatPanel` is editing an existing user message unless `EditComposerPanel` is explicitly updated to disable its Save/Regenerate/Cancel actions and route Escape to recording first. For this milestone, hide/disable the mic while edit mode is active.
- Do not allow entering edit mode while recording/transcribing; `startEditingUserMessage(...)` currently clears and replaces the composer, so bubble/context-menu edit actions must be disabled or ignored during STT.
- Suppress keyboard sends while recording/transcribing. Enter/Cmd+Enter/Ctrl+Enter must not call `fireSend()` until recording/transcription state exits, even if the preserved composer state contains text or attachments.
- On recording start, snapshot the full composer state needed for exact restoration: raw text, caret/selection, attachments, active skills, and any UI validation/status that should return after cancel. Do not rely on `InputBar.getText()` for this path because it trims whitespace; use raw document text/`getRawText()` semantics for append and restore decisions.
- The recording panel's own stop-square button stops recording and starts transcription.
- Escape cancels/discards active recording; while transcribing, Escape cancels the in-flight transcription and restores the preserved input without appending text.
- While transcribing, keep the UI responsive and show `Transcribing...`.
- Use one deterministic STT-active action policy shared by shortcuts, menu items, title-bar buttons, sidebar callbacks, command center actions, and direct `MainFrame`/`ChatPanel` methods:
  - Escape cancels/discards recording or cancels in-flight transcription; the stop-square stops recording and starts transcription.
  - Window close/final dispose/shutdown cancels and releases STT resources before shutdown continues.
  - User-initiated navigation/destructive/modal actions are blocked with a clear status while STT is active: File → New Chat, View → Toggle Model Dropdown, View → Chat Search, Model menu item selection, Settings, prompt command center, command-center actions, prompt insertion, edit/regenerate, conversation load/switch, clear chat, conversation deletion, sidebar rename/delete/delete group/delete all, conversation runtime setting application/reset, WebView transcript regenerate/read-aloud/open-attachment/open-diagram-html, attachment/project-root/web-search/reasoning mutations, read-aloud, Font/Theme changes, and About dialogs.
  - STT-safe UI/metadata actions are allowed while STT is active: sidebar visibility toggle, View → Toggle Sidebar, View → Toggle Preview/render-mode switch, sidebar filter text, sidebar favorite toggles, and WebView transcript copy/copy-selected/copy-text. Sidebar favorite toggles and render-mode changes may persist UI metadata, but must not switch conversations, mutate composer state, start assistant work, or discard captured audio. If an otherwise-allowed action is reached through the command center, the command center itself remains blocked/hidden while STT is active.
  - No STT-active action should silently discard captured audio except explicit Escape/cancel or final disposal.
- Conversation actions that would start assistant work, especially regenerate from Swing bubbles, WebView transcript regenerate, and command-center regenerate, must be disabled or ignored while recording/transcribing. Model selector actions/shortcuts and chat search entry/selection must be blocked while STT is active because they can change provider-derived composer state or switch conversations. These paths must not start streaming, truncate history, switch conversations, or call `InputBar.setEnabled(...)` over the recording UI. For WebView/System transcript rendering, add action-availability state to `TranscriptRenderSnapshot` (or equivalent) so regenerate/read-aloud buttons are hidden or disabled while STT is active while copy remains available; callback handlers must also block read-aloud/regenerate/open-attachment/open-diagram-html during STT and allow only copy-style actions.
- Opening Settings while recording/transcribing must be blocked across all entry points: sidebar button, macOS Preferences, global shortcut, command center, and any internal/test-visible direct Settings entrypoint. `MainFrame.openSettings()` is currently private, so tests should cover public entry points or introduce a package-private/test-visible gate seam rather than depending on reflective private-method calls. Show a concise status such as `Finish or cancel transcription before opening Settings.` and do not let the modal settings apply/reload path race active capture/transcription.
- Starting recording should stop active TTS/read-aloud playback first, and read-aloud actions should be disabled or ignored while recording/transcribing so microphone input does not capture Chat4J's own speech output. Swing bubble buttons, context-menu items, WebView transcript buttons/snapshots, and direct action handlers must all treat read-aloud as unavailable while STT is active. Transcribing may leave read-aloud disabled until the STT state returns to idle.
- On success, append transcript to the preserved raw text from the recording-start snapshot, not a trimmed `getText()` value:
  - blank raw input: set transcript
  - raw input without trailing whitespace: append newline + transcript
  - raw input with trailing whitespace: append transcript
  - preserve existing attachments/active skills, restore input focus, and move the caret to the end so the user can edit or send immediately
- On failure, preserve existing text and show a safe error.

Theme notes:

- Do not hard-code Codex colors.
- Use `UIManager` colors: foreground/accent for bars, disabled/separator color for dotted baseline, current button colors for stop/send.
- Add any missing SVG resources explicitly, including a mic icon and send/up-arrow icon if no suitable existing icon is present.

## Groq Provider

Implement Groq STT as batch transcription using Java `HttpClient` behind injectable STT HTTP transport/request/response types. Do not add a Groq SDK dependency. Unlike the current byte-array-oriented TTS transport, the STT transport contract must support path-backed/streaming request bodies and explicit cancellation of the active request.

Request:

- `multipart/form-data` with a generated boundary, CRLF separators, and no full request-body logging
- `Content-Type: multipart/form-data; boundary=<generated-boundary>` and `Accept: application/json` headers
- Authorization header: `Bearer <GROQ_API_KEY>` resolved through `CredentialResolver`
- file part: captured finalized WAV, field name `file`, filename ending in `.wav`, content type `audio/wav`, streamed from disk using a path-backed multipart part/`BodyPublisher`; do not copy the entire audio file into a `byte[]` request body
- model part: field name `model`, value is the selected Groq STT model ID
- response format part: `response_format=json`
- model catalog refresh: `GET <configured-base-url>/models`, with the same trailing-slash normalization and safe STT-model filtering used by bundled defaults. Initial Groq filtering should be conservative: include known transcription model IDs from bundled defaults and IDs containing `whisper`/`distil-whisper`; exclude TTS/speech-generation families such as `orpheus`, `tts`, and non-transcription `speech` models unless current Groq docs prove they are valid transcription models.

Safety:

- Never log API keys, full request bodies, or audio bytes.
- Parse successful JSON by reading the top-level `text` field, trimming/normalizing only surrounding whitespace, and preserving interior newlines/punctuation from the transcript. If `text` is missing or blank after trimming, treat it as an empty transcription with a safe user message rather than inserting blank text or reporting success.
- Parse provider errors safely, extracting useful `error.message`, `detail.message`, or top-level `message` when available.
- Use the configured Groq chat provider base URL from `SettingsKeys.providerBaseUrlKey("Groq")`, defaulting to `https://api.groq.com/openai/v1`, normalize trailing slashes, and append `/audio/transcriptions`. This preserves custom Groq-compatible endpoints instead of hard-coding the default URL. Blank stored base URL falls back to the default. Syntactically malformed values, unsafe non-`http(s)` schemes, relative URLs, and hostless URLs fail closed with a concise settings/provider error and no request construction/send; never throw an uncaught `IllegalArgumentException`/`URISyntaxException` from UI callbacks or background workers. Before network use, require the normalized URL to be an absolute `http`/`https` URI with a host; reject/report safe errors for `file:`, `jar:`, `mailto:`, relative, or hostless values without constructing or sending an HTTP request.
- Use bounded HTTP timeouts and make transcription/catalog cancellation interrupt/cancel the active request. The injectable `SttHttpTransport` should expose cancellation semantics, such as returning a cancellable future/handle or accepting a cancellation token that the Java `HttpClient` adapter maps to `CompletableFuture.cancel(true)` or equivalent request abortion. Also bound response buffering: transcription and catalog JSON responses should have a reasonable max in-memory size (for example 1 MiB for transcription JSON and 2 MiB for `/models`), and provider error detail extraction should cap inspected text (for example 64 KiB) before parsing/log-safe formatting.
- HTTP success/error contract: treat only 2xx transcription responses with parseable JSON and a nonblank top-level `text` as successful transcript insertion. 2xx malformed JSON, 2xx missing/blank `text`, and every non-2xx response must never insert transcript text; non-2xx responses should surface capped safe error detail with special-friendly messages where useful (`401/403` credentials, `404` endpoint/model, `413` upload too large, `429` rate limited, `5xx` provider unavailable). Catalog non-2xx or malformed responses preserve cached/bundled models and show safe status.
- At recording start, snapshot the normalized STT provider id, model id, Groq base URL, and final transcription endpoint. `SpeechToTextService` must re-resolve settings before upload and refuse transcription if STT is now `Off`, unavailable, or if provider id, model id, normalized Groq base URL, or final transcription endpoint changed during recording; show `Speech-to-text settings changed; recording was not uploaded.` instead of sending stale audio to a changed destination.

Bundle model items initially, e.g.:

- `whisper-large-v3-turbo`
- `whisper-large-v3`
- `distil-whisper-large-v3-en` if still supported by Groq

## Integration Points

- `SettingsKeys`: add STT constants and helper key methods.
- `StoragePaths`: add `sttModelsDirectory()` and `sttTempDirectory()`.
- `AppServices` / bootstrap / `MainFrame` / `SettingsDialog`: carry `StoragePaths` or resolved STT model/temp directories where needed for settings and service construction. `ApplicationBootstrap.initializeStorage()` should include `StoragePaths` in `AppServices` (or pass resolved directories alongside services) so deep UI code does not rediscover defaults. Preserve existing `MainFrame` and `SettingsDialog` constructor/test ergonomics with overloads or test-only defaults that still avoid production calls to `StoragePaths.defaultPaths()` from deep UI.
- `SettingsDialog`: add top-level `Speech to Text` section.
- `MainFrame` / `ChatPanel`: create/inject `SpeechToTextService` alongside `TextToSpeechService`; provide a `SpeechToTextService.disabled()` test/no-op path that mirrors TTS only at the API/constructor-ergonomics level. Unlike the current disabled TTS implementation, disabled STT must be a true no-op that allocates no executor, capture, HTTP, temp-file cleanup, or other background/file resources. Preserve existing `ChatPanel` constructor/test ergonomics by having older constructors delegate to disabled STT.
- `MainFrame` / `ChatPanel` / empty-state actions: route command-center actions, prompt-template insertion, empty-state quick actions, WebView transcript actions, bubble/context-menu edit actions, regenerate actions, Settings entry points, chat search entry/selection, conversation runtime setting application/reset, direct title-bar callbacks, sidebar callbacks, and menu/shortcut composer mutations through the same composer/STT busy gates used by `InputBar` during recording/transcribing. Introduce one STT-aware global action gate for `MainFrameShortcutBinder`/menu/title-bar/sidebar actions, or extend actions with availability predicates, so root-pane shortcuts, title-bar New Chat/Search, sidebar New Chat, sidebar conversation selection/deletion, sidebar Settings, command center, chat search, and model selector cannot run while STT is recording/transcribing except for the explicit Escape/cancel and shutdown/dispose policy.
- `MainFrame.openSettings()` apply callback: add `chatPanel.reloadSpeechToTextSettings()` next to `reloadTextToSpeechSettings()`. Reload should resolve file-backed STT settings off the EDT into an immutable snapshot, then update `InputBar` availability on the EDT.
- Conversation transitions follow the deterministic STT-active action policy: user-initiated new chat, clear chat, load/switch conversation/history, conversation deletion, conversation runtime settings reset/application, and settings reload are blocked while recording/transcribing; final window close, `ChatPanel.removeNotify()`, and dispose/shutdown paths cancel/release STT before shutdown continues. Direct/test-only calls that bypass the user-action gate must still be safe: either no-op/defer while STT is active or cancel through the same explicit lifecycle cancellation path, never mutate composer/history/runtime state under an active recording UI. Mirror TTS read-aloud cleanup but cover input-side capture state too. During shutdown, cancel/release STT before the shutdown save snapshot and final dispose so no microphone/request work survives the window close path.
- `InputBar`: add mic button/listener, send/up-arrow button, recording UI state methods, Escape binding, transcript insertion, slash-popup/model-popup suppression, immutable chip/project-root behavior, attachment/file-drop guards during recording/transcribing, explicit keyboard accessibility/focus handling for new STT icon controls, and explicit availability setters for STT/busy/edit state.
- Edit mode: `ChatPanel` should tell `InputBar` whether normal compose mode is active. Hide/disable STT while `EditComposerPanel` is installed unless edit-mode STT support is deliberately implemented with safe header-button/Escape handling.
- Streaming state wins: do not show mic/start recording while assistant generation/preparation is visible; existing generation cancel remains separate and higher priority.
- Final dispose/shutdown should cancel active recording/transcription. Ordinary settings reload entry points are blocked while STT is active; if a direct/test-only reload method is invoked anyway, it must defer or no-op until idle rather than racing active capture/transcription. On final window disposal, shut down STT executors/capture resources as well as canceling active sessions; `removeNotify()` should at least cancel active STT and release microphone/request resources so no background recording survives panel removal.
- WebView/System transcript action availability must be threaded through the actual rendering API surface: `TranscriptRenderSnapshot`, `TranscriptRenderSupport`, `SystemWebView.setTranscript(...)`, `JcefBrowserView.setTranscript(...)`, and their reload/change-detection logic all need regenerate/read-aloud availability state so stale rendered buttons disappear when STT becomes active.
- macOS packaging: add the supported jpackage macOS plist override under `src/jpackage/macos` (for example `src/jpackage/macos/Info.plist`, copied through the existing `--resource-dir` flow) with a clear `NSMicrophoneUsageDescription`. For this milestone, automate effective `Info.plist` validation in the built unsigned app/DMG; signed/notarized microphone-prompt validation is a release/manual gate unless signing/notarization is added to this milestone's packaging scope. Packaging validation must inspect the built app's effective `Chat4J.app/Contents/Info.plist` from the app image/DMG, not only the source resource, and ensure jpackage-generated keys such as bundle id/name/version remain present.
- Documentation: add compact `docs/speech-to-text.md` and link it from `docs/README.md`, covering settings, Groq credentials/base URL behavior, model directory, recording UX, cancellation, privacy/error handling, and future local/cloud provider extension points.

## Errors

Suggested messages:

- Mic unavailable: `Microphone access is unavailable. Check your input device and permissions.`
- Audio format unavailable: `The default microphone could not provide or convert to 16 kHz mono recording.`
- Empty recording: `No speech was recorded.`
- Groq missing key: `Groq requires GROQ_API_KEY.`
- Cancellation by Escape during recording: silent or brief `Recording canceled.`
- Cancellation by Escape during transcription: cancel the provider request if possible and show `Transcription canceled.` only if a status message is needed.

## Tests

Add/update tests:

- `StoragePathsTest`
  - `sttModelsDirectory()` resolves exactly to `appConfigDirectory().resolve("stt").resolve("models")` and `sttTempDirectory()` resolves exactly to `appConfigDirectory().resolve("stt").resolve("temp")` for Windows APPDATA, Windows fallback, XDG, and non-XDG fallback paths
- `SettingsRepositoryTest` / batch-writer tests
  - correlated put/remove operations persist under one lock/load/store operation
  - simulated store failure leaves the previous settings file intact and no partial STT provider/model/catalog tuple is visible
  - validation rejects blank keys in batch operations
  - concurrent batch and single-key writes remain serialized
- `SpeechToTextSettingsTest`
  - defaults off
  - defaults off even when a controlled/fake Groq provider is available
  - blank provider defaults off
  - saved Groq resolves
  - unavailable Groq remains selected/unavailable
  - selecting Groq without `GROQ_API_KEY` persists `chat4j.stt.provider=groq` while remaining unavailable and not auto-enabling recording, using an injectable credential source/test seam rather than the real process environment
  - unknown provider falls back off
- `SpeechToTextCatalogStoreTest`
  - malformed cache falls back to bundled models
  - missing/malformed/older-than-24-hours `updatedAt` marks catalog stale without breaking cached/bundled display
  - cached models merge with bundled/default models and saved selected model
  - refreshed catalogs preserve selected model when omitted
- `SpeechToTextModelDirectoryTest`
  - default path comes from injected `StoragePaths.sttModelsDirectory()` / default model directory
  - saved override path wins
  - `~` / `~/...` expand to `user.home`, relative paths become `Path.toAbsolutePath().normalize()`, absolute paths are normalized, and the normalized absolute path is persisted/used
  - explicit invalid/non-writable override reports an error instead of silently falling back
  - directory is created when saving/first using a valid path
  - provider/model IDs are safely slugged for local directories
- `SpeechToTextProviderRegistryTest`
  - Groq registered, no null providers
- `SpeechToTextProviderContractTest`
  - provider availability labels/messages come from provider contract
  - provider catalog fetch uses an explicit `SpeechToTextProviderContext` (or equivalent) carrying normalized base URL/endpoint, credential source, transport/cancellation, and provider id; providers do not read `SettingsRepository` directly
  - unavailable Groq text names `GROQ_API_KEY` without duplicating env-var formatting in UI/service code, using an injectable credential source/test seam to force present/missing credentials regardless of `System.getenv`
  - custom Groq base URL is honored for STT when the chat Groq provider is disabled; STT base URL resolution does not use `ProviderRegistry.availableProviders()` filtering and does not use raw `ProviderRegistry.allProviders()` defaults without applying runtime settings
- `GroqSpeechToTextProviderTest`
  - injectable STT HTTP transport is used; no real network calls
  - concrete `JavaNetSttHttpTransport` is isolated behind the STT transport interface and Swing/settings code never depends on it directly
  - STT model discovery does not use chat `ProviderRegistry` or chat model filters
  - STT request body is path-backed/streaming and does not buffer the entire audio file into a byte array
  - cancellable transport handle/token aborts an active transcription request
  - multipart request shape, `Content-Type` boundary header, `Accept: application/json`, boundary/CRLF formatting, file field name, filename, `audio/wav` content type, and `response_format=json` field
  - `Authorization: Bearer ...` auth header from `CredentialResolver`
  - selected model parameter
  - configured Groq base URL with trailing-slash normalization
  - blank configured Groq base URL falls back to the default; malformed/unsafe/relative/hostless configured URLs fail closed with a safe error and no request, without uncaught URI exceptions
  - syntactically valid but unsafe base URLs (`file:`, `jar:`, `mailto:`, relative, hostless, or non-http(s)) are rejected with a safe error before any HTTP request is built or sent
  - model catalog refresh uses configured base URL, `/models`, `Authorization: Bearer ...`, and conservative STT-model filtering that includes Whisper transcription models and excludes TTS/speech-generation models; refresh is skipped when Groq is unavailable or the API key is missing
  - audio file streamed or represented without requiring the whole file in provider memory
  - transcript JSON `text` parsing that preserves interior transcript formatting
  - missing or blank transcript `text` is handled as an empty transcription without inserting blank content
  - safe error parsing
  - timeout/cancellation behavior where injectable transport permits it
  - transcription HTTP status matrix: 401/403, 404, 413, 429, 5xx, 2xx malformed JSON, 2xx blank/missing text, and capped oversized error bodies never insert transcript text and produce safe messages
  - catalog non-2xx/malformed responses preserve cached/bundled models
  - oversized transcription/catalog/error responses are capped and fail safely without buffering unbounded provider bodies
- `SpeechToTextServiceTest`
  - disabled no-op service construction creates no executor, capture, HTTP, temp-file cleanup, or background/file resources
  - idempotent stop/cancel, stale callback/request-id rejection, and late transcription results ignored after explicit cancel, forced lifecycle replacement, shutdown, or dispose
  - settings are re-resolved before upload and provider/model/base-url/transcription-endpoint/off/unavailable changes refuse upload with `Speech-to-text settings changed; recording was not uploaded.`
  - active transcription request is aborted on cancel where the transport supports it
  - temp cleanup orchestration runs on service startup, preserves fresh files that may belong to another process/session, and executor/capture/transport resources shut down on dispose
- `WavFileWriterTest`
  - WAV header/format, including little-endian PCM fields
  - finalization and cleanup behavior
- `MicrophoneAudioCaptureTest`
  - fake audio source, no real microphone
  - exact 500 ms minimum duration behavior: below threshold does not upload, equal/above may upload
  - configured max-duration behavior below/equal/above threshold using a shorter test value and the default 600-second cap; when the effective duration limit is reached, capture finalizes and proceeds to too-short/size/transcription checks
  - 24 MiB captured-WAV and 25 MiB provider-upload cap below/equal/above-threshold behavior with synthetic finalized `CapturedAudio`/temp files because duration normally fires first for compliant 16 kHz mono 16-bit WAV capture
  - max duration settings validation rejects `0`, negative, nonnumeric, and `>600` values with a settings status error without persisting them; persisted invalid values resolve to default 600 and cannot exceed 600 seconds
  - direct 16 kHz little-endian capture path
  - temp WAVs use injected `StoragePaths.sttTempDirectory()`, safe non-user-content filenames, owner-only permissions on POSIX platforms where supported (and non-POSIX fallback does not fail or broaden access unexpectedly), finalized headers before upload, cleanup after success/failure/cancel, and narrowly scoped stale-temp cleanup on service startup without recursion or symlink following; files newer than 24 hours, fresh other-session files, active-session files, symlinks, directories, and recursive contents survive cleanup
  - fallback conversion path follows the explicit format order when direct format is unavailable and checks Java Sound conversion support before use
  - unsupported-format error after direct and all explicit conversion attempts fail
  - stop/cancel closes the line to unblock reads and cleans up temp files
  - RMS/peak calculation
  - audio-level callbacks are throttled/coalesced before reaching the EDT
- `InputRecordingPanelTest`
  - timer formatting
  - level updates repaint/update state
- `SpeechToTextPanelTest`
  - existing `SettingsDialog` constructors remain usable in tests or delegate to explicit injected STT paths without production deep-UI storage rediscovery
  - provider options are Off/Groq only
  - provider/model/model-directory/duration setting writes do not block the EDT and pending STT saves complete or are safely handled before SettingsDialog apply callbacks reload STT
  - correlated provider/model/duration/catalog settings are saved through a result-returning batch/snapshot path; simulated mid-save failure does not apply a partial STT selection/catalog and propagates to `SettingsDialog`
  - STT panel does not use inherited synchronous `AbstractSettingsPanel.writeSetting(...)` / `removeSetting(...)` / `bindTextField(...)` for async/pending saves that must report failure to the dialog
  - initial settings/catalog snapshots avoid repeated file-backed reads from high-frequency Swing listeners
  - generalized SettingsDialog pending-save errors identify STT failures accurately rather than saying only prompt settings failed
  - selecting unavailable Groq is allowed, saved, marked unavailable, and keeps mic hidden
  - cached/bundled models load immediately and selected model is preserved across refresh
  - unavailable Groq remains visible with missing-key helper text
  - model directory browse/save behavior uses injected default path
  - invalid/non-writable model directory shows a status error
  - model directory validation/creation and its settings write run off the EDT, with status updates marshaled back
  - Groq helper text states recorded audio is sent to Groq
  - no API key field is shown
- `SettingsDialogTest` / pending-save coordinator test
  - `dispose()` invokes all generic pending-save participants, not only `PromptsPanel`
  - closing is blocked when an STT pending save fails
  - error text is section-neutral or STT-specific instead of hard-coded prompt-only copy
- `InputBarSpeechToTextTest`
  - mic hidden when STT is off or selected provider is unavailable
  - mic visible only when STT is enabled/available and composer is not busy, editing, recording, or transcribing
  - mic/send visibility recomputes after text, attachment, skill, STT availability, busy, edit, selected provider/model, provider resolution start/success/failure, provider refresh invalidation, provider-derived capability, and recording/transcription state changes using in-memory snapshots only, with no settings-file reads on document events
  - late busy/loading/streaming callbacks that call existing input-state paths cannot re-enable mutation or restore normal action rows during recording/transcribing
  - mic hidden/disabled while edit mode is active
  - send/up-arrow hidden while edit mode is active
  - action row hidden during recording
  - text visible/read-only during recording
  - slash popup, detached web-search/reasoning/context menus, model selector popup, command center, and chat search popup are hidden/suppressed and root-pane/MainFrame-level Escape cancels recording/transcription before popup dismissal or focused child controls consume it
  - attachment picker, file drops, text drops/pastes, and drag-out MOVE/cut/export deletion are ignored while recording/transcribing
  - public mutation methods and external prompt/empty-state/menu/command-center/WebView/bubble-context actions are blocked or deferred while recording/transcribing
  - entering edit mode is blocked while recording/transcribing
  - existing attachment/skill chips cannot be removed while recording/transcribing
  - project-root chooser cannot open or mutate project root while recording/transcribing
  - Escape cancels recording
  - Escape cancels in-flight transcription before transcript insertion
  - keyboard send is suppressed while recording/transcribing
  - new send/up-arrow button visibility and dispatch
  - send/up-arrow remains visible/enabled for attachment-only or skill-only composer states when the selected chat provider is resolved and the conversation is otherwise sendable
  - send/up-arrow hides or disables with a clear tooltip/status while the selected provider is still resolving, missing/null, conversation-loading, preparing, streaming, editing, or STT-active
  - transcript append/send visibility
  - transcript append uses raw text, not trimmed `getText()`, so trailing whitespace controls newline insertion correctly
  - cancel restores the full composer snapshot, including attachments, active skills, caret/selection, and preserved text
  - focus and caret move to the end after transcript insertion
  - mic remains available after transcription when STT is still enabled/available and normal compose mode is restored
  - mic/send/stop/timer/waveform/transcribing controls expose accessible names and useful tooltips
  - mic/send/stop controls are keyboard reachable or have documented alternate key bindings despite existing input toolbar focus policy
  - starting recording stops active TTS/read-aloud playback
- `MainFrameSpeechToTextWiringTest`
  - `AppServices`/bootstrap carries `StoragePaths` or resolved STT dirs into `MainFrame`/`SettingsDialog` without deep UI calls to `StoragePaths.defaultPaths()`
  - existing `MainFrame` constructor/test paths remain usable via explicit injected or test-only STT paths
  - STT-active action matrix is enforced for menus: File → New Chat, View → Toggle Model Dropdown, View → Chat Search, Model menu item selection, Font/Theme changes, and About are blocked; View → Toggle Sidebar and View → Toggle Preview/render-mode switch are allowed as STT-safe metadata/UI actions
  - macOS `Desktop.setAboutHandler(...)` and any direct `AboutDialog.show(...)` entry points consult the same STT-active action gate as menu About
  - sidebar policy is enforced: New Chat, Settings, conversation selection, delete/delete group/delete all, and rename are blocked while STT is active; sidebar visibility toggle, filter text, and favorite toggles remain allowed
  - direct title-bar entry points use the same gate: title-bar New Chat/Search are blocked while STT is active; title-bar/sidebar visibility toggle remains allowed
  - window close/shutdown invokes STT cancel/release before `createShutdownSaveAction()` captures history/composer/runtime state
- `ChatPanelSpeechToTextTest`
  - existing `ChatPanel` constructors delegate to disabled STT for tests/default paths and do not start STT resources
  - start/stop callback wiring
  - no recording while streaming
  - no recording while preparing/provider send preparation is active
  - no recording while editing a user message
  - transcription re-resolves STT settings before upload and refuses stale/off/unavailable selections
  - composer state recomputation preserves recording/transcribing state across preparing/streaming/loading state changes and late `invokeLater` callbacks
  - transcription inserts text
  - late transcription results are ignored after explicit cancel, forced lifecycle replacement, shutdown, or dispose
  - user-initiated clear chat, conversation/history load, and settings reload are blocked while STT is active and do not cancel/discard captured audio implicitly
  - direct/test-only `clearChat()`, `clearChatView()`, `loadHistory()`/`loadConversationHistory()`, `showModelPopupCentered()`, and late `setConversationLoading(false)` calls while STT-active no-op/defer or preserve the recording/transcribing UI; they must not mutate history/composer/provider popup state or re-enable `InputBar`
  - visible `PromptCommandCenter` is hidden on STT start, `MainFrame.openCommandCenter()`/global command-center shortcuts are blocked while STT is active, Escape cancels STT before command-center dismissal, and command-center actions/prompts cannot mutate the composer or start assistant work during recording/transcribing
  - prompt-template insertion, empty-state quick actions, model-selector actions, chat search entry/selection, edit actions, conversation runtime settings reset/application, and WebView/bubble regenerate actions cannot mutate the composer or start assistant work during recording/transcribing
  - WebView/System transcript render snapshots plus `SystemWebView`/`JcefBrowserView` setTranscript/reload paths hide/disable regenerate and read-aloud action buttons while STT is active, keep copy available, and callback-side handling blocks read-aloud/regenerate/open-attachment/open-diagram-html while allowing copy/copy-selected/copy-text
  - Settings entry points from sidebar, macOS Preferences, global shortcut, command center, and any package-private/test-visible direct Settings gate seam are blocked while STT is active before showing the modal
  - Swing bubble buttons, context menus, WebView transcript snapshots/buttons, and direct handlers disable or ignore read-aloud while recording/transcribing
  - new chat/load/switch/runtime-setting application is blocked while STT is active through user entry points; shutdown save snapshots cancel/release active STT first
  - active recording/transcription is canceled and STT resources are released on `removeNotify()`/final disposal
  - removeNotify/dispose cancels active recording/transcription
- Documentation checks
  - `docs/speech-to-text.md` exists and is linked from `docs/README.md`
  - docs mention Groq env var/base URL, model directory, recording controls, cancellation, and local/cloud extension points
- Packaging/manual smoke checks
  - automated check confirms the jpackage macOS resource plist under `src/jpackage/macos` contains `NSMicrophoneUsageDescription`
  - automated packaging validation inspects the built unsigned app's effective `Chat4J.app/Contents/Info.plist` from the app image/DMG for `NSMicrophoneUsageDescription` and verifies jpackage-generated bundle id/name/version keys remain present
  - macOS packaged app includes microphone privacy description and prompts successfully in unsigned local/manual smoke testing; signed/notarized prompt validation is a release/manual gate unless signing/notarization is added to this milestone
  - microphone denial/unavailability shows the planned safe error

## Implementation Steps

1. Add STT settings keys and model/temp directory resolvers, including `StoragePaths.sttModelsDirectory()` / `StoragePaths.sttTempDirectory()`, extend `AppServices`/bootstrap wiring to carry those paths, and thread them through `MainFrame`/`SettingsDialog` while preserving constructor/test ergonomics.
2. Add provider contracts, availability messages, catalog item/store, registry, and settings resolver.
3. Add Groq provider with injectable cancellable STT HTTP transport, path-backed streaming multipart request builder, configured Groq base URL support, and exact upload-limit checks.
4. Add WAV writer and injectable/fakeable audio capture primitives, including conversion fallback to 16 kHz mono when direct capture is unavailable.
5. Add `SpeechToTextService` lifecycle, cancellation, and callbacks.
6. Add `SpeechToTextPanel` and register it in `SettingsDialog`.
7. Add the small STT UI/controller state layer (`ComposerSttState`, `InputBarSttController`, `ChatPanelSttCoordinator`, or equivalent) so lifecycle gates, popup/shortcut routing, and presentation recomputation are centralized.
8. Refactor `InputBar` action-row/project-root/chip controls and centralize composer presentation state, then add mic button, send/up-arrow button, separate recording stop control, recording UI, slash-popup suppression, accessibility metadata, and attachment/file-drop guards.
9. Wire `ChatPanel` to `SpeechToTextService`, including provider-resolution sendability recomputes, settings reload, global shortcut/menu gates, and dispose cleanup.
10. Update macOS jpackage resources for microphone privacy permission and verify the built app's effective plist.
11. Add compact STT documentation and link it from the docs index.
12. Add focused tests for each layer.
13. Run targeted Maven tests, then full `mvn -q test` and `git diff --check`.
