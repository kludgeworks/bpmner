/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

@PackageInfo
@ApplicationModule(
    allowedDependencies = ["core", "readiness", "observability", "alignment", "validation", "contract"],
)
internal class ModuleMetadata
