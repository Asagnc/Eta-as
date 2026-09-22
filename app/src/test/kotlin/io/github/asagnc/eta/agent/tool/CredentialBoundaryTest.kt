package io.github.asagnc.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialBoundaryTest {
    private val dataDir = "/data/data/io.github.asagnc.eta"

    @Test
    fun credentialFilesAndTheirDirectoriesAreDenied() {
        assertTrue(CredentialBoundary.denies(dataDir, "$dataDir/databases/eta.db"))
        assertTrue(CredentialBoundary.denies(dataDir, "$dataDir/databases/eta.db-wal"))
        assertTrue(CredentialBoundary.denies(dataDir, "$dataDir/databases"))
        assertTrue(CredentialBoundary.denies(dataDir, "$dataDir/databases/"))
        assertTrue(CredentialBoundary.denies(dataDir, "$dataDir/shared_prefs/anything.xml"))
        assertTrue(CredentialBoundary.denies(dataDir, "$dataDir/files/datastore/eta_settings.preferences_pb"))
        assertTrue(CredentialBoundary.denies(dataDir, "file://$dataDir/databases/eta.db"))
    }

    @Test
    fun unrelatedFilesStayReadable() {
        assertFalse(CredentialBoundary.denies(dataDir, "$dataDir/files/skills/capture/SKILL.md"))
        assertFalse(CredentialBoundary.denies(dataDir, "$dataDir/files/MEMORY.md"))
        assertFalse(CredentialBoundary.denies(dataDir, "$dataDir/cache/tmp.txt"))
        assertFalse(CredentialBoundary.denies(dataDir, "/data/local/tmp/eta/Eta-src/README.md"))
        assertFalse(CredentialBoundary.denies(dataDir, "/storage/emulated/0/Download/update/eta-release.apk"))
        assertFalse(CredentialBoundary.denies("", "$dataDir/databases/eta.db"))
    }

    @Test
    fun loosCheckStillCatchesRelativeTraversalToTheDatabase() {
        assertTrue(CredentialBoundary.deniesLoosely(dataDir, "$dataDir/files/../databases/eta.db"))
        assertFalse(CredentialBoundary.deniesLoosely(dataDir, "$dataDir/files/../files/MEMORY.md"))
    }
}
