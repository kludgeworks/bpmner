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
            .resideOutsideOfPackages("..ruleset.internal.domain.nlp..")
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

    @Test
    fun `only permitted validation classes may reach rules primary ports`() {
        // ACL pin (ADR-23 Decision 2): RuleEngineLintingAdapter is the Anti-Corruption Layer over
        // rules' @PrimaryPort interfaces (RuleEngine, RuleRegistry). Adding @SecondaryAdapter would
        // yield 7 ensureHexagonal(LENIENT) violations — the omission is intentional.
        // LlmValidator (@Deprecated) is a pre-existing second reacher of RuleRegistry; it is
        // excluded here as a named, audited exception pending its removal in a follow-on phase.
        // No other validation class may reach rules @PrimaryPorts directly.
        noClasses()
            .that()
            .resideInAPackage("..conformance..")
            .and()
            .doNotHaveSimpleName("RuleEngineLintingAdapter")
            .and()
            .doNotHaveSimpleName("LlmValidator")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("dev.groknull.bpmner.ruleset.RuleEngine")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("dev.groknull.bpmner.ruleset.RuleRegistry")
            .because(
                "Only RuleEngineLintingAdapter (ACL, ADR-23 Decision 2) and the deprecated " +
                    "LlmValidator (audited exception, pending removal) may reach rules' " +
                    "@PrimaryPorts; all other validation classes must go through BpmnLintingPort",
            )
            .check(classes)
    }

    @Test
    fun `internal domain classes are free of deep framework couplings`() {
        noClasses()
            .that()
            .resideInAPackage("..internal.domain..")
            .should(haveMethodAnnotatedWith("org.springframework.context.event.EventListener"))
            .check(classes)

        noClasses()
            .that()
            .resideInAPackage("..internal.domain..")
            .and()
            .resideOutsideOfPackages("..ruleset.internal.domain.beans..")
            .should(haveClassAnnotation("org.springframework.context.annotation.Configuration"))
            .check(classes)
    }

    @Test
    fun `framework-purity relocation keeps adapter configuration outside internal domain`() {
        classes()
            .that()
            .haveFullyQualifiedName(
                "dev.groknull.bpmner.repair.internal.adapter.inbound.BpmnRepairCapabilityStartupListener",
            )
            .should(haveMethodAnnotatedWith("org.springframework.context.event.EventListener"))
            .check(classes)

        classes()
            .that()
            .haveFullyQualifiedName(
                "dev.groknull.bpmner.ruleset.internal.adapter.inbound.RuleProfileConfiguration",
            )
            .should(haveClassAnnotation("org.springframework.context.annotation.Configuration"))
            .check(classes)
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
                                "the class may have been relocated or the annotation was removed; " +
                                "update or remove this pin if the FQN changed.",
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
                                "the coupling may have been relocated or the method was removed; " +
                                "update or remove this pin if the FQN changed.",
                        ),
                    )
                }
            }
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

    @Test
    fun `bpmn kernel is free of framework, IO, and cross-module dependencies`() {
        val rule =
            noClasses()
                .that()
                .resideInAPackage("..bpmner.bpmn..")
                .should()
                .dependOnClassesThat(forbiddenDomainDependencyClass())
        rule.check(classes)
    }

    private fun nonDomainDependencyClass(): DescribedPredicate<JavaClass> {
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

    private fun forbiddenDomainDependencyClass(): DescribedPredicate<JavaClass> {
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

    private fun isApprovedSpringDependency(input: JavaClass): Boolean {
        val approvedDependencyNames = setOf(
            "org.springframework.ai.tool.annotation.Tool",
            "org.springframework.ai.tool.execution.DefaultToolCallResultConverter",
            "org.springframework.modulith.ApplicationModule",
        )
        return input.fullName in approvedDependencyNames ||
            input.fullName.startsWith("org.springframework.modulith.ApplicationModule${'$'}")
    }
}
