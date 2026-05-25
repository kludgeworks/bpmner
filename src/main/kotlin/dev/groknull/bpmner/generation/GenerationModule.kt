/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import org.springframework.modulith.ApplicationModule

/**
 * Generation module — XML rendering, BPMN model factory, contract-fidelity checking
 * and default-flow assignment. Hosts the agent-platform invoker that drives LLM
 * generation.
 */
@ApplicationModule(allowedDependencies = ["alignment", "api", "contract", "core", "readiness", "validation"])
internal object GenerationModule
