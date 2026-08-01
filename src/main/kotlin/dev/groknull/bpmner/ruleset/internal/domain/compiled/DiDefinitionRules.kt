/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnAdHocSubProcess
import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnEventSubProcess
import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.BpmnSubProcess
import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.bpmn.RepairMetadata
import dev.groknull.bpmner.bpmn.RepairSafety
import dev.groknull.bpmner.bpmn.RuleCategory
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata
import dev.groknull.bpmner.bpmn.RuleSeverity
import org.springframework.stereotype.Component

/** An opt-in alternative retained outside component discovery so BPMN-DI remains the default. */
internal class NoBpmnDiRule : BpmnRule {
    override val id = "gen-no-bpmndi"
    override val metadata = RuleMetadata(
        id = id,
        name = "No BPMN-DI",
        slug = "no-bpmndi",
        category = RuleCategory.General,
        intent = "Disallow BPMN-DI when a model profile requires semantic-only BPMN.",
        forModellers = "Remove BPMN-DI only when the receiving tool does not require diagram geometry.",
        forAI = "Flag definitions that contain BPMN-DI when this alternative rule is explicitly selected.",
        targetElements = listOf("bpmndi:BPMNDiagram"),
        errorMessages = mapOf("default" to "BPMN-DI is not permitted by this alternative rule"),
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
        aliases = listOf("no-bpmndi"),
    )

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> =
        if (ctx.definition.diagramCount == 0) {
            emptyList()
        } else {
            listOf(
                RuleDiagnostic(id, id, RuleSeverity.ERROR, metadata.errorMessages.getValue("default"), ctx.definition.processId),
            )
        }
}

@Component
internal class NoOverlappingElementsRule : BpmnRule {
    override val id = "gen-no-overlapping-elements"
    override val metadata = RuleMetadata(
        id = id,
        name = "No Overlapping Elements",
        slug = "no-overlapping-elements",
        category = RuleCategory.General,
        intent = "Keep rendered BPMN element shapes from obscuring each other.",
        forModellers = "Keep element shapes separate; touching borders and contained subprocess geometry are allowed.",
        forAI = "Flag only positive-area intersections between eligible non-container BPMN shapes.",
        targetElements = listOf("bpmndi:BPMNShape"),
        errorMessages = mapOf("default" to "BPMN element shape overlaps another element shape"),
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
        aliases = listOf("no-overlapping-elements"),
    )

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> {
        val shapes = ctx.definition.diShapes
            .filter { (id, _) ->
                ctx.nodesById[id]?.let { node ->
                    node !is BpmnSubProcess && node !is BpmnAdHocSubProcess && node !is BpmnEventSubProcess
                } == true
            }
            .toList()
            .sortedBy { it.first }
        return buildList {
            shapes.forEachIndexed { index, (firstId, firstBounds) ->
                shapes.drop(index + 1)
                    .filter { (_, secondBounds) -> firstBounds.overlaps(secondBounds) }
                    .forEach { (secondId, _) ->
                        add(
                            RuleDiagnostic(
                                id,
                                id,
                                RuleSeverity.ERROR,
                                "${metadata.errorMessages.getValue("default")}: $firstId",
                                secondId,
                            ),
                        )
                    }
            }
        }
    }
}

private fun dev.groknull.bpmner.bpmn.BpmnDiShape.overlaps(other: dev.groknull.bpmner.bpmn.BpmnDiShape): Boolean =
    x < other.x + other.width && x + width > other.x && y < other.y + other.height && y + height > other.y
