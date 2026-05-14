package dev.groknull.bpmner

import com.tngtech.archunit.core.importer.ImportOption
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
    fun `verifies no illegal cross-module dependencies`() {
        modules.verify(VerificationOptions.defaults().withoutAdditionalVerifications())
    }
}
