# Sequence and local reading-band validation

Development pages were fixed at **32, 37, 47** before exporting the other pages.
Validation pages were **31, 33–36, 38–46, 48–50** (17 pages). The supplied screenshots
had already exposed some validation failures; this is a held-out implementation
validation split, not a claim that every validation image was unseen by the author.
No detector changes were made after exporting/inspecting validation inputs.
`app/src/test/resources/question-sequence/frozen-source-sha256.json` records the
five production source hashes at that boundary.

## Diagnosis and changes

Roles were correct on the development pages. The failure was in selecting
question lanes *within* the question region: the first plausible interior numeric
lane could beat the actual question-number sequence. All-height lanes also clipped
a lower question spanning both upper columns. Anchor validation then compounded
these geometry errors by treating the wrong lane as its trusted number margin.

- Rank candidate lanes using explicit markers and ordered number transitions;
  allow gaps and sparse lanes with positioned stem evidence.
- Let a displaced, separated content-block start receive support from consecutive
  neighbours. Keep inline-prefix, content, image-first and footer validation.
- Select the supported anchor path with soft sequence costs. Duplicate/backward
  transitions cost evidence; explicit separated resets and unknown numbers remain
  possible. No numbering state carries between pages.
- Refine two question columns into upper columns plus a lower spanning band only
  with crossing prose, a substantial raster block and a small number-lane component.
  Missing OCR does not fabricate a numeric value. The recovered start is diagnosed
  as visual recovery. Horizontal and vertical crops remain inside their local band;
  the existing final horizontal column clamp still runs.

Sidebar/document/answer-key role classification is unchanged by this follow-up.
QUESTION lane ranking changed because the development diagnostics demonstrated an
incorrect lane, not because a publisher/page/color was recognized. The local band
rule is deliberately conservative and currently handles lower spanning transitions,
not arbitrary nested layouts or every possible number of bands.

## Measurement

Ground truth labels were manually read from the bounded raster and positioned OCR,
without using proposal counts or predicted crops as labels. Each annotation contains
printed marker x/y and required content right/bottom (including graphics and options).
These are approximate normalized content extents, not pixel-perfect segmentation masks.

Anchor recall uses one-to-one proposal matches within 0.025 page height of the true
start and containing its x position. Unmatched proposals are false anchors. A complete
crop must include the required content, start no later than 0.003 below the annotated
marker, and contain no other annotated question start. Thus a merged crop does not
pass merely because it includes the content. All proposals also pass the assigned
column clamp assertions. Extra blank margins/semantic OCR accuracy are not scored.

| Split | Metric | Before | Frozen change |
|---|---|---:|---:|
| Development (13 questions) | Real-anchor recall | 10/13 | 13/13 |
| Development | False anchors | 4 | 0 |
| Development | Complete unmerged crops | 3/13 | 13/13 |
| Validation (84 questions) | Real-anchor recall | 75/84 (89.3%) | 81/84 (96.4%) |
| Validation | False anchors | 9 | 3 |
| Validation | Complete unmerged crops | 57/84 (67.9%) | 76/84 (90.5%) |

The pre-change detector was replayed in an isolated test package from a local source
snapshot, without replacing production files. Temporary baseline test sources were
removed afterward. Saved inputs and detailed diagnostics are in ignored
`app/build/sequence-development` and `app/build/sequence-validation`. Labels and both
sets of metrics are retained in test resources; private raster/OCR exports are not
committed. Replays skip on machines lacking those private inputs. Synthetic tests
for sequences, resets, local bands and negative evidence always run.

## Validation pages

| Page | Real | Recall before → after | False before → after | Complete before → after |
|---|---:|---:|---:|---:|
| 31 | 4 | 3 → 3 | 0 → 0 | 2 → 2 |
| 33 | 3 | 3 → 3 | 3 → 3 | 0 → 0 |
| 34 | 6 | 6 → 6 | 0 → 0 | 6 → 6 |
| 35 | 6 | 6 → 6 | 0 → 0 | 6 → 6 |
| 36 | 5 | 5 → 5 | 0 → 0 | 5 → 5 |
| 38 | 4 | 3 → 4 | 2 → 0 | 0 → 4 |
| 39 | 3 | 3 → 3 | 0 → 0 | 0 → 3 |
| 40 | 6 | 6 → 6 | 0 → 0 | 6 → 6 |
| 41 | 5 | 5 → 5 | 0 → 0 | 5 → 5 |
| 42 | 5 | 5 → 5 | 0 → 0 | 4 → 5 |
| 43 | 5 | 5 → 5 | 0 → 0 | 5 → 5 |
| 44 | 6 | 5 → 6 | 1 → 0 | 2 → 6 |
| 45 | 6 | 3 → 6 | 2 → 0 | 0 → 6 |
| 46 | 4 | 1 → 2 | 1 → 0 | 0 → 1 |
| 48 | 5 | 5 → 5 | 0 → 0 | 5 → 5 |
| 49 | 6 | 6 → 6 | 0 → 0 | 6 → 6 |
| 50 | 5 | 5 → 5 | 0 → 0 | 5 → 5 |

## Remaining validation failures (not tuned in this run)

- **31:** OCR joins the question number and a subpart into `10. 1.`. The image-first
  marker validator rejects it; the preceding right question absorbs its content.
- **33:** A wide question area is still split by interior numeric/stem evidence.
  All three real starts are recalled, but three additional fragments remain and
  none of the three crops spans its complete question.
- **46:** A sparse graph-first question lacks the raster density required by the
  current graphic validator; another image-first question has no usable OCR number.
  Two starts are missed and the preceding right crop includes the later question.

No validation page became worse on the measured metrics. These failures remain
recorded as the next structural work; the validation set is now observed and should
not be described as untouched in a subsequent development cycle. Page 18 and the
original-book replay remain separate regression gates. The earlier page-23 OCR
answer-strip limitation also remains; no answer-key recognition was added.

## Final verification and changed files

`gradlew.bat test assembleDebug`: BUILD SUCCESSFUL. Debug and release each ran
1,168 unit tests, zero failures/errors/skips. Both saved earlier-book replays and
both new split replays executed. `git diff --check` passed. Frozen production
hashes still matched after validation. No commit, push, or APK installation was
performed by this follow-up; device instrumentation only exported replay inputs.
The build emitted an SDK XML version warning but completed successfully.

Production changes in this follow-up: `QuestionNumberSequence.kt` (new),
`QuestionReadingBands.kt` (new), `QuestionAnchorDetector.kt`,
`QuestionPageRegions.kt` (question-lane ranking only), `QuestionLayoutDetector.kt`.
Tests: `QuestionNumberSequenceTest.kt`, `QuestionReadingBandsTest.kt`,
`QuestionSequenceReplayTest.kt`, and `question-sequence` test resources.
Documentation: this report and `milestone-4b.md`. Earlier uncommitted 4B.3 changes,
including `QuestionAnchorRecoveryTest.kt`, `QuestionPageRegionsTest.kt` and
`QuestionRegionReplayTest.kt`, were retained.
