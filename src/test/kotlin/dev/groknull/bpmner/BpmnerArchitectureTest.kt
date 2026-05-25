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
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.library.Architectures.LayeredArchitecture
import org.jmolecules.archunit.JMoleculesArchitectureRules
import org.jmolecules.archunit.JMoleculesArchitectureRules.VerificationDepth
import org.jmolecules.archunit.JMoleculesDddRules
import org.junit.jupiter.api.Test

class BpmnerArchitectureTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
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
