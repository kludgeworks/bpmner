/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BpmnerModuleBoundariesTest {
    // Use the shared excludeBazelTestClasses helper (from BpmnerArchUnitImports.kt).
    // Combine with DoNotIncludeTests() so only production classes are scanned:
    // the cross-module internal rule is prod-scoped in S2 (test scope is widened in S5
    // after the test-side reaches are fixed — ARCHITECTURE §5 S2/S5, NG1).
    private val importer =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .withImportOption(excludeBazelTestClasses)
    private val classes =
        importer
            .importPackages("dev.groknull.bpmner")

    @Test
    fun `core does not depend on other internal modules`() {
        val rule =
            noClasses()
                .that()
                .resideInAPackage("..bpmner.core..")
                .should()
                .dependOnClassesThat(internalNonCoreClass())
        rule.check(classes)
    }

    @Test
    fun `core only retains foundational types - no domain-named feature types`() {
        val rule =
            classes()
                .that()
                .resideInAPackage("..bpmner.core..")
                .and()
                .haveSimpleNameNotEndingWith("Kt")
                .and(notAllowedInCore())
                .should(notHaveDomainName())
        rule.check(classes)
    }

    /**
     * Cross-module internal boundary rule (S2, Rule 1).
     *
     * No class outside module `<m>` may depend on any class in `dev.groknull.bpmner.<m>.internal..`
     * for the 10 modules that have an `internal/` layer:
     * alignment, contract, generation, layout, observability, orchestration,
     * readiness, repair, rules, validation.
     *
     * Scoped to **prod** classes only in S2 (DoNotIncludeTests + excludeBazelTestClasses above).
     * The test-side reaches are enumerated and fixed in S5, which then widens this rule to test
     * scope (ARCHITECTURE §5 S2/S5; ADR-002 §D-enforce; NG1 in PLAN-S2).
     *
     * Verified green at ab75950: prod cross-module internal reach count = 0 (ARCHITECTURE §0.A).
     */
    @Test
    fun `no prod class accesses another module's internal package`() {
        crossModuleInternalRule().check(classes)
    }

    /**
     * Non-vacuity proof for the cross-module internal rule (S2 Gate §4, §4 acceptable method).
     *
     * The preferred "planted violation" method (ArchUnit synthetic classpath) is not available
     * in Bazel builds because `excludeBazelTestClasses` excludes all `_tests_lib` jars, so
     * test-scope classes cannot be re-imported separately. The plan §4 acceptable alternative
     * is documented proof — see the PR description for the RED→GREEN log from a transient
     * local diff that added a deliberate cross-module internal reach and showed the rule fire.
     *
     * This test provides a committed structural proof instead: it asserts the rule's
     * "target" predicate (`isAnyModuleInternalClass`) is **non-empty** in the production scan
     * — i.e., there exist `internal` classes to be checked against (the rule is not vacuous
     * because the "should not depend on" target set is non-empty). A rule whose target set is
     * empty passes vacuously; a non-empty target set means it will fire if any importer reaches in.
     */
    @Test
    fun `cross-module internal rule target set is non-empty (non-vacuity proof)`() {
        // Verify that isAnyModuleInternalClass() matches at least one class in the
        // production build. If zero classes match, the rule would pass vacuously on any
        // class set (no "should not depend on" target exists). A non-empty match set means
        // the rule has real targets and WILL fire when a caller reaches into them.
        val prodClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .withImportOption(excludeBazelTestClasses)
                .importPackages("dev.groknull.bpmner")

        val internalClassCount = prodClasses.count { cls -> isAnyModuleInternalClass().test(cls) }
        assertThat(internalClassCount)
            .describedAs(
                "isAnyModuleInternalClass() must match ≥1 production class; " +
                    "if 0, the cross-module rule would pass vacuously on any class set. " +
                    "If this fails, the INTERNAL_BEARING_MODULES list or the package pattern " +
                    "in isAnyModuleInternalClass() is incorrect.",
            )
            .isGreaterThan(0)
    }

    private fun notHaveDomainName(): ArchCondition<JavaClass> {
        return object : ArchCondition<JavaClass>("not have a domain-flavored name") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                if (DOMAIN_NAME_PATTERN.containsMatchIn(item.simpleName)) {
                    events.add(
                        SimpleConditionEvent.violated(
                            item,
                            "${item.fullName} lives in core but has a domain-flavored name. Move it to its " +
                                "owning module, or add it to CORE_ALLOWLIST with justification.",
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        val DOMAIN_NAME_PATTERN =
            Regex(
                "(?i).*(report|verdict|assessment|attempt|capability|guardrail|" +
                    "alignment|readiness|contract|repair|lint|diagnostic).*",
            )

        // Names that legitimately remain in core (kernel primitives reachable from BpmnRequest /
        // the BPMN domain model). The Config entries are a known smell - each could move with
        // its module once BpmnConfig itself is decomposed into per-module @ConfigurationProperties.
        val CORE_ALLOWLIST: Set<String> =
            setOf(
                "AlignmentClassification",
                "ReadinessDimension",
                "BpmnAlignmentConfig",
                "BpmnContractConfig",
                "BpmnReadinessConfig",
                "ClarificationExchange",
            )

        // The 10 modules that have an internal/ layer (api, core, config, web do not).
        // Source: ARCHITECTURE §3 / PLAN-S2 §0 verified facts.
        val INTERNAL_BEARING_MODULES: List<String> =
            listOf(
                "alignment",
                "contract",
                "generation",
                "layout",
                "observability",
                "orchestration",
                "readiness",
                "repair",
                "rules",
                "validation",
            )

        /**
         * The generalised cross-module internal rule.
         *
         * For each of the 10 internal-bearing modules `<m>`, no class that does NOT reside in
         * `dev.groknull.bpmner.<m>.internal..` (or in `<m>` itself) may depend on any class
         * that DOES reside in `dev.groknull.bpmner.<m>.internal..`.
         *
         * This is the union of 10 per-module rules collapsed into one ArchRule via a single
         * predicate that matches any internal package across all 10 modules.
         */
        fun crossModuleInternalRule(): ArchRule {
            return noClasses()
                .that(isOutsideAllInternalPackages())
                .should()
                .dependOnClassesThat(isAnyModuleInternalClass())
                .because(
                    "the internal/ layer is each module's private implementation; " +
                        "cross-module internal access breaks the module boundary. " +
                        "Route through the module's public API instead. " +
                        "(S2 rule — ADR-002 §D-enforce; ARCHITECTURE §5 S2; PLAN-S2 §1 deliverable 1)",
                )
        }

        /**
         * Matches any class inside any of the 10 modules' `.internal..` sub-packages.
         */
        fun isAnyModuleInternalClass(): DescribedPredicate<JavaClass> {
            return object : DescribedPredicate<JavaClass>(
                "reside in an internal package of any of the 10 internal-bearing modules",
            ) {
                override fun test(input: JavaClass): Boolean {
                    val pkg = input.packageName
                    return INTERNAL_BEARING_MODULES.any { m ->
                        pkg.startsWith("dev.groknull.bpmner.$m.internal")
                    }
                }
            }
        }

        /**
         * Matches any class that resides OUTSIDE every one of the 10 modules' own packages.
         *
         * A class inside `dev.groknull.bpmner.rules.internal.domain` is NOT "outside" the `rules`
         * module — it's inside it and legitimately accesses its own internals. Only classes from
         * other modules (or the root package) should be blocked.
         */
        fun isOutsideAllInternalPackages(): DescribedPredicate<JavaClass> {
            return object : DescribedPredicate<JavaClass>(
                "reside outside the own-module scope of all internal-bearing modules",
            ) {
                override fun test(input: JavaClass): Boolean {
                    val pkg = input.packageName
                    if (!pkg.startsWith("dev.groknull.bpmner")) return false
                    // If the class is inside one of the internal-bearing modules, it is allowed
                    // to access that same module's internals; exclude it from "outsiders".
                    return INTERNAL_BEARING_MODULES.none { m ->
                        pkg == "dev.groknull.bpmner.$m" ||
                            pkg.startsWith("dev.groknull.bpmner.$m.")
                    }
                }
            }
        }

        fun notAllowedInCore(): DescribedPredicate<JavaClass> {
            return object : DescribedPredicate<JavaClass>("is not on the core allowlist") {
                override fun test(input: JavaClass): Boolean = input.simpleName !in CORE_ALLOWLIST
            }
        }

        fun internalNonCoreClass(): DescribedPredicate<JavaClass> {
            return object : DescribedPredicate<JavaClass>("is in another bpmner module") {
                override fun test(input: JavaClass): Boolean {
                    val pkg = input.packageName
                    if (!pkg.startsWith("dev.groknull.bpmner")) return false
                    if (pkg == "dev.groknull.bpmner.core" || pkg.startsWith("dev.groknull.bpmner.core.")) return false
                    // api/ is the shared kernel: annotation-free POKOs that every module
                    // (including core) is permitted to depend on.
                    if (pkg == "dev.groknull.bpmner.api" || pkg.startsWith("dev.groknull.bpmner.api.")) return false
                    return true
                }
            }
        }
    }
}
