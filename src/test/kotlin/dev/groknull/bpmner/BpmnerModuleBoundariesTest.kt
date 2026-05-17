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
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

class BpmnerModuleBoundariesTest {
    private val importer = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests())
    private val classes =
        importer
            .withImportOption { url -> !url.contains("bpmner_tests_lib") && !url.contains("test_classes") }
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

    private fun notHaveDomainName(): ArchCondition<JavaClass> =
        object : ArchCondition<JavaClass>("not have a domain-flavored name") {
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
                "BpmnRepairConfig",
                "ClarificationExchange",
            )

        fun notAllowedInCore(): DescribedPredicate<JavaClass> =
            object : DescribedPredicate<JavaClass>("is not on the core allowlist") {
                override fun test(input: JavaClass): Boolean = input.simpleName !in CORE_ALLOWLIST
            }

        fun internalNonCoreClass(): DescribedPredicate<JavaClass> =
            object : DescribedPredicate<JavaClass>("is in another bpmner module") {
                override fun test(input: JavaClass): Boolean {
                    val pkg = input.packageName
                    if (!pkg.startsWith("dev.groknull.bpmner")) return false
                    if (pkg == "dev.groknull.bpmner.core" || pkg.startsWith("dev.groknull.bpmner.core.")) return false
                    return true
                }
            }
    }
}
