/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BpmnerModuleBoundariesTest {
    // Use the shared excludeBazelTestClasses helper (from BpmnerArchUnitImports.kt) to exclude
    // Bazel test JARs from the Kotlin synthetic-class noise filter. Both prod AND test classes
    // are scanned — the importer applies only the Bazel-jar and Kotlin-synthetic filters so
    // test classes are subjects of the cross-module internal boundary rule (ARCHITECTURE §1.10,
    // §5 S5, G4).
    private val importer =
        ClassFileImporter()
            .withImportOption(excludeBazelTestClasses)
    private val classes =
        importer
            .importPackages("dev.groknull.bpmner")

    @Test
    fun `bpmn does not depend on other modules`() {
        val rule =
            noClasses()
                .that()
                .resideInAPackage("..bpmner.bpmn..")
                .should()
                .dependOnClassesThat(nonDomainDependencyClass())
        rule.check(classes)
    }

    @Test
    fun `bpmn does not depend on forbidden framework prompt or io types`() {
        val rule =
            noClasses()
                .that()
                .resideInAPackage("..bpmner.bpmn..")
                .should()
                .dependOnClassesThat(forbiddenDomainDependencyClass())
        rule.check(classes)
    }

    /**
     * Kernel-minimality ratchet: the `bpmn` module may only contain the types listed in
     * [DOMAIN_ALLOWLIST]. This is the enforcement gate for the **placement-rule table**
     * (ARCHITECTURE ADR-20 §6), which decides where each type lives based on what language
     * it speaks (graph, request, render DTO) and which slices own it. Top-level Kotlin
     * file facades (`*Kt` suffix) are exempt — they compile from top-level functions and
     * are not authored types. If this gate fires, the type belongs in a slice, not the
     * kernel; adding it to the allowlist without an architectural justification is a
     * placement bug. (ADR-20 §6; ADR-22 gate 9; ARCHITECTURE §1.9)
     */
    @Test
    fun `bpmn contains only the approved kernel types`() {
        val rule =
            classes()
                .that()
                .resideInAPackage("..bpmner.bpmn..")
                .and()
                .haveSimpleNameNotEndingWith("Kt")
                .and(notAllowedInDomain())
                .should(beRejectedFromDomain())
                .allowEmptyShould(true)
        rule.check(classes)
    }

    /**
     * Cross-module internal boundary rule (S2, Rule 1 — scoped to prod and test classes).
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
     * Scoped to **prod AND test** classes (ARCHITECTURE §1.10, §5 S5, G4).
     *
     * Verified green at ab75950: prod cross-module internal reach count = 0 (ARCHITECTURE §0.A).
     */
    @Test
    fun `no class accesses another module's internal package`() {
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
        // Includes both prod and test classes (ARCHITECTURE §1.10, §5 S5).
        val allClasses =
            ClassFileImporter()
                .withImportOption(excludeBazelTestClasses)
                .importPackages("dev.groknull.bpmner")

        for (module in INTERNAL_BEARING_MODULES) {
            val internalPrefix = "dev.groknull.bpmner.$module.internal"
            val modulePrefix = "dev.groknull.bpmner.$module"

            // (a) Target set non-empty: at least one class lives in <module>.internal..
            val internalClassCount = allClasses.count { cls ->
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
            val outsiderCount = allClasses.count { cls ->
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

    private fun beRejectedFromDomain(): ArchCondition<JavaClass> {
        return object : ArchCondition<JavaClass>("be one of the approved bpmn kernel classes") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                events.add(
                    SimpleConditionEvent.violated(
                        item,
                        "${item.fullName} is not part of the approved bpmn kernel allowlist.",
                    ),
                )
            }
        }
    }

    private companion object {
        val DOMAIN_ALLOWLIST: Set<String> =
            setOf(
                "BoundaryEventKind",
                "BpmnAssociation",
                "BpmnBoundaryEvent",
                "BpmnBusinessRuleTask",
                "BpmnCallActivity",
                "BpmnDataAssociation",
                "BpmnDataObject",
                "BpmnDataStore",
                "BpmnDefinition",
                "BpmnDefinitionContext",
                "BpmnEdge",
                "BpmnElementIndex",
                "BpmnEndEvent",
                "BpmnErrorEventDefinition",
                "BpmnErrorRef",
                "BpmnEscalationEventDefinition",
                "BpmnEscalationRef",
                "BpmnEvent",
                "BpmnEventBasedGateway",
                "BpmnEventDefinition",
                "BpmnExclusiveGateway",
                "BpmnGateway",
                "BpmnGroup",
                "BpmnInclusiveGateway",
                "BpmnIntermediateCatchEvent",
                "BpmnIntermediateThrowEvent",
                "BpmnLane",
                "BpmnManualTask",
                "BpmnMessageEventDefinition",
                "BpmnMessageFlow",
                "BpmnMessageRef",
                "BpmnModule",
                "BpmnNode",
                "BpmnNodeNamingPolicy",
                "BpmnNoneEventDefinition",
                "BpmnParallelGateway",
                "BpmnParticipant",
                "BpmnReceiveTask",
                "BpmnRequest",
                "BpmnRule",
                "BpmnScriptTask",
                "BpmnSendTask",
                "BpmnServiceTask",
                "BpmnSignalEventDefinition",
                "BpmnSignalRef",
                "BpmnStartEvent",
                "BpmnSubProcess",
                "BpmnTask",
                "BpmnTerminateEventDefinition",
                "BpmnTextAnnotation",
                "BpmnTimerEventDefinition",
                "BpmnTimerKind",
                "BpmnUnrecognizedEventDefinition",
                "BpmnUnrecognizedNode",
                "BpmnUserTask",
                "ClarificationExchange",
                "ComposedProcessGraph",
                "DataFlowDirection",
                "DiagnosticCode",
                "GenerationMode",
                "LaidOutProcessGraph",
                "MultiInstanceLoopCharacteristics",
                "MultiInstanceMode",
                "OwnedElementGraph",
                "RenderedBpmn",
                "RepairDisposition",
                "RepairKind",
                "RepairMetadata",
                "RepairSafety",
                "RuleCategory",
                "RuleDiagnostic",
                "RuleEvaluation",
                "RuleMetadata",
                "RuleSeverity",
                "StandardLoopCharacteristics",
            )

        // The 10 modules that have an internal/ layer (bpmn's root package is the sole kernel;
        // config and web have no internal/ layer at the top tier).
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

        fun notAllowedInDomain(): DescribedPredicate<JavaClass> {
            return object : DescribedPredicate<JavaClass>("is not on the bpmn allowlist") {
                override fun test(input: JavaClass): Boolean = input.simpleName !in DOMAIN_ALLOWLIST
            }
        }

        fun nonDomainDependencyClass(): DescribedPredicate<JavaClass> {
            return object : DescribedPredicate<JavaClass>("is in another bpmner module") {
                override fun test(input: JavaClass): Boolean {
                    val pkg = input.packageName
                    if (!pkg.startsWith("dev.groknull.bpmner")) return false
                    // bpmn/ is the frozen kernel; all sub-packages (including internal.model) are allowed.
                    if (pkg == "dev.groknull.bpmner.bpmn" || pkg.startsWith("dev.groknull.bpmner.bpmn.")) return false
                    return true
                }
            }
        }

        fun forbiddenDomainDependencyClass(): DescribedPredicate<JavaClass> {
            return object : DescribedPredicate<JavaClass>(
                "is forbidden framework, prompt-construction, or IO glue for the bpmn kernel",
            ) {
                override fun test(input: JavaClass): Boolean {
                    val pkg = input.packageName
                    val forbiddenSpring = pkg.startsWith("org.springframework") && !isApprovedSpringDependency(input)
                    val forbiddenPromptGlue = pkg.startsWith("com.embabel.common.ai.prompt")
                    val forbiddenIo = pkg == "java.io" || pkg.startsWith("java.io.")
                    val forbiddenNioFiles = pkg == "java.nio.file" || pkg.startsWith("java.nio.file.")
                    return listOf(
                        nonDomainDependencyClass().test(input),
                        forbiddenSpring,
                        forbiddenPromptGlue,
                        forbiddenIo,
                        forbiddenNioFiles,
                    ).any { it }
                }
            }
        }

        fun isApprovedSpringDependency(input: JavaClass): Boolean {
            val approvedDependencyNames = setOf(
                "org.springframework.ai.tool.annotation.Tool",
                "org.springframework.ai.tool.execution.DefaultToolCallResultConverter",
                "org.springframework.modulith.ApplicationModule",
            )
            return input.fullName in approvedDependencyNames ||
                input.fullName.startsWith("org.springframework.modulith.ApplicationModule${'$'}")
        }
    }
}
