/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class BpmnerModulithTest {
    // ArchUnit's built-in DoNotIncludeTests only knows Maven/Gradle/IntelliJ test paths
    // (target/test-classes, build/classes/test, out/test). Bazel emits test classes to
    // bazel-out/.../bin/src/test/*_tests_lib*.jar, which slips through, so we add a
    // Bazel-aware ImportOption that filters at the classpath-entry level.
    private val modules =
        ApplicationModules.of(BpmnerApplication::class.java, excludeBazelTestClasses)

    @Test
    fun `verifies no illegal cross-module dependencies`() {
        // verify() applies Modulith's module-boundary rules AND the additional verifications
        // registered by VerificationOptions.defaults() — including the jMolecules hexagonal
        // architecture rule (auto-discovered via JMoleculesTypes.getRules() because
        // jmolecules-hexagonal is on the classpath).
        //
        // Two filters are stacked to make this gate pass:
        //  1. The DescribedPredicate passed to ApplicationModules.of(...) above excludes
        //     Bazel test classes from the import (ArchUnit's DoNotIncludeTests regex doesn't
        //     match Bazel paths).
        //  2. src/test/resources/archunit_ignore_patterns.txt suppresses violations against
        //     Kotlin compiler synthetic classes (lambdas, $Companion, $WhenMappings). It's
        //     the only mechanism that reaches through Modulith's internal ArchUnit
        //     invocation, which doesn't expose LayeredArchitecture.ignoreDependency(). See
        //     #234 for the root-cause analysis.
        modules.verify()
    }
}
