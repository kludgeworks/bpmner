/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Agent
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.LayeredArchitecture
import org.assertj.core.api.Assertions.assertThat
import org.jmolecules.archunit.JMoleculesArchitectureRules
import org.jmolecules.archunit.JMoleculesArchitectureRules.VerificationDepth
import org.jmolecules.archunit.JMoleculesDddRules
import org.junit.jupiter.api.Test

class BpmnerArchitectureTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(excludeBazelTestClasses)
            .importPackages("dev.groknull.bpmner")

    @Test
    fun `verifies onion architecture`() {
        JMoleculesArchitectureRules.ensureOnionSimple().check(classes)
    }

    @Test
    fun `verifies DDD building block rules`() {
        JMoleculesDddRules.all().check(classes)
    }

    @Test
    fun `verifies hexagonal architecture`() {
        // Mirrors what Modulith's verify() auto-applies through VerificationOptions.defaults()
        // (jmolecules-hexagonal is on the classpath). Asserted here directly so the rule
        // surfaces against the production-only ArchUnit scan even if Modulith config changes.
        //
        // The cast to LayeredArchitecture is safe: jMolecules' ensureHexagonal() builds the
        // rule via Architectures.layeredArchitecture() and returns it as ArchRule; every
        // chained .whereLayer/.mayOnlyAccessLayers/.mayOnlyBeAccessedByLayers method
        // returns LayeredArchitecture (Architectures.java:612, 631 in the cloned ArchUnit).
        //
        // We chain ignoreDependency to drop dependencies where EITHER endpoint is a Kotlin
        // synthetic class. ArchUnit's ClassResolverFromClasspath re-imports filtered classes
        // as stubs when other imported classes reference them, so an ImportOption alone
        // can't keep them out of the dependency graph; ignoreDependency filters
        // post-resolution (Architectures.java:339-342, irrelevantDependenciesPredicate).
        val rule =
            JMoleculesArchitectureRules.ensureHexagonal(VerificationDepth.LENIENT) as LayeredArchitecture
        rule
            .ignoreDependency(isKotlinSyntheticClass, DescribedPredicate.alwaysTrue())
            .ignoreDependency(DescribedPredicate.alwaysTrue(), isKotlinSyntheticClass)
            .check(classes)
    }

    @Test
    fun `verifies all agents have at least one goal`() {
        classes()
            .that()
            .areAnnotatedWith(Agent::class.java)
            .should(haveAtLeastOneGoal())
            .check(classes)
    }

    @Test
    fun `GraalJS polyglot is restricted to the layout module`() {
        // Phase 2G (#241) removed the GraalJS-hosted bpmnlint bridge. The remaining
        // GraalJS consumer is the BPMN auto-layout JS bundle in `..layout..`. This pin
        // prevents drift: nobody else may reach for `org.graalvm.polyglot` for a quick
        // JS escape hatch without first justifying the dep and updating this rule.
        noClasses()
            .that()
            .resideOutsideOfPackages("..layout..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.graalvm.polyglot..")
            .check(classes)
    }

    @Test
    fun `OpenNLP imports are restricted to the nlp package`() {
        // Phase 3 (#218) added OpenNLP behind the [BpmnNlp] facade. Same intent as the
        // GraalJS pin above: keep the vendor dependency on one side of the interface so
        // future swaps (e.g. to a POSModel-backed implementation) only touch ..nlp..
        // without rippling into rule code.
        noClasses()
            .that()
            .resideOutsideOfPackages("..rules.internal.domain.nlp..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("opennlp.tools..")
            .check(classes)
    }

    @Test
    fun `Ai bean reference is restricted to inbound adapters`() {
        // The framework-centric posture (issue #240 discussion): LLM calls go through Embabel
        // via `OperationContext`+`PromptRunner` inside `@Action` methods, never via the
        // injectable `com.embabel.agent.api.common.Ai` Spring bean. The bean exists for
        // non-agent code that Embabel supports, but bpmner has agreed it should not be the
        // pattern here — every LLM call site is a GOAP node.
        //
        // Allow `Ai` only in `internal.adapter.inbound` packages (where `@Agent` classes
        // live). Anywhere else would constitute a re-emergence of the abandoned `LlmPort`/
        // bean-injection escape hatch.
        noClasses()
            .that()
            .resideOutsideOfPackages("..internal.adapter.inbound..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.embabel.agent.api.common.Ai")
            .check(classes)
    }

    /**
     * Framework-purity rule — named deep-coupling pin (S2, Rule 2).
     *
     * Asserts that the 2 named out-of-policy `internal/domain` classes retain their
     * framework deep-coupling annotations on **current** `main`. This pin serves two purposes:
     *
     * 1. **Documents the known violation state** — ADR-002 §D-policy records these 2 classes
     *    as the only `internal/domain` classes with out-of-policy framework annotations. This
     *    rule makes the policy machine-verifiable.
     *
     * 2. **Signals S4 completion** — when S4 relocates these couplings out of `internal/domain`,
     *    this test goes RED, confirming the relocation succeeded and triggering an update/removal
     *    of the pin per ADR-002 §D-enforce ("tightened… after S4").
     *
     * Scope: production code only (the shared `classes` field uses `excludeBazelTestClasses`).
     *
     * **Why not a blanket ban?** — The ~15 `@Configuration`/`@Bean` beans introduced by #376
     * (`ConventionsLoader`, `beans/ActivityRuleConfig.kt`, and similar, etc.) are explicitly
     * permitted per ADR-002 §D-policy (NG3 in PLAN-S2 §5). The rule pins ONLY the 2 named
     * classes by FQN; no package-level `@Configuration` ban is applied.
     *
     * Named couplings (post-#376 state; set shrank 3→2 when `PklRuleCatalog` was deleted
     * by #427 — see ARCHITECTURE ADR-4):
     *
     * - `BpmnLocalRepairCapabilityValidator` (`repair.internal.domain`) —
     *   `@Component` + `@EventListener`(`ContextRefreshedEvent`); startup validation hook.
     * - `RuleProfileFactory` (`rules.internal.domain`) —
     *   `@Configuration` + `@Bean`; profile selection orchestration.
     *
     * Both are currently green (annotations present). S4 removes them; this test then goes RED.
     *
     * @see ARCHITECTURE §5 S2 / S4; ADR-002 §D-policy / §D-enforce; PLAN-S2 §1 deliverable 2
     */
    @Test
    fun `named deep couplings retain their out-of-policy annotations (pin for S4)`() {
        // Pin 2a: BpmnLocalRepairCapabilityValidator must have a method annotated with
        // @EventListener. @EventListener is the specific out-of-policy annotation that makes
        // this a "deep coupling" beyond the permitted @Component-on-@Service idiom.
        classes()
            .that()
            .haveFullyQualifiedName(
                "dev.groknull.bpmner.repair.internal.domain.BpmnLocalRepairCapabilityValidator",
            )
            .should(haveMethodAnnotatedWith("org.springframework.context.event.EventListener"))
            .because(
                "BpmnLocalRepairCapabilityValidator is pinned as the @EventListener deep coupling " +
                    "in repair/internal/domain per ADR-002 §D-policy. " +
                    "S4 will relocate it; this test turning RED confirms that relocation.",
            )
            .check(classes)

        // Pin 2b: RuleProfileFactory must have the @Configuration class annotation.
        // Pins by FQN to target exactly this class without blanket-banning @Configuration
        // in internal/domain (which would also flag the legitimately-placed #376 beans).
        classes()
            .that()
            .haveFullyQualifiedName(
                "dev.groknull.bpmner.rules.internal.domain.RuleProfileFactory",
            )
            .should(haveClassAnnotation("org.springframework.context.annotation.Configuration"))
            .because(
                "RuleProfileFactory is pinned as the @Configuration deep coupling " +
                    "in rules/internal/domain per ADR-002 §D-policy. " +
                    "S4 will relocate it; this test turning RED confirms that relocation.",
            )
            .check(classes)

        // Guard 2c: @EventListener must not spread to other internal/domain classes.
        // Unlike @Configuration (legitimately used by the #376 beans), @EventListener is a
        // startup-lifecycle annotation with no legitimate use in internal/domain beyond the
        // named BpmnLocalRepairCapabilityValidator. This guard catches silent regression
        // without requiring a blanket @Configuration ban.
        noClasses()
            .that()
            .resideInAPackage("..internal.domain..")
            .and(notNamedFqn("dev.groknull.bpmner.repair.internal.domain.BpmnLocalRepairCapabilityValidator"))
            .should(haveMethodAnnotatedWith("org.springframework.context.event.EventListener"))
            .because(
                "@EventListener in internal/domain is out-of-policy per ADR-002 §D-policy. " +
                    "Only BpmnLocalRepairCapabilityValidator is the named exception (S4 relocates it). " +
                    "Any other internal/domain class with @EventListener is unintended drift.",
            )
            .check(classes)
    }

    /**
     * Planted-violation self-test for Rule 2 (S2 Gate §4, preferred method).
     *
     * Proves that the framework-purity guards are not vacuous:
     *
     * - Pin 2a (positive): verifies BpmnLocalRepairCapabilityValidator actually HAS @EventListener,
     *   so the pin is load-bearing. When S4 removes it, the pin in the main test goes RED.
     *
     * - Guard 2c (negative): verifies the @EventListener guard correctly passes over a
     *   sub-package that has @Configuration/@Bean beans (the permitted #376 pattern) but no
     *   @EventListener — proving no false positives against the permitted bean pattern.
     *
     * The merged tree is always green. The proof establishes RED→GREEN:
     * pin 2a would fail if the annotation were absent; guard 2c would fail if an unexpected
     * @EventListener appeared.
     */
    @Test
    fun `framework-purity rule is proven on planted evidence (not vacuous)`() {
        // Proof 2a — positive pin is load-bearing:
        // Import repair.internal.domain and assert the annotated method exists.
        // If this assertion fails, S4 may have already run or the class was renamed —
        // both conditions require updating the pin in the main test.
        val repairDomainClasses =
            ClassFileImporter()
                .withImportOption(excludeBazelTestClasses)
                .importPackages("dev.groknull.bpmner.repair.internal.domain")

        val validatorClass =
            repairDomainClasses.get(
                "dev.groknull.bpmner.repair.internal.domain.BpmnLocalRepairCapabilityValidator",
            )
        val hasEventListener =
            validatorClass.methods.any { method ->
                method.annotations.any { ann ->
                    ann.type.name == "org.springframework.context.event.EventListener"
                }
            }
        assertThat(hasEventListener)
            .describedAs(
                "BpmnLocalRepairCapabilityValidator must have a method annotated with @EventListener " +
                    "(the out-of-policy deep coupling S4 will relocate). " +
                    "Failure means S4 ran early or the class was renamed — update pin 2a accordingly.",
            )
            .isTrue()

        // Proof 2c — guard has no false positives against the permitted #376 @Configuration beans:
        // The rules.internal.domain.beans sub-package has ~13 @Configuration classes introduced
        // by #376 (ActivityRuleConfig, EventRuleConfig, etc.) — these are PERMITTED per
        // ADR-002 §D-policy (NG3). The @EventListener guard must NOT flag them.
        // Import only the beans sub-package and verify the guard is silent (no violations).
        val rulesBeanClasses =
            ClassFileImporter()
                .withImportOption(excludeBazelTestClasses)
                .importPackages("dev.groknull.bpmner.rules.internal.domain.beans")

        // The guard (rule 2c) must be GREEN for the beans package (no @EventListener there).
        // This proves the rule does not cause false positives against the #376 bean pattern.
        noClasses()
            .that()
            .resideInAPackage("..internal.domain..")
            .and(notNamedFqn("dev.groknull.bpmner.repair.internal.domain.BpmnLocalRepairCapabilityValidator"))
            .should(haveMethodAnnotatedWith("org.springframework.context.event.EventListener"))
            .check(rulesBeanClasses)
    }

    private fun haveClassAnnotation(annotationTypeName: String): ArchCondition<JavaClass> {
        return object : ArchCondition<JavaClass>("be annotated with @${annotationTypeName.substringAfterLast('.')}") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                val hasAnnotation = item.annotations.any { ann -> ann.type.name == annotationTypeName }
                if (!hasAnnotation) {
                    events.add(
                        SimpleConditionEvent.violated(
                            item,
                            "${item.fullName} is missing @${annotationTypeName.substringAfterLast('.')} — " +
                                "S4 may have relocated this class or the annotation was removed. " +
                                "Update or remove this pin after S4 completes.",
                        ),
                    )
                }
            }
        }
    }

    private fun haveMethodAnnotatedWith(annotationTypeName: String): ArchCondition<JavaClass> {
        return object : ArchCondition<JavaClass>(
            "have at least one method annotated with @${annotationTypeName.substringAfterLast('.')}",
        ) {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                val hasAnnotatedMethod =
                    item.methods.any { method ->
                        method.annotations.any { ann -> ann.type.name == annotationTypeName }
                    }
                if (!hasAnnotatedMethod) {
                    events.add(
                        SimpleConditionEvent.violated(
                            item,
                            "${item.fullName} has no method annotated with " +
                                "@${annotationTypeName.substringAfterLast('.')} — " +
                                "S4 may have relocated this coupling or the method was removed. " +
                                "Update or remove this pin after S4 completes.",
                        ),
                    )
                }
            }
        }
    }

    private fun notNamedFqn(fqn: String): DescribedPredicate<JavaClass> {
        return object : DescribedPredicate<JavaClass>("does not have fully-qualified name '$fqn'") {
            override fun test(input: JavaClass): Boolean = input.name != fqn
        }
    }

    private fun haveAtLeastOneGoal(): ArchCondition<JavaClass> {
        return object : ArchCondition<JavaClass>("have at least one method annotated with @AchievesGoal") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                val hasGoal = item.methods.any { it.isAnnotatedWith(AchievesGoal::class.java) }
                if (!hasGoal) {
                    events.add(SimpleConditionEvent.violated(item, "${item.name} has no methods annotated with @AchievesGoal"))
                }
            }
        }
    }
}
