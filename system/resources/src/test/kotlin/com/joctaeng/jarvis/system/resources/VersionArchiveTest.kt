package com.joctaeng.jarvis.system.resources

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionArchiveTest {
    private fun dir(): File = kotlin.io.path.createTempDirectory("euno-versao").toFile()

    @Test fun firstOpenArchivesNothing() {
        val root = dir()
        File(root, "audit.jsonl").writeText("x")
        assertEquals(emptyList(), VersionArchive.archiveIfNewVersion(root, null, "0.32.0"))
        assertTrue(File(root, "audit.jsonl").exists())
    }

    @Test fun newVersionMovesCurrentItemsToArchive() {
        val root = dir()
        File(root, "audit.jsonl").writeText("antiga")
        File(root, "diagnostics").mkdirs()
        File(root, "diagnostics/poc.log").writeText("antiga")
        File(root, "conversas").mkdirs()
        File(root, "conversas/2026-10-14.txt").writeText("antiga")
        val moved = VersionArchive.archiveIfNewVersion(root, "0.31.0", "0.32.0")
        assertEquals(listOf("audit.jsonl", "diagnostics", "conversas"), moved)
        assertFalse(File(root, "audit.jsonl").exists())
        assertTrue(File(root, "arquivo/0.31.0/conversas/2026-10-14.txt").exists())
    }

    @Test fun sameVersionKeepsEverything() {
        val root = dir()
        File(root, "audit.jsonl").writeText("atual")
        assertEquals(emptyList(), VersionArchive.archiveIfNewVersion(root, "0.32.0", "0.32.0"))
        assertTrue(File(root, "audit.jsonl").exists())
    }
}
