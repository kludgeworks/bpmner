package dev.groknull.bpmner.validation

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest

@ApplicationModuleTest
class ValidationModuleTest {
    @Autowired
    private lateinit var lintingPort: BpmnLintingPort

    @Autowired
    private lateinit var xsdValidationPort: BpmnXsdValidationPort

    @Test
    fun `validation module bootstraps and exposes its ports`() {
        assertNotNull(lintingPort, "BpmnLintingPort should be available in the validation module context")
        assertNotNull(xsdValidationPort, "BpmnXsdValidationPort should be available in the validation module context")
    }
}
