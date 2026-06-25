/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.domain

import dev.groknull.bpmner.bpmn.BpmnDefinition
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
fun interface BpmnXmlParser {
    fun parse(xml: String): BpmnDefinition
}
