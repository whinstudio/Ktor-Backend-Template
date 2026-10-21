package io.cmpose.backendtemplate.local

import io.cmpose.backendtemplate.googleJwkProvider
import io.cmpose.backendtemplate.googleKeyIds
import java.io.File
import kotlin.system.exitProcess

/** `./gradlew runLocal` — args[0] is the .local-server folder. */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: ".local-server").apply { mkdirs() }
    val server = try {
        LocalServer.start(dir, LocalSettings.read(dir), googleJwkProvider(), ::println, keyIds = googleKeyIds())
    } catch (e: LocalSetupException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    Thread.currentThread().join()
}
