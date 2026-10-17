package io.cmpose.backendtemplate

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

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

/** Everything an event would put in a log line: message, MDC and the exception chain with its messages. */
fun ILoggingEvent.fullText(): String = buildString {
    append(formattedMessage)
    append(mdcPropertyMap)
    generateSequence<IThrowableProxy>(throwableProxy) { it.cause }.forEach {
        append(it.className).append(": ").append(it.message)
    }
}
