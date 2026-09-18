# Question Mode refinement

Question Mode uses the existing InfiniteEditor, InfiniteDocument, interaction, selection, history, and GL drawing pipeline. Pan remains unbounded. The crop is a fixed ImageItem in the normal image layer, excluded from editable document items; it contributes to Fit but never clamps canvas bounds. Its anchor uses original PDF page coordinates. Crop editing reuses PdfCropReview and changes the reference rectangle without moving ink or changing question identity.

The normal customizable xCanvas toolbar owns the top. Question navigation, progress, answers, peek, crop and confirmed deletion use ordinary ToolbarItem entries in a compact, scrolling bottom strip. Their ordering and visibility still use the existing xnote Preferences layout.

Page Peek now has only focused and faded states. A read-only normal PDF Editor opens every source page and jumps initially to the current question page. Normal PDF navigation browses all pages. The live question canvas, viewport, selection and history are retained; returning does not reload or alter ink.

The existing selected-folder SAF abstraction stores definitions/crops at `.xnote/questions/<set-id>.json`, progress at `<set-id>/state.json`, and CanvasCodec ink/view state at `<set-id>/questions/<question-id>/ink.xcanvas`. Temporary PDF renders stay private. No credentials are migrated. External-folder access must be granted again after reinstall; App storage remains app-private.

On first open, old notebook ink intersecting the crop is copied at unchanged page coordinates. Older standalone answer sheets are imported below the crop. The original notebook annotations remain intact because previous page ink cannot distinguish Question Mode ownership from ordinary annotations.

Confirmed deletion removes the question definition, workspace, old answer sheet, answer/progress and result/key metadata. A pending-deletion journal resumes interrupted cleanup on reopen. Folder backups and private migration copies are removed to prevent resurrection. The next question is selected, or the previous if deleting the last. The original PDF is unchanged.

No answer correctness is inferred. OCR, detector heuristics and AI verification are unchanged.

## Validation

`testDebugUnitTest assembleDebug` and `git diff --check` passed. Tests include unbounded ink round-trip, migration, deletion navigation, interrupted deletion recovery, folder backup/migration cleanup, crop identity and persistence. No device/stylus smoke test was performed. No commit or push was made.

## Files changed

- [app/src/main/java/com/xnotes/MainActivity.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/MainActivity.kt>)
- [app/src/main/java/com/xnotes/canvas/CanvasState.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/canvas/CanvasState.kt>)
- [app/src/main/java/com/xnotes/canvas/CanvasView.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/canvas/CanvasView.kt>)
- [app/src/main/java/com/xnotes/canvas/FrontInk.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/canvas/FrontInk.kt>)
- [app/src/main/java/com/xnotes/canvas/InteractionController.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/canvas/InteractionController.kt>)
- [app/src/main/java/com/xnotes/core/tools/ToolbarLayout.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/core/tools/ToolbarLayout.kt>)
- [app/src/main/java/com/xnotes/platform/QuestionFiles.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/platform/QuestionFiles.kt>)
- [app/src/main/java/com/xnotes/platform/QuestionProgressRepository.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/platform/QuestionProgressRepository.kt>)
- [app/src/main/java/com/xnotes/platform/QuestionSetRepository.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/platform/QuestionSetRepository.kt>)
- `app/src/main/java/com/xnotes/ui/AnswerCanvasPane.kt` — removed obsolete separate editor UI.
- [app/src/main/java/com/xnotes/ui/Editor.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/ui/Editor.kt>)
- [app/src/main/java/com/xnotes/ui/QuestionDetectionScreen.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/ui/QuestionDetectionScreen.kt>)
- [app/src/main/java/com/xnotes/ui/QuestionModeScreen.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/ui/QuestionModeScreen.kt>)
- [app/src/main/java/com/xnotes/ui/QuestionPageReview.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/ui/QuestionPageReview.kt>)
- [app/src/main/java/com/xnotes/ui/QuestionSession.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/ui/QuestionSession.kt>)
- [app/src/main/java/com/xnotes/ui/Toolbar.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/ui/Toolbar.kt>)
- [app/src/main/java/com/xnotes/ui/ToolbarCustomizer.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/main/java/com/xnotes/ui/ToolbarCustomizer.kt>)
- `app/src/main/java/com/xnotes/ui/ZoomableQuestion.kt` — removed obsolete separate editor UI.
- [app/src/test/java/com/xnotes/canvas/QuestionEditorViewportTest.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/test/java/com/xnotes/canvas/QuestionEditorViewportTest.kt>)
- [app/src/test/java/com/xnotes/core/tools/ToolbarLayoutTest.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/test/java/com/xnotes/core/tools/ToolbarLayoutTest.kt>)
- [app/src/test/java/com/xnotes/platform/QuestionEditorPersistenceTest.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/test/java/com/xnotes/platform/QuestionEditorPersistenceTest.kt>)
- [app/src/test/java/com/xnotes/platform/QuestionFolderFilesTest.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/test/java/com/xnotes/platform/QuestionFolderFilesTest.kt>)
- [app/src/test/java/com/xnotes/ui/QuestionEditorSessionTest.kt](<C:/Users/ismail/Desktop/Hopefully Codin'/Android Codin/xnotes-android/app/src/test/java/com/xnotes/ui/QuestionEditorSessionTest.kt>)
- `docs/question-mode-editor-integration.md` — this report.

Additional refinement files:
- `app/src/main/java/com/xnotes/core/model/QuestionCanvasMigration.kt`
- `app/src/main/java/com/xnotes/ui/QuestionCanvasWorkspace.kt`
- `app/src/main/java/com/xnotes/ui/InfiniteEditor.kt`
- `app/src/main/java/com/xnotes/ui/InfiniteToolbar.kt`
- `app/src/test/java/com/xnotes/core/model/QuestionCanvasMigrationTest.kt`
