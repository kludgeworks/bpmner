/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
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
     * Telemetry event-surface guard (ADR-451-7).
     *
     * A class in `..telemetry.internal.adapter.inbound..` may import from another capability
     * (alignment / authoring / conformance / readiness) ONLY:
     *   (a) types whose simple name ends with `Event` — the published event surface, OR
     *   (b) types in [TELEMETRY_PAYLOAD_ALLOWLIST] — payload types reachable through those
     *       events' payload fields (verified in PLAN-451-8 §3.3).
     *
     * This makes ADR-451-7's drift non-recurrable: any future import of a non-event,
     * non-payload-reachable cross-capability type will fail CI.
     *
     * Non-vacuity proof (D5, R6, goal 8): asserts the subject set is non-empty AND that a
     * synthetic non-event cross-capability class would be matched by the forbidden predicate
     * (i.e. the guard is not vacuous in either dimension).
     */
    @Test
    fun `telemetry inbound listeners may only import event surface and payload-reachable types from other capabilities`() {
        val rule =
            noClasses()
                .that()
                .resideInAPackage("..telemetry.internal.adapter.inbound..")
                .should()
                .dependOnClassesThat(crossCapabilityNonEventSurface())
        rule.check(classes)

        // Non-vacuity proof (D5): subject set must be non-empty
        val subjectCount = classes.count { cls ->
            cls.packageName.let { pkg ->
                pkg.startsWith("dev.groknull.bpmner.telemetry.internal.adapter.inbound")
            }
        }
        assertThat(subjectCount)
            .describedAs(
                "Telemetry event-surface guard: subject set (classes in " +
                    "..telemetry.internal.adapter.inbound..) must be non-empty. " +
                    "If 0, the guard passes vacuously — check the package structure.",
            )
            .isGreaterThan(0)

        // Non-vacuity proof (D5): the forbidden predicate must distinguish forbidden from allowed.
        // Evaluate the predicate logic inline for two synthetic cases:
        //   (1) A type in a cross-capability package whose name does NOT end with "Event" and is NOT
        //       in TELEMETRY_PAYLOAD_ALLOWLIST → must be matched as forbidden.
        //   (2) A type in a cross-capability package whose name DOES end with "Event" → must NOT be
        //       matched (it is on the event surface and is allowed).
        val syntheticForbiddenPkg = "dev.groknull.bpmner.alignment"
        val syntheticForbiddenName = "AlignmentHelperService"
        val isSyntheticForbidden = isCrossCapabilityNonEventSurface(syntheticForbiddenPkg, syntheticForbiddenName)
        assertThat(isSyntheticForbidden)
            .describedAs(
                "Telemetry event-surface guard non-vacuity: a synthetic cross-capability non-event " +
                    "type (pkg=alignment, name=AlignmentHelperService) must be matched as forbidden. " +
                    "If false, the crossCapabilityNonEventSurface predicate is broken.",
            )
            .isTrue()

        val syntheticAllowedPkg = "dev.groknull.bpmner.alignment"
        val syntheticAllowedName = "BpmnAlignmentCheckedEvent"
        val isSyntheticAllowed = isCrossCapabilityNonEventSurface(syntheticAllowedPkg, syntheticAllowedName)
        assertThat(isSyntheticAllowed)
            .describedAs(
                "Telemetry event-surface guard non-vacuity: a synthetic cross-capability *Event type " +
                    "(pkg=alignment, name=BpmnAlignmentCheckedEvent) must NOT be matched as forbidden. " +
                    "If true, the crossCapabilityNonEventSurface predicate would incorrectly block events.",
            )
            .isFalse()
    }

    /**
     * S9 boundary-guard audit note (ADR-451-8 §4.4 best-effort, lines 921–923):
     *
     * The four root-package `internal` leaks (L1–L4) identified by ADR-451-8 are structurally
     * fixed by S9: L2 (`BpmnContractFidelityChecker`), L3 (`DefaultFlowAssigner`), and
     * L4 (`BpmnRequestResolver`) are relocated to `authoring.internal.domain` and exposed only
     * via `BpmnContractFidelityPort`, `BpmnDefaultFlowPort`, and `BpmnRequestResolutionPort`
     * respectively — Modulith's `verify()` (mechanism 1) now enforces the `*.internal.*` package
     * path and will reject any future cross-module direct reach (REVIEW-451-9 #5, disposition-a).
     * L1 (`ProcessContractMarkdownRenderer`) is converted to disposition (b): drops `internal`
     * (deliberate public API of `contract`).
     *
     * A programmatic ArchUnit guard for "Kotlin `internal` modifier on a root-package type
     * imported cross-module" is not feasible from bytecode: the Kotlin compiler compiles
     * `internal` to package-private JVM visibility with name mangling, not to a flag ArchUnit
     * can query on `JavaClass`. Relying on `verify()` (which enforces the `*.internal.*` package
     * path) plus the per-PR audit documented in PLAN-451-9 §5 is the correct ongoing posture.
     * The structural re-seam (mechanism 1) is more robust than a lint heuristic (mechanism 2).
     */

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
                "RetryableBpmnGenerationException",
                "RuleCategory",
                "RuleDiagnostic",
                "RuleEvaluation",
                "RuleMetadata",
                "RuleSeverity",
                "StandardLoopCharacteristics",
            )

        /**
         * Payload-reachable types that telemetry inbound listeners may depend on from other
         * capabilities in addition to `*Event` types. These are types reachable through the
         * payload fields of the events telemetry consumes (ADR-451-7 lines 854–865;
         * PLAN-451-8 §3.3 verified imports). ArchUnit checks bytecode dependencies (method
         * calls), so types accessed via event payload fields — even without explicit imports —
         * must be listed here.
         *
         * Direct payload fields of consumed events:
         * - BpmnAlignmentReport: BpmnAlignmentCheckedEvent.report field
         * - ProcessInputAssessment: BpmnReadinessAssessedEvent.assessment field
         *
         * Second-level reachable types (fields of the above):
         * - AlignmentIssue: BpmnAlignmentReport.issues: List<AlignmentIssue>
         * - AlignmentClassification: AlignmentIssue.classification field
         * - GlobalDiagnostics: used with BpmnValidationFailedEvent.diagnostics (payload utility)
         * - BpmnDiagnosticSource: BpmnDiagnostic.source (payload field)
         * - BpmnDiagnostic: BpmnValidationFailedEvent.diagnostics: List<BpmnDiagnostic>
         */
        val TELEMETRY_PAYLOAD_ALLOWLIST: Set<String> =
            setOf(
                "AlignmentClassification",
                "AlignmentIssue",
                "BpmnAlignmentReport",
                "GlobalDiagnostics",
                "BpmnDiagnosticSource",
                "BpmnDiagnostic",
                "ProcessInputAssessment",
            )

        /**
         * The other capability packages that telemetry inbound listeners consume events from.
         * Used by [crossCapabilityNonEventSurface] to scope the guard to cross-capability imports only.
         */
        val CROSS_CAPABILITY_PACKAGES: List<String> =
            listOf(
                "dev.groknull.bpmner.alignment",
                "dev.groknull.bpmner.authoring",
                "dev.groknull.bpmner.conformance",
                "dev.groknull.bpmner.readiness",
            )

        /**
         * Pure-function helper that evaluates the crossCapabilityNonEventSurface predicate
         * logic given a package name and simple class name. Used by the non-vacuity proof
         * (D5) to assert predicate correctness without needing a real JavaClass instance.
         */
        fun isCrossCapabilityNonEventSurface(pkg: String, simpleName: String): Boolean {
            val isCrossCapability = CROSS_CAPABILITY_PACKAGES.any { pkg == it || pkg.startsWith("$it.") }
            if (!isCrossCapability) return false
            val isEventSurface = simpleName.endsWith("Event") || simpleName in TELEMETRY_PAYLOAD_ALLOWLIST
            return !isEventSurface
        }

        /**
         * ArchUnit predicate that matches cross-capability types not on the telemetry event surface.
         *
         * A class is "forbidden" for telemetry inbound imports iff:
         *   - its package starts with one of the cross-capability capability prefixes, AND
         *   - its simple name does NOT end with "Event" (not a published event type), AND
         *   - its simple name is NOT in [TELEMETRY_PAYLOAD_ALLOWLIST] (not payload-reachable).
         *
         * Implements ADR-451-7's rule: telemetry may consume another capability's published
         * event surface (`*Event` types) and the types reachable through those events' payload
         * fields — nothing else. (PLAN-451-8 §3.3; §5 S8 gate line 677.)
         */
        fun crossCapabilityNonEventSurface(): DescribedPredicate<JavaClass> {
            return object : DescribedPredicate<JavaClass>(
                "is a cross-capability type that is not on the telemetry event surface " +
                    "(not a *Event type and not in TELEMETRY_PAYLOAD_ALLOWLIST)",
            ) {
                override fun test(input: JavaClass): Boolean {
                    return isCrossCapabilityNonEventSurface(input.packageName, input.simpleName)
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
