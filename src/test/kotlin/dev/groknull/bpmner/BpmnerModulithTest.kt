package dev.groknull.bpmner

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class BpmnerModulithTest {

    private val modules = ApplicationModules.of(BpmnerApplication::class.java)

    @Test
    fun `verifies expected modules are detected`() {
        val moduleNames = modules.map { it.name }.toSet()
        assertTrue(moduleNames.contains("core"), "Expected 'core' module, found: $moduleNames")
        assertTrue(moduleNames.contains("generation"), "Expected 'generation' module, found: $moduleNames")
        assertTrue(moduleNames.contains("validation"), "Expected 'validation' module, found: $moduleNames")
        assertTrue(moduleNames.contains("repair"), "Expected 'repair' module, found: $moduleNames")
        assertTrue(moduleNames.contains("layout"), "Expected 'layout' module, found: $moduleNames")
        assertTrue(moduleNames.contains("observability"), "Expected 'observability' module, found: $moduleNames")
    }

    @Disabled("detectViolations() triggers a jMolecules binary incompatibility (AbstractMethodError). " +
        "Re-enable when jmolecules-archunit version is aligned with archunit in the Bazel WORKSPACE.")
    @Test
    fun `verifies no illegal cross-module dependencies`() {
        val violations = modules.detectViolations()
        assertTrue(violations.getMessages().isEmpty(), "Module violations found: ${violations.getMessages()}")
    }
}
