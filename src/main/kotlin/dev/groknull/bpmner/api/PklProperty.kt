/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Marks a property whose name (or nested path, e.g. `eventDefinition.expression`) is exposed
 * to Pkl rule definitions through the generated `NodeProperty.pkl` type alias. Annotated
 * properties are enumerated at build time by the `pkl-enum-gen` tool (#214).
 *
 * Retention is BINARY for downstream compiled consumers; the generator itself reads Kotlin source
 * files in Bazel and does not require runtime reflection.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.FIELD)
annotation class PklProperty(
    val path: String = "",
)
