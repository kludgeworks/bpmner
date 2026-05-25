/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

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
        // verify() applies Modulith's module-boundary rules AND the additional verifications
        // registered by VerificationOptions.defaults() — including the jMolecules hexagonal
        // architecture rule (auto-discovered via JMoleculesTypes.getRules() because
        // jmolecules-hexagonal is on the classpath). The Bazel-aware ImportOption above
        // restricts the scan to production code, which is what makes this pass cleanly
        // (#234 confirmed that the prior `withoutAdditionalVerifications()` bypass was
        // only masking test-class leakage, not real production violations).
        modules.verify()
    }
}
