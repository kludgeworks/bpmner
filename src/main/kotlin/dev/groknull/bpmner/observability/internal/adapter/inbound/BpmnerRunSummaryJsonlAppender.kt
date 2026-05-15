package dev.groknull.bpmner.observability.internal.adapter.inbound

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Component
class BpmnerRunSummaryJsonlAppender(
    private val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules(),
) {
    private val logger = LoggerFactory.getLogger(BpmnerRunSummaryJsonlAppender::class.java)

    fun append(
        logDir: Path,
        summary: BpmnerStructuredRunSummary,
    ) {
        try {
            Files.createDirectories(logDir)
            val line = objectMapper.writeValueAsString(summary) + System.lineSeparator()
            Files.writeString(
                logDir.resolve(JSONL_FILE_NAME),
                line,
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (e: JsonProcessingException) {
            logger.warn("Failed to append BPMN structured run summary", e)
        } catch (e: java.io.IOException) {
            logger.warn("Failed to append BPMN structured run summary", e)
        } catch (e: SecurityException) {
            logger.warn("Failed to append BPMN structured run summary", e)
        }
    }

    companion object {
        const val JSONL_FILE_NAME = "bpmner-runs.jsonl"
    }
}
