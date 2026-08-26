/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnDefinition
import org.jmolecules.architecture.onion.simplified.ApplicationRing

/**
 * Reads BPMN XML back into the domain model — the inverse of [BpmnRenderer].
 *
 * Published alongside [BpmnRenderer] because the pipeline needs it to verify that auto-layout
 * returned the same process it was given: layout replaces the XML wholesale while the
 * `BpmnDefinition` beside it is carried through untouched, so without a parse-back the artifact
 * that alignment checks and the artifact the user receives are never reconciled (issue #746).
 */
@ApplicationRing
fun interface BpmnXmlParser {
    fun parse(xml: String): BpmnDefinition
}
