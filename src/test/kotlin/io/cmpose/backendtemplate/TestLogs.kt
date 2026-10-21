package io.cmpose.backendtemplate

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import kotlin.test.assertTrue

/** Runs [block] and returns every log event it produced, from any logger. */
fun capturedLogs(block: () -> Unit): List<ILoggingEvent> {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(appender)
    try {
        block()
    } finally {
        root.detachAppender(appender)
    }
    return appender.list.toList()
}

/**
 * Runs [block] and fails when the database aborted a transaction as deadlocked meanwhile. The answers cannot show it:
 * Exposed retries the aborted transaction, which then passes. Exposed logs each failed attempt before retrying it, on
 * the thread that ran it, so its log (off in `logback.xml`) is turned on while [block] runs.
 */
fun assertNoDeadlock(block: () -> Unit) {
    val exposed = LoggerFactory.getLogger("Exposed") as Logger
    val level = exposed.level
    exposed.level = Level.WARN
    val logs = try {
        capturedLogs(block)
    } finally {
        exposed.level = level
    }
    val deadlocks = logs.map { it.fullText() }.filter { "deadlock detected" in it }
    assertTrue(deadlocks.isEmpty(), "$deadlocks")
}

/** Everything an event would put in a log line: message, MDC and the exception chain with its messages. */
fun ILoggingEvent.fullText(): String = buildString {
    append(formattedMessage)
    append(mdcPropertyMap)
    generateSequence<IThrowableProxy>(throwableProxy) { it.cause }.forEach {
        append(it.className).append(": ").append(it.message)
    }
}
