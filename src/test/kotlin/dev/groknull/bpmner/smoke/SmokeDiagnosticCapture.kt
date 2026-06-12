/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Test-only diagnostic tap for live smoke tests.
 *
 * The application already logs rich structured-return and validation failures, but most are warnings during
 * retries and never reach JUnit's final failure cause. This extension records a bounded per-test rollup so
 * recovered retry failures remain visible in `smoke-results.jsonl` without depending on retained Actions logs.
 */
class SmokeDiagnosticCapture :
    BeforeAllCallback,
    BeforeEachCallback,
    AfterAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        Companion.install()
    }

    override fun beforeEach(context: ExtensionContext) {
        Companion.reset()
    }

    override fun afterAll(context: ExtensionContext) {
        Companion.uninstall()
    }

    @Suppress("TooManyFunctions") // test-only diagnostic classifier: many small extraction helpers
    companion object {
        private const val APPENDER_NAME = "SMOKE_DIAGNOSTIC_CAPTURE"
        private const val MAX_SAMPLE = 500
        private const val MAX_SIGNATURE = 240
        private const val MAX_DIAGNOSTICS = 50

        private val ROOT_CAUSE_REGEX = Regex("""Root cause=([^|]+)""")
        private val CAUSED_BY_REGEX = Regex("""Caused by:\s*([^|]+)""")
        private val EXCEPTION_REGEX = Regex("""([A-Za-z0-9_.$]+(?:Exception|Error):\s*[^|]+)""")
        private val EXCEPTION_CLASS_REGEX = Regex("""([A-Za-z0-9_.$]+(?:Exception|Error))""")
        private val TARGET_TYPE_EXPECTING_REGEX = Regex("""expecting\s+([A-Za-z0-9_.$]+)""")
        private val TARGET_TYPE_CLASS_REGEX = Regex("""class\s+([A-Za-z0-9_.$]+)]""")
        private val TARGET_TYPE_SIMPLE_REGEX = Regex("""simple type,\s+class\s+([A-Za-z0-9_.$]+)""")
        private val FIELD_PATH_PROPERTY_REGEX = Regex("""JSON property ([A-Za-z0-9_.-]+)""")
        private val FIELD_PATH_CREATOR_REGEX = Regex("""creator parameter ([A-Za-z0-9_.-]+)""")
        private val AGENT_NAME_REGEX = Regex("""invocation\s+([^:]+):""")
        private val NORMALIZE_HEX_REGEX = Regex("""\b[0-9a-f]{7,40}\b""", RegexOption.IGNORE_CASE)
        private val NORMALIZE_TS_REGEX = Regex("""\b\d{4}-\d{2}-\d{2}[T ][0-9:.+-Z]+\b""")
        private val NORMALIZE_NUM_REGEX = Regex("""\b\d+\b""")
        private val NORMALIZE_SPACE_REGEX = Regex("""\s+""")

        private val lock = Any()
        private val diagnostics = linkedMapOf<DiagnosticKey, MutableDiagnostic>()
        private var rootLogger: Logger? = null
        private var appender: SmokeDiagnosticAppender? = null
        private var activeInstallations = 0

        fun reset() {
            synchronized(lock) { diagnostics.clear() }
        }

        fun snapshot(): List<SmokeDiagnostic> = synchronized(lock) {
            diagnostics.values
                .sortedWith(compareByDescending<MutableDiagnostic> { it.count }.thenBy { it.key.kind })
                .take(MAX_DIAGNOSTICS)
                .map { it.toSmokeDiagnostic() }
        }

        internal fun diagnosticFor(
            loggerName: String,
            message: String,
            throwableClass: String? = null,
            throwableMessage: String? = null,
            level: Level = Level.WARN,
        ): SmokeDiagnostic? {
            if (!level.isGreaterOrEqual(Level.WARN)) return null
            val combined = if (throwableMessage.isNullOrBlank()) {
                message.trim()
            } else {
                "$message | $throwableMessage".trim()
            }
            if (combined.isBlank() || !isInteresting(loggerName, combined, throwableClass)) return null
            val root = rootCause(combined, throwableClass)
            val signature = normalize(root).take(MAX_SIGNATURE)
            val key =
                DiagnosticKey(
                    kind = kindOf(loggerName, root, throwableClass),
                    exceptionClass = exceptionClass(root, throwableClass),
                    messageSignature = signature,
                    targetType = targetType(root) ?: targetType(combined),
                    fieldPath = fieldPath(root) ?: fieldPath(combined),
                    agentName = agentName(root) ?: agentName(combined),
                    model = null,
                )
            return MutableDiagnostic(key, 1, root.take(MAX_SAMPLE)).toSmokeDiagnostic()
        }

        private fun install() {
            synchronized(lock) {
                activeInstallations++
                if (appender != null) return
                val context = LoggerFactory.getILoggerFactory() as? LoggerContext
                if (context == null) {
                    activeInstallations--
                    return
                }
                val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
                val newAppender = SmokeDiagnosticAppender()
                newAppender.context = context
                newAppender.name = APPENDER_NAME
                newAppender.start()
                root.addAppender(newAppender)
                rootLogger = root
                appender = newAppender
            }
        }

        private fun uninstall() {
            synchronized(lock) {
                if (activeInstallations > 0) activeInstallations--
                if (activeInstallations > 0) return
                appender?.let {
                    rootLogger?.detachAppender(it)
                    it.stop()
                }
                appender = null
                rootLogger = null
                diagnostics.clear()
            }
        }

        private fun record(event: ILoggingEvent) {
            val diagnostic =
                diagnosticFor(
                    loggerName = event.loggerName,
                    message = event.formattedMessage.orEmpty(),
                    throwableClass = event.throwableProxy?.className,
                    throwableMessage = event.throwableProxy?.message,
                    level = event.level,
                ) ?: return
            synchronized(lock) {
                val key =
                    DiagnosticKey(
                        kind = diagnostic.kind,
                        exceptionClass = diagnostic.exceptionClass,
                        messageSignature = diagnostic.messageSignature,
                        targetType = diagnostic.targetType,
                        fieldPath = diagnostic.fieldPath,
                        agentName = diagnostic.agentName,
                        model = diagnostic.model,
                    )
                diagnostics.getOrPut(key) { MutableDiagnostic(key, 0, diagnostic.sample) }.count++
            }
        }

        private fun isInteresting(
            loggerName: String,
            message: String,
            throwableClass: String?,
        ): Boolean {
            return loggerName.containsInteresting() ||
                throwableClass?.containsInteresting() == true ||
                message.containsInteresting()
        }

        private fun String.containsInteresting(): Boolean {
            return contains("FilteringJacksonOutputConverter") ||
                contains("ExceptionWrappingConverter") ||
                contains("KotlinInvalidNullException") ||
                contains("InvalidLlmReturnFormatException") ||
                contains("LlmDataBindingProperties") ||
                contains("BpmnContractAgent") ||
                contains("ActionRunner") ||
                contains("DefaultActionMethodManager") ||
                contains("DefaultAgentValidationManager") ||
                contains("AgentMetadataReader") ||
                contains("Default LLM") ||
                contains("NO_PATH_TO_GOAL")
        }

        private fun kindOf(
            loggerName: String,
            message: String,
            throwableClass: String?,
        ): String = when {
            message.contains("Default LLM") -> "model_config"
            isAgentValidation(loggerName, message) -> "agent_validation"
            isContractValidation(loggerName, message) -> "contract_validation"
            isInvalidLlmReturn(loggerName, message) -> "invalid_llm_return"
            loggerName.endsWith("FilteringJacksonOutputConverter") -> "llm_parse_error"
            message.contains("KotlinInvalidNullException") -> "llm_parse_error"
            isActionError(loggerName) -> "action_error"
            throwableClass?.contains("Assertion") == true -> "assertion"
            else -> "diagnostic"
        }

        private fun isAgentValidation(
            loggerName: String,
            message: String,
        ): Boolean = message.contains("NO_PATH_TO_GOAL") ||
            loggerName.endsWith("DefaultAgentValidationManager") ||
            loggerName.endsWith("AgentMetadataReader")

        private fun isContractValidation(
            loggerName: String,
            message: String,
        ): Boolean = loggerName.endsWith("BpmnContractAgent") ||
            message.contains("Contract validation found")

        private fun isInvalidLlmReturn(
            loggerName: String,
            message: String,
        ): Boolean = message.contains("InvalidLlmReturnFormatException") ||
            loggerName.endsWith("LlmDataBindingProperties")

        private fun isActionError(loggerName: String): Boolean = loggerName.endsWith("ActionRunner") ||
            loggerName.endsWith("DefaultActionMethodManager")

        private fun rootCause(
            message: String,
            throwableClass: String?,
        ): String {
            val rootCause = ROOT_CAUSE_REGEX.find(message)?.groupValues?.get(1)
            val causedBy = CAUSED_BY_REGEX.findAll(message).lastOrNull()?.groupValues?.get(1)
            val exception = EXCEPTION_REGEX.findAll(message)
                .lastOrNull()
                ?.groupValues
                ?.get(1)
            return (rootCause ?: causedBy ?: exception ?: message).let {
                if (throwableClass != null && !it.contains(throwableClass.substringAfterLast("."))) {
                    "$throwableClass: $it"
                } else {
                    it
                }
            }.trim()
        }

        private fun exceptionClass(
            message: String,
            throwableClass: String?,
        ): String? {
            return throwableClass ?: EXCEPTION_CLASS_REGEX
                .find(message)
                ?.groupValues
                ?.get(1)
        }

        private fun targetType(message: String): String? {
            return TARGET_TYPE_EXPECTING_REGEX.find(message)?.groupValues?.get(1)
                ?: TARGET_TYPE_CLASS_REGEX.find(message)?.groupValues?.get(1)
                ?: TARGET_TYPE_SIMPLE_REGEX.find(message)?.groupValues?.get(1)
        }

        private fun fieldPath(message: String): String? {
            return FIELD_PATH_PROPERTY_REGEX.find(message)?.groupValues?.get(1)
                ?: FIELD_PATH_CREATOR_REGEX.find(message)?.groupValues?.get(1)
        }

        private fun agentName(message: String): String? {
            return AGENT_NAME_REGEX.find(message)?.groupValues?.get(1)
        }

        private fun normalize(message: String): String {
            return message
                .replace(NORMALIZE_HEX_REGEX, "<hex>")
                .replace(NORMALIZE_TS_REGEX, "<ts>")
                .replace(NORMALIZE_NUM_REGEX, "<n>")
                .replace(NORMALIZE_SPACE_REGEX, " ")
                .trim()
        }

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private data class DiagnosticKey(
            val kind: String,
            val exceptionClass: String?,
            val messageSignature: String,
            val targetType: String?,
            val fieldPath: String?,
            val agentName: String?,
            val model: String?,
        )

        private data class MutableDiagnostic(
            val key: DiagnosticKey,
            var count: Int,
            val sample: String,
        ) {
            fun toSmokeDiagnostic(): SmokeDiagnostic {
                return SmokeDiagnostic(
                    kind = key.kind,
                    exceptionClass = key.exceptionClass,
                    messageSignature = key.messageSignature,
                    messageHash = sha256(key.messageSignature),
                    targetType = key.targetType,
                    fieldPath = key.fieldPath,
                    agentName = key.agentName,
                    model = key.model,
                    count = count,
                    sample = sample,
                )
            }
        }

        private class SmokeDiagnosticAppender : AppenderBase<ILoggingEvent>() {
            override fun append(eventObject: ILoggingEvent) {
                record(eventObject)
            }
        }
    }
}
