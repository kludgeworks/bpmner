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
@ApplicationModule
internal object ApiModule
