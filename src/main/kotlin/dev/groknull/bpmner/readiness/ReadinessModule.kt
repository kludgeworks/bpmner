/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import org.springframework.modulith.ApplicationModule

/**
 * Readiness module — owns the readiness dimensions and readiness-classification logic.
 * Depends only on [dev.groknull.bpmner.domain] for the BPMN domain model.
 */
@ApplicationModule(allowedDependencies = ["bpmn", "config"])
internal object ReadinessModule
