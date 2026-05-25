/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

import org.springframework.modulith.ApplicationModule

/**
 * Core module — annotated data class implementations of the [dev.groknull.bpmner.api]
 * interfaces plus the BPMN domain model, configuration container, and graph types
 * consumed by every downstream module.
 */
@ApplicationModule(allowedDependencies = ["api"])
internal object CoreModule
