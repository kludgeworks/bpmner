/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

import org.springframework.modulith.ApplicationModule

/**
 * API module — the shared kernel of annotation-free Kotlin interfaces and value types
 * that every other module depends on. Leaf node of the application's module graph: this
 * module imports zero other bpmner modules.
 */
// Strict leaf: `allowedDependencies = []` actively forbids any dependency on another
// bpmner module. Bare `@ApplicationModule` would leave the dependency list unrestricted
// (per the annotation's Javadoc), letting an accidental `import dev.groknull.bpmner.core.*`
// in the api module silently pass `modules.verify()`.
@ApplicationModule(allowedDependencies = [])
internal object ApiModule
