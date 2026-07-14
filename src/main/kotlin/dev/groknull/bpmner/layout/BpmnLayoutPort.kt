/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout

import org.jmolecules.architecture.onion.simplified.ApplicationRing

@ApplicationRing
fun interface BpmnLayoutPort {
    fun layout(xml: String): String
}
