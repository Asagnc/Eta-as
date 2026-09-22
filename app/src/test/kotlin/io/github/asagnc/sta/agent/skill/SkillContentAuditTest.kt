package io.github.asagnc.sta.agent.skill

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillContentAuditTest {
    @Test
    fun kindIsDecidedByFileExtension() {
        assertEquals(SkillContentAudit.Kind.SCRIPT, SkillContentAudit.kind("scripts/build.sh"))
        assertEquals(SkillContentAudit.Kind.SCRIPT, SkillContentAudit.kind("tools/CHECK.PY"))
        assertEquals(SkillContentAudit.Kind.BINARY, SkillContentAudit.kind("lib/native.so"))
        assertEquals(SkillContentAudit.Kind.BINARY, SkillContentAudit.kind("dist/pack.zip"))
        assertEquals(SkillContentAudit.Kind.DOCUMENT, SkillContentAudit.kind("references/guide.md"))
        assertEquals(SkillContentAudit.Kind.OTHER, SkillContentAudit.kind("scripts/Makefile"))
        assertEquals(SkillContentAudit.Kind.DOCUMENT, SkillContentAudit.kind("SKILL.md"))
    }

    @Test
    fun networkMarkersOnlyReportPresentCommands() {
        val text = "#!/bin/sh\ncurl -s https://example.com/data\n"
        val markers = SkillContentAudit.networkMarkers(text)
        assertTrue(markers.contains("curl "))
        assertTrue(markers.contains("https://"))
        assertFalse(markers.contains("wget "))
        assertTrue(SkillContentAudit.networkMarkers("echo hello").isEmpty())
    }

    @Test
    fun summaryStripsSkillRootAndCapsTheListedFiles() {
        val files = buildList {
            add(SkillContentAudit.FileEntry("skill-a/SKILL.md", 100))
            add(SkillContentAudit.FileEntry("skill-a/scripts/run.sh", 200))
            add(SkillContentAudit.FileEntry("skill-a/lib/native.so", 300))
            (0 until 10).forEach { index ->
                add(SkillContentAudit.FileEntry("skill-a/scripts/extra_$index.py", 10))
            }
            add(SkillContentAudit.FileEntry("other/readme.md", 50))
        }
        val summary = SkillContentAudit.summarize("skill-a", files)

        assertEquals(8, summary.scripts.size)
        assertTrue(summary.scripts.contains("scripts/run.sh"))
        assertFalse(summary.scripts.contains("scripts/extra_9.py"))
        assertEquals(listOf("lib/native.so"), summary.binaries)
        assertEquals(1, summary.documents)
        assertEquals(700, summary.totalBytes)
        assertTrue(summary.hasExecutableContent)
        assertTrue(summary.requiresConfirmation)
        assertTrue(summary.describe().contains("可执行脚本"))
    }

    @Test
    fun quietSkillNeedsNoConfirmation() {
        val summary = SkillContentAudit.summarize(
            root = ".",
            files = listOf(
                SkillContentAudit.FileEntry("SKILL.md", 10),
                SkillContentAudit.FileEntry("references/guide.md", 20),
            ),
        )
        assertFalse(summary.hasExecutableContent)
        assertFalse(summary.requiresConfirmation)
        assertEquals(false, summary.toJson().getBoolean("requires_confirmation"))
        assertEquals("未发现脚本或二进制", summary.describe())
    }

    @Test
    fun archiveAuditStripsTheGithubTopLevelDirectoryAndScansScripts() {
        val archive = File.createTempFile("skill-audit", ".zip")
        try {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.putEntry("repo-main/skill-a/SKILL.md", "# a")
                zip.putEntry("repo-main/skill-a/scripts/run.sh", "#!/bin/sh\nwget https://example.com/a\n")
                zip.putEntry("repo-main/skill-a/lib/native.so", "binary")
                zip.putEntry("repo-main/skill-b/SKILL.md", "# b")
            }
            val audit = SkillContentAudit.auditArchive(archive, listOf("skill-a", "skill-b"))

            val first = audit.getValue("skill-a")
            assertEquals(listOf("scripts/run.sh"), first.scripts)
            assertEquals(listOf("lib/native.so"), first.binaries)
            assertEquals(listOf("wget ", "https://"), first.networkMarkers.getValue("scripts/run.sh"))
            assertTrue(first.requiresConfirmation)

            val second = audit.getValue("skill-b")
            assertEquals(emptyList<String>(), second.scripts)
            assertFalse(second.requiresConfirmation)
        } finally {
            archive.delete()
        }
    }

    private fun ZipOutputStream.putEntry(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray())
        closeEntry()
    }
}
