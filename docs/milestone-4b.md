# Milestone 4B: automatic on-device OCR

Automatic detection checks each page independently. PDFBox positioned text remains the fast path. Empty, sparse, corrupt/private-use, or geometrically collapsed text layers fall back to the bundled ML Kit Latin model (`com.google.mlkit:text-recognition:16.0.1`). Turkish printed Latin text is supported; no model download, account, server, API key, or network permission is needed. The application manifest removes network permissions contributed by SDK dependencies.

Both paths return upright normalized text boxes and the same raster occupancy layout to `QuestionLayoutDetector`. OCR words retain their individual boxes, avoiding block-level rectangles that can bridge columns. Number anchors (including spaced punctuation and `Soru 3`) initiate proposals. Options help flag cut boundaries but are not required. The shared detector uses text geometry and raster whitespace for column gutters, groups anchors per column, and seeks clean horizontal boundaries near the next anchor while preserving diagrams and writing space. Ambiguous continuations and gutter crossings remain review warnings.

Rendering is sequential: a 1400-pixel layout bitmap is reduced to an ink mask and recycled before the OCR bitmap is created. OCR uses up to 300 dpi, capped at 3500 pixels on the long edge (about 35 MB for an A4 ARGB bitmap; up to 49 MB for a square page). Each OCR bitmap is recycled before proceeding. One recognizer is reused for the job and closed with both PDF readers. Progress reports page count and extraction/render/recognition phases. Cancellation is checked during extraction and layout processing; ML Kit's uncancellable native request is allowed to finish before recycling its bitmap, then no further page is processed.

The review page displays `PDF text` or `OCR`. Proposal editing, acceptance, manual rectangles, duplicate checks and batch append retain the existing workflow and persisted schema. Answer-key recognition is not implemented.

## Validation

- `./gradlew test assembleDebug`
- `./gradlew connectedDebugAndroidTest`: generates image-only and text-backed Turkish two-column PDFs on the device, checks source selection, real OCR text/boxes, question counts, gutter isolation, and diagram inclusion.
- `git diff --check`

The generated fixture is a repeatable smoke test, not a benchmark of photographed, skewed, faded, or unusual question-bank pages. Review crops on actual books, especially shared passages and missing/unrecognized question numbers.

## Distribution

ML Kit adds a proprietary bundled model/native dependency and increases APK size. The repository's previous F-Droid/from-source distribution assumptions need revisiting before publishing this build through F-Droid.
