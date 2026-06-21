/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

import org.springframework.modulith.ApplicationModule

/**
 * Bpmn kernel root tier — the shared kernel of annotation-free Kotlin interfaces and value types
 * that every other module depends on. Leaf node of the application's module graph: this
 * module imports zero other bpmner modules. The internal/model tier (Jackson/Jakarta) is part
 * of this same compilation unit.
 */
@ApplicationModule(allowedDependencies = [])
internal object BpmnModule
