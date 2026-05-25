/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.shell

import org.springframework.modulith.ApplicationModule

/**
 * Shell module — Spring Shell command surface for invoking the pipeline interactively.
 */
@ApplicationModule(allowedDependencies = ["api", "core", "generation", "readiness"])
internal object ShellModule
