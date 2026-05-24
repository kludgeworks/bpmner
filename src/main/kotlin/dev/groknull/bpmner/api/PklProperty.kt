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
 * Retention is BINARY because the generator reads compiled class metadata — RUNTIME would be
 * unnecessary and pulls reflection into the deployment artifact.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.FIELD)
annotation class PklProperty(
    val path: String = "",
)
