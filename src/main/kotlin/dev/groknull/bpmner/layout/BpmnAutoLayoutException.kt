/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout

/**
 * Thrown when the auto-layout engine fails to generate BPMNDI from semantic XML.
 */
class BpmnAutoLayoutException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
