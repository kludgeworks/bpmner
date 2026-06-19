/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

import org.springframework.modulith.ApplicationModule

/**
 * Config module — Spring `@ConfigurationProperties` bindings and the pipeline-wide
 * configuration glue. It intentionally declares no internal module dependencies.
 */
@ApplicationModule(allowedDependencies = [])
internal object ConfigModule
