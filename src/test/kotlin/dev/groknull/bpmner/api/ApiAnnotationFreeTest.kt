/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Guards the `api` shared-kernel module: its **contract types** must never depend on Jackson,
 * Jakarta Validation, Spring, or Embabel. Annotated implementations stay in `core/`; `api/` is
 * the neutral contract layer everyone — including future Tier-3 plugin authors — can depend on
 * without pulling in the framework world.
 *
 * Excluded: module-marker objects (suffix `Module`, e.g. `ApiModule`). They carry the
 * `@org.springframework.modulith.ApplicationModule` annotation for Spring Modulith's module-
 * boundary gate (#215), which is build-tooling metadata — not a runtime dependency that any
 * api consumer would observe. The exclusion is by simple-name suffix.
 */
class ApiAnnotationFreeTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("dev.groknull.bpmner.api")

    @Test
    fun `api types must not depend on Jackson`() {
        noClasses()
            .that()
            .resideInAPackage("..bpmner.api..")
            .and()
            .haveSimpleNameNotEndingWith("Module")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.fasterxml.jackson..")
            .check(classes)
    }

    @Test
    fun `api types must not depend on Jakarta`() {
        noClasses()
            .that()
            .resideInAPackage("..bpmner.api..")
            .and()
            .haveSimpleNameNotEndingWith("Module")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta..")
            .check(classes)
    }

    @Test
    fun `api types must not depend on Spring`() {
        noClasses()
            .that()
            .resideInAPackage("..bpmner.api..")
            .and()
            .haveSimpleNameNotEndingWith("Module")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..")
            .check(classes)
    }

    @Test
    fun `api types must not depend on Embabel`() {
        noClasses()
            .that()
            .resideInAPackage("..bpmner.api..")
            .and()
            .haveSimpleNameNotEndingWith("Module")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.embabel..")
            .check(classes)
    }
}
