package com.xnotes.settings

import com.xnotes.platform.QuestionFiles
import org.json.JSONObject

/** Versioned projection of the existing settings model, never a copy of private configuration files. */
object PortableSettings {
    const val VERSION = 1
    private val fields = setOf("tools", "toolbar_colors", "toolbar_color_count", "toolbar_layout",
        "canvas_toolbar_layout", "start_on_home", "explorer_sort_key", "explorer_sort_descending",
        "new_note_style", "new_note_flow", "new_canvas_background", "view_defaults", "auto_import_pdfs")
    private val preferences = setOf("ui_appearance", "accent_color", "system_palette_style",
        "dark_palette_style", "light_palette_style", "oled_palette_style", "material_seed",
        "hide_window_decoration", "page_color", "default_page_size", "new_note_name_template",
        "default_page_orientation", "custom_page_width_mm", "custom_page_height_mm", "finger_draws",
        "zoom_lock_pan", "detect_shapes", "pen_button_tool", "pen_button_hover", "two_finger_tap",
        "three_finger_tap", "stylus_double_tap", "stylus_button_tap", "stylus_button_1_tap",
        "stylus_button_2_tap", "side_margin", "hide_page_borders", "min_zoom_enabled",
        "min_zoom_percent", "max_zoom_enabled", "max_zoom_percent", "canvas_min_zoom_percent",
        "canvas_max_zoom_percent", "start_fullscreen", "default_code_language")

    private fun select(source: JSONObject, keys: Set<String>) = JSONObject().apply {
        keys.forEach { key -> put(key, source.opt(key) ?: JSONObject.NULL) }
    }

    fun encode(settings: Settings): JSONObject {
        val source = settings.toJson()
        val values = select(source, fields).put("prefs", select(source.getJSONObject("prefs"), preferences))
        return JSONObject().put("version", VERSION).put("settings", values)
    }

    /** v0 used a `preferences` payload; v1 uses `settings`. Newer versions are never overwritten. */
    fun restore(root: JSONObject, local: Settings): Settings {
        val version = root.getInt("version")
        require(version in 0..VERSION) { "Unsupported settings version" }
        val incoming = root.getJSONObject(if (version == 0) "preferences" else "settings")
        val merged = local.toJson()
        fields.forEach { key -> if (incoming.has(key)) merged.put(key, incoming.get(key)) }
        val prefs = merged.getJSONObject("prefs")
        incoming.optJSONObject("prefs")?.let { values ->
            preferences.forEach { key -> if (values.has(key)) prefs.put(key, values.get(key)) }
        }
        // Decode through the normal model: foreign fields (including nested secrets) cannot enter it.
        return Settings.fromJson(merged)
    }
}

/** Same verified pending/previous publication protocol as Question Mode metadata. */
class FolderSettingsStore(private val files: QuestionFiles) {
    fun restore(local: Settings): Settings = try {
        val bytes = files.read("settings.json")
        if (bytes == null) { save(local); local }
        else PortableSettings.restore(JSONObject(bytes.decodeToString()), local).copy(fingerDrawAutoChecked = true)
    } catch (_: Exception) { local }

    fun save(settings: Settings) {
        // Refuse to replace corrupt or future-version data with this version's defaults.
        files.read("settings.json")?.let { PortableSettings.restore(JSONObject(it.decodeToString()), settings) }
        val bytes = PortableSettings.encode(settings).toString().toByteArray(Charsets.UTF_8)
        files.write("settings.json") { it.write(bytes) }
    }
}
