# OpenAI In-Chat Image Generation Plan

## Goal

Add in-chat image generation for OpenAI GPT image models and normal OpenAI chat models, matching the existing Google/Gemini Nano Banana generated-image experience.

Generated images should flow through the existing `GeneratedImagePart` pipeline so storage, persistence, and transcript rendering continue to work without UI rewrites.

## Current Context

Chat4J already supports generated image display and persistence:

- `GeneratedImagePart` represents assistant-generated image output.
- `GeneratedImageAttachmentWriter` stores generated image bytes as attachments.
- `MessageHtmlRenderer`, transcript rendering, and conversation JSON persistence already handle generated images.
- `GoogleAiGenerateContentClient` already demonstrates the desired provider pattern: detect image-output model, call provider-native endpoint, decode base64 image bytes, store them, and emit `GeneratedImagePart` through `onPart`.

OpenAI currently uses `OpenAiChatCompletionClient` for OpenAI-compatible providers. It supports Chat Completions and some Responses API text/web-search paths, but it does not emit generated image parts.

## Recommended Architecture

Create a routing wrapper around the existing OpenAI chat client, tentatively named:

```text
OpenAiImageGenerationChatCompletionClient
```

This class should implement `ChatCompletionClient` and wrap/delegate to `OpenAiChatCompletionClient`.

Routing rules:

1. **Direct GPT image model selected**
   - Detect image models such as:
     - `gpt-image-1`
     - `gpt-image-1.5`
     - `gpt-image-2`
     - `gpt-image-1-mini`
     - `chatgpt-image-latest`
   - Use OpenAI Images API generation endpoint.
   - Extract `data[].b64_json`.
   - Write images with `GeneratedImageAttachmentWriter`.
   - Emit `GeneratedImagePart` via `onPart`.

2. **Normal OpenAI chat model selected and latest user message has image intent**
   - Use Responses API with hosted `image_generation` tool.
   - Build structured recent conversation input, not flattened text.
   - Include available text and image context.
   - Extract final `image_generation_call.result` values.
   - Emit generated images as `GeneratedImagePart`.
   - Continue emitting text deltas when present.

3. **No image route applies**
   - Delegate unchanged to the existing OpenAI chat completion client.

Wire this wrapper in `OpenAiCompatibleModule.selectChatClient()` for `OpenAI`. Consider `OpenAI Codex` only after confirming the OAuth token works with the chosen endpoint and does not break the existing Codex CLI flow.

## Direct GPT Image Model Flow

For selected GPT image models, keep v1 simple:

1. Find the latest user message.
2. Build the prompt from the latest user text/content projection.
3. Call Images API generation with:
   - `model = runtime.selectedModel()`
   - `prompt = latest user prompt`
   - default output format from API, usually PNG/base64
   - default size/quality unless future UI/settings are added
4. Decode returned `b64_json`.
5. Store using `GeneratedImageAttachmentWriter`.
6. Emit `GeneratedImagePart` through `onPart`.
7. Optionally emit a short text token such as `Generated image:` only if needed for transcript readability.

Do not implement direct image-model edits in v1. Add it as a documented follow-up using the Images Edits API.

## Normal Chat Model Image Tool Flow

When a normal OpenAI chat model receives image intent, use the Responses API with the `image_generation` tool.

Request shape should use structured input:

- system messages as system input/context where supported
- recent user/assistant text as `input_text`
- uploaded image attachments as `input_image` data URLs
- recent generated images as `input_image` data URLs when files still exist

Tool configuration:

```json
{
  "type": "image_generation",
  "action": "auto"
}
```

Use `action: auto` so the model can decide whether to generate a new image or edit an image already present in context.

For v1, include a bounded context window:

- system messages
- last 8-12 relevant messages
- last 1-3 available generated images
- user-uploaded image parts in recent context

If no image result is returned but text is returned, show the text response normally.

## Follow-Up Editing Behavior

Support editing previous/generated images through normal chat models in v1.

Examples:

- User: `Draw a tabby cat in a scarf.`
- Assistant: generated image
- User: `Make it watercolor.`
- Chat4J sends the previous generated image as `input_image` plus the new instruction.
- Responses API image tool chooses edit behavior and returns a new generated image.

If previous generated image files are missing, skip those image inputs and continue with text context. Missing local files should not fail the whole request.

## Image Intent Detection

Use a conservative latest-user-message heuristic to avoid accidental image generation and unintended costs.

Positive examples:

- `draw ...`
- `generate an image ...`
- `create an image ...`
- `make an image ...`
- `make a picture ...`
- `illustrate ...`
- `render an image ...`
- `edit this image ...`
- `modify this image ...`
- `replace the background ...`
- `turn this photo into ...`
- `make it watercolor/realistic/anime/etc.` when recent image context exists

### Intent Guardrails: Do Not Hijack Renderable Text/Diagram Requests

Image intent detection must not conflict with Chat4J's existing renderable source features.

Do **not** trigger OpenAI image generation when the latest user request is asking for code/text/markup representations such as:

- Mermaid diagrams:
  - `draw a mermaid diagram`
  - `create a Mermaid flowchart`
  - `render this mermaid`
  - fenced ```mermaid blocks
- LaTeX/math formulas:
  - `write LaTeX`
  - `render equation`
  - `derive formula`
  - `$...$`, `\[...\]`, or explicit TeX/LaTeX requests
- Chemical structures/formulas that should use text or SMILES:
  - `SMILES`
  - `chemical formula`
  - `molecule notation`
  - `draw the molecule as SMILES`
- Other diagram/source formats:
  - PlantUML
  - Graphviz/DOT
  - SVG code
  - HTML/CSS
  - ASCII diagram

Recommended implementation shape:

1. Normalize latest user text.
2. Check explicit non-image/renderable-source exclusions first.
3. Only then check positive image intent phrases.
4. If ambiguous, prefer normal chat response over image generation.

This keeps requests like “draw a Mermaid sequence diagram” routed to text/diagram rendering rather than OpenAI image generation.

## Error Handling

Handle errors consistently with existing provider behavior:

- No image data returned: `OpenAI returned no generated image.`
- Empty decoded bytes: `Generated image was empty.`
- Missing previous image file: log/skip, do not fail.
- Unsupported image-generation tool/model: if no image has been emitted, fall back to normal chat completion.
- Moderation block: show a concise user-safe error message, optionally logging technical details.
- Cancellation: cancel active stream/request and do not call completion callbacks after cancellation.

## Tests

Add unit tests for:

1. GPT image model detection.
2. Positive image intent detection.
3. Exclusion rules for Mermaid, LaTeX, SMILES/chemical formulas, Graphviz/DOT, SVG/code, and ASCII diagrams.
4. Direct Images API response extraction from `b64_json` into `GeneratedImagePart`.
5. Responses API `image_generation_call.result` extraction into `GeneratedImagePart`.
6. Previous `GeneratedImagePart` file converted to `input_image` data URL when present.
7. Missing previous generated image file skipped.
8. Non-image prompts delegate to existing chat client.
9. Unsupported tool/model failure falls back to normal chat when safe.

## Follow-Up Work

### Direct image-model edits

After v1 lands, add direct GPT image model editing through the Images Edits API:

- detect when selected model is `gpt-image-*` and latest request references an uploaded/generated image
- submit multipart image inputs to `/images/edits`
- support simple edits and reference-image workflows
- optionally support masks later

This is intentionally out of v1 because normal chat model + Responses `image_generation` tool provides the more natural multi-turn editing experience first.
