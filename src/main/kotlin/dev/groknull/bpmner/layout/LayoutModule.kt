/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout

import org.springframework.modulith.ApplicationModule

/**
 * Layout module — graph layout pass that decorates the BPMN definition with
 * visual coordinates. Triggers downstream repair via re-validation when the
 * laid-out form surfaces additional structural issues.
 */
@ApplicationModule(allowedDependencies = ["api", "core", "repair", "validation"])
internal object LayoutModule
