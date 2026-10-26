package io.cmpose.backendtemplate.local

import java.io.File

/** What the local server writes into its folder; anything else there means the folder belongs to someone else. */
val LOCAL_FOLDER_ENTRIES: Set<String> = setOf("config.properties", "postgres", "files", "server.pid", ".DS_Store")

private const val LISTED_ENTRIES = 3

/**
 * Why [dir] cannot be the local server's folder, or null when it can: it may not be [projectDir] or one of its
 * parents, and an existing folder may hold only [LOCAL_FOLDER_ENTRIES] (resetLocal deletes it as a whole).
 */
fun localFolderProblem(dir: File, projectDir: File): String? {
    val folder = dir.canonicalFile
    if (projectDir.canonicalFile.toPath().startsWith(folder.toPath())) {
        return "localServerDir must not be this project or one of its parent folders: $folder"
    }
    val foreign = if (folder.isFile) {
        listOf(folder.name)
    } else {
        folder.list().orEmpty().filterNot { it in LOCAL_FOLDER_ENTRIES }.sorted()
    }
    if (foreign.isEmpty()) return null
    val listed = foreign.take(LISTED_ENTRIES).joinToString() + if (foreign.size > LISTED_ENTRIES) ", ..." else ""
    return "localServerDir holds files the local server did not write ($listed): $folder; " +
        "point it at an empty or new folder"
}
