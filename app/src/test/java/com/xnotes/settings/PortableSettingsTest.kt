package com.xnotes.settings

import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.platform.LocalQuestionFiles
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortableSettingsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun files() = LocalQuestionFiles(temp.newFolder())

    @Test fun reinstallRestoresBothToolbarsNormalPreferencesAndColorCount() {
        val files = files()
        val note = ToolbarLayout.fromRaw(listOf(listOf(ToolbarItem.QUESTION_PEEK.id to false,
            ToolbarItem.PEN.id to true)))
        val canvas = ToolbarLayout.fromRaw(listOf(listOf(ToolbarItem.PEN.id to false)),
            ToolbarLayout.CANVAS_ITEMS, ToolbarLayout.CANVAS_DEFAULT)
        val old = Settings(toolbarLayout = note, canvasToolbarLayout = canvas, toolbarColorCount = 3,
            prefs = Preferences(detectShapes = true, fingerDraws = false, uiAppearance = "dark", sideMargin = 35.0))
        FolderSettingsStore(files).save(old)
        val restored = FolderSettingsStore(files).restore(Settings(browseRoot = "new-grant"))
        assertEquals(note, restored.toolbarLayout)
        assertEquals(canvas, restored.canvasToolbarLayout)
        assertEquals(3, restored.toolbarColorCount)
        assertTrue(restored.prefs.detectShapes)
        assertEquals("dark", restored.prefs.uiAppearance)
        assertEquals(35.0, restored.prefs.sideMargin, 0.0)
        assertEquals("new-grant", restored.browseRoot)
        assertTrue(restored.fingerDrawAutoChecked)
        assertFalse(restored.prefs.fingerDraws)
    }

    @Test fun missingCorruptAndFutureFilesKeepLocalSettings() {
        val files = files()
        val store = FolderSettingsStore(files)
        val local = Settings(toolbarColorCount = 2)
        assertEquals(local, store.restore(local))
        for (text in listOf("{bad", "{}", "{\"version\":999,\"settings\":{}}")) {
            files.write("settings.json") { it.write(text.toByteArray()) }
            assertEquals(local, store.restore(local))
            assertThrows(Exception::class.java) { store.save(local) }
            assertEquals(text, files.read("settings.json")!!.decodeToString())
        }
    }

    @Test fun versionZeroMigratesThroughExistingSettingsDecoder() {
        val files = files()
        val old = JSONObject().put("version", 0).put("preferences",
            JSONObject().put("toolbar_color_count", 6).put("prefs", JSONObject().put("detect_shapes", true)))
        files.write("settings.json") { it.write(old.toString().toByteArray()) }
        val store = FolderSettingsStore(files)
        val result = store.restore(Settings())
        assertEquals(6, result.toolbarColorCount)
        assertTrue(result.prefs.detectShapes)
        store.save(result)
        assertEquals(1, JSONObject(files.read("settings.json")!!.decodeToString()).getInt("version"))
    }

    @Test fun credentialsPathsAndInstallationStateNeverExportOrRestore() {
        val local = Settings(browseRoot = "content://local-folder", fingerDrawAutoChecked = true,
            prefs = Preferences(pageTemplatePdf = "/private/template.pdf", codeThemePath = "/private/theme", codeThemeName = "theme"))
        val encoded = PortableSettings.encode(local)
        val text = encoded.toString()
        for (forbidden in listOf("browse_root", "finger_draw_auto_checked", "page_template_pdf", "code_theme_path", "code_theme_name", "/private/"))
            assertFalse(forbidden, forbidden in text)
        val data = encoded.getJSONObject("settings")
        data.put("gemini_api_key", "SECRET").put("credentials", JSONObject().put("token", "SECRET"))
        data.put("browse_root", "content://foreign-folder")
        data.getJSONObject("prefs").put("api_key", "SECRET").put("page_template_pdf", "/foreign/template")
        data.getJSONObject("tools").put("credentials", "SECRET")
        val restored = PortableSettings.restore(encoded, local)
        assertEquals(local.browseRoot, restored.browseRoot)
        assertEquals(local.prefs.pageTemplatePdf, restored.prefs.pageTemplatePdf)
        assertFalse(PortableSettings.encode(restored).toString().contains("SECRET"))
    }

    @Test fun clearingOptionalDefaultsSurvivesRestore() {
        val local = Settings(prefs = Preferences(startFullscreen = true, materialSeed = Preferences.DEFAULT_ACCENT))
        val restored = PortableSettings.restore(PortableSettings.encode(Settings()), local)
        assertNull(restored.prefs.startFullscreen)
        assertNull(restored.prefs.materialSeed)
    }
}
