package dev.groknull.bpmner

import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.core.VerificationOptions

class BpmnerModulithTest {
    private val modules =
        ApplicationModules.of(
            BpmnerApplication::class.java,
            ImportOption { location ->
                !location.contains("bpmner_tests_lib")
            },
        )

    @Test
    fun `verifies expected modules are detected`() {
        val moduleNames = modules.map { it.name }.toSet()
        assertEquals(
            setOf(
                "config",
                "core",
                "generation",
                "validation",
                "repair",
                "layout",
                "observability",
                "readiness",
                "contract",
                "shell",
            ),
            moduleNames,
        )
    }

    @Test
    fun `verifies no illegal cross-module dependencies`() {
        modules.verify(VerificationOptions.defaults().withoutAdditionalVerifications())
    }
}
