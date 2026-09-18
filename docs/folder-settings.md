# Folder-backed application preferences

The existing SettingsRepository still owns local settings and LiveSettings still owns the shared live model. SettingsRepository restores the selected external folder at initial load, and Editor.updateBrowseRoot restores when the existing Folder picker grants a folder. Restored fields pass through Settings.fromJson and existing editor preference application. There is no new picker or preference UI. Restore takes precedence over local portable fields; fields absent from older data retain current values.

## Portable

- xnote (including Question Mode items) and xcanvas toolbar layouts, visible color count and palette
- persisted pen/eraser/tool and shape configurations
- appearance, palette, paper color, fullscreen preference
- input gestures, pen button assignments and shape assistance
- page size/orientation, naming, flow/page/canvas defaults
- global view defaults, zoom limits, margins and page borders
- start-on-home, explorer sorting and default code language

Question answer/feedback/review preferences remain notebook-specific in the existing `.xnote/questions/<set-id>/state.json`; this change does not duplicate them in global settings.

## Local only

Folder grants/URIs, PDF template paths and their template mode, imported code theme paths/names, recent/active color and tool/session state, sidebar visibility, render scale, cache resolution, front-buffer device tuning, installation capability flags and recent code language remain local. Gemini/API credentials are in separate stores and are never read by this projection. Unknown fields are never copied from local JSON.

## Format and failure behavior

`.xnote/settings.json` contains `{ "version": 1, "settings": { ... } }`. The payload is an explicit allowlist of the existing Settings JSON, with explicit nulls for resettable optional values. Version 0 (`preferences` payload) migrates through the existing model and is upgraded on the next successful save. Unknown fields of supported versions are ignored rather than blindly exported; additions to portable settings require a reviewed allowlist/schema change. Unsupported newer versions are not overwritten.

The existing FolderQuestionFiles SAF implementation now accepts a metadata-root parameter and an optional private migration source. Settings use `.xnote` and no private migration source, reusing verified pending-file writes, previous-generation recovery and rename publication. Question storage keeps its prior defaults unchanged.

Local/default preferences load first. A valid folder copy restores portable values and leaves local paths intact. Missing files are initialized from current settings. Corrupt/unreadable files leave current settings intact; corrupt/future versions are not automatically replaced. Saves continue locally on folder failures. Folder writes are serialized across split panes on an IO executor; restore waits for prior writes and is applied once, without a file watcher or reload loop. Re-selecting an external folder restores its configuration automatically, including after reinstall. App storage remains local and is excluded from folder settings synchronization.

Restoring an explicit finger-drawing preference marks the local one-time input auto-detection as handled, so first-run detection cannot overwrite the user's restored choice. The installation marker itself is not exported.

## Validation

Tests cover both toolbar profiles/Question Mode items, normal preferences, reinstall-style local loss, local path retention, secret exclusion (including unknown nested keys), missing/corrupt/future data, v0 migration, optional value reset, metadata location and interrupted SAF publication. Run `testDebugUnitTest assembleDebug` and `git diff --check`. No physical-device reinstall was performed.

Changed files for this milestone: SettingsRepository.kt, PortableSettings.kt (new), Preferences.kt (nullable fullscreen decoding), Editor.kt (apply restored settings), QuestionFiles.kt (shared metadata-root support), PortableSettingsTest.kt (new), QuestionFolderFilesTest.kt and this report. Notebook formats, OCR, detection and Gemini verification are unchanged. No commit or push.
