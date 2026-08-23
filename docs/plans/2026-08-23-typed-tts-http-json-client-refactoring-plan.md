# Typed HTTP/JSON boundary for remote TTS providers

Status: Proposed

## Objective

Refactor the remote text-to-speech providers so that provider code no longer:

- accesses Jackson's `ObjectMapper`, `JsonNode`, or `ObjectNode` directly;
- constructs JSON requests as mutable trees;
- parses provider responses through untyped tree traversal; or
- depends directly on the JDK `HttpClient`.

The refactor must preserve provider behavior, request contracts, safe error messages, credential handling, catalog fallbacks, and binary audio handling.

This plan covers the five HTTP-backed TTS providers: Deepgram, Groq, ElevenLabs, ListenHub, and Speechify. It does not attempt an application-wide Jackson or HTTP abstraction. Speech-to-text transport, provider authentication, persistence codecs, MCP, transcript callbacks, and the Windows SAPI JSON bridge have different contracts and should not be coupled to this refactor.

## Current structure and constraints

Observed mechanics:

- `TextToSpeechProviderRegistry.createDefault()` creates one `JavaNetTtsHttpTransport` and shares it across all five remote providers.
- `JavaNetTtsHttpTransport` is already the sole TTS owner of `java.net.http.HttpClient`, connect timeout, request timeout, and byte-array response handling.
- `TtsHttpTransport` is the existing deterministic test seam. Provider tests inject lambdas that capture `TtsHttpRequest` and return `TtsHttpResponse`.
- `AbstractHttpTextToSpeechProvider` currently combines credential behavior, HTTP success handling, JSON serialization, JSON parsing, and common error extraction.
- Every remote provider builds requests with `ObjectNode` and/or traverses responses with `JsonNode`.
- Synthesis responses are not uniformly JSON. Deepgram, Groq, ElevenLabs, and ListenHub can return binary audio; Speechify returns base64 MP3 in JSON.
- ListenHub can return an application-level JSON error in a nominal HTTP success or in place of expected audio.
- ElevenLabs currently accepts both a root model array and an object containing a `models` array.
- Catalog code distinguishes a missing or wrongly typed array from a present empty array. Missing data is invalid; an authoritative empty catalog uses bundled fallback entries.
- HTTP error reporting intentionally extracts only a small structured message and suppresses HTML, malformed JSON, credentials, headers, and complete response bodies.
- Synthesis already runs away from the Swing EDT through `TextToSpeechService`. This refactor must not change threading, cancellation, playback, or disposal behavior.

This is a type-safety and ownership refactor, not a response to a known provider outage. The implementation should therefore remain behavior-preserving and avoid adding retries, rate-limit handling, new response limits, or a new lifecycle framework.

## Design decision

Keep the existing low-level transport and add one TTS-specific typed client above it:

```text
Remote TTS provider
        |
        v
AbstractHttpTextToSpeechProvider
  - credentials
  - provider-labeled HTTP errors
  - typed helper methods
        |
        v
TtsHttpClient
  - request DTO serialization
  - response DTO deserialization
  - safe JSON error extraction
        |
        v
TtsHttpTransport
        |
        v
JavaNetTtsHttpTransport
  - JDK HttpClient
  - timeouts
  - byte transport
```

`TtsHttpClient` owns the only `ObjectMapper` used by remote TTS HTTP code. `JavaNetTtsHttpTransport` remains the only owner of the JDK `HttpClient`. Provider code receives typed records and domain objects rather than Jackson tree types.

The registry creates one immutable `TtsHttpClient` around the existing shared transport and passes that client to all five remote providers. `ObjectMapper` and `HttpClient` are safe to reuse concurrently after configuration, and neither requires shutdown.

### Why the client is TTS-specific

A global `HttpJsonClient` would create coupling without one coherent policy. Existing callers differ materially:

- STT uses asynchronous requests, cancellation, multipart bodies, and bounded response streams.
- Persistence mappers use file-specific read constraints and serialization settings.
- MCP supplies its own mapper contract.
- Authentication and capability probes have endpoint-specific redirects and credential flows.

The current common boundary is the five remote TTS providers. That gives `TtsHttpClient` multiple production consumers without imposing TTS assumptions on unrelated packages.

## Caller usage

The following sketches are proposed API usage, not compiled implementation.

### Registry composition

```java
TtsHttpTransport transport = new JavaNetTtsHttpTransport();
var httpClient = new TtsHttpClient(transport);

return new TextToSpeechProviderRegistry(List.of(
        SystemTextToSpeechProvider.createDefault(subprocessEnvironment),
        new DeepgramTextToSpeechProvider(httpClient, credentialResolver),
        new GroqTextToSpeechProvider(httpClient, credentialResolver),
        new ElevenLabsTextToSpeechProvider(httpClient, credentialResolver),
        new ListenHubTextToSpeechProvider(httpClient, credentialResolver),
        new SpeechifyTextToSpeechProvider(httpClient, credentialResolver)
));
```

No compatibility constructor that accepts `TtsHttpTransport` should remain after all in-repository callers move. Retaining both constructor paths would create duplicate composition rules with no external consumer.

### Typed catalog request

```java
SpeechifyApi.ModelsResponse response = getJson(
        URI.create("%s/audio/models".formatted(BASE_URL)),
        authHeaders(apiKey()),
        SpeechifyApi.ModelsResponse.class,
        "Speechify model catalog response was invalid."
);

List<SpeechifyApi.Model> models = response.models();
```

The provider still owns filtering, normalization, fallback selection, and mapping to `TextToSpeechCatalogItem`. The client owns only HTTP and JSON representation concerns.

### Typed JSON synthesis with binary response

```java
var body = new DeepgramApi.SynthesisRequest(request.text());
TtsHttpResponse response = postJson(uri, jsonHeaders(apiKey), body);
return new TextToSpeechAudio(wavBytes(response), "audio/wav", "wav");
```

The typed client serializes the request record. The provider retains binary content-type checks and audio conversion because those are provider/domain policies, not JSON transport policy.

### Typed JSON synthesis response

```java
var body = new SpeechifyApi.SynthesisRequest(
        request.text(),
        resolvedVoiceId,
        "mp3",
        resolvedModelId
);
SpeechifyApi.SynthesisResponse response = postJson(
        uri,
        jsonHeaders(apiKey),
        body,
        SpeechifyApi.SynthesisResponse.class,
        "Speechify TTS returned an invalid response."
);
return decodeSpeechifyAudio(response);
```

## Interface shape

### `TtsHttpClient`

Add `src/main/java/com/github/drafael/chat4j/tts/provider/TtsHttpClient.java` as a final, immutable class.

Proposed responsibilities:

- hold one private, fully configured `ObjectMapper`;
- hold the existing `TtsHttpTransport`;
- build `TtsHttpRequest` values for GET and JSON POST operations;
- serialize arbitrary request records to UTF-8 JSON bytes;
- deserialize JSON bytes into an explicit response class;
- provide a non-throwing typed decode operation for endpoints where a binary response may instead contain a JSON application error;
- extract the existing safe HTTP error detail without exposing Jackson types or raw body content;
- never log or include request headers, authorization values, or complete bodies in exceptions.

The public surface should stay small. Only construction must be public because provider constructors live in subpackages and tests create clients around fake transports. Request, decode, and error-detail operations can remain package-private when `AbstractHttpTextToSpeechProvider` is their only production caller. A reusable test-only helper in `src/test/java/com/github/drafael/chat4j/tts/provider` can call those package-private operations when provider tests need to decode captured request bytes into their package-local request record.

Conceptual signatures:

```java
public final class TtsHttpClient {
    public TtsHttpClient(TtsHttpTransport transport);

    TtsHttpResponse get(URI uri, Map<String, String> headers) throws Exception;

    TtsHttpResponse postJson(
            URI uri,
            Map<String, String> headers,
            Object requestBody
    ) throws Exception;

    <T> T readJson(
            byte[] body,
            Class<T> responseType,
            String invalidResponseMessage
    );

    <T> Optional<T> tryReadJson(byte[] body, Class<T> responseType);

    String safeErrorDetail(TtsHttpResponse response);
}
```

The exact overload set may be reduced during implementation if the protected base helpers can compose it without duplicate send/decode logic. Do not expose the `ObjectMapper`, `JsonNode`, Jackson `JavaType`, or `TypeReference` in this API.

`TtsHttpClient` should use a mapper dedicated to remote TTS wire DTOs. Do not reuse `TextToSpeechCatalogStore`'s mapper because that mapper owns persistence-specific constraints and a separate file format.

### `AbstractHttpTextToSpeechProvider`

Replace the protected static `OBJECT_MAPPER` and private transport field with one `TtsHttpClient` field.

Keep these responsibilities in the base class:

- credential availability and resolution;
- provider-labeled HTTP status failures;
- bundled catalog fallback;
- conversion of binary responses to `TextToSpeechAudio` where the behavior is common.

Provide protected final operations for current provider call patterns:

```java
protected final <T> T getJson(
        URI uri,
        Map<String, String> headers,
        Class<T> responseType,
        String invalidResponseMessage
) throws Exception;

protected final TtsHttpResponse postJson(
        URI uri,
        Map<String, String> headers,
        Object requestBody
) throws Exception;

protected final <T> T postJson(
        URI uri,
        Map<String, String> headers,
        Object requestBody,
        Class<T> responseType,
        String invalidResponseMessage
) throws Exception;

protected final <T> Optional<T> tryJson(
        byte[] body,
        Class<T> responseType
);
```

Each send operation must check HTTP success before decoding a normal response. The base class continues to format the existing message:

```text
<Provider> TTS request failed with HTTP <status>[: <safe detail>]
```

Jackson exceptions must not escape to the UI. Typed decode failures become the endpoint-specific stable message supplied by the provider.

## Wire DTO ownership

Define package-private provider wire models next to each provider. Prefer one package-private container per provider, with nested records, rather than many public DTO classes. This keeps external wire schemas at their owning boundary and avoids making them application-wide contracts.

All response records should use `@JsonIgnoreProperties(ignoreUnknown = true)` so additive provider fields do not break Chat4J. Use `@JsonProperty` only when the JSON name differs from the Java component name. Do not put credentials in any DTO.

Do not normalize a missing list to an empty list in a record constructor. Providers currently distinguish:

- missing or wrong-shaped catalog arrays: invalid response;
- present, empty catalog arrays: valid response with bundled fallback.

Keep that distinction visible as `null` versus an empty `List` until the provider validates the response.

### Speechify wire records

Add `SpeechifyApi` in the Speechify provider package with records equivalent to:

- `ModelsResponse(List<Model> models)`;
- `Model(String id, String name, String description)`;
- `VoicesResponse(List<Voice> voices)`;
- `Voice(String id, String displayName, String locale, String gender, String type)`;
- `SynthesisRequest(String input, String voiceId, String audioFormat, String model)`;
- `SynthesisResponse(String audioData, String audioFormat)`.

Map `displayName`, `voiceId`, `audioFormat`, and `audioData` with `@JsonProperty`. Ignore `dialogue_models`, cursor metadata, billable-character counts, and speech marks because no current caller uses them.

### Deepgram wire records

Add `DeepgramApi` with records for:

- the root model catalog and its `tts` list;
- model `canonicalName`, `name`, and `metadata`;
- metadata `tags`;
- synthesis request `text`.

Provider code retains model-family derivation, model-ID validation, description formatting, PCM validation, and WAV wrapping.

### Groq wire records

Add `GroqApi` with records for:

- model catalog `data`;
- model `id`;
- synthesis request `model`, `voice`, `input`, and `responseFormat`.

Provider code retains current model and voice filtering and binary audio handling.

### ElevenLabs wire records

Add `ElevenLabsApi` with records for:

- models and model capabilities;
- voices and voice metadata;
- synthesis request `text` and `modelId`.

Preserve support for both accepted model-catalog roots: a JSON array and an object containing `models`. Localize that compatibility rule inside the ElevenLabs wire adapter. A small Jackson deserializer may use the streaming parser internally, but it must return the typed `ModelsResponse`; neither the provider nor its callers should receive a `JsonNode`.

Provider code retains the `can_do_text_to_speech` filter and fallback ID/label rules.

### ListenHub wire records

Add `ListenHubApi` with records for:

- the application envelope `code`, `message`, and `data`;
- voice data `items`;
- voice `speakerId`, `name`, and nested profile description;
- synthesis request `input`, `voice`, and `responseFormat`;
- the application error subset needed when expected audio is JSON instead.

Keep application-level `code == 0` validation and message formatting in `ListenHubTextToSpeechProvider`. A missing or non-integral code remains invalid. If Jackson's default scalar coercion would accept a string code, use a field-specific strict deserializer for this component rather than globally changing mapper behavior for every provider.

### Common HTTP error DTO

Move common error extraction into `TtsHttpClient` using a private typed representation that supports the current shapes:

- `error.message`;
- `detail.message`;
- top-level `message`;
- string-valued `detail`.

A small internal deserializer may normalize a string-or-object field to a message. The detail precedence and HTML/malformed-body suppression must remain unchanged. Do not expose a general JSON tree to handle these four known forms.

## Request and response flow

A representative Speechify synthesis request should flow as follows:

1. `TextToSpeechService` invokes the selected provider on its existing synthesis worker thread.
2. `SpeechifyTextToSpeechProvider` resolves model and voice defaults and creates a `SpeechifyApi.SynthesisRequest`.
3. `AbstractHttpTextToSpeechProvider.postJson(...)` delegates serialization and transport to `TtsHttpClient`.
4. `TtsHttpClient` serializes the request record and sends a `TtsHttpRequest` through `TtsHttpTransport`.
5. `JavaNetTtsHttpTransport` performs the existing synchronous byte request with unchanged timeouts.
6. The base class rejects a non-2xx status using `TtsHttpClient.safeErrorDetail(...)`.
7. The client deserializes a successful body into `SpeechifyApi.SynthesisResponse`.
8. The provider validates MP3 format, decodes base64, rejects empty audio, and returns `TextToSpeechAudio`.

No mutable state is added. One `TtsHttpClient`, its configured mapper, and the existing transport are shared safely by provider instances.

## Invariants

The implementation must preserve these invariants:

1. Provider classes import no `ObjectMapper`, `JsonNode`, or `ObjectNode`.
2. Only `JavaNetTtsHttpTransport` imports `java.net.http.HttpClient` within the remote TTS boundary.
3. `TtsHttpClient` is the only remote TTS class that owns an `ObjectMapper`.
4. DTOs contain wire data only. They do not resolve credentials, apply catalog fallback, select defaults, or produce domain audio.
5. Provider classes remain the owners of provider-specific validation and mapping to `TextToSpeechCatalogItem` or `TextToSpeechAudio`.
6. A non-2xx response is checked before normal response deserialization.
7. Error messages do not contain API keys, authorization headers, or raw response bodies.
8. Unknown response properties are tolerated, but missing required response structures remain invalid.
9. URLs, methods, headers, query parameters, request property names, default model/voice behavior, and audio formats remain unchanged.
10. No HTTP or JSON work moves onto the Swing EDT.

## Alternatives considered

### One application-wide HTTP/JSON service

Rejected. It would have to absorb incompatible cancellation, streaming, multipart, redirect, persistence, and parser-constraint policies. The resulting interface would expose many options or force unrelated callers into TTS behavior.

### Add a shared `ObjectMapper` but leave `JsonNode` in providers

Rejected. This removes mapper construction duplication but leaves wire schemas untyped and provider code coupled to Jackson tree traversal. It does not meet the requested boundary.

### Add a separate generic JSON codec and HTTP client

Rejected for this slice. A standalone codec would initially have one production consumer, `TtsHttpClient`, and mostly forward `ObjectMapper` methods. Keeping the mapper private inside the typed client gives a deeper module with less public surface. Extract a codec later only if a second current subsystem can share the same mapper policy.

### Refactor only Speechify

Rejected. Speechify is not the only current consumer of the shared raw JSON helpers. A Speechify-only wrapper would coexist with `AbstractHttpTextToSpeechProvider.OBJECT_MAPPER` and produce two TTS HTTP patterns. Migrating all five current remote providers makes the boundary consistent and justifies the shared client.

### Replace the transport with a third-party HTTP or REST framework

Rejected. The JDK client already satisfies the current synchronous byte transport contract and has deterministic injected-transport tests. A new dependency would add configuration and packaging risk without solving an observed transport failure.

## Implementation sequence

### 1. Pin current behavior with characterization tests

Before changing production code, add or strengthen tests for behavior that DTO binding could accidentally alter:

- common error-detail precedence and malformed/HTML suppression;
- missing catalog array versus present empty array;
- unknown response properties;
- invalid catalog item filtering and bundled fallback;
- each provider's request property names and defaults;
- Speechify JSON/base64 MP3 validation;
- ListenHub integral application codes and JSON errors returned instead of audio;
- ElevenLabs array-root and object-root model responses;
- binary content-type and audio validation for existing providers;
- absence of credentials from exception messages and transport `toString()` values.

Keep tests deterministic through `TtsHttpTransport`; do not add live provider calls.

### 2. Introduce and test `TtsHttpClient`

Add the typed client around the existing transport. Test:

- GET and POST request construction;
- record serialization to expected property names;
- typed response deserialization;
- unknown-property tolerance through annotated DTOs;
- stable invalid-response messages;
- non-throwing optional decode for binary-or-JSON responses;
- safe HTTP error extraction for every currently supported shape;
- malformed JSON and HTML suppression;
- no secret-bearing diagnostics.

Do not modify `JavaNetTtsHttpTransport` behavior except for constructor/type wiring required by the client.

### 3. Move shared provider helpers to the typed client

Update `AbstractHttpTextToSpeechProvider` to receive `TtsHttpClient`, add the protected typed operations, and remove its static mapper and Jackson imports. Preserve credential and HTTP status behavior exactly.

Update `TextToSpeechProviderRegistry.createDefault()` to create and share one client.

### 4. Migrate straightforward providers

Migrate Speechify, Groq, and Deepgram first:

1. add provider-local wire records;
2. replace `ObjectNode` construction with request records;
3. replace `JsonNode` traversal with response records;
4. retain existing domain validation and fallback code;
5. update provider tests to decode captured request bodies as the matching request record through the shared test-only typed JSON helper, not a raw mapper or tree.

Run focused tests after each provider so failures remain attributable.

### 5. Migrate compatibility-sensitive providers

Migrate ElevenLabs and ListenHub after the base path is stable:

- implement and test ElevenLabs dual-root model decoding;
- implement and test ListenHub application-envelope and binary-or-JSON handling;
- verify that existing error text and fallback decisions remain unchanged.

Do not generalize these endpoint-specific rules into the common client.

### 6. Remove obsolete raw JSON paths

After all providers use records:

- remove `jsonBody(...)` and the protected static mapper from `AbstractHttpTextToSpeechProvider`;
- remove Jackson tree imports from all five remote provider classes;
- remove raw mapper/tree parsing from their tests;
- update all constructors and test fixtures to use `TtsHttpClient`;
- do not retain deprecated constructor overloads or duplicate transport/client composition.

Leave `TextToSpeechCatalogStore` and `WindowsSapiBackend` unchanged because they are outside the remote HTTP boundary and own different formats.

## Verification boundary

Run focused tests for the complete remote TTS boundary:

```bash
mvn -Dtest='TextToSpeechProviderTest,DeepgramTextToSpeechProviderTest,ListenHubTextToSpeechProviderTest,SpeechifyTextToSpeechProviderTest,TextToSpeechCatalogStoreTest,TextToSpeechSettingsTest,TextToSpeechPanelTest' test
```

Add the new `TtsHttpClientTest` to the focused set when implemented. Then run the complete Maven suite:

```bash
mvn test
```

Use a scope-specific source check to confirm the ownership boundary. Expected exceptions are `TtsHttpClient` for `ObjectMapper`, `JavaNetTtsHttpTransport` for `HttpClient`, and Jackson annotations in provider wire records:

```bash
rg -n '\b(ObjectMapper|JsonNode|ObjectNode|HttpClient)\b' \
  src/main/java/com/github/drafael/chat4j/tts/provider/AbstractHttpTextToSpeechProvider.java \
  src/main/java/com/github/drafael/chat4j/tts/provider/{deepgram,elevenlabs,groq,listenhub,speechify} \
  src/test/java/com/github/drafael/chat4j/tts
```

The refactor can establish deterministic request serialization, response mapping, validation, and error behavior. It cannot establish live provider schema compatibility, authentication, quota behavior, or real media synthesis without manual provider calls. Existing packaged/runtime behavior is otherwise unchanged because the transport and playback paths remain intact.

## Complexity checkpoint

Required new structure:

- one shared `TtsHttpClient` with five current production consumers;
- one package-private wire-model container per remote provider;
- an endpoint-local ElevenLabs root adapter and, if needed, strict ListenHub code deserializer;
- one focused client test, one reusable test-only typed JSON helper, and updates to existing provider tests.

The simpler alternative—creating records inside each provider while retaining direct mapper usage—would not establish the requested ownership boundary and would duplicate serialization/error policy. A broader application-wide client would add substantially more coupling than this concrete TTS refactor needs.

## Completion criteria

The refactor is complete when:

- all five remote provider classes construct typed request records and consume typed response records;
- no remote provider or its focused tests use `ObjectMapper`, `JsonNode`, or `ObjectNode` directly;
- no provider imports or constructs `HttpClient`;
- the registry shares one typed TTS client;
- transport injection remains deterministic for tests;
- all current catalog, synthesis, fallback, binary audio, and safe error behaviors pass focused and full-suite tests;
- no unrelated Jackson/HTTP subsystem is changed.
