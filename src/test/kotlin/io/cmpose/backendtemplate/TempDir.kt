package io.cmpose.backendtemplate

import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.createTempDirectory

private val tempDirs = ConcurrentLinkedQueue<Path>().also { dirs ->
    Runtime.getRuntime().addShutdownHook(Thread { dirs.forEach { it.toFile().deleteRecursively() } })
}

/** A new temporary directory, removed when the test JVM exits. */
fun tempDir(prefix: String): Path = createTempDirectory(prefix).also(tempDirs::add)
