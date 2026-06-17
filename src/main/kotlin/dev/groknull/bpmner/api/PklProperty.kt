/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Marks a property whose name (or nested path, e.g. `eventDefinition.expression`) is exposed
 * to rule definitions.
 *
 * This annotation was used by `pkl-enum-gen` to generate Kotlin enum constants from Pkl source.
 * The codegen tool was removed in #386; annotated properties are now statically defined in
 * Kotlin bean configs (`*RuleConfig.kt`).
 *
 * The annotation is deprecated with no replacement. Existing usages are preserved for
 * compatibility but serve no functional purpose.
 *
 * Retention is BINARY for downstream compiled consumers.
 */
@Deprecated(
    message = "This annotation was used by pkl-enum-gen. The codegen tool was removed in #386 " +
        "(2026-06); annotated properties are now statically defined in Kotlin bean configs. " +
        "Scheduled for removal in 2027-06 once all consuming code is migrated.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.FIELD)
annotation class PklProperty(
    val path: String = "",
)
