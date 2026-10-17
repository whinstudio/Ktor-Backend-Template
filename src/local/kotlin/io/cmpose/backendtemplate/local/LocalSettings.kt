package io.cmpose.backendtemplate.local

import java.io.File
import java.util.Properties

/** A problem the developer fixes by hand; LocalMain prints the message and exits with 1. */
class LocalSetupException(message: String, cause: Throwable? = null) : Exception(message, cause)

const val DEFAULT_LOCAL_PORT = 18_001
const val SETTINGS_FILE = "config.properties"
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535

data class LocalSettings(val port: Int, val firebaseProjectId: String) {
    companion object {
        fun read(dir: File): LocalSettings {
            val file = dir.resolve(SETTINGS_FILE)
            if (!file.isFile) {
                throw LocalSetupException(
                    "Missing ${file.path}. Create it with firebaseProjectId=<Firebase project id>"
                )
            }
            val props = Properties().apply { file.inputStream().use(::load) }
            val projectId = props.getProperty("firebaseProjectId")?.trim().orEmpty()
            if (projectId.isEmpty()) throw LocalSetupException("firebaseProjectId is empty in ${file.path}")
            return LocalSettings(portOf(props.getProperty("port"), file), projectId)
        }

        // Absent means the default; present must be a usable TCP port, or PortCheck fails later with a raw trace
        private fun portOf(
            raw: String?,
            file: File
        ): Int {
            val value = raw?.trim() ?: return DEFAULT_LOCAL_PORT
            val port = value.toIntOrNull()
            if (port == null || port !in MIN_PORT..MAX_PORT) {
                throw LocalSetupException("port=$value in ${file.path} is not a port number ($MIN_PORT-$MAX_PORT)")
            }
            return port
        }
    }
}
