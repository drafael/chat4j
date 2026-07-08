# Vosk Local Speech-to-Text Implementation Plan

## Goal

Add Vosk as the first local Speech-to-Text provider for Chat4J. Chat4J should bundle the Vosk Java/native runtime, but it must not bundle any speech models. Users select/download models from Settings or provide local model folders themselves.

This is not an MVP: implement the full settings, catalog, download/install, validation, error handling, docs, packaging, and test surface needed for a reliable local STT feature.

## Product Decisions

- First local provider: Vosk.
- Ship Vosk runtime/dependency with the app; ship zero Vosk models.
- Batch transcription first: reuse the current record-WAV-then-transcribe flow.
- No Vosk model caching initially. Load and close the Vosk model per transcription for predictable native memory and filesystem lifecycle.
- Use official Vosk JSON model index, not HTML scraping:
  - `https://alphacephei.com/vosk/models/model-list.json`
- Catalog loading strategy:
  - fetch online JSON when refreshing;
  - cache successful JSON locally;
  - include a bundled fallback snapshot for first-run/offline behavior.
- Build catalog metadata from official speech-recognition models:
  - include speech-recognition types `small`, `big`, `big-lgraph`;
  - exclude non-transcription `tts` and `spk` entries from Vosk STT metadata;
  - keep obsolete speech-recognition entries in metadata for matching already-installed models and showing warnings;
  - exclude obsolete entries from default remote download choices.
- Already-installed valid Vosk speech-recognition models should remain visible and usable even if the official catalog marks that model obsolete; show an “obsolete/not recommended” warning/status instead of silently hiding a user’s installed model.
- Model dropdown contains only installed model folders that are eligible for local use: they must pass required structural/safety validation or be marked as a clearly surfaced “plausible but unverified” Vosk layout. Hard-invalid, missing, remote-only, and unsafe-path entries must never be selectable. A plausible-but-unverified selection must not make the mic ready until an off-EDT validation/probe or explicit preparing flow succeeds.
- Downloadable remote-only catalog entries appear only in the local model table.
- Installed custom local model folders appear in the model dropdown even if not present in the official catalog.
- Vosk is selectable in Settings even when no model is installed, but recording/transcription remains unavailable until a model is installed and selected.

## Evidence / Research Notes

- Vosk Java binding is available on Maven Central as `com.alphacephei:vosk`.
- The `0.3.38` Vosk artifact contains native resources for `linux-x86-64`, `darwin`, and `win32-x86-64`; the bundled macOS `darwin/libvosk.dylib` is a universal x86_64/arm64 binary. `0.3.45` was tested but its Java binding expects `vosk_recognizer_set_grm`, which is absent from the bundled macOS native library, causing runtime probing to fail on macOS. Treat unsupported platform/architecture combinations as runtime-unavailable with a clear message.
- Vosk's Java binding has a Windows-specific static initializer that unpacks companion DLL resources (`libwinpthread-1.dll`, `libgcc_s_seh-1.dll`, `libstdc++-6.dll`, plus its empty marker) before registering `libvosk`. Packaging validation must preserve the whole `win32-x86-64` resource directory, not only `libvosk.dll`.
- Chat4J already has a direct JNA dependency. Adding Vosk must not downgrade or duplicate the project's selected JNA version.
- Maven is not a complete model distribution channel. `com.alphacephei:vosk-model-en` exists, but it is an Android AAR and not useful as an all-model desktop catalog.
- Alpha Cephei Hugging Face has some models, but not the full classic Vosk ZIP model catalog.
- The official JSON index includes fields such as `name`, `lang`, `lang_text`, `type`, `url`, `md5`, `size`, `size_text`, `obsolete`, and `version`. Parse defensively because fields such as `obsolete` may be represented as strings rather than booleans.
- The official JSON index does not include full license text. Since licenses vary by model, the UI/docs must link to the official Vosk model page and avoid claiming a license unless license metadata is explicitly known.
- Vosk Java demo flow matches Chat4J's batch WAV architecture: create `Model`, create `Recognizer`, feed audio chunks, read final JSON text.
- Vosk recognizer expects PCM 16-bit mono input and a matching sample rate. Chat4J's microphone capture already writes 16 kHz, 16-bit, mono, little-endian WAV, but the provider should still read/convert defensively from the WAV format.
- Official Vosk model layouts differ. For example, `vosk-model-small-en-us-0.15` contains `am/final.mdl`, `conf/model.conf`, `conf/mfcc.conf`, `graph/HCLr.fst`, `graph/Gr.fst`, and `graph/phones/word_boundary.int`, but does not contain `graph/phones.txt` or `graph/words.txt`. Structural validation must not reject valid official models by requiring files absent from known-good layouts.

## Current Architecture Constraints to Address

The current STT foundation has several assumptions that must be refactored deliberately rather than worked around:

1. `SpeechToTextProvider.available(CredentialSource)` only knows credentials. Vosk availability depends on runtime loadability plus selected model installation/validation. Add a provider availability/settings context or a provider-specific resolver that includes selected model and model directory.
2. `SpeechToTextSettings.resolve()` currently resolves Groq endpoints for all non-Groq providers and stores non-null HTTP URIs in `SpeechToTextProviderContext`. `SpeechToTextProviderContext` itself declares `@NonNull URI baseUri` and `@NonNull URI transcriptionUri`, so simply passing `null` for Vosk will fail at construction time. Refactor endpoint/context handling so cloud providers receive required HTTP endpoints and local providers receive either an explicitly endpoint-free local context or optional endpoint fields with no Lombok null checks.
3. `SpeechToTextProvider.defaultModel()` and `SpeechToTextSettings.selectedModel(...)` assume every enabled provider has a usable fallback model; `selectedModel(...)` immediately dereferences `fallback.id()`, `fallback.label()`, and `fallback.description()`. Vosk should support “no installed model selected” without inventing a fake dropdown entry. Refactor the provider/settings contract so local providers can return no effective model until one is installed, while Groq keeps its bundled default, and ensure settings resolution never dereferences a missing fallback model.
3a. `SpeechToTextSettings.saveModel(...)` only writes provider model id/label keys, and `SpeechToTextCatalogItem` rejects blank IDs. Clearing a stale/deleted Vosk selection therefore needs an explicit settings API that removes `SettingsKeys.sttModelIdKey(providerId)` and `SettingsKeys.sttModelLabelKey(providerId)` or records stale state separately; do not represent “no model” as a blank or placeholder `SpeechToTextCatalogItem`.
3b. Persisting only Vosk model id/label is especially unsafe for custom models because generated IDs such as `local:<folder-slug>` are scoped to the current managed root and can refer to a different folder after the STT model directory changes. Persist enough selected-model identity for Vosk, such as managed-root identity plus model fingerprint/real path summary, or clear custom selections when the root changes; never silently treat a same custom ID in a different root as the previous selected model. Official catalog model IDs may remain portable by name only after validation in the new root.
4. `SpeechToTextService.prepareAndStartRecording(...)` currently assumes `settingsSnapshot.model().id()` is always available for enabled providers. Update service guards before recording so missing model produces a clean unavailable message instead of a null dereference.
5. `SpeechToTextCatalogStore.mergeWithSelected(...)` intentionally adds the selected item even if it is not in the current catalog. That is correct for cloud catalog resilience, but wrong for Vosk's installed-model dropdown. Do not use this path for Vosk dropdown rows unless every row is revalidated as installed.
6. `SpeechToTextPanel.refreshCatalogs(...)` and download button enablement are currently gated by `snapshot.available()`. For Vosk, catalog refresh and model download must work while transcription is unavailable because no model is installed yet. Split “provider selectable/catalog-manageable” from “ready to transcribe.”
6a. `SpeechToTextPanel.refreshCatalogs(...)` currently constructs a `GroqSttEndpointResolver.Endpoint` from `snapshot.baseUri().toString()` before calling `snapshot.provider().fetchModels(context)`. Vosk catalog refresh must not go through this Groq-only endpoint resolver or a generic HTTP provider context. Make catalog refresh provider-aware: Groq can keep the current HTTP model fetch path, while Vosk should call the Vosk catalog/model-management service that loads official JSON/cache/fallback metadata without requiring provider readiness or HTTP transcription endpoints.
6b. The `SpeechToTextProvider` interface currently requires every provider to implement `fetchModels(SpeechToTextProviderContext)`, but that method is cloud/HTTP-shaped and receives the same context that is problematic for local providers. Do not overload `fetchModels(...)` to mean Vosk official-catalog/model-management refresh. Either make remote model fetching an optional cloud-provider capability/default method, split it into a cloud-specific interface, or let Vosk implement an explicit unsupported/no-op method while Settings uses the Vosk model-management service directly.
6c. Vosk catalog JSON fetches are network responses too, even though they are much smaller than model ZIPs. Do not read the online `model-list.json` with an unbounded `readAllBytes()` or equivalent. Use the fixed official catalog URL, reasonable connect/read timeouts, successful-status validation, conservative redirect handling, and a documented maximum response size before parsing JSON; on oversized, invalid, or failed responses, fall back to cache/bundled metadata without blocking Settings.
7. The current local model table stores only `SpeechToTextCatalogItem` rows and two columns. Vosk needs richer row state: official metadata, installed path, custom flag, downloadability, deleteability, selected state, validation status, and progress.
8. The current generic `SpeechToTextModelDownloader` only receives provider/model/directory. Vosk downloads need URL, MD5, expected size, type, language, cancellation, and progress. Add provider-aware local model management APIs instead of overloading the generic descriptor.
8a. The existing `JavaNetSttHttpTransport` buffers provider responses into memory via `boundedBody(...)`; it must not be reused for Vosk model ZIP downloads. Vosk downloads can be hundreds of MiB or larger and must stream directly to the temp ZIP file with bounded buffers, progress accounting, cancellation, and size enforcement.
9. `SpeechToTextPanel` currently receives only the STT models directory. `SettingsDialog` also stores only `sttModelsDirectory`, `MainFrame.openSettings()` passes only that path, and some `SettingsDialog` constructors fall back to a hardcoded `~/.config/chat4j/stt/models` path instead of `StoragePaths`. Vosk downloads also need the platform-correct STT temp directory for ZIP downloads and partial cleanup. Wire `StoragePaths`, both STT paths, or a shared model-management service into Settings instead of inventing temp paths in the panel; keep runtime and Settings on the same resolved model/temp roots.
10. `MicrophoneAudioCapture.cleanupStaleTempFiles()` only cleans STT-owned WAV recordings. Vosk downloads/imports need their own stale cleanup for temp ZIPs and `.partial` install directories so crashes/cancels do not leave unbounded disk usage.
11. Current provider/UI availability is used both for provider combo labeling and recording readiness. Vosk requires those states to be separate so “Vosk” is not labeled unavailable merely because no model is installed.
12. `SpeechToTextService` snapshots provider ID, model ID, and endpoint URIs before recording, but not local model directory/path. `SpeechToTextRequest` also carries only a model ID, not a resolved local model reference. Local transcription should snapshot enough model-resolution state to detect model directory changes, selected model path changes, or deletion between recording start and transcription, and should pass the validated local model reference/path/fingerprint to the local provider instead of forcing it to re-resolve an ambiguous model ID.
12a. `SpeechToTextService.matchesSnapshot(...)` currently dereferences `current.model().id()` after recording. Once Vosk can resolve to no effective model, model deletion/clearing between capture start and transcription must produce the planned clear “model changed/missing; recording was not transcribed” message, not a null pointer or generic Java error. Make snapshot comparison null-safe for provider/model/context fields before enabling local no-model states.
13. `SettingsDialog.dispose()` relies on `PendingSettingsSaveParticipant.savePendingChanges()` and runs from Swing close actions. Long-running Vosk downloads/imports must participate in this close barrier or have a clear cancel/block policy so Settings cannot close while leaving ambiguous UI state or corrupt installs. Do not make multi-minute downloads/imports freeze the EDT via a plain `Future.get()` from `savePendingChanges()`; return a clear “operation in progress” failure, show a cancel/keep-open path, or use a progress-aware close flow.
14. `ChatPanel.startSpeechToTextRecording()` currently blocks STT when the chat provider is missing/resolving, while `InputBar` mic visibility is based on STT availability, normal compose mode, and conversation-busy state, not chat-provider readiness. Reconcile this mismatch by making Speech-to-Text intentionally independent of chat-provider readiness: users should be able to dictate into the composer whenever the composer is otherwise editable and STT is ready, even if the chat model/provider is missing or still resolving. Keep blocking STT during editing modes, conversation loading, visible conversation streaming/busy states, and active STT, but do not gate mic visibility or start validation on `currentProvider`/`currentProviderResolving`.
14a. `InputBar` currently has a single `conversationBusy` flag that controls both send availability and mic visibility, and `ChatPanel.refreshComposerAvailability()` sets that flag to `conversationLoading || isVisibleConversationBusy() || currentProviderResolving`. That means provider resolution currently hides the mic indirectly even if `startSpeechToTextRecording()` stops checking `currentProviderResolving`. Refactor the composer state so chat-provider readiness/resolution gates send controls only, while mic visibility/start validation use an STT-specific busy rule based on normal compose mode, conversation loading, visible conversation busy/streaming, and active STT. Do not solve this by setting `conversationBusy` false globally during provider resolution, because send must still stay disabled until the chat provider is ready.
15. `SpeechToTextService` contains cloud-specific wording such as “Recording is too large to upload” and “settings changed; recording was not uploaded.” Local Vosk should use provider-neutral wording such as “transcribe” or provider-specific messages where appropriate.
16. The runtime `SpeechToTextService` and `SpeechToTextPanel` currently create their own default provider registries/settings instances. Vosk model-management state must not depend on per-provider-instance memory that can diverge between Settings and the runtime service. Share model state through filesystem/cache/settings, or introduce an explicitly shared model-management factory/service wired into both places.
17. The current local model table maps `JTable.getSelectedRow()` directly into `localModelItems`. That is only safe without sorting/filtering. Since Vosk requires table sorting/search/filtering, row actions must convert view rows to model rows or use stable row IDs; otherwise download/delete/select can target the wrong model.
18. `ChatPanel.reloadSpeechToTextSettings()`, `SpeechToTextService.available()`, `SpeechToTextSettings.resolve()`, `SpeechToTextPanel.reloadProviderOptions()`, and `SpeechToTextPanel.refreshControlsFromSettings(...)` are all used from Swing UI flows. Vosk must not hide model-directory scans, catalog parsing, disk checks, or native runtime probes inside these synchronous availability/resolve paths. Keep these calls cheap and EDT-safe by using cached model-management snapshots/status, or by making UI refreshes explicitly asynchronous.
19. Once availability uses cached snapshots for EDT safety, cached readiness can be stale. `SpeechToTextService.prepareAndStartRecording(...)` already runs on the STT executor before microphone capture; local providers should use that background phase for authoritative selected-model/runtime validation before opening the microphone. Do not start recording based only on stale cached Vosk readiness.
20. Shared Vosk model-management snapshots will be read by Swing UI code, Settings background workers, and the runtime STT executor. Publish immutable snapshots atomically, make status/cache updates thread-safe, and serialize mutating operations such as download, import, delete, cleanup, and rescan so the UI/runtime never observes half-updated rows or paths.
20a. Runtime readiness cannot depend on opening Settings. `MainFrame` creates `SpeechToTextService` during normal chat startup, and `ChatPanel.reloadSpeechToTextSettings()` only reads `speechToTextService.available()` synchronously. If a user manually placed a valid Vosk model under `<sttModelsDir>/vosk` before launch, the runtime model-management service must initialize/scan asynchronously at startup (or service construction) and notify/reload the chat UI when the snapshot changes; otherwise the mic can stay hidden or unavailable until Settings is opened. Distinguish this from blocking startup/native loading: constructors and EDT startup paths must not synchronously load Vosk, but a deliberately scheduled background post-startup probe may load Vosk to promote cached “unknown” runtime status to ready/unavailable and then notify the composer on the EDT.
20b. Background Vosk snapshot/probe notifications must not blindly call a user-facing reload path that rejects active STT. `ChatPanel.reloadSpeechToTextSettings()` currently shows “Finish or cancel transcription before reloading Speech to Text settings.” when `speechToTextService.active()` is true. Model-management notifications that arrive during recording/transcribing/preparing should either defer composer availability refresh until STT becomes inactive or use a silent/internal refresh path that updates cached availability without showing validation noise or resetting the active composer state.
21. `ChatPanel.startSpeechToTextRecording()` currently calls `inputBar.showRecordingState()` immediately before the service finishes capture preparation. If Vosk pre-capture validation becomes noticeably slower than current microphone setup, avoid making users think audio is already being recorded. Keep pre-capture validation lightweight, or add a distinct “Preparing speech model…” state/status before waveform recording begins.
21a. The current recording control has a subtle pre-capture no-op: while `SpeechToTextService.active()` is true but `activeCapture == null` and `recording == false`, `stopRecordingAndTranscribe()` returns without doing anything. If Vosk adds an explicit preparation phase, the UI must not show the normal “Stop recording and transcribe” control before a capture session exists. During preparation, expose cancel-only semantics or a disabled stop/transcribe action, and ensure cancellation invalidates the pending preparation before microphone capture starts.
21b. New Vosk UI controls and states must preserve the accessibility/tool-tip pattern already used by the composer recording controls. Any preparing/cancel control, progress indicator, download/delete/import buttons, table action/status cells, and warning labels should have clear text, tool tips where useful, and accessible names/descriptions so the feature is not icon-only or status-color-only.
21c. Avoid a dead-end state for plausible-but-unverified selected models. If `SpeechToTextService.available()` remains false, the current composer hides the mic, so a “visible preparing validation before capture” cannot be reached from the composer unless the UI has a separate startable/preparing eligibility state. Either automatically schedule off-EDT validation/probing when a plausible model is selected/imported/discovered, or expose an explicit Validate/Prepare action in Settings/composer that can promote or reject the selection. Do not leave the user with a selected plausible model, hidden mic, and no visible way to complete validation.
22. `SpeechToTextService.transcribe(...)` catches `Exception`, but Vosk/JNA native loading and calls can throw `LinkageError`/`UnsatisfiedLinkError`, especially if packaging/native resources are wrong or runtime probing was stale. The Vosk provider/runtime adapter must catch native linkage/load errors at its boundary and convert them to `SpeechToTextException` with a user-friendly message so the existing UI error callback path is used. Do not rely only on Settings/runtime probing to catch these errors.
22a. Keeping Vosk logging quiet can itself trigger native loading if implemented by calling `LibVosk.setLogLevel(...)`. Do not call Vosk log-level APIs during application startup, synchronous availability resolution, or Settings UI population. Configure Vosk log level only inside the off-EDT runtime adapter/probe/transcription path where native loading is already intentional, and wrap that call like every other native call.
23. Vosk stale cleanup cannot safely identify partial installs by a generic `.partial` suffix alone. Official/custom model folder names are user-visible filesystem entries, and users could create similarly named folders. Every Chat4J-created Vosk temp ZIP/partial install directory must use an operation-scoped Chat4J prefix and/or contain a Chat4J ownership marker, and cleanup must require that marker/prefix before deleting anything.
24. Download/install paths must not trust catalog `name` values as filesystem-safe just because the catalog came from the official JSON/cache/fallback. Validate model names before using them as final directory names, reject path separators, absolute paths, `.`/`..`, control characters, empty names, and names that normalize outside the Vosk root, and prefer an allowlist matching known Vosk model-id characters. The final resolved path must stay under `<sttModelsDir>/vosk` after normalization/real-path checks.
25. Official Vosk ZIPs commonly contain a top-level folder named after the model. If extracted directly into `<model-name>.partial` and then that partial directory is moved to `<model-name>`, the final layout can accidentally become `<model-name>/<model-name>/am/...`, which the scanner/provider will not treat as the model root. The installer must normalize ZIP layout so the completed final directory has model files directly under `<sttModelsDir>/vosk/<model-name>` (`am/`, `conf/`, `graph/`, etc.), not one level deeper.
26. `SpeechToTextPanel.removeNotify()` currently only increments `refreshCounter`. Vosk model management will add long-running scans/downloads/imports and snapshot/progress listeners. Settings UI listeners must be explicitly registered/unregistered with the panel lifecycle, and panel-scoped callbacks must be cancelled or ignored after dialog disposal so background model-management work does not update disposed Swing components or leak whole Settings dialogs.
27. A shared Vosk model-management service introduces background scans, downloads, imports, cleanup, and runtime probes outside the current short-lived `SpeechToTextPanel` virtual-thread pattern. Define a clear owner/lifecycle for that service, such as application services or the main frame/runtime STT wiring, and dispose/cancel it during app/window shutdown so background work does not leak threads, hold stale `SettingsRepository`/path state, or notify disposed UI/runtime objects.

## Architecture

### Provider

Add `VoskSpeechToTextProvider` under `src/main/java/com/github/drafael/chat4j/stt/provider/vosk/`.

Provider properties:

- `id()`: `vosk`
- `displayName()`: `Vosk`
- `requiredEnvVar()`: blank
- `supportsLocalModels()`: `true`
- effective/default model: none until at least one installed valid model exists. Do not expose a remote-only catalog entry as `defaultModel()` for Vosk.

Register Vosk in `SpeechToTextProviderRegistry.createDefault()` alongside Groq.

Provider availability must distinguish two concepts:

- **Selectable/manageable**: Vosk is registered as a provider and Settings can show/manage its models. This should not require loading the native Vosk library, because model catalog/download management is useful even on a platform where transcription is currently unavailable.
- **Ready to transcribe**: Vosk native runtime loads successfully and the selected model resolves to an installed, valid model directory.

The provider status message should explain the exact unavailable reason:

- runtime/native library unavailable on this platform/package;
- no installed Vosk model;
- selected model missing;
- selected model invalid;
- selected model directory not readable.

Runtime probing must be lazy and safe: do not load `org.vosk.LibVosk` on the EDT, in constructors, or in synchronous startup/settings code just to populate UI state. This includes avoiding seemingly harmless static calls such as Vosk log-level configuration until an off-EDT runtime probe or transcription is intentionally loading the native library. A background post-startup probe is acceptable when needed to make manually installed models usable without opening Settings, but it must be cancellable/serialized with model-management operations, publish only immutable status, and notify the composer on the EDT. Catch `LinkageError`/`UnsatisfiedLinkError` as well as ordinary exceptions, and cache only a safe runtime status summary, not a half-initialized native object.

Provider/settings availability resolution must stay cheap enough for synchronous UI calls. `available()`/`resolve()` may read persisted settings and a cached installed-model/runtime status snapshot, but must not perform fresh filesystem scans, catalog parsing, network calls, disk-space checks, or native runtime loading on the caller thread. If status is stale, show the last known safe status and trigger an asynchronous refresh/rescan that updates the UI when complete. Snapshot updates from runtime startup scans, Settings rescans, downloads, imports, deletes, and cleanup must have a notification path that causes `ChatPanel.reloadSpeechToTextSettings()` or equivalent composer availability refresh to run on the EDT.

Refactor `SpeechToTextProviderContext` or split it into cloud/local context types before implementing Vosk. The current record's non-null HTTP URI fields are valid for Groq but wrong for local providers. Likewise, refactor `SpeechToTextSettings.selectedModel(...)` before allowing a provider whose effective model can be absent; do not use dummy endpoint URIs or dummy model catalog items merely to satisfy old non-null assumptions. Also refactor the provider catalog-fetch contract so local providers are not forced into a cloud `fetchModels(SpeechToTextProviderContext)` workflow for non-transcription model management.

### Transcription

Use the existing STT flow:

1. `MicrophoneAudioCapture` records WAV.
2. `SpeechToTextService` invokes selected provider with `SpeechToTextRequest` only after settings resolution confirms a selected installed model is available.
3. For local providers, `SpeechToTextService.prepareAndStartRecording(...)` must perform or request a fresh off-EDT selected-model/runtime validation on the STT executor before opening the microphone. This authoritative pre-capture validation should update the session snapshot with the validated model path/fingerprint and fail fast with a setup message if cached readiness was stale. Keep this validation lightweight: verify selected-model metadata, paths, structural validation/fingerprint, and cached or lightweight native runtime status. Do not construct a full `org.vosk.Model` before recording unless the UI explicitly shows a preparing state and the extra model load is intentionally accepted; otherwise the first implementation could double-load a large model before and after capture. If a preparing state is shown, wire it as a first-class STT state distinct from recording/transcribing, with cancel-only behavior until `activeCapture` is assigned; do not route its primary control through the existing stop-and-transcribe path.
4. Extend the provider request/context path for local providers so the Vosk provider receives the validated local model reference, such as normalized real path plus fingerprint, captured during pre-capture validation. Do not make the provider re-resolve only `modelId` from global settings at transcription time.
5. Perform selected-model directory resolution in the shared model-management/settings-preparation layer using the same installed-model metadata used by settings resolution, then pass that concrete reference to the provider. The provider may sanity-check that the supplied local reference is present and still matches the request, but it must not independently map `modelId` through `SpeechToTextModelDirectory.directoryFor(...)` or global settings at transcription time; custom IDs may not map 1:1 to directory names.
6. Vosk provider opens WAV through `AudioSystem.getAudioInputStream(...)`.
7. Convert to PCM signed, 16-bit, mono, little-endian if necessary. Use the stream's actual sample rate after conversion when creating the recognizer. For Chat4J-captured audio this should normally be 16 kHz. Read and feed audio in frame-aligned chunks for the converted format; for 16-bit mono byte input, never pass an odd byte count to `Recognizer.acceptWaveForm(byte[], len)`. If a stream returns a partial frame, carry it into the next read or reject the malformed audio with a user-friendly `SpeechToTextException`.
8. Create `org.vosk.Model` and `org.vosk.Recognizer`.
9. Wrap Vosk model construction, recognizer construction, native calls, and log-level calls so `LinkageError`/`UnsatisfiedLinkError` become `SpeechToTextException` rather than escaping past `SpeechToTextService`'s `Exception` catch block.
10. Feed chunks to `Recognizer.acceptWaveForm(...)`. The Java binding exposes this as `boolean`, so do not rely on it to distinguish every native error condition; treat exceptions/errors from the call plus malformed `getResult()`/`getFinalResult()` JSON as transcription failures with user-friendly messages.
11. Whenever `acceptWaveForm(...)` returns true, parse `Recognizer.getResult()` JSON and append its `text` segment to an ordered transcript buffer. Do not ignore these intermediate finalized segments; for longer recordings, relying only on `getFinalResult()` can drop earlier utterances.
12. After EOF, parse `Recognizer.getFinalResult()` JSON and append its final `text` segment.
13. Check cancellation before expensive setup, after model/audio setup, and between chunks via the provider context cancellation token. Cancellation may not interrupt a native `Model` constructor already in progress, but the provider should avoid starting new expensive work once cancellation is requested and must close any resources it did open.
14. Do not apply the current 60-second cloud HTTP request timeout to local Vosk transcription. Local transcription time depends on recording length and model size; cancellation should be explicit/user-driven, with any local timeout policy designed separately from HTTP transport timeouts.
15. Normalize whitespace across collected Vosk result segments.
16. If the combined transcript is blank, throw a user-friendly `SpeechToTextException` rather than returning an empty successful result.
17. Close native/audio resources deterministically on success, failure, and cancellation. Close `Recognizer` before the `Model` it references, then close audio streams; if using try-with-resources, declare resources in an order that produces that close order or close them explicitly. Do not let cleanup exceptions mask the primary transcription failure.

Do not cache `Model` objects in the first implementation.

Before provider transcription, extend the existing STT session snapshot for local providers to include selected model directory identity, such as normalized real path plus validation fingerprint/last-modified summary. If the selected model changes, disappears, clears to no effective model, or no longer validates after recording, abort with a clear “Speech-to-text model changed; recording was not transcribed.” style message. Keep the snapshot comparison null-safe so missing `current.model()` or missing local context never leaks as a `NullPointerException`.

Review service-level recording size limits and messages. The existing 24 MiB WAV cap is compatible with the current 600-second 16 kHz mono capture limit, but any rejection/error text should be provider-neutral for local STT. Do not describe local Vosk transcription as an upload.

## Model Catalog and Install Data

Create Vosk-specific model classes rather than over-generalizing for future Sphinx/whisper integrations:

- `VoskModelCatalogEntry`
- `VoskModelCatalogClient`
- `VoskModelCatalogCache`
- `VoskBundledCatalogLoader`
- `VoskInstalledModelScanner`
- `VoskModelInstaller`
- `VoskModelValidator`
- `VoskLocalModelRow` or equivalent UI/domain row model
- `VoskRuntime` / `VoskTranscriber` adapter for testability
- provider-aware local model management service used by settings resolution, Settings UI, and runtime provider resolution

Remote catalog entry fields:

- name/model ID
- language code
- language display name
- type
- URL
- MD5
- byte size parsed and stored as `long`/64-bit values, never `int`
- size label
- version
- obsolete flag
- source page URL for license/details
- optional known license if provided by a bundled supplemental map; otherwise show “See Vosk model page”

Installed model fields:

- model ID
- label
- local directory
- normalized real directory when available
- official metadata if matched
- custom/local flag
- obsolete flag/status if official metadata marks an installed model obsolete
- validation status and validation message
- deleteable flag
- lightweight validation fingerprint suitable for stale-change detection

Define the Vosk managed model root as `<sttModelsDir>/vosk`. Scan only immediate child directories of that root for installed models unless an explicit import action places/copies a model there. Avoid broad recursive scans of the whole STT models directory because they are slow, ambiguous, and risky for delete operations.

IDs:

- official installed models: official Vosk `name`, e.g. `vosk-model-small-en-us-0.15`
- custom installed models: stable generated IDs such as `local:<folder-slug>`

Custom model resolution must store or recover the actual directory path from scanner metadata. Do not assume `local:<folder-slug>` can always be passed through `SpeechToTextModelDirectory.directoryFor(...)` to recover the correct path.

Custom model IDs and copied-folder destinations must be collision-safe. If two imported/manual folders slug to the same value, or an imported custom folder conflicts with an official model directory, generate a stable disambiguated ID/destination or require explicit user action. Never silently overwrite an existing model folder or allow two dropdown rows to share the same effective ID. Treat custom IDs as scoped to the current managed Vosk root unless the persisted selection also matches a saved fingerprint/real-path identity; after a model-directory change, validate a persisted custom selection against that identity or clear it as stale instead of matching by `local:<folder-slug>` alone.

User-managed/custom models should be supported in one of two explicit ways:

1. user manually places an unzipped Vosk model directory under `<sttModelsDir>/vosk`; or
2. Settings offers an “Add existing model folder…” action that validates a chosen Vosk model folder and copies it into `<sttModelsDir>/vosk`.

For the first implementation, import should copy rather than move so Chat4J never destructively relocates a user's external model folder. Reject imports where the source is already the destination, where the destination would be nested inside the source, or where real-path checks show the copy would recurse into itself. If the chosen source is already inside the current managed Vosk root, do not copy it into a duplicate destination; rescan/select the existing managed model or reject with a clear “already managed” message. Folder import must also handle symlinks, hardlinks, and special files safely: do not follow symlinks out of the source tree, do not create managed models containing links to arbitrary external files, and either reject such entries with a clear message or copy only regular files/directories after real-path validation. If the user changes the configured STT model directory while an import/download is active, cancel or block the directory change before files are copied/moved, then rescan the new effective Vosk root.

Do not persist arbitrary external model-folder references in the first implementation unless a separate path-safety/deleteability design is added.

Keep Vosk model-management state source-of-truth outside individual provider instances. Settings may create a fresh provider registry while the chat runtime already has another registry; both must see the same installed models, selected model, catalog cache, and partial-install cleanup state through shared storage or shared services. Shared services should expose immutable model/status snapshots and replace them atomically after scans or catalog refreshes; do not mutate row lists in place while Swing components or runtime validation may be reading them. Listener APIs must return unsubscribe handles or otherwise support lifecycle cleanup so Settings panels can detach from model-management progress/snapshot events on `removeNotify()`/dialog disposal while the shared runtime service continues safely. The shared service itself must also have an explicit application/window lifecycle: own any executor/virtual-thread coordination in one place, support cancellation/disposal of queued and running operations where possible, reject new UI-initiated work after disposal, and suppress callbacks after its owning runtime has shut down.

## Catalog Cache and Fallback

Implement Vosk's official catalog cache separately from the generic cloud-model `SpeechToTextCatalogStore` unless the generic store is extended to safely preserve rich metadata.

The cache must preserve at least URL, MD5, size, type, language, version, and obsolete state. A list of `SpeechToTextCatalogItem` is not enough for install safety.

Acceptable cache location options:

- settings repository key containing the raw official JSON plus fetched timestamp; or
- an app-config file under a new STT catalog/cache location.

Choose one deliberately and test corruption/fallback behavior. If using settings, keep the raw JSON size in mind and recover gracefully from malformed properties.

Catalog resolution order:

1. online JSON when explicit refresh succeeds;
2. cached JSON when online refresh fails or first load has recent cache;
3. bundled fallback resource when no valid cache exists.

Initial Settings display should be non-blocking: show cached/bundled catalog data immediately, then refresh online only from the explicit Refresh action or a clearly intentional background stale-refresh path that never blocks opening Settings. Catalog parsing, installed-model scanning, runtime probing, downloads, imports, extraction, deletion, and disk-space checks must all run off the EDT and publish results back on the EDT. Opening Settings should use a cached model-management snapshot first, then refresh/rescan asynchronously if needed. The same model-management service must also perform startup/runtime initialization independent of Settings so manually installed models are discovered without requiring the user to open Settings. Settings-panel subscriptions to shared model-management updates must be removed when the panel/dialog is disposed, and queued EDT updates must check a disposal/generation token before touching Swing components. Vosk catalog refresh must be implemented through the Vosk catalog/model-management service rather than the current Groq `fetchModels`/endpoint path, because Vosk catalog management is available even when transcription is not ready.

The bundled fallback resource should contain speech-recognition metadata entries, including obsolete speech-recognition entries when needed for installed-model recognition/status, or the official JSON plus deterministic filtering at runtime. Runtime row construction must still exclude obsolete remote-only entries from default download choices. The fallback must not contain model ZIPs.

## Settings UI Behavior

When provider is Vosk:

### Model dropdown

Show only installed models that are eligible for local use:

- installed official Vosk models;
- installed custom Vosk folders detected locally.

Eligibility means the folder passed required structural/safety validation, or it is marked as “plausible but unverified” because the layout is not known-bad but cannot be proven without Vosk model construction. Hard-invalid, missing, remote-only, and unsafe-path entries must not be selectable. A plausible-but-unverified row may be selected only with a visible warning/status, and it must not make `SpeechToTextService.available()` or the composer mic ready until a background validation/probe has promoted it to ready or the user enters a visible preparing flow that validates before capture. Because the current composer hides the mic when STT is unavailable, selecting a plausible-but-unverified model must also trigger an automatic off-EDT probe or expose an explicit Validate/Prepare action; otherwise the user has no path to reach the visible preparing flow. If a composer-side prepare action is added, model it separately from the normal ready mic state and make the tooltip/status explain that the model must be validated before recording starts. Do not show remote-only official catalog models in the dropdown. If an installed official model is eligible but obsolete according to catalog metadata, keep it in the dropdown/table with an obsolete warning/status; do not hide it solely because it is obsolete.

If no installed model exists:

- dropdown disabled or displays a non-selectable “No installed Vosk models” placeholder;
- Vosk provider remains selectable;
- transcription unavailable with clear status: “Download or add a Vosk model to enable transcription.”

If the previously selected model is no longer installed or becomes invalid:

- do not keep it in the dropdown just because it is persisted;
- show a status explaining that the saved model is missing/invalid;
- clear the effective selected model or leave the persisted ID only as stale state, but do not allow recording until the user selects an installed model;
- when the user explicitly clears the stale selection, deletes the selected model, or changes to a model directory where that selection is no longer meaningful, remove the persisted Vosk model id/label keys or store explicit stale metadata separately instead of saving a placeholder item.

The no-model placeholder must not be represented as a normal saveable `SpeechToTextCatalogItem` row. `SpeechToTextCatalogItem` validates that IDs are non-blank, and the current Settings panel saves any selected catalog item from the model combo box; refactor this path so placeholders/stale notices cannot be persisted as a model ID and cannot satisfy recording readiness.

### Local model table

Make the path presentation unambiguous. The existing setting is the base STT model directory, while Vosk's managed root is `<sttModelsDir>/vosk`; labels/help text should distinguish those paths and show the effective Vosk root for downloads/imports/deletes so users do not copy models into the wrong level.

Show:

- official active speech-recognition catalog models from bundled/cache/online JSON;
- installed obsolete official speech-recognition models with an obsolete/not-recommended status, while keeping obsolete remote-only entries out of default download choices;
- installed custom models not found in the official catalog.

Columns:

- Model
- Language
- Type
- Size
- Installed
- Selected
- Status/Action detail

Actions:

- Download selected model: enabled for official catalog rows that are not installed and whose URL/MD5/size metadata is valid.
- Delete selected model: enabled for installed models managed under Chat4J's Vosk model directory.
- Delete must be disabled for any model path that is not safely under the managed Vosk directory after real-path normalization.
- Selecting/checking a row only changes selected model if that row is installed and eligible under the dropdown validation rules; hard-invalid, unsafe, missing, and remote-only rows must not be selectable.
- Remote-only rows should not become selected until after successful install and validation.
- After successful download:
  - rescan installed models;
  - installed model appears in dropdown;
  - if no usable model was previously selected, auto-select the newly downloaded model only after it passes install validation;
  - provider becomes ready to transcribe only if runtime status is ready and the installed model satisfies readiness rules.
- After deleting the selected model:
  - clear the persisted selected-model id/label keys or auto-select another installed model only if the behavior is explicit and tested;
  - refresh dropdown/table/status immediately.

### Refresh, download, and progress

- Refresh catalog must be available when Vosk is selected even if Vosk is not ready to transcribe.
- Download must be available when Vosk is selected even if Vosk is not ready to transcribe.
- Offer an “Add existing model folder…” flow if user-managed models are not discovered by simply placing them under `<sttModelsDir>/vosk`.
- Keep one-at-a-time mutating model operations for the first pass, covering download, import, delete, stale cleanup, and explicit rescan, but provide real progress for large operations:
  - bytes downloaded / total size when available;
  - current phase: downloading, verifying, extracting, installing;
  - disable conflicting provider/model-directory/delete/rescan controls during install/import/delete/cleanup;
  - provide a cancel button or cancellation path for in-progress downloads;
  - integrate active download/import futures with `PendingSettingsSaveParticipant` so Settings close either uses a progress-aware wait, cancels safely, or reports why it cannot close yet without freezing the EDT.
- For very large models, show size before download and require confirmation above a reasonable threshold (for example >500 MiB).
- The Vosk catalog can contain dozens of speech models, including many very large entries. Add table sorting/filtering at least by language, type, installed status, and text search so selecting a model does not become a long unstructured list.
- After sorting/filtering is added, every table action must resolve the selected view row back to the underlying `VoskLocalModelRow` by model index or stable row ID before selecting, downloading, deleting, or showing status.
- Check available disk space before starting when expected ZIP size is present. Because the STT temp directory and configured model directory may be on different filesystems, check free space for both the temp ZIP location and the final/partial install location. After download, inspect ZIP entry uncompressed sizes before extraction and re-check disk space for the partial install while accounting for the ZIP and extracted files that may coexist until cleanup. Use 64-bit byte counters for catalog size, content length, progress, ZIP entry sizes, and total uncompressed bytes, and fail safely on arithmetic overflow.
- Apply the same progress/cancel/disk-space treatment to “Add existing model folder…” imports/copies; large local model imports can be as expensive as downloads.
- Clean stale Vosk-owned temp ZIPs and partial install directories on startup or model-management initialization using conservative age plus Chat4J ownership marker/prefix rules. This cleanup must not touch completed model directories, user-created `.partial` folders, or arbitrary user files.

## Download and Install Safety

Downloader must:

1. Accept only official catalog entries from the loaded Vosk JSON/fallback/cache.
2. Require HTTPS URLs whose host is exactly `alphacephei.com` and whose path is under `/vosk/models/` and ends with `.zip` for the first implementation. If HTTP redirects are followed, revalidate every redirect target/final URI with the same scheme/host/path rules and reject cross-host, non-HTTPS, or non-model ZIP redirects.
2a. Validate the catalog model `name` before deriving any local path: it must be non-blank, relative, contain no path separators/control characters, not be `.` or `..`, match a conservative Vosk model-name allowlist such as `[A-Za-z0-9._-]+`, and resolve under the managed Vosk root after normalization. Reject entries whose URL basename and catalog name disagree unless a deliberately tested compatibility rule handles the mismatch.
3. Download to a temp ZIP path under Chat4J's STT temp area. This requires Settings/model-management wiring to receive `StoragePaths.sttTempDirectory()` or an equivalent temp-directory service. Stream HTTP response bytes directly to disk with a small fixed-size buffer; do not use the existing buffered STT HTTP transport or accumulate ZIP bytes in memory.
4. Enforce expected byte size when present.
5. Verify MD5 from the catalog. Treat MD5 as integrity checking from the official catalog, not as a modern security signature.
6. Extract to an operation-scoped Chat4J-owned staging/partial directory under `<sttModelsDir>/vosk`, such as `<model-name>.partial-<operation-id>` or `chat4j-vosk-install-<operation-id>.partial`, and create a small ownership marker file inside it before extraction. Do not rely on a bare `.partial` suffix as the only signal that a directory is safe to delete. Treat this directory as staging, not necessarily the exact directory that will be renamed into place.
7. Reject zip-slip paths, absolute paths, parent traversal, unsafe names, and symlink/hardlink surprises inside archives.
8. Defend against zip bombs and malformed archives: enforce a maximum entry count, reject unknown/negative entry sizes when a safe total cannot be established, cap total uncompressed bytes to a documented limit, use `long` totals with overflow checks, and re-check free disk space after reading the central directory.
9. Handle common ZIP layouts and normalize them before final install:
   - one top-level folder matching the model name: validate that folder as the model root and move/copy its contents so `am/`, `conf/`, and `graph/` end up directly under the final `<model-name>` directory, not under a nested `<model-name>/<model-name>` directory;
   - direct model files at archive root only if validation can normalize them safely into the final model directory.
10. Require a valid Vosk model structure after extraction and after layout normalization.
11. After validation succeeds, remove any staging ownership marker that should not remain in the completed model, then atomically publish the normalized model root to `<sttModelsDir>/vosk/<model-name>` using `ATOMIC_MOVE` when available; otherwise use a safe same-filesystem move fallback. The final directory must itself be the model root passed to `new Model(path)`, not a parent containing another model-named child directory.
12. If the final directory already exists:
    - if valid, treat as already installed or ask before replacing;
    - if invalid/partial, require explicit cleanup/retry behavior.
13. Clean temp ZIP and partial directories after success/failure/cancel where practical.
14. Use Vosk-specific temp filename/directory prefixes and ownership marker files so stale cleanup can identify only Chat4J-owned download/install artifacts.

Installed model validation should require enough Vosk structure to fail early without excluding valid official layouts:

- required:
  - `am/final.mdl`
  - `conf/model.conf`
  - `conf/mfcc.conf`
  - `graph/` directory
- require one supported graph layout:
  - `graph/HCLG.fst`; or
  - both `graph/HCLr.fst` and `graph/Gr.fst`
- treat files such as `graph/words.txt`, `graph/phones.txt`, `graph/phones/word_boundary.int`, and `ivector/*` as optional/diagnostic unless a tested model family specifically requires them.

Do not require `graph/words.txt` or `graph/phones.txt` globally; known official models such as `vosk-model-small-en-us-0.15` omit them while remaining valid. Distinguish validation states explicitly:

- structurally valid/known-compatible: selectable and can satisfy cached readiness if runtime status is also ready;
- plausible but unverified: selectable only with a visible warning/status, not auto-selected after import/download unless the user confirms, does not satisfy cached readiness/mic availability by itself, and must be promoted by an off-EDT validation/probe or validated in a visible preparing flow before recording opens;
- hard-invalid/unsafe/missing: not selectable and cannot satisfy readiness.

If structural validation is uncertain, mark the model as “structure looks plausible” and let off-EDT runtime/model construction or pre-capture validation produce the authoritative failure message, rather than hiding the model from the dropdown based on overly strict optional-file checks. Never let a plausible-but-unverified status bypass the pre-capture selected-model validation or make the normal mic button appear ready without a completed validation/probe. If validation requires constructing `org.vosk.Model`, do it only off the EDT, surface it as model validation/preparation rather than recording, close the model immediately after probing, and accept any later transcription load as intentional rather than accidental hidden double-loading.

Validator and scanner must not follow symlinks outside the managed model root when deciding deleteability or install validity.

## Error Handling

- If Vosk native runtime fails to load, mark provider not ready to transcribe with a clear message. Keep Settings model management usable when possible.
- If Vosk native linkage/loading fails during actual transcription despite prior probing, catch `LinkageError`/`UnsatisfiedLinkError` inside the Vosk provider/runtime adapter and convert it to `SpeechToTextException` so `SpeechToTextService` can report it through normal callbacks.
- If selected model is missing or invalid, mark provider not ready to transcribe with a clear message.
- If no model is installed, do not surface this as a provider selection failure; present it as the next required setup step.
- If remote catalog refresh fails, use cached catalog; if absent, use bundled fallback.
- If MD5/size validation fails, delete partial download and show a concise error.
- If ZIP extraction is unsafe or malformed, reject and clean up.
- If Chat4J starts after a crashed download/import, stale Vosk-owned temp/partial artifacts should be cleaned conservatively without deleting valid installed models.
- If transcription result JSON is malformed or text is blank, return a user-friendly failure.
- Use provider-neutral STT lifecycle messages unless a cloud provider is specifically uploading audio.
- Keep Vosk logging quiet by default, but configure Vosk log level only inside off-EDT runtime/probe/transcription code paths where native loading is already intentional; never call Vosk log-level APIs from startup or synchronous Settings/availability code.
- Do not run Vosk native runtime probes, catalog parsing, model scans, downloads, imports, extraction, deletion, or disk-space checks on the EDT.
- Avoid logging full local model paths or transcript text in normal logs. Use safe summaries for records/toString methods that include local paths or transcript/body text.

## Tests

Add focused unit tests without requiring real native Vosk runtime:

- catalog JSON parsing;
- speech model metadata filtering includes transcription types (`small`, `big`, `big-lgraph`) and excludes `tts`/`spk`, while downloadable-row filtering excludes obsolete remote-only entries;
- fallback order: online, cache, bundled resource;
- online catalog fetch uses timeout/status/redirect handling and a bounded maximum response size before JSON parsing;
- malformed cache fallback;
- rich metadata preservation through cache;
- official URL validation rejects non-HTTPS, wrong host, wrong path, non-ZIP, malformed URLs, and unsafe redirects/final URIs;
- catalog model-name validation rejects unsafe names, path separators, dot segments, control characters, URL/name mismatches, and any final directory that would resolve outside the managed Vosk root;
- MD5 mismatch rejection;
- size mismatch rejection;
- catalog/download/progress/ZIP size handling uses 64-bit counters, streams ZIP downloads directly to disk without buffering the full response in memory, and rejects overflow instead of truncating large model sizes;
- zip-slip rejection;
- unsafe symlink/archive entry rejection where feasible;
- install-to-partial/staging then final move, including top-level-folder ZIP normalization so final directory is not nested as `<model>/<model>/...`;
- existing final directory behavior;
- cancellation during download leaves no final corrupt model;
- disk-space preflight behavior with expected ZIP size, including separate temp-directory and model-directory filesystems;
- ZIP uncompressed-size/disk-space recheck before extraction, including coexistence of ZIP plus extracted partial install before cleanup;
- zip-bomb limits for entry count and total uncompressed bytes;
- installed model scanner detects valid official small-model layout that lacks `graph/words.txt` and `graph/phones.txt`;
- installed model scanner detects valid official model;
- installed obsolete official speech-recognition model remains visible/usable with an obsolete warning, while obsolete remote-only catalog entries remain excluded from default download choices;
- installed model scanner detects valid custom model;
- invalid model folders are excluded or reported unavailable;
- custom `local:<folder-slug>` ID resolves to the actual scanned directory;
- Vosk provider runtime unavailable behavior;
- selected model missing/invalid behavior;
- no installed models leaves Vosk selectable but not ready to transcribe;
- Vosk settings resolution and `SpeechToTextService.available()` are cheap/EDT-safe and do not perform fresh scans, catalog parsing, disk checks, network calls, or native runtime probes synchronously;
- local-provider settings/context resolution does not construct `SpeechToTextProviderContext` with null values that violate current `@NonNull` URI fields, and does not use dummy Groq URIs for Vosk;
- Groq/cloud STT regression coverage after context/catalog/model-selection refactors: Groq still resolves configured/default endpoints through `GroqSttEndpointResolver`, keeps its bundled default model fallback, uses credential-gated availability, fetches remote models through the cloud fetch path, hides local model-management UI, and retains cloud upload timeout/size-limit behavior without being affected by Vosk local-context changes;
- Vosk no-model settings resolution does not dereference `defaultModel()`/fallback fields and does not persist a dummy model just to satisfy old non-null assumptions;
- STT start-recording path performs lightweight authoritative off-EDT Vosk selected-model/runtime validation before opening the microphone, rejects stale cached readiness cleanly, and does not silently double-load large models before and after capture;
- STT preparing state, if added, is distinct from recording/transcribing: stop-and-transcribe is disabled or hidden before `activeCapture` exists, cancel invalidates pending preparation, and pressing the visible control during preparation never no-ops silently;
- settings UI model dropdown includes only installed eligible models, excludes hard-invalid/missing/remote-only entries, clearly labels plausible-but-unverified layouts, and does not make the mic ready for plausible/unverified selections until an off-EDT validation/probe or visible preparing validation succeeds;
- selecting/importing/discovering a plausible-but-unverified model schedules validation or exposes an explicit Validate/Prepare path, so the user is not stuck with hidden mic/unavailable STT and no way to promote or reject the model;
- settings UI model dropdown does not include remote-only catalog entries;
- settings UI no-model/stale placeholders cannot be saved as model selections and never satisfy recording readiness;
- deleting or explicitly clearing a stale selected Vosk model removes persisted model id/label keys, or records stale metadata separately, rather than saving a blank/placeholder `SpeechToTextCatalogItem`;
- settings UI local table includes downloadable catalog entries plus installed obsolete official models with obsolete warnings while excluding obsolete remote-only entries from default download choices;
- settings UI refresh/download controls work when no model is installed;
- Vosk catalog refresh does not call `GroqSttEndpointResolver`, does not require `snapshot.baseUri()`, does not depend on `SpeechToTextProvider.fetchModels(...)`, and works through the Vosk catalog/model-management service while transcription is not ready;
- settings UI download progress/cancel state;
- Vosk Settings and composer preparing/progress/cancel controls expose clear labels/tooltips/accessibility metadata and do not rely only on icons, color, or transient status text;
- settings UI catalog table sorting/filtering by language, type, installed status, and search text, including correct view-row to model-row mapping for select/download/delete actions;
- model-management snapshots are immutable/atomically published and concurrent UI/runtime reads never observe partial scan/download/delete state;
- Settings panel model-management listeners/progress callbacks unsubscribe on disposal and queued EDT updates do not touch disposed Swing components;
- runtime startup/model-management initialization discovers manually installed Vosk models without opening Settings, performs any needed native runtime probe only as non-blocking background work, and notifies the chat composer/mic availability on the EDT when readiness changes;
- background readiness notifications during active STT defer or use a silent/internal refresh path instead of showing `ChatPanel.reloadSpeechToTextSettings()` validation or resetting recording/transcribing/preparing UI state;
- download/import/delete/cleanup/rescan operations are serialized or otherwise safely coordinated;
- shared model-management service lifecycle cancels/disposes background scans/probes/downloads/imports on app/window shutdown, rejects new work after disposal, and does not notify disposed Settings/runtime listeners;
- deleting selected model updates dropdown/status;
- changing local model directory rescans models and handles stale selected model;
- persisted custom Vosk selections are cleared or marked stale when the managed root changes unless the scanned model matches the saved fingerprint/path identity, while official model IDs can remain selected only after validation in the new root;
- fake Vosk transcription adapter appends `getResult()` finalized segments plus `getFinalResult()` text in order;
- Vosk transcription feeds frame-aligned PCM chunks and handles/rejects partial trailing frames without passing malformed odd byte counts to `acceptWaveForm(byte[], len)`;
- fake transcription adapter normalizes whitespace across Vosk result segments;
- fake transcription adapter rejects blank/malformed `getResult()` and `getFinalResult()` JSON, including errors surfaced only after `acceptWaveForm(...)` completes because the Java binding exposes the native return as boolean;
- Vosk transcription closes recognizer before model and closes all native/audio resources on success, failure, and cancellation without masking the primary error;
- cancellation token is checked before expensive Vosk setup, after setup, and between audio chunks;
- runtime probe catches `LinkageError`/`UnsatisfiedLinkError` without crashing Settings;
- Vosk log-level configuration does not run during startup or synchronous Settings/availability resolution, and native failures from log-level calls are wrapped when performed inside off-EDT runtime/probe/transcription paths;
- Vosk provider/runtime adapter converts transcription-time `LinkageError`/`UnsatisfiedLinkError` into `SpeechToTextException` and does not bypass the service error callback path;
- Vosk local transcription is not aborted by the cloud HTTP request timeout;
- Settings/model-management receives and uses the configured/platform-correct STT temp directory, and `SettingsDialog` default/test constructors do not regress to hardcoded `~/.config` paths on Windows/XDG platforms;
- user-managed model import/copy behavior, progress/cancel, disk-space checks, self-copy rejection, already-managed source handling, symlink/hardlink/special-file handling, custom ID/destination collision handling, configured-directory-change handling, and managed-root scan boundaries;
- local model session snapshot detects selected model directory changes, deletion, invalidation, and clear-to-no-model states between recording start and transcription with null-safe comparison and a user-friendly model-changed message;
- local provider request/context carries the validated model path/fingerprint so Vosk does not re-resolve ambiguous `modelId` state at transcription time;
- active download/import participates in Settings close/save-pending behavior without blocking the EDT indefinitely;
- stale Vosk temp ZIP and partial-install cleanup requires Chat4J ownership markers/prefixes and does not delete valid installed models, user-created `.partial` folders, or unrelated files;
- STT mic visibility and start-recording validation are both independent of chat-provider readiness while still respecting normal compose mode, conversation loading/busy state, editing mode, and active STT state;
- provider resolving/missing keeps send disabled but does not hide or block the STT mic when STT is ready and the composer is otherwise eligible;
- local Vosk errors do not use cloud/upload-specific wording.

Integration/manual validation:

- download a small model through Settings;
- verify it appears in dropdown only after install;
- record/transcribe with Vosk;
- delete model and confirm provider becomes not ready to transcribe;
- refresh catalog offline and confirm fallback/cache behavior;
- verify the shaded application JAR contains Vosk native resources such as `darwin/libvosk.dylib`, `linux-x86-64/libvosk.so`, `win32-x86-64/libvosk.dll`, and Windows companion resources `win32-x86-64/libwinpthread-1.dll`, `win32-x86-64/libgcc_s_seh-1.dll`, `win32-x86-64/libstdc++-6.dll`, and `win32-x86-64/empty`;
- verify macOS jpackage includes Vosk native resources and still passes DMG checks;
- verify Windows jpackage/shaded runtime keeps the full `win32-x86-64` Vosk resource directory so `LibVosk` can unpack companion DLLs at runtime;
- smoke-test runtime loading on the current development platform;
- run or explicitly assess `dependency-audit`/SBOM impact for the added Vosk native dependency;
- verify third-party notice/license documentation covers the bundled Vosk Java/native runtime and still states that Vosk speech models themselves are not bundled.

## Documentation Updates

Update `docs/speech-to-text.md`:

- explain Vosk local STT;
- clarify no models are bundled;
- document model download/select/delete flow;
- mention official Vosk model catalog source;
- link to the official Vosk model page for model details and licenses;
- explain that model licenses vary and are not bundled by Chat4J;
- explain privacy: Vosk transcription runs locally once the model is downloaded;
- explain network behavior separately: catalog refresh and model downloads contact the official Vosk/Alpha Cephei model host, while recorded audio is not uploaded for Vosk transcription;
- document the default storage location under `<app-config>/stt/models/vosk` and clarify that users can change the base STT model directory in Settings, making the effective Vosk root `<configured-stt-model-dir>/vosk`;
- document that large models can require substantial disk and memory;
- document that Chat4J verifies official catalog MD5 for integrity but MD5 is not a cryptographic trust guarantee.

Also update `docs/README.md` so the Speech to Text entry mentions Vosk local STT/model management, and update `THIRD_PARTY_NOTICES.md` or the appropriate dependency-notice location for the bundled Vosk Java/native runtime. Do not add model-license notices as if models are bundled; instead point users to the official Vosk model page because model licenses vary and are downloaded/imported by the user.

## Implementation Steps

1. Add Vosk dependency to `pom.xml` without downgrading the project's JNA dependency.
2. Verify Vosk native resources survive Maven shade and are included by the normal Maven and jpackage flows, including Windows companion DLL resources under `win32-x86-64` and not just the main `libvosk` library.
3. Add `SettingsKeys.STT_PROVIDER_VOSK` and any Vosk catalog/cache settings keys or cache-file path helpers needed for persistence.
4. Wire `StoragePaths`, the STT temp directory, or a shared model-management service into `SettingsDialog`/`SpeechToTextPanel` so downloads do not invent their own temp location; update `MainFrame.openSettings()` and `SettingsDialog` convenience constructors so Settings and runtime use the same platform-correct model/temp roots.
5. Ensure the runtime `SpeechToTextService` and `SpeechToTextPanel` use compatible Vosk model-management services or shared filesystem/cache/settings state despite separate provider registry instances, with immutable atomically published snapshots, serialized mutating operations, runtime startup scanning/cleanup, explicit service ownership/disposal, and EDT notifications that refresh chat mic availability when snapshots change. Split user-initiated Settings reloads from background readiness notifications so notifications arriving during active STT do not show validation noise, clear composer state, or fight the active recording/transcribing/preparing UI.
6. Add bundled fallback catalog resource derived from official JSON, retaining speech-recognition metadata needed to identify installed obsolete models while filtering obsolete remote-only rows out of default download choices.
7. Add Vosk catalog/client/cache classes and tests, including bounded online catalog response handling, timeout/status/redirect failures, and cache/bundled fallback.
8. Add installed model scanner/validator and tests, including official small-model layouts that lack `graph/words.txt` and `graph/phones.txt`.
9. Add safe streaming downloader/installer with catalog-name/path validation, ZIP layout normalization, progress, cancellation, no full-response buffering, ZIP bomb defenses, disk-space preflight/recheck, operation-scoped partial/staging directories with Chat4J ownership markers, 64-bit size/progress accounting, and tests.
10. Add user-managed model import or clearly documented managed-root scan support, including import progress/cancel/disk-space behavior, self-copy rejection, already-managed source handling, symlink/hardlink/special-file handling, collision handling, and configured-directory-change handling.
11. Add Vosk runtime/transcriber adapter abstraction for testability.
12. Refactor STT provider model-selection contracts so Vosk can have no effective selected/default model until an installed valid model exists, including removing the current `selectedModel(...)` fallback dereference path for providers with no effective model, adding an explicit clear-selection path for provider model id/label settings, and persisting enough Vosk selected-model identity to avoid matching a custom `local:<folder-slug>` selection to a different model after the managed root changes.
13. Refactor STT settings/provider availability so local providers can distinguish selectable/manageable from ready-to-transcribe and can access cached model directory/selected model state without doing expensive work in synchronous UI availability/resolve calls.
14. Refactor local provider endpoint/context handling and catalog-fetch contracts so Vosk does not depend on dummy Groq URIs, current `@NonNull` HTTP URI fields, cloud `fetchModels(...)`, or cloud HTTP timeouts. Preserve and test the existing Groq/cloud path separately so endpoint resolution, credential availability, remote model refresh, default-model fallback, upload limits, and local-model UI hiding do not regress.
15. Extend STT start-recording preparation so local providers can perform lightweight authoritative selected-model/runtime validation off the EDT before microphone capture, without double-loading large models or misleading the UI about recording state; if preparation is user-visible, add a distinct preparing/cancel-only UI state rather than reusing the recording stop-and-transcribe control before `activeCapture` exists. If plausible-but-unverified models are allowed, ensure selection/import/discovery triggers background validation or a visible Validate/Prepare path before the normal mic-ready state is required, so validation is reachable even while `SpeechToTextService.available()` is false.
16. Extend STT session snapshot matching for local providers to include selected model directory identity/fingerprint, handle missing current model/context fields null-safely, and pass the validated local model reference into the provider request/context.
17. Reconcile STT mic visibility/start-recording gating so STT can start when the composer is otherwise editable and STT is ready, regardless of whether the chat provider is missing or resolving; keep conversation-loading/visible-busy/editing/active-STT guards consistent between visibility and start validation. Refactor `InputBar`/`ChatPanel.refreshComposerAvailability()` as needed so `currentProviderResolving` and missing `currentProvider` disable send controls without flowing through a shared busy flag that also hides the mic.
18. Replace cloud/upload-specific generic STT messages with provider-neutral wording where local providers share the path.
19. Implement `VoskSpeechToTextProvider` with batch WAV transcription, wrapping native Vosk/JNA linkage failures into `SpeechToTextException` at the provider/runtime adapter boundary.
20. Register Vosk provider.
21. Refactor `SpeechToTextPanel` local model handling to be provider-aware and to separate installed dropdown models from downloadable table rows, including lifecycle-safe subscription/unsubscription for model-management snapshot/progress events.
22. Add sorting/filtering/search for the Vosk local model table, with safe view-row/model-row conversion for every row action.
23. Ensure catalog refresh/download are not blocked by Vosk lacking an installed model, and split Settings catalog refresh so Vosk does not use the current Groq endpoint/fetchModels path.
24. Add stale cleanup for Vosk-owned temp ZIPs and partial install directories, requiring Chat4J ownership markers/prefixes before deletion.
25. Ensure active downloads/imports participate in Settings close/save-pending behavior.
26. Add settings tests for Vosk UI behavior, including accessibility/tool-tip coverage for new Vosk action/progress/status controls and any composer preparing/cancel state.
27. Update docs and notices, including `docs/speech-to-text.md`, `docs/README.md`, and third-party dependency notices for the bundled Vosk Java/native runtime while keeping model-license language separate because models are not bundled.
28. Run targeted tests, full test suite, compile, shaded-JAR native resource inspection, dependency/SBOM assessment where relevant, jpackage mac validation where relevant, and `git diff --check`.
