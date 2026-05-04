package dev.groknull.bpmner

import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class WebJarDiagnosticTest {

    @Test
    fun `diagnose webjar extraction`() {
        val tempDir = Files.createTempDirectory("bpmnlint-diag-")
        val nodeModules = tempDir.resolve("node_modules")
        Files.createDirectories(nodeModules)

        val resolver = PathMatchingResourcePatternResolver()
        val resources = resolver.getResources("classpath*:META-INF/resources/webjars/**")
        val webjarPath = Regex("webjars/([^/]+)/([^/]+)/(.+)")

        println("Total resources found: ${resources.size}")
        val byPackage = mutableMapOf<String, Int>()
        var skipped = 0

        for (resource in resources) {
            if (!resource.isReadable) { skipped++; continue }
            val urlStr = resource.url.toString()
            val match = webjarPath.find(urlStr)
            if (match == null) { skipped++; continue }

            val packageName = match.groupValues[1]
            val subPath = match.groupValues[3]
            byPackage[packageName] = (byPackage[packageName] ?: 0) + 1

            val target = nodeModules.resolve(packageName).resolve(subPath)
            Files.createDirectories(target.parent)
            resource.inputStream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        }

        println("Skipped: $skipped")
        println("Packages extracted:")
        byPackage.entries.sortedByDescending { it.value }.forEach { (pkg, count) ->
            println("  $pkg: $count files")
        }

        val bpmnlintDir = nodeModules.resolve("bpmnlint").toFile()
        println("node_modules/bpmnlint exists: ${bpmnlintDir.exists()}, isDirectory: ${bpmnlintDir.isDirectory}")
        if (bpmnlintDir.isDirectory) {
            println("Files in bpmnlint/: ${bpmnlintDir.listFiles()?.map { it.name }?.take(10)}")
        }

        println("Temp dir: $tempDir")
        tempDir.toFile().deleteRecursively()
    }
}
