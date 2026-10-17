package io.cmpose.backendtemplate.files

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Where uploaded bytes live. A stored path is what [write] returned; the database keeps it, never a client's name. */
interface FileStore {
    /** Stores [bytes] under a new random name and returns its path. */
    fun write(bytes: ByteArray): String

    /**
     * The bytes at [path], or null when no file is there.
     *
     * @throws IllegalArgumentException when [path] is not a path [write] could have returned
     */
    fun read(path: String): ByteArray?

    /**
     * Removes the file at [path]; a missing file is ignored.
     *
     * @throws IllegalArgumentException when [path] is not a path [write] could have returned
     */
    fun delete(path: String)
}

// A random UUID (SecureRandom), so a name reveals nothing and cannot be guessed.
private val STORED_NAME = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * Files directly under [root] (`FILES_DIR`, a named volume in production), each under a random name. Only names of
 * that form are accepted back, so no path can reach outside [root] (`../x`, absolute paths, sub-folders).
 */
class LocalDiskFileStore(root: Path) : FileStore {
    private val root: Path = root.toAbsolutePath().normalize()

    override fun write(bytes: ByteArray): String {
        Files.createDirectories(root)
        val name = UUID.randomUUID().toString()
        Files.write(root.resolve(name), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return name
    }

    override fun read(path: String): ByteArray? = try {
        Files.readAllBytes(resolve(path))
    } catch (_: NoSuchFileException) {
        null
    }

    override fun delete(path: String) {
        Files.deleteIfExists(resolve(path))
    }

    private fun resolve(path: String): Path {
        require(STORED_NAME.matches(path)) { "Not a stored file name" }
        return root.resolve(path)
    }
}
