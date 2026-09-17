# Milestone 4C — optional question-crop verification

The deterministic 4B detector is unchanged. Verification is a separate, optional post-processing step in the temporary detection/review session. It is off by default and available only in local debug builds with a configured key. Nothing is saved or accepted automatically.

## Components

- `core/verification/QuestionCropVerifier.kt`: provider-independent whole-page input, proposals, operations and atomic validation/application.
- `core/verification/VerificationQueue.kt`: single-worker session queue and metadata cache.
- `platform/GeminiQuestionCropVerifier.kt`: development configuration, REST transport, prompt, JSON Schema, strict operation JSON decoding.
- `platform/VerifierPageRenderer.kt`: full upright PDF page, aspect preserved, 2048-pixel longest edge, JPEG quality 90; bitmap and PDF handle released after encoding. No whole PDF, notebook URI, title, saved answers or ink are sent.
- `ui/QuestionDetectionSession.kt` and `QuestionDetectionScreen.kt`: optional toggle/status, Verify page, Restore detector crops, stale-result protection and existing manual acceptance/save flow.
- `app/build.gradle.kts` and `app/src/debug/AndroidManifest.xml`: debug credentials and debug-only Internet permission. No SDK dependency added. Release key is empty and release Internet permission remains removed.
- `app/src/test/java/com/xnotes/core/QuestionCropVerifierTest.kt`: offline fake-provider and protocol regression tests.
- `app/src/test/java/com/xnotes/ui/QuestionDetectionSessionTest.kt`: integration coverage for temporary AI corrections, restore and manual-gesture protection.

## Local configuration

Set `GEMINI_API_KEY` in the gitignored root `local.properties`, an environment variable, or a Gradle property. Precedence: Gradle property, environment, local.properties. Rebuild the debug APK after changing it. Blank/missing keys make verification unavailable; normal detection/manual review still work.

`GEMINI_MODEL` uses the same configuration path; default `gemini-2.5-flash`. Model names must contain only letters, digits, dots, underscores or hyphens. Model and prompt/render version form the cache configuration identity. Configuration is separate from Question Mode and the detector.

A debug APK contains its configured key; keep that APK private. Release builds never receive the key. Keys and provider error bodies are never logged or shown. Prefer local.properties/environment over command-line key arguments that can enter shell history.

## Request / structured response

POST to `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` with `x-goog-api-key` in the header; redirects are disabled. The request contains a complete page image in `inlineData`, stable proposal IDs and full-page normalized coordinates (top-left origin). No PDF upload or Files API is used.

The system prompt asks for minimal repairs, preferring KEEP. Each crop must contain exactly one complete question including images, diagrams, tables and choices; teaching sidebars, examples, answer keys and navigation are excluded. Page content is treated as document data, never instructions.

`generationConfig` requests `application/json` and `responseJsonSchema`. The object has an `operations` array, capped at 256 entries. Each entry has action KEEP, ADJUST, DELETE or ADD. Existing IDs are required for the first three; ADD has no ID. ADJUST/ADD require numeric left/top/right/bottom. Omitted proposals are kept. Gemini must finish with STOP; truncated/blocked/prose responses are not applied.

Local validation rejects unknown/repeated IDs, unexpected fields, duplicate ADD rectangles, nonnumeric or nonfinite coordinates, inverted rectangles and crops smaller than .005 page width/height after clamping to [0,1]. Strict JSON validation rejects duplicate keys, nonstandard JSON and trailing prose. Validation finishes before any mutation: one invalid operation rejects the whole patch. Added IDs are generated locally, and adjusted/added crops are unaccepted. The UI clears acceptance on all applied AI results, including explicit reverification.

The AI layer intentionally uses page bounds rather than forcing corrected crops into the detector's inferred lanes: a repair may need to reunite a wide question split by those lanes. The deterministic detector's existing column clamps are untouched.

Reference: [Gemini structured output](https://ai.google.dev/gemini-api/docs/generate-content/structured-output) and [image input](https://ai.google.dev/gemini-api/docs/image-understanding).

## Queue / cache / lifecycle

Only scanned pages in the window N through N+9 are eligible. Concurrency is one; images are rendered on demand. Navigating replaces pending work with the latest window, ordered nearest first. An already-started request may finish before the next starts, avoiding overlapping network calls. Completed off-window responses can be cached without applying them until the page is revisited.

Cache keys contain PDF identity, page, an immutable full proposal revision and model/config version. Both input and applied-output revisions are recorded so an applied repair cannot trigger another request. Cache entries contain only proposal metadata, not images. The cache is detection-session-only, discarded on rescan/close. Failures are not automatically retried; Verify page permits a deliberate retry. Successful cache hits do not call Gemini again.

Entering edit mode, editing, adding, accepting or rejecting protects that page from automatic verification and invalidates in-flight application. Verify page explicitly opts that page back in. Restore detector crops restores the original unaccepted proposals and protects the page. Closing, rescanning or saving stops queue work; disabling AI stops new requests and invalidates in-flight application. Existing saved Question Mode data and format are unchanged.

Network reads/connects have timeouts, a connection watchdog bounds blocked writes, and the queue has a 60-second timeout. The adapter serializes requests across queue replacement too. HTTP errors, rate limits, timeouts, render failures and malformed responses retain current proposals with a safe status. There is no automatic retry/backoff loop.

## Validation

Offline tests cover actions, stable IDs, clamping and malformed rectangles, unknown/duplicate operations, strict JSON and truncated responses, absent keys, failure fallback, successful cache reuse, proposal revision changes, ten-page prefetch, rapid navigation, late manual-edit/reject responses, disabled/closed sessions and timeouts. Live tests are not part of the unit suite.

Manual opt-in smoke test: configure a local key, build/install debug, scan a small range, enable AI and review a known split or missed question. Check page-image quality and actual crop completeness, navigate backward to verify no repeat request, edit while pending, disable while pending, restore detector crops, and accept/save only after reviewing. A real key/device test is required to validate service availability, quota/model access and crop quality; offline tests do not establish those.

Validation completed September 17, 2026: `test assembleDebug` passed, with 1,187 unit tests in each of debug and release (zero failures/errors/skips). This includes 17 new verifier tests and two new session integration tests. `git diff --check` passed. All five frozen deterministic source hashes remain unchanged. Merged manifests confirm debug Internet permission and no release Internet permission. Both generated builds currently have no configured key; no live Gemini request/device verification was performed.

## Temporary HTTP 400 diagnostics

Debug builds now read at most 64 KiB of the provider error body and display only error code, status/type and a sanitized message in the existing AI status. Release builds retain generic HTTP messages and do not read error bodies. Known key/image values, credential patterns, long encoded tokens and echoed JSON objects are redacted; raw bodies/details are never displayed or logged. Messages are capped at 1,500 characters. Malformed/oversized errors fall back to a generic status.

The request payload remains unchanged pending an actual provider error. The current official generateContent structured-output guide uses `generationConfig.responseFormat.text.{mimeType,schema}`. Our implementation still sends `responseMimeType` plus `responseJsonSchema` (not `responseSchema`). All schema keywords we use — type, enum, properties, required, additionalProperties, items, maxItems — appear in the documented supported subset. This comparison alone does not establish the cause of the device's HTTP 400. Reproduce with the diagnostic debug build and capture the sanitized AI status before changing request fields.

Two explicit live diagnostics using a synthetic PNG and the unchanged production request builder returned HTTP 400, code 400, status INVALID_ARGUMENT, message: "Request contains an invalid argument." The first used the currently configured `gemini-3.5-flash-lite`; the second used the requested `gemini-3.5-flash` via a test-only override. No workbook content was sent. This generic message does not identify a rejected field. Production payload/model configuration remains unchanged. `GeminiProviderDiagnosticTest` runs live only with `XNOTES_GEMINI_DIAGNOSTIC=1`; `XNOTES_GEMINI_DIAGNOSTIC_MODEL` optionally selects a test model. Never enable this flag for the normal suite. Gradle can cache tests, so use an explicit rerun when repeating the diagnostic.

### Debug response diagnostics

Failures now identify page render/load, network/provider, response parsing, or response validation. In debug builds, parsing/validation failures offer a selectable **Show AI response diagnostic** panel. It includes returned text and candidate finish reason, capped at 12,000 characters plus a truncation notice. Credentials, image payloads, and request-shaped echoes are redacted; diagnostics are not persisted or logged. Release builds do not retain/display response diagnostics. The current schema-free request format and local crop validation rules are unchanged.
