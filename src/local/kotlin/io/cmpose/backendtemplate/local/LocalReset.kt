package io.cmpose.backendtemplate.local

import java.io.File
import kotlin.system.exitProcess

/** Deletes [dir] unless [localFolderProblem] refuses it; returns that problem, or null once the folder is gone. */
fun resetLocalFolder(dir: File, projectDir: File): String? =
    localFolderProblem(dir, projectDir) ?: run {
        dir.deleteRecursively()
        null
    }

/** `./gradlew resetLocal` — args[0] is the .local-server folder, args[1] this project. */
fun main(args: Array<String>) {
    resetLocalFolder(File(args[0]), File(args[1]))?.let { problem ->
        System.err.println(problem)
        exitProcess(1)
    }
}
