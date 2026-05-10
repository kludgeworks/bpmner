package dev.groknull.bpmner

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

@Disabled("Enable after Phase 2 package restructuring into vertical slices is complete")
class BpmnerModulithTest {

    @Test
    fun `verifies application module structure`() {
        ApplicationModules.of(BpmnerApplication::class.java).verify()
    }
}
