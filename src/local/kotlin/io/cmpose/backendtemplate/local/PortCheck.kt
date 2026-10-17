package io.cmpose.backendtemplate.local

import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket

object PortCheck {
    fun isFree(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { true }
    } catch (_: BindException) {
        false
    }

    /** `lsof` output for whoever listens on [port], or a hint when lsof itself fails. */
    fun holder(port: Int): String = runCatching {
        val process = ProcessBuilder("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown process" }
    }.getOrElse { "run: lsof -nP -iTCP:$port -sTCP:LISTEN" }
}
