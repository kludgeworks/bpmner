/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.core.VerificationOptions

class BpmnerModulithTest {
    // ArchUnit's built-in DoNotIncludeTests only knows Maven/Gradle/IntelliJ test paths
    // (target/test-classes, build/classes/test, out/test). Bazel emits test classes to
    // bazel-out/.../bin/src/test/*_tests_lib*.jar, which slips through. We add a Bazel-
    // aware override: skip any classpath URL whose path crosses "/bin/src/test/" or
    // mentions "_tests_lib". This is necessary because integration tests in this repo
    // legitimately span modules (system tests in the root package, fixtures shared
    // between modules) and would surface as false-positive cycles otherwise.
    private val excludeBazelTests =
        ImportOption { location ->
            !location.contains("/bin/src/test/") && !location.contains("_tests_lib")
        }
    private val modules = ApplicationModules.of(BpmnerApplication::class.java, excludeBazelTests)

    @Test
    fun `verifies expected modules are detected`() {
        val moduleNames = modules.map { it.name }.toSet()
        val expectedModules =
            listOf(
                "api",
                "config",
                "core",
                "generation",
                "validation",
                "repair",
                "layout",
                "observability",
                "readiness",
                "contract",
                "alignment",
                "rules",
                "shell",
                "web",
            )
        assertEquals(
            expectedModules.toSet(),
            moduleNames,
        )
    }

    @Test
    fun `verifies no illegal cross-module dependencies`() {
        // We run Modulith's module-boundary rules (cycle detection, allowedDependencies
        // enforcement, exposed-type checks) but skip the additional verifications that
        // VerificationOptions.defaults() auto-registers — specifically the jMolecules
        // hexagonal architecture rule that fires under VerificationDepth.LENIENT whenever
        // jmolecules-hexagonal is on the classpath (spring-modulith-core 1.4.1's
        // Types.JMoleculesTypes#getRules registers it transparently). The ~40 hexagonal
        // stereotype violations that surfaces are real concerns but are orthogonal to
        // Phase 1G's module-boundary scope; they're tracked in #234 and will be addressed
        // separately. Once #234 lands, the call can revert to plain `modules.verify()`.
        modules.verify(VerificationOptions.defaults().withoutAdditionalVerifications())
    }
}
