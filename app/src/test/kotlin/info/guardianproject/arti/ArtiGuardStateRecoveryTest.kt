package info.guardianproject.arti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

class ArtiGuardStateRecoveryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun guards(files: File) = File(files, "arti/state/state/guards.json")
    private fun backup(files: File) = File(files, "arti/state/state/guards.json.corrupt")

    private fun fixture(content: String): File = temporary.newFolder().also { files ->
        requireNotNull(guards(files).parentFile).mkdirs()
        guards(files).writeText(content, Charsets.UTF_8)
    }

    private fun diagnostic(directory: String) =
        "ERROR: Failed to initialize Arti: tor: corrupted data in persistent state: " +
            "Error setting up the guard manager\nCaused by: " +
            "JSON error while loading persistent data on state/guards.json in $directory/state\n" +
            "expected value at line 1 column 1"

    private fun failInitialization(files: File, recovery: ArtiGuardStateRecovery): Int =
        recovery.initialize(files) { directory ->
            recovery.onLogLine(diagnostic(directory))
            -3
        }

    @Test
    fun `corrupt guards are backed up and initialization retries without touching app data`() {
        val files = fixture("\u0000truncated synthetic guards")
        val original = guards(files).readBytes()
        val modified = guards(files).lastModified()
        val permissions = Files.getPosixFilePermissions(guards(files).toPath())
        val unrelated = listOf(
            "identity.key", "contacts.json", "messages.db", "preferences.xml",
            "arti/cache/directory", "arti/state/state/other.json", "arti/state/guards.json",
            "arti/state/state/state.lock"
        ).associateWith { name ->
            File(files, name).apply {
                requireNotNull(parentFile).mkdirs()
                writeText("synthetic $name", Charsets.UTF_8)
            }.readText(Charsets.UTF_8)
        }
        val recovery = ArtiGuardStateRecovery()
        var calls = 0

        val result = recovery.initialize(files) { directory ->
            calls++
            assertEquals(File(files, "arti").absolutePath, directory)
            if (guards(files).exists()) {
                recovery.onLogLine(diagnostic(directory))
                -3
            } else {
                guards(files).writeText("{}", Charsets.UTF_8)
                0
            }
        }

        assertEquals(0, result)
        assertEquals(2, calls)
        assertTrue(original.contentEquals(backup(files).readBytes()))
        assertEquals(modified, backup(files).lastModified())
        assertEquals(permissions, Files.getPosixFilePermissions(backup(files).toPath()))
        assertEquals("{}", guards(files).readText(Charsets.UTF_8))
        unrelated.forEach { (name, content) ->
            assertEquals(content, File(files, name).readText(Charsets.UTF_8))
        }
    }

    @Test
    fun `healthy JSON is preserved even if native reports a schema error`() {
        listOf("{}", "null", "[]", "{\"default\":{\"guards\":[]}}", "\"synthetic\"").forEach { content ->
            val files = fixture(content)
            val recovery = ArtiGuardStateRecovery()
            var calls = 0
            assertEquals(-3, recovery.initialize(files) { directory ->
                calls++
                recovery.onLogLine(diagnostic(directory))
                -3
            })
            assertEquals(1, calls)
            assertEquals(content, guards(files).readText(Charsets.UTF_8))
            assertFalse(backup(files).exists())
        }
    }

    @Test
    fun `empty truncated and invalid JSON can recover`() {
        listOf("", " ", "{", "{\"guards\":", "\u0000", "{} {}").forEach { content ->
            val files = fixture(content)
            val recovery = ArtiGuardStateRecovery()
            var calls = 0
            assertEquals(0, recovery.initialize(files) { directory ->
                calls++
                if (calls == 1) {
                    recovery.onLogLine(diagnostic(directory))
                    -3
                } else 0
            })
            assertEquals(2, calls)
            assertFalse(guards(files).exists())
            assertEquals(content, backup(files).readText(Charsets.UTF_8))
        }
    }

    @Test
    fun `generic initialization network bind and other state failures never change guards`() {
        listOf(
            null,
            "ERROR: Failed to initialize Arti: network timeout",
            "ERROR: Failed to bind SOCKS proxy",
            "ERROR: Another process has the lock on our state files",
            "ERROR: Failed to initialize Arti: corrupted data in persistent state"
        ).forEach { line ->
            val files = fixture("{")
            val recovery = ArtiGuardStateRecovery()
            var calls = 0
            assertEquals(-3, recovery.initialize(files) {
                calls++
                recovery.onLogLine(line)
                -3
            })
            assertEquals(1, calls)
            assertEquals("{", guards(files).readText(Charsets.UTF_8))
            assertFalse(backup(files).exists())
        }
        val files = fixture("{")
        val recovery = ArtiGuardStateRecovery()
        recovery.initialize(files) { directory ->
            recovery.onLogLine(diagnostic(directory).replace("guards.json", "other.json"))
            -3
        }
        recovery.initialize(files) { directory ->
            recovery.onLogLine(diagnostic("$directory/elsewhere"))
            -3
        }
        assertEquals("{", guards(files).readText(Charsets.UTF_8))
        assertFalse(backup(files).exists())
    }

    @Test
    fun `diagnostics must belong to this failed initialization and its error code`() {
        val files = fixture("{")
        listOf(0, -1, -2, -4).forEach { code ->
            val recovery = ArtiGuardStateRecovery()
            assertEquals(code, recovery.initialize(files) { directory ->
                recovery.onLogLine(diagnostic(directory))
                code
            })
        }
        val recovery = ArtiGuardStateRecovery()
        assertEquals(-2, recovery.initialize(files) { directory ->
            recovery.onLogLine(diagnostic(directory))
            -2
        })
        assertEquals(-3, recovery.initialize(files) { -3 })
        recovery.onLogLine(diagnostic(File(files, "arti").absolutePath))
        assertEquals(-3, recovery.initialize(files) { -3 })
        assertEquals("{", guards(files).readText(Charsets.UTF_8))
        assertFalse(backup(files).exists())
    }

    @Test
    fun `a prior native client is never reset because native stop retains it`() {
        val files = fixture("{}")
        val recovery = ArtiGuardStateRecovery()
        assertEquals(0, recovery.initialize(files) { 0 })
        guards(files).writeText("{", Charsets.UTF_8)
        assertEquals(-3, failInitialization(files, recovery))
        assertEquals("{", guards(files).readText(Charsets.UTF_8))
        assertFalse(backup(files).exists())
    }

    @Test
    fun `a prior failed bootstrap also prevents recovery until process restart`() {
        val files = fixture("{")
        val recovery = ArtiGuardStateRecovery()
        assertEquals(-3, recovery.initialize(files) { -3 })
        assertEquals(-3, failInitialization(files, recovery))
        assertEquals("{", guards(files).readText(Charsets.UTF_8))
        assertFalse(backup(files).exists())
    }

    @Test
    fun `recovery retries once and never overwrites the preserved corrupt file`() {
        val files = fixture("first corrupt file")
        val recovery = ArtiGuardStateRecovery()
        var calls = 0
        assertEquals(-3, recovery.initialize(files) { directory ->
            calls++
            if (calls == 2) guards(files).writeText("second corrupt file", Charsets.UTF_8)
            recovery.onLogLine(diagnostic(directory))
            -3
        })
        assertEquals(2, calls)
        assertEquals("first corrupt file", backup(files).readText(Charsets.UTF_8))
        assertEquals("second corrupt file", guards(files).readText(Charsets.UTF_8))
        assertTrue(backup(files).delete())
        assertEquals(-3, failInitialization(files, recovery))
        assertEquals("second corrupt file", guards(files).readText(Charsets.UTF_8))
        assertFalse(backup(files).exists())
    }

    @Test
    fun `existing backup blocks recovery without changing either file`() {
        val files = fixture("{")
        backup(files).writeText("previous synthetic backup", Charsets.UTF_8)
        assertEquals(-3, failInitialization(files, ArtiGuardStateRecovery()))
        assertEquals("{", guards(files).readText(Charsets.UTF_8))
        assertEquals("previous synthetic backup", backup(files).readText(Charsets.UTF_8))
    }

    @Test
    fun `symlinked path components guards and backup are left untouched`() {
        listOf("", "arti", "arti/state", "arti/state/state", "arti/state/state/guards.json").forEach { name ->
            val files = fixture("{")
            val path = if (name.isEmpty()) files.toPath() else File(files, name).toPath()
            val target = temporary.newFolder().toPath().resolve("target")
            Files.move(path, target)
            Files.createSymbolicLink(path, target)
            assertEquals(-3, failInitialization(files, ArtiGuardStateRecovery()))
            assertTrue(Files.isSymbolicLink(path))
            assertEquals("{", guards(files).readText(Charsets.UTF_8))
            assertFalse(backup(files).exists())
        }
        val files = fixture("{")
        val target = temporary.newFile().apply { writeText("synthetic backup target", Charsets.UTF_8) }
        Files.createSymbolicLink(backup(files).toPath(), target.toPath())
        assertEquals(-3, failInitialization(files, ArtiGuardStateRecovery()))
        assertTrue(Files.isSymbolicLink(backup(files).toPath()))
        assertEquals("synthetic backup target", target.readText(Charsets.UTF_8))
        assertEquals("{", guards(files).readText(Charsets.UTF_8))
    }

    @Test
    fun `unreadable JSON is not treated as proven corruption`() {
        val files = fixture("{")
        val path = guards(files).toPath()
        val permissions = Files.getPosixFilePermissions(path)
        try {
            Files.setPosixFilePermissions(path, emptySet())
            assertEquals(-3, failInitialization(files, ArtiGuardStateRecovery()))
            assertTrue(Files.exists(path, NOFOLLOW_LINKS))
            assertFalse(backup(files).exists())
        } finally {
            Files.setPosixFilePermissions(path, permissions)
        }
        assertEquals("{", guards(files).readText(Charsets.UTF_8))
    }

    @Test
    fun `dangling guard and backup links are not followed or replaced`() {
        val files = fixture("{")
        assertTrue(guards(files).delete())
        val missing = File(temporary.newFolder(), "missing").toPath()
        Files.createSymbolicLink(guards(files).toPath(), missing)
        assertEquals(-3, failInitialization(files, ArtiGuardStateRecovery()))
        assertTrue(Files.isSymbolicLink(guards(files).toPath()))
        assertFalse(Files.exists(missing, NOFOLLOW_LINKS))
        assertTrue(guards(files).delete())
        guards(files).writeText("{", Charsets.UTF_8)
        Files.createSymbolicLink(backup(files).toPath(), missing)
        assertEquals(-3, failInitialization(files, ArtiGuardStateRecovery()))
        assertTrue(Files.isSymbolicLink(backup(files).toPath()))
        assertFalse(Files.exists(missing, NOFOLLOW_LINKS))
        assertEquals("{", guards(files).readText(Charsets.UTF_8))
    }

    @Test
    fun `missing nonregular and oversized files are not recovered`() {
        val files = fixture("{")
        assertTrue(guards(files).delete())
        assertEquals(-3, failInitialization(files, ArtiGuardStateRecovery()))
        assertFalse(Files.exists(guards(files).toPath(), NOFOLLOW_LINKS))
        assertTrue(guards(files).mkdir())
        assertEquals(-3, failInitialization(files, ArtiGuardStateRecovery()))
        assertTrue(guards(files).isDirectory)
        assertTrue(guards(files).delete())
        guards(files).writeText("{".repeat(1024 * 1024 + 1), Charsets.UTF_8)
        assertEquals(-3, failInitialization(files, ArtiGuardStateRecovery()))
        assertEquals(1024L * 1024L + 1L, guards(files).length())
        assertFalse(backup(files).exists())
    }
}
