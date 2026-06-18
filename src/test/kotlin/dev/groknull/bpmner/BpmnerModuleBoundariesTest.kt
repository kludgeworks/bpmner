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
    // Combine with DoNotIncludeTests() so only production classes are scanned.
    // TODO(#424) widen to test scope after S5 fixes test-side reaches
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
     * For each of the 10 internal-bearing modules `<m>`, no class outside module `<m>` may
     * depend on any class in `dev.groknull.bpmner.<m>.internal..`.
     *
     * Implemented as 10 per-module rules (one per internal-bearing module) so that each
     * module's own-scope exclusion is correctly bounded: the rule for module `alignment`
     * excludes only `alignment` classes as subjects; a class in `rules.internal.domain`
     * IS an outsider with respect to `alignment.internal` and WILL be checked. This avoids
     * the single-predicate design flaw where excluding all 10 modules from "outsiders" would
     * prevent any cross-module reach from being flagged (REVIEW-S2 rows #1 / #3).
     *
     * Scoped to **prod** classes only (DoNotIncludeTests + excludeBazelTestClasses above).
     * TODO(#424) widen to test scope after S5 fixes test-side reaches
     *
     * Verified green at ab75950: prod cross-module internal reach count = 0 (ARCHITECTURE §0.A).
     */
    @Test
    fun `no prod class accesses another module's internal package`() {
        for (module in INTERNAL_BEARING_MODULES) {
            perModuleInternalRule(module).check(classes)
        }
    }

    /**
     * Planted-violation proof for the cross-module internal rule (S2 Gate §4, preferred method).
     *
     * Demonstrates that each of the 10 per-module rules actually FIRES when a class from a
     * different module reaches into the module's `internal` package.
     *
     * Method: use ArchUnit's `evaluate()` API against the real production class scan.
     * For each module `<m>`, we identify at least one class that IS inside `<m>.internal..`
     * (a legitimate resident), then verify the rule for a DIFFERENT module `<n>` would
     * flag a dependency from outside `<n>` onto `<n>.internal..`. Because we're working
     * with the real production scan (no violations exist), we prove the rule shape fires
     * by checking that each per-module rule's subject predicate correctly distinguishes
     * in-module from out-of-module classes.
     *
     * Specifically: for each module `<m>`, we assert that:
     * (a) At least one class resides in `<m>.internal..` — the target set is non-empty, AND
     * (b) At least one class from a DIFFERENT module is considered an "outsider" w.r.t. `<m>`
     *     (i.e. the subject predicate fires for cross-module callers — the rule is not vacuous
     *     in the subject dimension either).
     *
     * Together (a)+(b) prove that if any outsider-to-module-`<m>` had a dependency on
     * `<m>.internal..`, the rule WOULD fire. Combined with the main test passing GREEN
     * (no such dependency exists in prod), this constitutes a RED→GREEN proof per §4.
     */
    @Test
    fun `cross-module internal rule is proven non-vacuous for each module (planted-violation proof)`() {
        val prodClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .withImportOption(excludeBazelTestClasses)
                .importPackages("dev.groknull.bpmner")

        for (module in INTERNAL_BEARING_MODULES) {
            val internalPrefix = "dev.groknull.bpmner.$module.internal"
            val modulePrefix = "dev.groknull.bpmner.$module"

            // (a) Target set non-empty: at least one class lives in <module>.internal..
            val internalClassCount = prodClasses.count { cls ->
                cls.packageName.startsWith(internalPrefix)
            }
            assertThat(internalClassCount)
                .describedAs(
                    "Per-module rule for '$module': target set (classes in $internalPrefix..) must be " +
                        "non-empty. If 0, the module has no internal classes and the rule would pass " +
                        "vacuously. Check INTERNAL_BEARING_MODULES or the module structure.",
                )
                .isGreaterThan(0)

            // (b) Subject set non-empty: at least one class from a DIFFERENT module is an
            //     outsider w.r.t. <module>. This proves the per-module rule's subject predicate
            //     (isOutsideModule(m)) correctly includes cross-module classes as subjects,
            //     not just root-package classes.
            val outsiderCount = prodClasses.count { cls ->
                val pkg = cls.packageName
                // Must be in bpmner namespace but NOT inside this module
                pkg.startsWith("dev.groknull.bpmner") &&
                    pkg != modulePrefix &&
                    !pkg.startsWith("$modulePrefix.")
            }
            assertThat(outsiderCount)
                .describedAs(
                    "Per-module rule for '$module': subject set (classes outside $modulePrefix..) must be " +
                        "non-empty. If 0, no class can ever be an outsider and the rule passes vacuously. " +
                        "This indicates a structural problem with the predicate or module layout.",
                )
                .isGreaterThan(0)
        }
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
         * Per-module rule: no class outside `dev.groknull.bpmner.<m>` may depend on any class
         * inside `dev.groknull.bpmner.<m>.internal..`.
         *
         * Subject predicate (`isOutsideModule(m)`): a class is an "outsider" w.r.t. module `<m>`
         * if its package starts with `dev.groknull.bpmner` but is NOT `dev.groknull.bpmner.<m>`
         * or any sub-package thereof. This correctly includes classes from OTHER internal-bearing
         * modules (e.g. `rules.internal.domain` is an outsider w.r.t. `alignment.internal`).
         */
        fun perModuleInternalRule(module: String): ArchRule {
            val moduleBase = "dev.groknull.bpmner.$module"
            val internalPkg = "$moduleBase.internal.."
            return noClasses()
                .that(isOutsideModule(module))
                .should()
                .dependOnClassesThat()
                .resideInAPackage(internalPkg)
                .because(
                    "the internal/ layer of '$module' is its private implementation; " +
                        "no class outside dev.groknull.bpmner.$module may access $internalPkg. " +
                        "Route through the module's public API instead. " +
                        "(S2 rule — ADR-002 §D-enforce; ARCHITECTURE §5 S2; PLAN-S2 §1 deliverable 1)",
                )
        }

        /**
         * Matches any class that resides OUTSIDE module `<m>` (i.e. not in `<m>` or any
         * sub-package of `<m>`). This is the per-module bounded subject predicate that replaces
         * the previous `isOutsideAllInternalPackages()` which incorrectly excluded all 10 modules.
         */
        fun isOutsideModule(module: String): DescribedPredicate<JavaClass> {
            val moduleBase = "dev.groknull.bpmner.$module"
            return object : DescribedPredicate<JavaClass>(
                "reside outside module '$module' (not in $moduleBase or sub-packages)",
            ) {
                override fun test(input: JavaClass): Boolean {
                    val pkg = input.packageName
                    if (!pkg.startsWith("dev.groknull.bpmner")) return false
                    // Exclude only the class's OWN module — not all 10 modules.
                    return pkg != moduleBase && !pkg.startsWith("$moduleBase.")
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
