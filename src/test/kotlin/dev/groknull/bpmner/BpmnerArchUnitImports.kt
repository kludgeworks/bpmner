/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ImportOption

// ArchUnit's built-in DoNotIncludeTests only recognises Maven/Gradle/IntelliJ test
// output paths (target/test-classes, build/classes/test, out/test). Bazel emits test
// classes to bazel-out/.../bin/src/test/*_tests_lib*.jar, which slips through. This
// custom ImportOption fills that gap.
internal val excludeBazelTestClasses =
    ImportOption { location ->
        val uri = location.asURI().toString()
        val archiveUri = uri.substringBefore('!')
        !(archiveUri.endsWith("Test.jar") || archiveUri.contains("_tests_lib") || archiveUri.contains("/test-classes/"))
    }

// Kotlin compiler-generated synthetic classes (lambdas, $Companion, $WhenMappings,
// $sam$..., $inlined$...). Every dollar in a Kotlin class name comes from compiler
// synthesis — no production class uses `$` in its declared source name. ArchUnit's
// classpath resolver re-imports these as stubs even when filtered via ImportOption,
// so we use this predicate as both `LayeredArchitecture.ignoreDependency(...)` input
// (Solution 1 in #234's plan) and as the structural marker used by other tests.
internal val isKotlinSyntheticClass: DescribedPredicate<JavaClass> =
    DescribedPredicate.describe("Kotlin compiler-generated synthetic class") { it.name.contains('$') }
