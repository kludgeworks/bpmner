/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.observability.internal.adapter.inbound

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Component
class BpmnerRunSummaryJsonlAppender(
    private val objectMapper: ObjectMapper,
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
