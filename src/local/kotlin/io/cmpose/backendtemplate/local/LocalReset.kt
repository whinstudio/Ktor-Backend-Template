package io.cmpose.backendtemplate.local

import java.io.File
import java.io.IOException
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.system.exitProcess

/**
 * Deletes [dir] unless [localFolderProblem] refuses it; returns that problem, or null once the folder is gone.
 * Links inside [dir] are removed, not followed. A delete that fails is reported, so resetLocal does not pass.
 */
@OptIn(ExperimentalPathApi::class)
fun resetLocalFolder(dir: File, projectDir: File): String? =
    localFolderProblem(dir, projectDir) ?: try {
        dir.toPath().deleteRecursively()
        null
    } catch (e: IOException) {
        "could not delete localServerDir: ${dir.canonicalFile} (${e.message})"
    }

/** `./gradlew resetLocal` — args[0] is the .local-server folder, args[1] this project. */
fun main(args: Array<String>) {
    resetLocalFolder(File(args[0]), File(args[1]))?.let { problem ->
        System.err.println(problem)
        exitProcess(1)
    }
}
