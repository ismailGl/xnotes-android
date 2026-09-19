# Semantic verification, 2026-09-20

This supersedes the coordinate-generating protocols in the earlier Milestone 4C notes. Gemini returns only `{"decisions":[{"action":"KEEP","target":"P1"}]}`. Actions are KEEP, DELETE, MERGE, SPLIT and MISSED. Proposal IDs name existing crops; candidate IDs name geometry computed locally before the request. Arbitrary provider coordinates are not accepted.

Local candidates use saved OCR, raster occupancy, question markers, option sets and whitespace. SPLIT selects complete local partitions, MERGE selects adjacent fragments, and MISSED selects substantial uncovered content. The request includes the clean page, labelled proposal images and separately rendered MISSED regions. Region images are rendered directly from the PDF at higher effective resolution. Every correction must remain inside existing QUESTION bounds and column clamps and avoid classified exclusions. The 4B detector is unchanged.

Conflicting, unknown, overlapping or excluded candidates are rejected with original crops retained. Diagnostics record original geometry, semantic decision, selected candidate/evidence, final rectangles and rejection reasons. Existing queue, ten-page prefetch, cache and restore behavior remain; the cache protocol version changes to avoid reusing old coordinate responses. Manual-edit protection and acceptance behavior remain: new AI crops require review, accepted KEEP crops retain their state.

## Biology pages 8–16

Nine live requests using `gemini-3.5-flash-lite` returned STOP and valid semantic decisions with the production request builder/schema. Inputs used actual Android OCR/raster exports and clean PDF-rendered images. The local resolver and strict patch validation applied the responses. This is a development corpus, not an independent holdout or a real-device UI rendering test.

Manual visual comparison counts a crop as complete only when it contains exactly one question, including its printed number, diagrams and all options, without instructional sidebar content. These are visual assessments, not a fully annotated automated benchmark.

| PDF page | Visible questions | Complete detector crops | Complete assisted crops | Remaining issue |
| --- | ---: | ---: | ---: | --- |
| 8 | 4 | 0 | 2 | Right crops omit printed numbers at existing clamp; left merged/sidebar crop split correctly |
| 9 | 4 | 4 | 4 | Correct crops preserved |
| 10 | 4 | 0 | 2 | Right questions recovered; QUESTION region clips left question markers |
| 11 | 4 | 2 | 2 | Two right crops still include sidebar; Gemini incorrectly kept them |
| 12 | 4 | 2 | 4 | Both missing left questions recovered with complete options |
| 13 | 4 | 3 | 4 | Missing upper-right question recovered |
| 14 | 4 | 2 | 4 | Both left questions recovered, including table/options |
| 15 | 5 | 2 | 2 | Upper-right merged questions and sidebar remain; Gemini incorrectly kept them |
| 16 | 4 | 4 | 4 | Correct crops preserved |
| Total | 37 | 19 | 28 | |

Sidebar-contaminated proposals decreased from five to four. No new fragments or non-question additions were observed in the final replay. This does not imply all verified crops are correct: pages 11 and 15 remain material failures. Existing region diagnostics fall back to a full-page QUESTION region on several biology pages, so semantic safeguards cannot exclude an unclassified sidebar geometrically. No publisher exceptions or detector changes were introduced to hide that limitation. Page 10's region clipping and page 8's clamp clipping likewise remain unchanged.

Private replay exports, provider responses and comparison images remain ignored under `app/build/biology-verification`; PDF content and credentials are not committed. The final live replay preceded a renderer-only minimum effective-resolution increase; local candidate geometry was frozen. The final renderer change is build/unit validated, not separately live-quality evaluated.

## Separate eraser correction

Question Mode erasing now maps gestures over the crop into normal source-page ink through a shared eraser session, clips interaction to the crop, and refreshes the composed background after erase, undo and redo. Source and scratch changes share one history command. Scratch remains authoritative for Question Mode ink and its derived source projection; this change does not enable editing locked Question Mode projections from normal mode. Eight focused tests cover source/scratch erasing, clipping, transformed coordinates and combined history. Real-device pointer interaction remains to be checked.

## Final checks

`gradlew.bat test assembleDebug` passed: 1,248 tests in each of debug and release, zero failures, errors or skips. The original-book anchor replay, second-book region replay and sequence development/validation replays ran successfully using the saved private exports. The debug APK built successfully. Both staged and unstaged `git diff --check` passed. No commit or push was performed.
