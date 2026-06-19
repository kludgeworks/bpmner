/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

import org.springframework.modulith.ApplicationModule

/**
 * Config module — Spring `@ConfigurationProperties` bindings and the pipeline-wide
 * configuration glue. Reads from [dev.groknull.bpmner.domain]; nothing else.
 */
@ApplicationModule(allowedDependencies = [])
internal object ConfigModule
