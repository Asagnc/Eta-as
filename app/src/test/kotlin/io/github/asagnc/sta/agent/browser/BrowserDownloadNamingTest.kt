package io.github.asagnc.sta.agent.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserDownloadNamingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `path separators and control characters are stripped`() {
        assertEquals("payload.bin", BrowserDownloadNaming.sanitizeFileName("/tmp/../../etc/payload.bin"))
        assertEquals("payload.bin", BrowserDownloadNaming.sanitizeFileName("..\\..\\payload.bin"))
        assertEquals("report.pdf", BrowserDownloadNaming.sanitizeFileName("report?.pdf"))
        assertEquals("report.pdf", BrowserDownloadNaming.sanitizeFileName("report\u0000.pdf"))
        assertEquals("download", BrowserDownloadNaming.sanitizeFileName("   "))
        assertEquals("download", BrowserDownloadNaming.sanitizeFileName(".."))
        assertEquals("download", BrowserDownloadNaming.sanitizeFileName(null))
        assertEquals("a".repeat(120), BrowserDownloadNaming.sanitizeFileName("a".repeat(300)))
    }

    @Test
    fun `existing files are not overwritten`() {
        val directory = temporaryFolder.newFolder("Sta")
        val first = BrowserDownloadNaming.uniqueFile(directory, "payload.bin")
        assertEquals("payload.bin", first.name)
        first.writeText("first")

        val second = BrowserDownloadNaming.uniqueFile(directory, "payload.bin")
        assertEquals("payload (1).bin", second.name)

        val third = BrowserDownloadNaming.uniqueFile(directory, "no-extension")
        assertEquals("no-extension", third.name)
        third.writeText("third")
        assertEquals("no-extension (1)", BrowserDownloadNaming.uniqueFile(directory, "no-extension").name)
    }

    @Test
    fun `content disposition prefers the extended file name`() {
        assertEquals(
            "中文报告.pdf",
            BrowserDownloadNaming.contentDispositionFileName(
                "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''%E4%B8%AD%E6%96%87%E6%8A%A5%E5%91%8A.pdf",
            ),
        )
        assertEquals(
            "report.pdf",
            BrowserDownloadNaming.contentDispositionFileName("attachment; filename=\"report.pdf\""),
        )
        assertEquals(
            "report.pdf",
            BrowserDownloadNaming.contentDispositionFileName("attachment; filename=report.pdf"),
        )
        assertNull(BrowserDownloadNaming.contentDispositionFileName("attachment"))
        assertNull(BrowserDownloadNaming.contentDispositionFileName(null))
    }

    @Test
    fun `url fallback drops query and fragment`() {
        assertEquals("file.zip", BrowserDownloadNaming.urlFileName("https://example.com/a/b/file.zip?x=1#frag"))
        assertEquals("file.zip", BrowserDownloadNaming.urlFileName("https://example.com/file.zip"))
        assertNull(BrowserDownloadNaming.urlFileName("https://example.com/"))
    }
}
