/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair

import org.springframework.modulith.ApplicationModule

/**
 * Repair module — refinement engine, repair routing (local-fix vs LLM-patch),
 * and contract-aware re-validation. Depends on generation (XML conversion +
 * fidelity check) and validation (lint / XSD / rule catalog) to do its work.
 */
@ApplicationModule(allowedDependencies = ["api", "contract", "core", "generation", "rules", "validation"])
internal object RepairModule
