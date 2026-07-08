# Sphinx4 Local Speech-to-Text Plan

## Goal

Add CMU Sphinx4 as a local Speech-to-Text provider for Chat4J, similar to Vosk, with model management, local/offline transcription after install, and a cautious staged catalog for SourceForge CMU Sphinx acoustic/language model packages.

This is not an MVP: the implementation should include the full local-provider lifecycle, installer safety, model validation, UI integration, tests, and documentation needed for a production-quality local STT provider. The only staged part is model availability: all target languages are visible, but one-click download is enabled only for entries with fully verified recipes.

## Key Decisions

- Add a new local provider: `sphinx4` / `Sphinx4`.
- Use a direct Maven dependency for the Sphinx4 core/API runtime unless compile/test validation proves the artifact line unusable.
  - Verify exact coordinates during implementation. Likely core/API candidate: `net.sf.phat:sphinx4-core:5prealpha`.
  - Do **not** add `sphinx4-data` or any dependency/artifact that bundles acoustic/language model assets by default. Chat4J’s STT design keeps speech models under the managed model root, not inside the application jar.
  - If a Sphinx4 data/model artifact appears necessary, stop and explicitly evaluate package size, license notices, duplicate managed-model behavior, jpackage/shaded-jar impact, and user approval before adding it.
  - Verify whether Sphinx4 `Configuration` accepts absolute filesystem paths or requires `file:` URI strings for acoustic/dictionary/language-model paths; encode the adapter accordingly and cover it with tests where possible.
- Add Apache Commons Compress, or an equivalent well-maintained archive reader, before marking any `.tar.gz` / `.tgz` recipe downloadable. Java has ZIP support but not TAR support in the standard library. Because CMU Sphinx SourceForge model packages are commonly `.tar.gz`, expect TAR/GZIP support to be part of the feature unless verification finds a safe non-TAR downloadable package. If TAR support is not implemented yet, TAR-based SourceForge packages must remain catalog-only/not verified and the feature is not complete unless the user explicitly accepts an import-only release.
- Inspect the full Maven dependency tree before committing dependencies, not only a filtered Sphinx4 artifact view. Exclude or reject transitive data/model artifacts even if they appear under unexpected groups, and verify dependency licenses are compatible with redistribution in Chat4J's shaded jar/package.
- Use a Vosk-like model management UX, but keep Sphinx4-specific services separate.
- Prefer light duplication over shared abstractions for the first pass. Small UI helper methods for “managed local provider” dispatch are acceptable; avoid a broad local-model framework until Vosk and Sphinx4 both stabilize.
- Use `<configured-stt-model-dir>/sphinx4` as the Sphinx4 model root.
- Exclude `Archive` from the normal catalog.
- Show the full non-archive language list, but only enable one-click downloads for entries with verified recipes.
- Catalog-only entries are visible but not downloadable/selectable until installed/imported and validated.
- Do not claim modern accuracy, active upstream maintenance, or cloud-level transcription quality.
- Completion bar: ship with at least one fully verified one-click-downloadable model recipe, preferably US English, unless the user explicitly approves a catalog/import-only Sphinx4 release. This ensures the Vosk-like download/install/select path is real and tested, not only theoretical.

## Initial Catalog Scope

Target visible language rows from the CMU Sphinx SourceForge “Acoustic and Language Models” folder:

1. Mexican Spanish
2. Portuguese
3. Mandarin
4. Indian English
5. Catalan
6. German
7. Greek
8. French
9. Dutch
10. US English
11. Spanish
12. Italian
13. Hindi
14. Kazakh
15. Russian

`Archive` is intentionally excluded from the normal catalog.

Each catalog entry starts with a status:

- `downloadable/verified`: safe one-click install recipe exists.
- `catalog-only/not verified`: upstream language folder exists, but no verified install recipe yet.
- `installed/selectable`: validated local Sphinx4 model exists and selected-model validation has succeeded.
- `plausible/unverified`: an imported folder has enough structure to investigate, but it is not selectable yet.
- `invalid/incomplete`: local/imported folder is missing required Sphinx4 files, safe metadata, or a successful recognizer-construction validation.

The Sphinx4 model combo should list only eligible installed/selectable models, matching Vosk. The local model table should show the broader catalog and installed/imported rows, including catalog-only rows.

## Catalog and Recipe Schema

Sphinx4 packages are less standardized than Vosk packages. A recipe must support one or more downloadable artifacts because some languages may ship the acoustic model, dictionary, and language model in separate files.

Proposed `Sphinx4ModelCatalogEntry` fields:

```text
id
label
language
description
status/catalogOnly/downloadable
verifiedDownload
sampleRateHz
licenseName/licenseUrl/sourceNotes
artifacts[]
recipePaths
requiredFiles[]
```

Proposed artifact fields:

```text
artifactId
role: acoustic | dictionary | languageModel | supplemental
url
expectedSizeBytes
expectedUncompressedBytes for archive/raw-gzip artifacts when known
sha256 of the downloaded artifact bytes
archiveFormat: zip | tar.gz | tgz | raw-gzip | raw-file
extractTo: relative staging subdirectory for archive artifacts
targetPath: required relative output file path for raw-file and raw-gzip artifacts
stripTopLevelDirectory: true/false
required: true/false
```

Proposed recipe path fields:

```text
acousticModelPath
dictionaryPath
languageModelPath
```

Rules:

- Catalog/download IDs, managed directory names, and any persisted Sphinx4 installed model IDs must be safe single path segments before they are used for final directories, settings values, or local model references: nonblank, not `.`/`..`, no slash/backslash, no control characters, and restricted to a conservative character set such as `[A-Za-z0-9._-]+`. Reject unsafe Sphinx4 catalog/download IDs before resolving `<configured-stt-model-dir>/sphinx4/<model-id>`.
- Imported/custom Sphinx4 models should use safe generated IDs such as `local-<slug>` or `local-<slug>-<stableSuffix>`. Do not copy Vosk’s colon-prefixed `local:<slug>` custom ID shape if that ID may be used as a directory name or persisted Sphinx4 model ID.
- A downloadable entry must have at least one artifact, all required recipe paths, and explicit source/license notes sufficient for docs/third-party notices. Entries with unknown or incompatible redistribution/download terms stay catalog-only.
- Multi-artifact installs download all artifacts into one operation-specific temp directory, validate each artifact, extract/assemble into one staging directory, validate the completed recipe, then publish atomically.
- Artifact extraction targets and raw artifact target paths must be relative, safe paths under staging.
- Raw-file artifacts are copied to their explicit `targetPath`; never derive filenames from SourceForge `/download` URLs.
- Raw-gzip artifacts are decompressed to their explicit `targetPath` before recipe validation; the compressed download filename is irrelevant. Enforce decompressed byte limits and disk-space checks for raw-gzip just as for archive entries.
- Artifact path collisions are rejected unless the recipe explicitly allows the same bytes for the same relative path. Prefer rejecting all collisions for the first implementation.
- The catalog bundled with the app is a curated snapshot. Do not dynamically scrape SourceForge for the first implementation.
- The “Refresh catalogs” button for Sphinx4 should rescan local models and reload the bundled curated catalog. It should not scrape SourceForge. Downloads contact SourceForge only when a verified downloadable model is installed.

## Verification Definition for Downloadable Entries

A catalog entry may be marked one-click downloadable only after verifying:

1. **Download source**
   - Initial URL is from the expected CMU Sphinx SourceForge project path.
   - Archive/file type is supported (`.zip`, `.tar.gz`, `.tgz`, raw `.gz` language model, or raw file when explicitly described by the recipe).
   - Expected file size is recorded per artifact.
   - SHA-256 checksum is recorded per artifact after manual verification.

2. **SourceForge redirect policy**
   - Start from `https://sourceforge.net/projects/cmusphinx/files/Acoustic%20and%20Language%20Models/.../download`, or for the verified US English bootstrap recipe, `https://sourceforge.net/projects/cmusphinx/files/sphinx4/5prealpha/sphinx4-5prealpha-src.zip/download`. Other equivalent trusted CMU Sphinx SourceForge file URLs must be explicitly captured in the recipe and allowlist before being marked downloadable.
   - Follow redirects manually, not with unrestricted client redirects.
   - Cap redirects, e.g. max 3.
   - Require HTTPS for every hop.
   - Reject URLs with user-info, unexpected ports, unsupported schemes, or hostless values.
   - Allow only SourceForge download hosts whose paths remain within the CMU Sphinx project file tree, such as `sourceforge.net` project URLs and `downloads.sourceforge.net` / `*.dl.sourceforge.net` mirror URLs with `/project/cmusphinx/Acoustic and Language Models/...` paths, plus the explicitly verified `/project/cmusphinx/sphinx4/5prealpha/sphinx4-5prealpha-src.zip` source-data bootstrap path.
   - Canonicalize redirect hosts before allowlist checks: lowercase/IDN-normalize, reject trailing-dot ambiguity, and require exact host matches or true label-boundary suffix matches. Do not accept lookalikes such as `downloads.sourceforge.net.evil.example` or `evil-dl.sourceforge.net.example`.
   - Validate paths after safe percent-decoding/normalization so `%20` and spaces are handled consistently, while encoded traversal, encoded slashes/backslashes, double-encoded traversal, and path confusion are rejected.
   - Revalidate the resolved URL before sending each redirected request.

3. **Archive safety**
   - Extraction rejects path traversal and absolute paths.
   - Extraction happens in a staging directory.
   - Failed installs clean partial downloads/staging folders.
   - Entry counts, nesting depth, single-entry sizes, and total uncompressed bytes are capped with 64-bit counters.
   - ZIP entries must be regular files/directories only. Do not preserve external attributes blindly; detect and reject ZIP entries whose Unix mode/external attributes indicate symlinks or non-regular types when the archive library exposes that data, and verify extracted paths are not symlinks before publishing.
   - TAR entries must reject symlinks, hard links, device files, FIFOs, sparse entries if unsupported, absolute paths, path traversal, and unsafe names.
   - Extracted file permissions should be normalized conservatively; never preserve executable bits unless explicitly needed.
   - Disk-space checks should run before download when size is known and before publish/extraction when uncompressed size is known or estimated.

4. **Required Sphinx4 files**
   - Acoustic model directory exists with recipe-required files. Typical checks include `mdef`, `means`, `variances`, transition matrices, and model properties, but use recipe-specific manifests because layouts vary.
   - Pronunciation dictionary exists, usually `.dic`.
   - Language model exists, such as `.lm`, `.lm.bin`, `.dmp`, or equivalent.
   - All metadata paths are relative and remain inside the model root after normalization.

5. **Mandatory Sphinx4 construction smoke test**
   - Build `edu.cmu.sphinx.api.Configuration` with acoustic model, dictionary, and language model paths.
   - Construct a `StreamSpeechRecognizer` successfully through the adapter.
   - Run this off the Swing EDT.
   - A model is not selectable until this validation succeeds.
   - Because recognizer construction can be expensive, cache heavy validation results, including failures, by model fingerprint and runtime/dependency version. Re-run heavy construction only when the fingerprint changes, the runtime/dependency version changes, install/import produces a new fingerprint, selection targets a model with no cached result, explicit validation is requested, or no cached validation exists. Startup/refresh scans should use cheap structural checks first and queue heavy validation only for the selected model or changed models.

6. **Optional audio smoke test**
   - If language-specific sample audio is available, run a tiny recognition smoke test.
   - This is not required for first-pass “downloadable” status because collecting reliable samples for every language is extra work.

## Architecture

Add package:

```text
src/main/java/com/github/drafael/chat4j/stt/provider/sphinx4/
```

Proposed classes:

- `Sphinx4SpeechToTextProvider`
- `Sphinx4ModelManagementService`
- `Sphinx4ModelManagementSnapshot`
- `Sphinx4ModelCatalogEntry`
- `Sphinx4ModelArtifact`
- `Sphinx4ModelRecipe`
- `Sphinx4InstalledModel`
- `Sphinx4InstalledModelScanner`
- `Sphinx4ModelInstaller`
- `Sphinx4ModelValidator`
- `Sphinx4BundledCatalogLoader`
- `Sphinx4LocalModelRow`
- `Sphinx4ModelMetadata`
- `Sphinx4RecognizerAdapter`

Keep Vosk and Sphinx4 management independent initially. Revisit shared local-provider abstractions only after both implementations stabilize.

## Metadata

Each installed Sphinx4 model folder should contain Chat4J metadata:

```text
.chat4j-sphinx4-model.json
```

Suggested fields:

```json
{
  "schemaVersion": 1,
  "id": "cmusphinx-en-us-ptm-5.2",
  "label": "US English PTM 5.2",
  "language": "US English",
  "sourceArtifacts": [
    {
      "artifactId": "acoustic-and-dictionary",
      "url": "https://sourceforge.net/.../download",
      "sha256": "...",
      "sizeBytes": 11744051
    }
  ],
  "acousticModelPath": "acoustic",
  "dictionaryPath": "dict/cmudict-en-us.dict",
  "languageModelPath": "lm/en-us.lm.bin",
  "sampleRateHz": 16000,
  "requiredFiles": [
    "acoustic/mdef",
    "acoustic/means",
    "acoustic/variances",
    "dict/cmudict-en-us.dict",
    "lm/en-us.lm.bin"
  ],
  "recipeId": "cmusphinx-en-us-ptm-5.2",
  "recipeVersion": 1,
  "verifiedDownload": true
}
```

The provider should load this metadata at transcription time rather than widening `LocalSpeechToTextModelReference` with provider-specific fields.

Metadata rules:

- Metadata paths must be relative paths.
- Normalized metadata paths must stay inside the selected model directory.
- Metadata must be written by Chat4J for downloaded models.
- Treat metadata fields such as `verifiedDownload`, `sourceArtifacts`, and license/source notes as informational for imported models; do not trust them to bypass local path, file-manifest, checksum/fingerprint, or recognizer-construction validation.
- Downloaded model metadata must snapshot enough of the verified recipe to validate the installed model later even if the bundled catalog changes: acoustic/dictionary/language-model paths, sample rate, required-file manifest, recipe id/version, and source artifact records.
- Imports must either create metadata from a known recipe/inference result or leave the imported folder visible as invalid/incomplete. Do not allow transcription from a folder with missing or unsafe metadata.
- Fingerprints should be computed by the scanner/validator from stable file metadata and/or checksums of normalized metadata plus required model files. Do not trust or require a fingerprint stored inside model metadata; avoid circular fingerprints by excluding any cache/selection fields from the fingerprint source. The computed fingerprint must change when required paths/files change.

## Provider Behavior

`Sphinx4SpeechToTextProvider` should implement `SpeechToTextProvider`:

- `id()`: `sphinx4`
- `displayName()`: `Sphinx4`
- `requiredEnvVar()`: empty string
- `supportsLocalModels()`: `true`
- `available(...)`: true at provider level; selected-model readiness is determined by settings/model-management snapshot.
- `defaultModel()`: `null`, because selectable Sphinx4 models come from validated local installs.
- `bundledModels()`: empty list; the bundled Sphinx4 language catalog belongs to `Sphinx4ModelManagementService`, not the generic cloud catalog combo/cache.
- `fetchModels(...)`: empty, because Sphinx4 catalog is local/bundled and managed by `Sphinx4ModelManagementService`, not by the cloud catalog cache.
- `transcribe(...)`: local transcription using Sphinx4.

Transcription flow:

1. Check cancellation before setup.
2. Require a selected `LocalSpeechToTextModelReference`.
3. Verify model directory exists.
4. Load `.chat4j-sphinx4-model.json`.
5. Resolve acoustic, dictionary, and language model paths safely.
6. Build Sphinx4 `Configuration`.
7. Create `StreamSpeechRecognizer` through a small adapter.
8. Open the Chat4J WAV file and convert/resample only if required by the selected model metadata. Chat4J captures 16 kHz mono PCM by default, so 16 kHz models should usually avoid extra conversion.
   - Be explicit about the Sphinx4 input format. If using `StreamSpeechRecognizer.startRecognition(InputStream)`, feed a Sphinx4-compatible WAV stream/file, not ambiguous headerless PCM, unless the chosen Sphinx4 API is verified to accept raw PCM.
   - If conversion is needed, write a temporary converted WAV file under the Chat4J STT temp area supplied through provider context/request plumbing, with owner-only permissions, pass that WAV to Sphinx4, and delete it in `finally`.
   - Use a Chat4J-owned temp filename prefix/suffix that is covered by STT stale-temp cleanup, or extend `MicrophoneAudioCapture.cleanupStaleTempFiles()` / the STT temp cleanup path to remove stale provider-created conversion WAVs after crashes.
   - If Java Sound cannot convert to the selected model’s sample rate/format, fail with a clear Sphinx4 model/audio-format error before recognition.
9. Feed audio to recognizer and collect hypotheses into a single transcript.
10. Check cancellation before setup, before/after stream recognition, and between result reads where Sphinx4 permits. If Sphinx4 blocks inside recognition, cancellation may complete after the current recognition call returns; document this limitation.
11. Close recognizer/audio streams and delete any converted temp WAV in all paths.
12. Throw `SpeechToTextException("No speech was recognized.")` on blank transcript.

Expected clear errors:

- `Select an installed Sphinx4 model to enable transcription.`
- `Selected Sphinx4 model is missing.`
- `Selected Sphinx4 model is incomplete.`
- `Sphinx4 runtime could not load this model.`
- `No speech was recognized.`
- `Transcription canceled.`

## Settings and Service Integration

Update:

- `SettingsKeys`
  - add `STT_PROVIDER_SPHINX4 = "sphinx4"`.
  - add provider-specific persistence for selected local identity, mirroring Vosk:
    - `chat4j.stt.sphinx4.model.fingerprint`
    - `chat4j.stt.sphinx4.model.root`
- `SpeechToTextProviderRegistry`
  - add Sphinx4 as a default provider.
  - Suggested order: cloud providers first, local providers last, e.g. `Groq`, `ElevenLabs`, `Deepgram`, `Vosk`, `Sphinx4`.
- `SpeechToTextSettings`
  - accept/use both `VoskModelManagementService` and `Sphinx4ModelManagementService`.
  - add `resolveSphinx4(...)` similar to `resolveVosk(...)`.
  - add `validateSelectedSphinx4ModelNow()` or a small generic `validateSelectedLocalModelNow(providerId)` dispatcher.
  - provider should resolve to available only when selected installed model validates.
- `SpeechToTextSettingsSnapshot`
  - ideally no changes; use existing local model reference.
- `SpeechToTextProviderContext` / request plumbing
  - add an app-controlled STT temp directory to the provider context, or add an equivalent provider-safe temp-file facility, because the current provider interface exposes only the captured WAV path and has no way for Sphinx4 to create converted WAV files under Chat4J's controlled temp root.
  - preserve existing cloud/local provider behavior by adding compatibility constructors or updating all provider call sites/tests deliberately.
- `SpeechToTextService`
  - update factory constructors to accept/share `Sphinx4ModelManagementService` alongside Vosk.
  - store or otherwise expose the STT temp directory used by `MicrophoneAudioCapture`, then pass it into `SpeechToTextProviderContext` for providers that need provider-side temp files.
  - before microphone capture, validate selected Sphinx4 model the same way Vosk is validated today, then re-resolve settings before starting capture.
  - ensure settings/model/fingerprint changes still make `matchesSnapshot(...)` reject stale transcriptions.

Selected model persistence:

- Persist selected model id/label via existing `sttModelIdKey("sphinx4")` / `sttModelLabelKey("sphinx4")`.
- Persist Sphinx4 selected root and fingerprint using provider-specific keys.
- When resolving selected installed models, reject stale custom selections if saved root/fingerprint no longer match the current scanned model, mirroring Vosk behavior.

## Application Lifecycle Wiring

Update object construction and cleanup paths explicitly:

- `MainFrame`
  - create one long-lived `Sphinx4ModelManagementService` using the same STT models/temp roots as Vosk.
  - add a Sphinx4 snapshot listener analogous to the current Vosk listener so chat STT settings reload when the selected/installed Sphinx4 model state changes and no STT session is active.
  - call `sphinx4ModelManagementService.refreshAsync()` during startup, analogous to Vosk’s startup refresh.
  - pass it to `SpeechToTextService.createDefault(...)`.
  - pass it to `SettingsDialog`.
  - close it during frame disposal/shutdown wherever Vosk is currently closed.
- `SettingsDialog`
  - constructors should create or accept `Sphinx4ModelManagementService` alongside `VoskModelManagementService`.
  - ownership flags should close only services owned by the dialog.
  - pass both services into `SpeechToTextPanel`.
- `SpeechToTextPanel`
  - constructors should create or accept both local model management services.
  - listener subscriptions for both services must be removed in `removeNotify()`.
  - owned services must be closed in `removeNotify()`.

Add tests for shared-service lifecycle and disposal to avoid leaked virtual-thread executors and temp directory deletion flakes.

## UI Integration

Update `SpeechToTextPanel` to support both local providers. The current panel has many Vosk-only branches; the implementation must update all of them, not just add a provider row.

Required UI changes:

- Add fields for `Sphinx4ModelManagementService`, ownership flag, unsubscribe callback, Sphinx4 rows, operation state, and operation success message.
- Update constructors to create/pass Sphinx4 service consistently.
- Update `savePendingChanges()` to block closing/saving while any local model service has an operation in progress, even if that provider is not currently selected.
- Update `removeNotify()` to unsubscribe/close both services.
- Replace `isVosk(...)`-only decisions with helpers such as:
  - `isVosk(snapshot)`
  - `isSphinx4(snapshot)`
  - `isManagedLocalProvider(snapshot)`
  - `localOperationInProgress(snapshot)`
- Update table model column editability and selected-column routing for Sphinx4 rows.
- Update provider/model selection:
  - Block provider changes while any local model service operation is active; reset the combo and show a clear status message instead of switching away from a hidden running operation.
  - Vosk and Sphinx4 model combo selections should call their service’s async select method.
  - Non-local cloud providers continue using `settings.saveModel(...)`.
- Update automatic catalog refresh logic so Sphinx4 is not treated like a cloud provider and does not call `SpeechToTextCatalogStore`/`fetchModels(...)`.
- Update `browseModelDirectory()` / `saveModelDirectory()` to block model directory changes while either local provider operation is active, and refresh both local services after a successful directory change.
- Update `refreshCatalogs(...)`:
  - Vosk uses Vosk refresh.
  - Sphinx4 uses Sphinx4 management refresh/reload-bundled-catalog/rescan.
  - Cloud providers use existing remote catalog flow.
- Update `updateLocalModels(...)` to branch to Sphinx4 rows and configure columns.
- Update local model table columns for Sphinx4. Suggested columns: `Model`, `Language`, `Type`, `Size`, `Installed`, `Selected`, `Status`.
- Update buttons:
  - Download enabled only for verified downloadable Sphinx4 rows and idle service.
  - Delete enabled only for managed installed Sphinx4 rows and idle service.
  - Add Folder enabled when idle.
  - Catalog-only selected rows should show a clear disabled reason.
- Rename tooltips/accessibility text so they are not Vosk-specific when Sphinx4 is selected.
- Add Sphinx4 import action instead of reusing `importExistingVoskModel()` directly.
- Update helper/privacy text:
  - Sphinx4 transcription runs locally after model install.
  - Sphinx4 downloads contact SourceForge/CMU Sphinx.
  - Catalog-only languages require manual import or a future verified recipe.

## Import UX

Sphinx4 import behavior must be concrete and safe:

- Imports copy into Chat4J’s managed Sphinx4 root, matching Vosk. Do not reference arbitrary external folders for selectable models.
- Reject null source, missing source, non-directory source, source already managed, source/destination nesting, existing destination collision, symlinks, hard links where detectable, special files, and unsafe names.
- Generate a safe managed directory/model ID for imports from the source folder name plus a stable suffix when needed; keep the original folder name only as a display label if it is not safe.
- Use a staging directory and publish atomically after validation.
- Support cancellation/progress through the management service.
- Provider switching, dialog save/close, model directory changes, and app shutdown should account for in-flight local operations; the UI should not allow hiding a running Sphinx4/Vosk operation by switching providers and then closing Settings as if idle.
- If a catalog row is selected during import, use it only as a language/recipe hint; still validate actual files.
- If imported folder contains safe `.chat4j-sphinx4-model.json`, validate it and rewrite/normalize metadata in the managed copy.
- If imported folder has no metadata, run deterministic inference:
  - find exactly one plausible acoustic model directory;
  - find exactly one dictionary;
  - find exactly one language model;
  - infer sample rate only when clear from recipe/config/package metadata.
- If inference is ambiguous, incomplete, or cannot determine sample rate safely, keep the folder visible as invalid/incomplete or plausible/unverified and not selectable; do not silently default to 16 kHz for selectable imports.
- Selection behavior should mirror Vosk: if no saved Sphinx4 selection exists, `resolveSphinx4(...)` may use the first ready installed model; downloaded/imported models must not become selected unless validation reaches ready/selectable status.
- Delete only managed folders under `<configured-stt-model-dir>/sphinx4`; reject deletion outside the managed root.

## Installer Behavior

`Sphinx4ModelInstaller` should mirror Vosk safety practices and extend them for multi-artifact recipes:

- allowlist SourceForge CMU Sphinx model URLs and redirects as defined above;
- reject unsupported schemes/hosts/paths;
- stream each artifact to an operation temp directory with explicit request timeouts, cancellation checks between chunks, closed response bodies on every path, and progress updates;
- validate HTTP status, expected content length when provided, downloaded byte count, size limits, and SHA-256 for every artifact;
- support `.zip`, `.tar.gz`, `.tgz`, raw `.gz`, and raw files only when the recipe declares the format;
- require raw `.gz` and raw-file artifacts to declare a safe relative `targetPath`; copy raw files there and decompress raw `.gz` files there before validation;
- enforce raw-gzip decompressed-size limits, expected uncompressed size when known, and path-collision checks for the decompressed target;
- extract/assemble every artifact into staging;
- reject path traversal, absolute archive entries, symlinks/hardlinks/special files, unsafe permissions, excessive entries, excessive nesting, and excessive uncompressed size;
- reject artifact path collisions;
- validate the completed expected model recipe;
- write `.chat4j-sphinx4-model.json`;
- validate catalog/download model IDs as safe single path segments before resolving the final install directory;
- publish atomically to `<configured-stt-model-dir>/sphinx4/<model-id>` (equivalently `<sphinx4-model-root>/<model-id>`, where `<sphinx4-model-root>` is `<configured-stt-model-dir>/sphinx4`);
- clean partial downloads and stale staging folders conservatively.

For catalog-only entries, installer should not run. The UI should block the action before reaching installer code, and the service should still reject attempts defensively.

## Scanner and Validator

`Sphinx4InstalledModelScanner` should:

- scan immediate directories under `<configured-stt-model-dir>/sphinx4`;
- create rows for official catalog entries and installed/imported custom folders;
- identify folders with `.chat4j-sphinx4-model.json`;
- run deterministic inference for no-metadata imports as described above;
- mark no-metadata or ambiguous folders invalid/incomplete but visible;
- preserve custom IDs with stable safe slugs/suffixes; use `local-<slug>`-style IDs rather than Vosk’s `local:<slug>` if the ID is persisted or used as a managed directory name;
- compute fingerprints used by selection persistence.

`Sphinx4ModelValidator` should:

- validate metadata JSON schema/version;
- validate relative paths stay inside the model root;
- reject unsafe symlinks or paths escaping the managed root;
- check metadata-snapshotted recipe required files/directories exist, falling back to the current bundled catalog recipe only when safe and compatible;
- separate cheap structural validation from heavy recognizer-construction validation;
- construct the Sphinx4 recognizer adapter off the EDT as a mandatory selectable validation gate, then close/release any recognizer/model resources immediately after the construction smoke test;
- cache heavy validation results by fingerprint and runtime/dependency version so ordinary refreshes do not repeatedly load large Sphinx4 models;
- invalidate cached validation when metadata, required files, selected root, or runtime version changes;
- return explicit validation statuses such as `MISSING`, `UNSAFE`, `INVALID`, `PLAUSIBLE_UNVERIFIED`, `VALIDATING`, and `VALID`;
- never mark a model selectable until status is `VALID`.

## Model Management Service

`Sphinx4ModelManagementService` should mirror Vosk’s operational model:

- single-threaded virtual-thread executor;
- immutable snapshot;
- listener registration/removal;
- `refreshAsync()` for scanning/revalidating, using cheap checks first and only running heavy recognizer validation when cache/fingerprint rules require it;
- `refreshCatalogAsync()` for reloading bundled catalog and scanning, not scraping SourceForge;
- `downloadAsync(modelId)`;
- `importAsync(Path source, optional selected row hint if needed)`;
- `deleteAsync(modelId)`;
- `selectModelAsync(modelId)`;
- selected-model resolution that mirrors Vosk: saved valid model wins; if none is saved, first ready installed model may be used; stale root/fingerprint custom selections are ignored;
- `validateSelectedNow()`;
- `close()` cancels active operation and shuts down executor;
- one active operation at a time;
- cleanup stale partial downloads/imports/installations before operations.

Snapshot should include:

- model root and temp root;
- bundled catalog;
- installed models;
- local table rows;
- selected model;
- runtime/dependency readiness;
- status message;
- operation-in-progress flag;
- operation status/error.

## Documentation

Update:

- `docs/speech-to-text.md`
- `docs/README.md`
- root `README.md` if provider list/API-key section needs adjustment
- `THIRD_PARTY_NOTICES.md` for Sphinx4, Commons Compress if added, and model-source notes

Docs should state:

- Sphinx4 is local/offline after model install.
- Sphinx4 downloads contact SourceForge/CMU Sphinx.
- The Sphinx4 catalog is a bundled curated snapshot; Chat4J does not scrape SourceForge dynamically in this implementation.
- Not every visible language is one-click-downloadable initially.
- Sphinx4 quality/speed may vary and may be weaker than Vosk/cloud providers.
- CMU Sphinx project assets are older/upstream status may be abandoned.
- Model licenses vary by package; Chat4J does not bundle model license texts unless it bundles model files.
- Cancellation during Sphinx4 recognition may not interrupt a blocking Sphinx4 call immediately.

## Tests

Add focused tests for:

- provider metadata and availability;
- settings resolution and provider order;
- `SpeechToTextService` pre-capture Sphinx4 validation and stale settings rejection;
- MainFrame/SettingsDialog/SpeechToTextPanel constructor/service lifecycle, startup listener/refresh behavior, and close behavior;
- dependency tree and license validation for Sphinx4/archive dependencies, plus package validation proving the shaded jar does not bundle Sphinx4 acoustic/language model assets;
- full 15-language bundled catalog excluding Archive;
- downloadable vs catalog-only row state;
- Sphinx4 not using cloud `SpeechToTextCatalogStore` refresh/fetch flow;
- selected root/fingerprint persistence, validation-cache behavior, and stale custom selection rejection;
- multi-artifact recipe assembly, including successful assembly, raw-file/raw-gzip explicit target paths, raw-gzip decompressed-size limits, and artifact collision rejection;
- SourceForge redirect chains, redirect cap, HTTPS enforcement, canonical host allowlist including suffix-lookalike rejection, malicious redirect rejection, HTTP error handling, timeout/cancellation behavior, response-body closing, and progress updates;
- safe URL validation;
- unsafe catalog/model/import-generated ID rejection before final install path resolution;
- checksum mismatch rejection;
- archive traversal rejection;
- ZIP excessive entries/unknown or excessive size, ZIP symlink/external-attribute hazards where detectable, and post-extraction symlink rejection;
- TAR symlink/hardlink/device/special-file rejection;
- raw `.gz` language model handling if supported;
- archive layout normalization and top-level folder stripping;
- staging/publish cleanup;
- disk-space failure handling;
- metadata read/write/path normalization, imported-metadata trust boundaries, metadata recipe snapshot validation across bundled-catalog changes, and fingerprint computation that excludes untrusted/circular metadata fields;
- validator success/failure/status cases, including recognizer resource cleanup after construction smoke tests;
- scanner handling installed, first-ready fallback selection, incomplete, ambiguous no-metadata, catalog-only, and imported models;
- import copy-vs-reference behavior, collisions, already-managed source, nested source/destination rejection, symlink/special-file rejection, ambiguous/sample-rate-unknown inference, and no auto-select unless valid;
- UI status/helper text, button enablement, table columns, tooltips/accessibility labels;
- provider changes, dialog save/close, and model directory changes blocked while any local model service operation is active, including operations for a provider that is no longer selected;
- transcription provider using a fake `Sphinx4RecognizerAdapter` rather than heavyweight real models;
- provider-context temp-directory plumbing for Sphinx4 conversion without regressing cloud/Vosk providers;
- audio conversion success/failure around sample-rate metadata, Sphinx4-compatible WAV-vs-raw stream behavior, converted temp WAV cleanup, stale converted-temp cleanup after simulated crash, and owner-only temp permissions where supported;
- docs mention local/privacy/source/quality limitations.

Run validation:

```bash
mvn -q -DskipTests compile
mvn -q dependency:tree -DoutputFile=target/sphinx4-dependency-tree.txt
# Inspect target/sphinx4-dependency-tree.txt for selected Sphinx4 coordinates, commons-compress, transitive data/model artifacts, and redistribution/license concerns.
mvn -q -Dtest='*Sphinx4*Test,SpeechToTextSettingsTest,SpeechToTextPanelTest,SpeechToTextServiceTest,SettingsDialog*Test,MainFrame*Test' test
mvn -q test
mvn -q -DskipTests package
git diff --check
```

## Implementation Order

1. Verify Sphinx4 Maven coordinates and add core/API dependency properties/dependencies only; explicitly avoid bundling Sphinx4 model/data artifacts unless separately approved. Capture the full dependency tree after choosing the artifact and inspect all transitive dependencies for unexpected model/data artifacts.
2. Add archive dependency for TAR/GZIP support if the first verified downloadable recipe uses `.tar.gz` / `.tgz` as expected, and update third-party notices.
3. Create the model verification worksheet early and externally verify at least one candidate recipe, preferably US English, enough to design the bundled recipe metadata. After the installer/validator exists, re-run that same recipe through Chat4J end-to-end before considering the feature complete unless the user explicitly accepts import-only behavior.
4. Add `SettingsKeys.STT_PROVIDER_SPHINX4` and Sphinx4 selected root/fingerprint keys.
5. Add Sphinx4 provider skeleton and recognizer adapter.
6. Add Sphinx4 model metadata, artifact/recipe/catalog entry types, bundled catalog loader, and catalog resource.
7. Add scanner/validator and mandatory recognizer-construction validation.
8. Add multi-artifact installer with safe archive handling and SourceForge redirect policy.
9. Add model management service/snapshot/local row types.
10. Wire settings resolution and selected-model validation.
11. Wire `SpeechToTextService` pre-capture validation, factory constructors, and provider-context temp-directory plumbing.
12. Wire `MainFrame` and `SettingsDialog` lifecycle ownership/sharing/close paths.
13. Generalize/branch `SpeechToTextPanel` local model handling for Vosk and Sphinx4 across constructors, listeners, saving, refresh, selection, directory blocking, table rows, buttons, import/download/delete, and helper text.
14. Add documentation and third-party notices.
15. Add tests.
16. Run validation and parent-orchestrated review loop.

## Open Verification Work

Before implementation marks any language one-click downloadable, create/update a verification worksheet with one row per complete recipe. Multi-artifact recipes should list artifact details either in separate rows or in an attached JSON block. The table can start with all rows as `catalog-only`, but the final implementation must update at least one recipe to `downloadable/verified` and prove it through the Chat4J installer/validator path unless the user explicitly accepts import-only behavior.

| Language | Recipe ID | Artifact(s) | URL(s) | Size(s) | SHA-256(s) | Acoustic Path | Dictionary Path | LM Path | Sample Rate | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| US English | cmusphinx-en-us | sphinx4-5prealpha-source-data | https://sourceforge.net/projects/cmusphinx/files/sphinx4/5prealpha/sphinx4-5prealpha-src.zip/download | 41,256,644 bytes | 6c17507925b379e51cd8af904dcc77a088f1fa74b41b772bdf4e2a370be2d9a9 | sphinx4-data/src/main/resources/edu/cmu/sphinx/models/en-us/en-us | sphinx4-data/src/main/resources/edu/cmu/sphinx/models/en-us/cmudict-en-us.dict | sphinx4-data/src/main/resources/edu/cmu/sphinx/models/en-us/en-us.lm.bin | 16000 | downloadable/verified |
| Indian English | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| Spanish | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| Mexican Spanish | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| Portuguese | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| Mandarin | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| Catalan | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| German | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| Greek | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| French | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| Dutch | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| Italian | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| Hindi | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| Kazakh | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |
| Russian | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD | catalog-only |

## Risks

- Sphinx4 Maven artifacts are old and coordinates may be inconsistent.
- Accidentally adding Sphinx4 data/model artifacts could bloat the shaded application jar and conflict with managed-model/download licensing expectations.
- Sphinx4 API path expectations may differ between artifact lines.
- SourceForge package layouts vary significantly by language.
- Some languages may require separate acoustic/dictionary/language model files rather than one complete package.
- TAR/GZIP support adds archive-security complexity and may require another dependency.
- Java Sound conversion may not support every required sample-rate conversion on every runtime.
- Sphinx4 recognition cancellation may not be immediately interruptible while inside blocking recognizer calls.
- Recognition quality may be poor for open-ended dictation compared with Vosk/cloud providers.
- UI complexity may grow if local provider handling is not carefully contained.
