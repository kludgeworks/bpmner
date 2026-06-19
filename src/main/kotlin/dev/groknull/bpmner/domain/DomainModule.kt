/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.domain

import org.springframework.modulith.ApplicationModule

/** Domain kernel — shared BPMN graph model and pure cross-tier DTOs. */
@ApplicationModule(allowedDependencies = ["api"])
internal object DomainModule
