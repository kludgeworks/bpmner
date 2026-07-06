/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

import org.springframework.modulith.ApplicationModule

/**
 * Single-tier bpmn kernel — concrete BPMN data types, sealed hierarchy, pipeline graph
 * types, and value types that every other module depends on. Leaf node of the application's
 * module graph: this module imports zero other bpmner modules.
 */
@ApplicationModule
internal object BpmnModule

/**
 * Opt-in marker annotation for classes that are sanctioned exceptions to architecture rules.
 * Handled by [dev.groknull.bpmner.BpmnerArchitectureTest] to generalize name-based rule pins.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SanctionedArchitectureException(val reason: String)
