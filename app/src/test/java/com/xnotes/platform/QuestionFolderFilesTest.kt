package com.xnotes.platform

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuestionFolderFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private class Provider : QuestionFolderDocuments {
        override val root = "root"
        val nodes = mutableMapOf(root to byteArrayOf())
        var failPublish = false
        var failWrite = false
        override fun child(parent: String, name: String) = "$parent/$name".takeIf { it in nodes }
        override fun create(parent: String, name: String, directory: Boolean) = "$parent/$name".also { nodes[it] = byteArrayOf() }
        override fun read(uri: String) = nodes.getValue(uri)
        override fun write(uri: String, bytes: ByteArray) { check(!failWrite); nodes[uri] = bytes.copyOf() }
        override fun rename(uri: String, name: String): String {
            check(!failPublish || ".pending-" !in uri)
            return "${uri.substringBeforeLast('/')}/$name".also { nodes[it] = nodes.remove(uri)!! }
        }
        override fun delete(uri: String) { nodes.keys.filter { it == uri || it.startsWith("$uri/") }.forEach(nodes::remove) }
    }

    @Test fun migratedDataSurvivesLossOfAppPrivateFilesAndDoesNotCopyCredentials() {
        val legacy = temp.newFolder()
        val local = LocalQuestionFiles(legacy)
        local.write("set/state.json") { it.write("progress".toByteArray()) }
        local.write("set/answers/q.xnote") { it.write("ink".toByteArray()) }
        local.write("api-key.json") { it.write("private".toByteArray()) }
        val provider = Provider()
        val first = FolderQuestionFiles(provider, legacy)
        assertEquals("progress", first.read("set/state.json")!!.decodeToString())
        assertEquals("ink", first.read("set/answers/q.xnote")!!.decodeToString())
        val reinstalled = FolderQuestionFiles(provider, temp.newFolder())
        assertEquals("progress", reinstalled.read("set/state.json")!!.decodeToString())
        assertEquals("ink", reinstalled.read("set/answers/q.xnote")!!.decodeToString())
        assertFalse(provider.nodes.keys.any { "api-key" in it })
        assertNotNull(reinstalled.documentUri("set/answers/q.xnote"))
    }

    @Test fun interruptedPublishRecoversPreviousGenerationAndRetryPublishesNewData() {
        val provider = Provider()
        val legacy = temp.newFolder()
        val files = FolderQuestionFiles(provider, legacy)
        files.write("set/state.json") { it.write("saved".toByteArray()) }
        provider.failPublish = true
        assertThrows(IllegalStateException::class.java) {
            files.write("set/state.json") { it.write("replacement".toByteArray()) }
        }
        val reopened = FolderQuestionFiles(provider, legacy)
        assertEquals("saved", reopened.read("set/state.json")!!.decodeToString())
        provider.failPublish = false
        reopened.write("set/state.json") { it.write("replacement".toByteArray()) }
        assertEquals("replacement", reopened.read("set/state.json")!!.decodeToString())
        assertFalse(provider.nodes.keys.any { ".pending-" in it })
    }

    @Test fun deletionRemovesFolderBackupsAndPrivateMigrationSource() {
        val provider = Provider()
        val legacy = temp.newFolder()
        val local = LocalQuestionFiles(legacy)
        val path = "set/questions/q/ink.xcanvas"
        local.write(path) { it.write(byteArrayOf(1)) }
        val files = FolderQuestionFiles(provider, legacy)
        assertNotNull(files.read(path))
        files.write(path) { it.write(byteArrayOf(2)) }
        files.delete("set/questions/q")
        assertNull(FolderQuestionFiles(provider, legacy).read(path))
        assertNull(local.read(path))
        assertFalse(provider.nodes.keys.any { "/questions/q/" in it })
    }

    @Test fun applicationSettingsUseMetadataRootAndRecoverInterruptedPublication() {
        val provider = Provider()
        val files = FolderQuestionFiles(provider, null, ".xnote")
        val store = com.xnotes.settings.FolderSettingsStore(files)
        store.save(com.xnotes.settings.Settings(toolbarColorCount = 2))
        assertTrue("root/.xnote/settings.json" in provider.nodes)
        assertFalse(provider.nodes.keys.any { "/questions/" in it })
        provider.failPublish = true
        assertThrows(IllegalStateException::class.java) {
            store.save(com.xnotes.settings.Settings(toolbarColorCount = 7))
        }
        provider.failPublish = false
        val restored = com.xnotes.settings.FolderSettingsStore(FolderQuestionFiles(provider, null, ".xnote"))
            .restore(com.xnotes.settings.Settings())
        assertEquals(2, restored.toolbarColorCount)
    }

    @Test fun unavailableFolderNeverFallsBackToPrivateWrites() {
        val provider = Provider()
        val legacy = temp.newFolder()
        val files = FolderQuestionFiles(provider, legacy)
        files.write("set/state.json") { it.write("saved".toByteArray()) }
        provider.failWrite = true
        assertThrows(IllegalStateException::class.java) {
            files.write("set/state.json") { it.write("unsaved".toByteArray()) }
        }
        assertEquals("saved", files.read("set/state.json")!!.decodeToString())
        assertNull(LocalQuestionFiles(legacy).read("set/state.json"))
    }
}
