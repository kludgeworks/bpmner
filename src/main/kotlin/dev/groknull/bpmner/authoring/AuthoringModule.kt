/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import org.springframework.modulith.ApplicationModule

/**
 * Authoring module — XML rendering, BPMN model factory, contract-fidelity checking
 * and default-flow assignment. Hosts the agent-platform invoker that drives LLM
 * generation.
 */
@ApplicationModule
internal object AuthoringModule
