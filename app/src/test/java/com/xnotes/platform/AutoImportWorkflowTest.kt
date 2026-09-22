package com.xnotes.platform

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class AutoImportWorkflowTest {
    @get:Rule val temp = TemporaryFolder()
    private class Fake : AutoImportStorage {
        val listing = mutableMapOf<String, MutableList<AutoImportFile>>()
        val directories = mutableSetOf<String>()
        val usable = mutableSetOf<String>()
        var ledger = emptyList<AutoImportRecord>()
        var copies = 0
        var failCopy = false
        var failSave = false
        var interruptDuringCopy = false
        var inPlace = false
        val removedSources = mutableListOf<String>()
        val removedDestinations = mutableListOf<String>()
        fun dir(parent: String, name: String): String {
            val uri = "$parent/$name"
            directories += uri
            listing.getOrPut(parent) { mutableListOf() } += AutoImportFile(name, uri, 0, 0)
            return uri
        }
        fun pdf(parent: String, name: String, size: Long = 100, modified: Long = 10): AutoImportFile {
            val file = AutoImportFile(name, "$parent/$name", size, modified)
            listing.getOrPut(parent) { mutableListOf() } += file
            return file
        }
        override fun children(folder: String) = listing[folder].orEmpty()
        override fun isDirectory(file: AutoImportFile) = file.uri in directories
        override fun hash(file: AutoImportFile) = "digest-${file.size}"
        override fun copyPdf(file: AutoImportFile): String? {
            if (failCopy) return null
            copies++
            val destination = if (inPlace) file.uri else "note-$copies"
            usable += destination
            if (interruptDuringCopy) throw InterruptedException()
            return destination
        }
        override fun destinationUsable(uri: String) = uri in usable
        override fun destinationFullyUsable(uri: String) = uri in usable
        override fun deleteDestination(uri: String) { removedDestinations += uri; usable -= uri }
        override fun deleteSource(uri: String): Boolean { removedSources += uri; return true }
        override fun records() = ledger
        override fun saveRecords(records: List<AutoImportRecord>) {
            if (failSave) error("write failed")
            ledger = records
        }
        override fun stillSame(file: AutoImportFile, hash: String?) =
            listing.values.flatten().any { it.uri == file.uri && it.size == file.size && it.modified == file.modified } &&
                (hash == null || hash == hash(file))
    }

    @Test fun recursiveFolderMappingAndDuplicateNames() {
        val s = Fake()
        val t = s.dir("root", "Denemeler")
        val a = s.dir(t, "TYT")
        s.pdf(s.dir(a, "Fizik"), "book.pdf")
        s.pdf(s.dir(a, "Biyoloji"), "book.pdf")
        val paths = AutoImportWorkflow.collect(s, "root").map { it.path }
        assertEquals(listOf("Denemeler/TYT/Fizik/book.pdf", "Denemeler/TYT/Biyoloji/book.pdf"), paths)
        assertEquals(2, AutoImportWorkflow.scan(s, "tree", "root").imported.size)
        assertEquals(2, s.ledger.size)
    }

    @Test fun emptyAndIgnoredFoldersAndFiles() {
        val s = Fake()
        s.dir("root", "empty")
        s.pdf(s.dir("root", ".stfolder"), "hidden.pdf")
        s.pdf(s.dir("root", ".stversions"), "old.pdf")
        s.pdf(s.dir("root", ".stfolder.removed-99"), "old.pdf")
        s.pdf("root", "DO_NOT_DELETE.txt")
        s.pdf("root", ".temporary.pdf")
        s.pdf("root", "visible.PDF")
        assertEquals(listOf("visible.PDF"), AutoImportWorkflow.collect(s, "root").map { it.path })
    }

    @Test fun unchangedFileIsNotImportedTwice() {
        val s = Fake(); s.pdf("root", "a.pdf")
        assertEquals(1, AutoImportWorkflow.scan(s, "tree", "root").imported.size)
        assertTrue(AutoImportWorkflow.scan(s, "tree", "root").imported.isEmpty())
        assertEquals(1, s.copies)
    }

    @Test fun changedSourceGetsOneUpdatedCopyAndPreservesPreviousNote() {
        val s = Fake(); s.pdf("root", "a.pdf")
        AutoImportWorkflow.scan(s, "tree", "root")
        s.listing["root"]!![0] = s.listing["root"]!![0].copy(size = 120, modified = 11)
        val updated = AutoImportWorkflow.scan(s, "tree", "root")
        assertTrue(updated.imported.single().updated)
        assertEquals(2, s.copies)
        assertTrue(AutoImportWorkflow.scan(s, "tree", "root").imported.isEmpty())
        assertTrue(s.removedDestinations.isEmpty())
    }

    @Test fun failedImportIsNotRecordedAndRetries() {
        val s = Fake(); s.pdf("root", "a.pdf")
        s.failCopy = true
        assertEquals(listOf("a.pdf"), AutoImportWorkflow.scan(s, "tree", "root").failed)
        assertTrue(s.ledger.isEmpty())
        s.failCopy = false
        assertEquals(1, AutoImportWorkflow.scan(s, "tree", "root").imported.size)
    }

    @Test fun interruptedImportAndFailedLedgerWriteRecoverWithoutSuccessRecord() {
        val s = Fake(); s.pdf("root", "a.pdf")
        s.interruptDuringCopy = true
        assertEquals(1, AutoImportWorkflow.scan(s, "tree", "root").failed.size)
        assertTrue(s.ledger.isEmpty())
        s.interruptDuringCopy = false; s.failSave = true
        assertEquals(1, AutoImportWorkflow.scan(s, "tree", "root").failed.size)
        assertTrue(s.ledger.isEmpty())
        assertEquals(listOf("note-2"), s.removedDestinations)
        s.failSave = false
        assertEquals(1, AutoImportWorkflow.scan(s, "tree", "root").imported.size)
    }

    @Test fun deleteOnlySuccessfullyCopiedUnchangedOriginals() {
        val s = Fake(); val good = s.pdf("root", "a.pdf"); val bad = s.pdf("root", "b.pdf")
        val report = AutoImportWorkflow.scan(s, "tree", "root")
        s.listing["root"]!![1] = bad.copy(modified = 20)
        assertEquals(1, AutoImportWorkflow.deleteImportedOriginals(s, "tree", report.imported))
        assertEquals(listOf(good.uri), s.removedSources)
    }

    @Test fun noDeleteForInPlaceOrUnverifiedDestination() {
        val s = Fake(); s.inPlace = true; s.pdf("root", "a.pdf")
        val report = AutoImportWorkflow.scan(s, "tree", "root")
        // In-place sources never have a distinct copied destination and may not be removed.
        assertFalse(AutoImportWorkflow.canDeleteOriginals(report.imported))
        assertEquals(0, AutoImportWorkflow.deleteImportedOriginals(s, "tree", report.imported))
        assertTrue(s.removedSources.isEmpty())
    }

    @Test fun revokedPermissionIsReportedWithoutImporting() {
        val s = object : AutoImportStorage by Fake() {
            override fun children(folder: String): List<AutoImportFile> = throw SecurityException("revoked")
        }
        assertNotNull(AutoImportWorkflow.scan(s, "tree", "root").error)
    }

    @Test fun unknownModifiedTimeUsesHash() {
        val s = Fake(); s.pdf("root", "a.pdf", modified = 0)
        AutoImportWorkflow.scan(s, "tree", "root")
        assertEquals("digest-100", s.ledger.single().sha256)
    }

    @Test fun ledgerSurvivesFolderSaveAndReload() {
        val files = LocalQuestionFiles(temp.newFolder())
        val original = listOf(AutoImportRecord("content://source", "Denemeler/TYT/Fizik/book.pdf",
            1234, 9876, null, "content://destination/book.xnote"))
        files.write("auto_import.json") { it.write(AutoImportLedgerJson.encode(original)) }
        assertEquals(original, AutoImportLedgerJson.decode(files.read("auto_import.json")!!))
    }
}
