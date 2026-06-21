/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.web

import org.springframework.modulith.ApplicationModule

/**
 * Web module — HTTP entry point for the generation pipeline. The thinnest possible
 * adapter over the generation module.
 */
@ApplicationModule(allowedDependencies = ["authoring", "bpmn"])
internal object WebModule
