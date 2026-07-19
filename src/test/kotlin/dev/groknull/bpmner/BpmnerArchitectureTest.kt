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
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import org.jmolecules.archunit.JMoleculesDddRules
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener

class BpmnerArchitectureTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(excludeBazelTestClasses)
            .importPackages("dev.groknull.bpmner")

    @Test
    fun `verifies DDD building block rules`() {
        JMoleculesDddRules.all().check(classes)
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
    fun `internal domain classes are free of deep framework couplings`() {
        noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..internal.domain..")
            .and()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackages("..ruleset.internal.domain.beans..")
            .should()
            .beAnnotatedWith(EventListener::class.java)
            .check(classes)

        noClasses()
            .that()
            .resideInAPackage("..internal.domain..")
            .and()
            .resideOutsideOfPackages("..ruleset.internal.domain.beans..")
            .should()
            .beAnnotatedWith(Configuration::class.java)
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
