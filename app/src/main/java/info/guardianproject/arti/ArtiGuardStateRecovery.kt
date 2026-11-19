package info.guardianproject.arti

import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ

/** A deliberately narrow recovery for a failed, first-time native guard-manager setup. */
internal class ArtiGuardStateRecovery {
    @Volatile
    private var initializingDirectory: File? = null
    @Volatile
    private var corruptGuardDiagnostic = false
    private var initializationAttempted = false

    fun onLogLine(line: String?) {
        val directory = initializingDirectory ?: return
        if (line != null &&
            line.startsWith("ERROR: Failed to initialize Arti:") &&
            line.contains("corrupted data in persistent state") &&
            line.contains("Error setting up the guard manager") &&
            line.contains("JSON error while loading persistent data on state/guards.json in ${File(directory, "state").absolutePath}")
        ) {
            corruptGuardDiagnostic = true
        }
    }

    // The caller serializes all native start/stop operations across proxy instances.
    fun initialize(filesDirectory: File, initializeNative: (String) -> Int): Int {
        val directory = File(filesDirectory, "arti")
        val mayRecover = !initializationAttempted
        initializationAttempted = true
        var result = initializeOnce(directory, initializeNative)
        // Pinned Arti ba421ece: arti-client/src/client.rs constructs GuardMgr before CircMgr;
        // tor-guardmgr/src/lib.rs loads guards before spawning its persistence tasks. This exact
        // setup error therefore drops the failed client's state manager before JNI returns.
        // Only the first attempt is eligible: even a previous failed bootstrap may have native
        // work outstanding, and JNI stop() retains any successfully created process-global client.
        if (result == -3 && corruptGuardDiagnostic && mayRecover &&
            quarantineMalformedGuards(filesDirectory)
        ) {
            result = initializeOnce(directory, initializeNative)
        }
        return result
    }

    private fun initializeOnce(directory: File, initializeNative: (String) -> Int): Int {
        corruptGuardDiagnostic = false
        initializingDirectory = directory
        try {
            return initializeNative(directory.absolutePath)
        } finally {
            initializingDirectory = null
        }
    }

    private fun quarantineMalformedGuards(filesDirectory: File): Boolean {
        // TorClientConfig's state directory is arti/state; FsStateMgr adds another "state".
        val arti = File(filesDirectory, "arti").toPath()
        val state = arti.resolve("state")
        val persistentState = state.resolve("state")
        val guards = persistentState.resolve("guards.json")
        val backup = persistentState.resolve("guards.json.corrupt")
        try {
            if (listOf(filesDirectory.toPath(), arti, state, persistentState).any {
                    !Files.isDirectory(it, NOFOLLOW_LINKS)
                } || !Files.isRegularFile(guards, NOFOLLOW_LINKS) ||
                Files.exists(backup, NOFOLLOW_LINKS) || Files.size(guards) > MAX_GUARD_FILE_BYTES
            ) return false

            // A native JSON error can also mean an incompatible schema. Never discard valid JSON.
            val malformed = Files.newByteChannel(guards, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                JsonReader(Channels.newReader(channel, Charsets.UTF_8.newDecoder(), -1)).use { reader ->
                    reader.strictness = Strictness.STRICT
                    try {
                        if (reader.peek() == JsonToken.END_DOCUMENT) {
                            true
                        } else {
                            JsonParser.parseReader(reader)
                            reader.peek() != JsonToken.END_DOCUMENT
                        }
                    } catch (_: JsonSyntaxException) {
                        true
                    } catch (_: com.google.gson.stream.MalformedJsonException) {
                        true
                    } catch (_: java.io.EOFException) {
                        true
                    }
                }
            }
            if (!malformed) return false

            // No deletion or replacement: preserve the bad bytes and any existing backup.
            Files.move(guards, backup)
            return true
        } catch (_: JsonIOException) {
            return false
        } catch (_: IOException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
    }

    private companion object {
        const val MAX_GUARD_FILE_BYTES = 1024L * 1024L
    }
}
