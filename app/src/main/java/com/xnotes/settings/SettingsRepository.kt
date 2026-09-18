package com.xnotes.settings

import android.content.Context
import com.xnotes.platform.JsonStore

/** Loads/saves [Settings] via the atomic, failure-tolerant [JsonStore]. */
class SettingsRepository(context: Context) {
    private val app = context.applicationContext
    private val store = JsonStore.settings(app)

    private fun folder(settings: Settings): FolderSettingsStore? {
        val tree = settings.browseRoot ?: return null
        if (android.net.Uri.parse(tree).authority == app.packageName + ".documents") return null
        return FolderSettingsStore(com.xnotes.platform.FolderQuestionFiles(app, tree, null, ".xnote"))
    }

    fun load(): Settings = restoreFolder(Settings.fromJson(store.read()))

    /** Restore before publishing the current installation's settings into a newly granted folder. */
    fun restoreFolder(local: Settings): Settings = runCatching {
        folderIo.submit<Settings> { folder(local)?.restore(local) ?: local }.get()
    }.getOrDefault(local).also { store.write(it.toJson()) }

    fun save(settings: Settings) {
        store.write(settings.toJson())
        folderIo.execute {
            try { folder(settings)?.save(settings) }
            catch (_: Exception) { android.util.Log.w("Settings", "Folder settings could not be saved; local settings retained") }
        }
    }

    companion object {
        // Shared across split panes: older writes cannot overtake a restore or a newer edit.
        private val folderIo = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}

/**
 * The one live [Settings] for the process. A split view runs an editor per pane and both of them
 * read and write preferences, so they have to share a single copy: with a copy each, whichever pane
 * saved last would write its own stale view over the other pane's change.
 */
object LiveSettings {

    @Volatile private var value: Settings? = null

    /** The current settings, loaded through [repo] the first time anything asks for them. */
    fun get(repo: SettingsRepository): Settings = value ?: repo.load().also { value = it }

    fun set(settings: Settings) { value = settings }
}
