/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import org.springframework.modulith.ApplicationModule

/**
 * Contract module — process contract derivation, owns the contract-prompt scaffolding
 * for downstream LLM contract elicitation.
 */
@ApplicationModule
internal object ContractModule
