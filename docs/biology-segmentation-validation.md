# Independent biology segmentation validation

## Implementation

Question Mode now composites a clipped, read-only view of source notebook items over its PDF crop background. It excludes the current question's linked projection because editable scratch ink is already drawn above the background. Source items never enter the scratch document/history or its persistence. Other questions' projected ink and normal notebook ink remain visible where they intersect the crop. Crop rendering is regenerated on entering/navigating/editing; viewport transforms remain in the existing infinite editor.

Gemini now receives the clean full page and compact detector hints labeled P1, P2, etc. No UUIDs are transmitted. Hints use the same artificial 0..1000 grid. The prompt requests independent segmentation, not proposal repair. A second annotated bitmap is not sent; the clean image stays authoritative and the short hint list avoids extra image rendering/memory in the existing queue.

The sole response is `{"questions":[[left,top,right,bottom],...]}`. Schema and parser require four integer values per box in 0..1000. Decimal, string, null, nonfinite, out-of-range, inverted, tiny, duplicate and malformed boxes are rejected atomically. Valid boxes are divided by 1000 locally and replace the page's temporary proposals with new unaccepted IDs. Existing detector backup, manual-change protection, ten-page queue and cached stable IDs are preserved. The deterministic detector is unchanged.

## Real-page method

Used PDF pages 8, 10, 12, 14 and 15 of the supplied biology book. SHA-256 of the local PDF matched the tablet's source PDF. Existing device instrumentation exported real OCR/raster inputs; the current unmodified detector was replayed from those inputs. No app reinstall, data clear, or notebook modification was performed. PDFium rendered clean full pages at 2048 pixels on the long edge, matching production image scale (desktop JPEG quality 92 versus Android quality 90).

Requests used the production Gemini verifier and schema, model gemini-3.5-flash-lite. Each request sent only its clean page image and short detector hint list. The private source images, OCR exports, detector/AI coordinates and visual comparisons remain under ignored `app/build/biology-verification/`.

First pass: four pages produced schema-valid rectangles, but visual comparison exposed recurring Y-first output despite X-first instructions; page 14 returned an inverted rectangle and was rejected. Its exact rejection was `Question box must have increasing edges and width/height of at least 5 grid units`. Its response was:

```json
{"questions":[[105,335,321,632],[105,653,923,648],[105,653,950,942],[768,653,923,942]]}
```

Added an explicit X-first versus Y-first warning, a top-right coordinate example, and a schema description specifying horizontal indices 0/2 and vertical indices 1/3. No local coordinate swapping or malformed-box repair was added. Repeated the same five pages once. All five returned STOP with integer-grid JSON and passed structural validation. This is not proof of crop correctness.

## Visual boundary comparison: final pass

| Page | Detector baseline | Independent AI output against visible content |
| --- | --- | --- |
| 8 | One large crop includes sidebar and both left-lane questions; right-lane crops start inside question-number margins. | Separates the first question body from the sidebar but clips its number. Top-right crop loses the stem and most diagram content. Another crop includes the sidebar and cuts through question 2. Question 4 body is mostly covered but its printed number is outside. Not a complete repair. |
| 10 | No proposals. | Recovers a complete first question. The top-right crop starts inside its diagram; a lower crop spans both questions 2 and 4, and another overlaps it while missing question 4's beginning. Partial recovery only. |
| 12 | Right-lane questions are covered; both left-lane starts are missed. | Cuts the beginnings of the upper questions, places a lower box in the sidebar, and extends the final right box through the answer strip/page bottom. Regresses correct baseline coverage. |
| 14 | Right-lane questions are covered; left-lane questions missed. | Recovers question 1, but divides the rest into horizontal bands spanning both lanes, splitting the table and question 3. Only the first returned crop is a complete individual question. |
| 15 | Questions 5 and 6 are complete. The other lane merges questions 7/8 with sidebar material, and question 9 includes sidebar material. | Merges upper parts of questions 5 and 7, splits question 5, clips question 8, selects sidebar regions, and misses questions 6 and 9. Worse than detector. |

Conclusion: the protocol now rejects decimals and out-of-grid values and allows independent replacement, but this model's visual localization remains unreliable. Successful HTTP/JSON/validation must not be advertised as reliable segmentation. Manual review and Restore detector crops remain necessary. No page-specific prompt, coordinate correction, or OCR threshold was introduced. No returned proposals were accepted or persisted as user questions by this diagnostic.

## Regression coverage

Tests cover source-to-question visibility and clipping without importing editable duplicates, active-projection exclusion, question-to-source clipping and persistence, strict integer wire format, atomic invalid-output rejection, empty final lists, review-required replacement, stable cached IDs and detector restoration. Rendering tests exercise the model/renderer contract; final on-device UI visual verification remains outstanding.
