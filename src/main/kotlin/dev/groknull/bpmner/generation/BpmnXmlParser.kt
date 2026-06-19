/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.domain.BpmnDefinition
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
fun interface BpmnXmlParser {
    fun parse(xml: String): BpmnDefinition
}
