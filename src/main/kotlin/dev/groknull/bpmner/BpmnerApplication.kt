package dev.groknull.bpmner

import com.embabel.agent.config.annotation.EnableAgents
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.ansi.AnsiColor
import org.springframework.boot.ansi.AnsiOutput
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAgents
class BpmnerApplication

fun main(args: Array<String>) {
    configureLogFile()
    runApplication<BpmnerApplication>(*args)
}

private fun configureLogFile() {
    if (System.getProperty("LOG_FILE").isNullOrBlank().not()) {
        return
    }

    val logDir = Path.of(System.getProperty("LOG_DIR") ?: "logs")

    try {
        logDir.createDirectories()
        pruneOldLogFiles(logDir, keep = 10)
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val logFile = logDir.resolve("bpmner-$timestamp.log")
        System.setProperty("LOG_FILE", logFile.toString())
    } catch (e: IOException) {
        System.err.println(AnsiOutput.toString(AnsiColor.RED, "Unable to configure bpmner log file: ${e.message}"))
    }
}

private fun pruneOldLogFiles(logDir: Path, keep: Int) {
    Files.list(logDir).use { paths ->
        paths
            .asSequence()
            .filter { path ->
                path.isRegularFile() &&
                    path.extension == "log" &&
                    path.name.startsWith("bpmner-")
            }
            .sortedBy { it.name }
            .toList()
            .dropLast(keep)
            .forEach { Files.deleteIfExists(it) }
    }
}

