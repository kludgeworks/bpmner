/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment

import org.springframework.modulith.ApplicationModule

/**
 * Alignment module — compares produced BPMN definitions against the upstream
 * contract, surfaces alignment classifications and feeds them back into the
 * pipeline.
 */
@ApplicationModule(allowedDependencies = ["api", "contract", "core", "readiness", "validation"])
internal object AlignmentModule
