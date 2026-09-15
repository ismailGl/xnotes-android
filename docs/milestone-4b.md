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

## Continuation-page layout regression

The supplied review screenshots show a different failure from missing OCR text: full-width rectangles alternate at left- and right-column question starts, and footer answer strips produce numerous extra candidates. The previous algorithm searched for an empty gap between every detected anchor x-position. Numbered footer rows filled that gap, so column inference failed before raster gutter scoring was even attempted. Global grouping then used anchors from the opposite column as crop boundaries. Sparse-anchor crops also retained large trailing blank areas.

The shared detector now excludes dense numbered footer rows before inference and finds repeated x-position modes instead of requiring all observations to leave a gap. Robust body-text edges and raster projection refine an inferred gutter, allowing long divider ink and occasional contamination. Candidates are assigned to a column before boundary selection; trailing whitespace is trimmed using text and raster content in that column. Repeated question margins also reject interior numbering. No page-parity or OCR/source-selection rule is used.

`QuestionLayoutDetector.analyze` returns column count, normalized gutter range, anchor boxes/column assignments/exclusion reasons, and original proposal rectangles. The review screen's **Layout diagnostics** toggle displays this snapshot as selectable text. It describes the original detection, not subsequent manual rectangle edits. No raster pages are retained in diagnostics.

Regression fixtures model the supplied screenshots' geometry (including a numbered footer spanning the gutter, a divider, center logo contamination, staggered anchors, interior numbering, and trailing blank areas). They are not raw OCR replays of the source PDF, which was not provided. Existing clean/uneven two-column, single-column, diagram and open-ended tests remain in place.

## Final column-boundary clamp and real page-13 replay

Crop bounds are now independent of the midpoint used for anchor assignment. A narrow long printed divider within the gutter defines the crop boundary where available; otherwise the right edge of the inferred gutter is used. A 0.002 normalized safety inset is applied on each side. Every final automatic proposal is intersected with its assigned `ColumnBounds` after all layout/padding decisions. Single-column bounds remain `[0, 1]`. Anchor and footer detection are unchanged by this boundary fix.

The optional device regression accepts `boundaryPdf` (relative to the app files directory) and `boundaryPage` (one-based) instrumentation arguments, allowing private cached PDFs to be replayed without adding them to the repository. On the actual cached PDF page 13, OCR returned a broad gutter search range `[0.364761905, 0.507619048]`; its former midpoint, `0.436190476`, explains the leftward intrusion shown in the screenshot. Printed-divider crop bounds were:

- Left column: `[0.000000000, 0.483714286]`; all three left proposals have that x-range.
- Right column: `[0.489619048, 1.000000000]`; all three right proposals have that x-range.

These values are a recorded device-test result, not hardcoded detector rules. Review diagnostics now show allowed column bounds alongside the search gutter and final proposal rectangles.

Device-test execution note: this environment's Gradle/UTP connected-test configuration sets `uninstall_after_test: true` for the debug app. Do not use `connectedDebugAndroidTest` on a device containing app-private work. Use a disposable test device, or install the test APK and invoke `am instrument` directly when replaying a live cached PDF. During the page-13 validation the first replay produced the ranges above; a later replay failed because UTP had removed the debug package/cache. The debug APK was reinstalled afterward. Unit tests and APK assembly do not perform this cleanup.
