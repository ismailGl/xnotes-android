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

## Milestone 4B.2: anchor validation and recovery

Real exported OCR/raster inputs for pages 12, 14, 16, 20, 22, 24, 25, 28 and 30 were replayed locally. The recurring top-right failure was missing punctuation, not missing OCR text: the printed question markers became bare `3`/`4`. Page 12's missing middle `2` had the same form. Page 14 contained the damaged marker `)5.` and an interior equation `1.2B-A`; page 30 contained a Roman statement misread as `1.` at an indented body position. Compact answer strips on pages 24/25 were narrower than the old 0.30-page-width footer condition.

`QuestionAnchorDetector` now validates candidates within the established column bounds. It learns the question-number lane from markers supported by nearby stems and separates it from the body-text lane. A regex match alone is insufficient: following content, margin proximity, block position and duplicate-row checks contribute to acceptance/rejection. Bare/damaged markers require supporting layout and are reported as recovered. No answer choices are required.

Recovery also examines separated content starts above the first recognized anchor and inside unusually large anchor gaps. Small raster components in the expected number lane support recovery even when the number has no OCR token. Populated first regions and substantial separated multi-line clusters provide additional evidence; a blank column does not produce an anchor. Equation-first questions can use subsequent explanatory text as support. Recovered proposals always carry a review warning. Confidence values are heuristic ranks, not calibrated probabilities.

Footer rejection now combines low y-position with repeated compact number/punctuation/letter structure, without requiring a full-width strip. It does not decode or persist answer associations. Legitimate lower-page questions remain eligible above a detected strip.

Diagnostics expose OCR/PDF text, visual recovered, and rejected sources, x/y, assigned column, heuristic confidence and reason. Existing gutter inference, hard column crop clamp, source selection, manual review and Question Mode persistence remain in place.

Replay results (page: proposal count): `12:7, 14:5, 16:6, 20:4, 22:4, 24:4, 25:3, 28:6, 30:4`. Tests verify the requested starts, rejection of interior/footer anchors and column containment. A second pass removes the OCR number tokens from those inputs while retaining the real raster; recovery must still find the requested starts.

The opt-in `exportAnchorReplayInputs` instrumentation diagnostic is retained because it provides reproducible inputs for scanner-specific failures. Invoke it directly with `am instrument` and `anchorPdf`, optionally `anchorPages`; do not invoke destructive Gradle device cleanup. Exports remain in device cache and ignored `app/build/anchor-replay/`. `QuestionAnchorReplayTest` runs when these files are available and is skipped otherwise; no private source pages are committed. Independent anonymized geometry regressions cover these failure structures in ordinary CI.


## Milestone 4B.3: page regions and roles

`QuestionPageRegions` runs before anchor selection. It identifies tall outer instructional panels from separated text geometry, instructional headings and separate numbered main content; either side is supported. It excludes contents pages with repeated leaders and page references. Uncertain content remains eligible for the existing question validator rather than being silently discarded. No publisher names, colours, logos or page identity enter classification.

Question-bearing areas are mapped into a local horizontal frame so the existing gutter/column detector can split the main area independently of a sidebar. The 4B.2 anchor selector is unchanged. Results return to page coordinates and are finally intersected with their assigned column bounds. Only a bounded binary raster grid is copied, sequentially; no full-resolution page bitmap is added or retained. Source selection and Question Mode persistence are unchanged.

Answer keys have a separate ANSWER_KEY role. Compact sequential number/choice entries, including fragmented tokens and multi-row grids, are excluded irrespective of page position. Prose and numbered diagram exits are not sufficient key evidence; nearby explanatory prose vetoes ambiguous label groups. Exclusion is local: preceding crops stop before a key they intersect, while questions below or beside it remain eligible. The former broad numbered-footer-row cutoff is removed. No answer associations are exposed as answers or saved to Question Mode.

Review diagnostics include region roles, boxes, confidence and reasons. Regression fixtures cover both sidebar orientations, two main question columns, one wide main column, notes inside genuine questions, contents pages, bottom/middle/dedicated-grid keys, nearby numbered diagrams/tables and legitimate short bottom questions. The original private first-book OCR/raster replay remains an additional regression check. These new fixtures model the second-book structure; they are not a fresh on-device OCR replay of that book.

Limitations: role scores are heuristic, and instructional panels whose headings are unreadable may remain unclassified. Mixed layouts without a tall outer sidebar, severe OCR corruption and ambiguous compact number/letter diagrams may still need manual review. Image-first anchor recall remains governed by the preserved 4B.2 selector. A region diagnostic is an exclusion overlay on the tentative question-bearing area, not a claim that every unclassified area contains questions.


### 4B.3 continuation: both real corpora replayed

Started from the clean `bfcf95b` implementation. Original-book exports remain in `app/build/anchor-replay`. The available tablet's second book was exported with the existing opt-in instrumentation method into a separate ignored local directory, `app/build/region-replay-second`. The installed test APK was stale and initially failed with a method mismatch; rebuilding and replacing only that test APK resolved the export. App data was not cleared. These are actual OCR/raster inputs, not screenshot OCR or synthetic replacements.

Repeated region-layer failures found in the second-book replay:

- Sidebar headings at the upper body edge were discarded by the region-only header cutoff, and overlapping OCR word boxes prevented joining some titles. The region stage now uses the normal body header limit and tolerates small word-box overlaps.
- Counting all tokens rewarded panel bounds that swallowed isolated main-question markers. Panel scoring now counts substantive text instead.
- Within a sidebar page, ordinary prose/table alignment sometimes became a second question column and placed its boundary past the real marker lane. The region layer now uses repeated numeric marker lanes for this nested split and passes those established regions to the existing detector as single columns. Wide question areas without a second repeated marker lane remain wide. The ordinary-page gutter algorithm and final per-column intersection remain intact.
- Contents OCR retained few dotted leaders, but retained aligned, increasing page references. Classification now combines that structural evidence with surviving leaders.
- Real answer strips contain missing numbers, lowercase choices and incomplete cells. Compact sequential groups tolerate limited fragments while still requiring number/choice evidence and rejecting nearby explanatory prose. Joined and fragmented OCR forms are both tested.

`QuestionAnchorDetector` is unchanged. No book/page-specific runtime rule was added. `QuestionRegionReplayTest` records regions, candidate decisions and crops for all 16 second-book pages, checks both sidebar orientations, one/two question columns, non-question exclusion, full-image start on page 18, and preservation of the lower numbered-diagram prompt on page 19. Ordinary CI uses independent geometry fixtures; private replay files are optional and untracked.

Second-book observed proposal counts (not runtime rules):

| PDF page | Proposals | Visible questions |
|---|---:|---:|
| 6 (contents) | 0 | 0 |
| 8 | 5 | 5 |
| 9 | 5 | 5 |
| 10 | 3 | 5 |
| 11 | 4 | 5 |
| 12 | 6 | 6 |
| 13 | 5 | 5 |
| 14 | 6 | 6 |
| 15 | 6 | 7 |
| 16 | 5 | 5 |
| 17 | 5 | 6 |
| 18 | 2 | 2 |
| 19 | 2 | 2 |
| 20 | 2 | 4 |
| 21 | 5 | 5 |
| 22 | 3 | 5 |

Counts alone do not establish perfect crop accuracy. All detected sidebar regions and answer strips on the replayed normal question pages are excluded from proposals. Pages 18/19 preserve the wide question area instead of splitting image/table text into artificial columns. Page 22 is no longer empty, but right-column recall is incomplete. Remaining misses recur on image/diagram-first starts: candidates reach the unchanged validator and can fail learned-margin or following-content checks. They remain a documented anchor-recall limitation rather than being bypassed by weaker validation in this region change. No final APK on-device UI retest was performed; validation used fresh on-device extraction and local detector replays.


### Follow-up: anchors inside classified question regions

Replayed the saved second-book OCR/raster inputs, including fresh device exports
for pages 23, 25 and 27. The repeated causes were horizontal tolerances changing
meaning after region normalization; immediate-prose validation rejecting printed
numbers before graphics; and interior option/measurement numbers competing with
real question margins. Question-lane diagnostics additionally showed interior
numeric lanes selected on pages 21, 23 and 25. Sidebar roles were correct.

Horizontal tolerances now retain their page-space meaning. Compact margin markers
can use a substantial raster block followed by later text as supporting evidence.
Inline prefixes and weak splits inside an active question face stricter validation.
Question-lane selection favors plausible outer start lanes over populous interior
numeric lanes. Sidebar classification, source selection, persistence and final
column clamps are preserved.

Actual raster coverage assertions check complete image/diagram/prompt/option
extents on pages 10, 11, 17, 18, 20, 21, 22, 23, 25 and 27, including page 18's
previously successful wide image question. These supplement proposal counts and
role/clamp assertions. Original-book replay remains a separate regression gate.
Private corpus inputs remain optional ignored build artifacts, so those replay
tests skip on machines without the exports; synthetic anchor tests always run.

Known remaining corpus limitation: page 23's answer strip contains an OCR duplicate
number (4 read as 1), and the existing key classifier rejects that inconsistent
sequence. The strip is still included at the bottom of the last crops. This
follow-up does not weaken answer-key validation to accommodate that ambiguity.


### Sequence-aware holdout evaluation

See [sequence and local-band validation](milestone-4b-sequence-validation.md) for the fixed development/validation split, pre/post anchor and crop metrics, and remaining failures. No detector tuning followed the validation replay.
