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
@ApplicationModule(allowedDependencies = [])
internal object BpmnModule
