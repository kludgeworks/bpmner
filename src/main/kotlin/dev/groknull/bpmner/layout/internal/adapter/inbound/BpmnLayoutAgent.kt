/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import dev.groknull.bpmner.layout.BpmnLayoutPort
import dev.groknull.bpmner.layout.LayoutedBpmnXml
import dev.groknull.bpmner.repair.AutoFixedBpmnXml
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import dev.groknull.bpmner.validation.FinalValidatedBpmnXml
import dev.groknull.bpmner.validation.ValidatedBpmnXml
import dev.groknull.bpmner.validation.XsdValidationIssue
import org.jmolecules.architecture.hexagonal.Application
import org.slf4j.LoggerFactory

/**
 * Owns the post-repair pipeline: auto-layout and final XSD validation.
 *
 * `autoFixBpmnXml` was historically a bounded pre-layout cleanup that routed `LOCAL_XML_FIX`
 * diagnostics through bpmnlint's TS auto-fixer. After #243 collapsed `LOCAL_XML_FIX` into
 * `LOCAL_MODEL_FIX`, every local repair runs in [DeterministicTopologyRepairStrategy] before this
 * agent ever sees the XML, so the auto-fix routing is dead. The `@Action` survives as a typed
 * passthrough because the Embabel GOAP plan threads `ValidatedBpmnXml → AutoFixedBpmnXml →
 * LayoutedBpmnXml`; removing it would break planning. The `AutoFixedBpmnXml` carrier type and
 * this `@Action` are slated for deletion in Phase 3 once the agent's input/output types can be
 * collapsed.
 */
@Application
@Agent(description = "Apply auto-layout and final validation to validated BPMN XML")
internal class BpmnLayoutAgent(
    private val layoutService: BpmnLayoutPort,
    private val bpmnXsdValidationPort: BpmnXsdValidationPort,
) {
    private val logger = LoggerFactory.getLogger(BpmnLayoutAgent::class.java)

    @Action(description = "Pre-layout passthrough (XML auto-fix retired with bpmnlint in #243)")
    fun autoFixBpmnXml(bpmn: ValidatedBpmnXml): AutoFixedBpmnXml = AutoFixedBpmnXml(definition = bpmn.definition, xml = bpmn.xml)

    @Action(description = "Apply auto-layout to the auto-fixed BPMN XML")
    fun layoutBpmnXml(bpmn: AutoFixedBpmnXml): LayoutedBpmnXml {
        val layoutedXml = layoutService.layout(bpmn.xml)
        return LayoutedBpmnXml(definition = bpmn.definition, xml = layoutedXml)
    }

    @AchievesGoal(
        description = "Apply auto-layout and final validation to validated BPMN XML",
        export = Export(name = "finalizeLayout", remote = true, startingInputTypes = [LayoutedBpmnXml::class]),
    )
    @Action(description = "XSD-validate the final layouted BPMN XML")
    fun validateFinalBpmnXml(bpmn: LayoutedBpmnXml): FinalValidatedBpmnXml {
        val xsdIssues = bpmnXsdValidationPort.validateDetailed(bpmn.xml)
        if (xsdIssues.isNotEmpty()) {
            throw BpmnLayoutCorruptionException(
                "Auto-layout produced structurally invalid BPMN: " +
                    xsdIssues.joinToString("; ") { it.summary() },
            )
        }
        logger.info("Final BPMN XSD validation passed after auto-layout")
        return FinalValidatedBpmnXml(definition = bpmn.definition, xml = bpmn.xml)
    }
}

private fun XsdValidationIssue.summary(): String = listOfNotNull(elementId, message).joinToString("|")

class BpmnLayoutCorruptionException(
    message: String,
) : IllegalStateException(message)
