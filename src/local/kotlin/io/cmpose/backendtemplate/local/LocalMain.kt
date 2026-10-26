package io.cmpose.backendtemplate.local

import io.cmpose.backendtemplate.googleJwkProvider
import io.cmpose.backendtemplate.googleKeyIds
import java.io.File
import kotlin.system.exitProcess

/** `./gradlew runLocal` — args[0] is the .local-server folder, args[1] this project. */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: ".local-server")
    localFolderProblem(dir, File(args.getOrNull(1) ?: "."))?.let { problem ->
        System.err.println(problem)
        exitProcess(1)
    }
    dir.mkdirs()
    val server = try {
        LocalServer.start(dir, LocalSettings.read(dir), googleJwkProvider(), ::println, keyIds = googleKeyIds())
    } catch (e: LocalSetupException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    Thread.currentThread().join()
}
