/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.observability.internal.adapter.inbound

import com.embabel.agent.api.event.ActionExecutionStartEvent
import com.embabel.agent.api.event.ProgressUpdateEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class BpmnProgressProjectionObserver(
    private val eventPublisher: ApplicationEventPublisher,
) {
    @EventListener
    fun onActionStart(event: ActionExecutionStartEvent) {
        val actionName = event.action.name
        val friendlyLabel = mapActionToLabel(actionName)
        if (friendlyLabel != null) {
            // We publish a ProgressUpdateEvent using the label, which Embabel uses for SSE updates.
            // 0 out of 0 means indeterminate progress, which is typically good for status text updates.
            eventPublisher.publishEvent(ProgressUpdateEvent(event.agentProcess, friendlyLabel, 0, 0))
        }
    }

    @EventListener
    fun onSnapshot(event: BpmnSnapshotEvent) {
        val label =
            when (event.stage) {
                "INITIAL_RENDER" -> {
                    "Rendered BPMN XML"
                }

                "VALIDATION_FAILED" -> {
                    val base = "Validating and repairing (Attempt ${event.attemptNumber ?: 1})"
                    val counts =
                        listOfNotNull(
                            if (event.graphIssues > 0) "${event.graphIssues} graph" else null,
                            if (event.xsdIssues > 0) "${event.xsdIssues} XSD" else null,
                            if (event.lintIssues > 0) "${event.lintIssues} lint" else null,
                        )
                    if (counts.isNotEmpty()) {
                        "$base: ${counts.joinToString(", ")} issues"
                    } else {
                        base
                    }
                }

                "FINAL_VALIDATION" -> {
                    "Final validation"
                }

                else -> {
                    null
                }
            }

        if (label != null) {
            eventPublisher.publishEvent(ProgressUpdateEvent(event.agentProcess, label, 0, 0))
        }
    }

    private fun mapActionToLabel(actionName: String): String? =
        when (actionName) {
            "createProcessOutline" -> "Generating BPMN structure"

            "validateOutline" -> "Validating outline"

            "generatePhasePlans" -> "Planning phase details"

            // not explicitly in ticket list but makes sense
            "composeProcessGraph" -> "Composing process graph"

            "assignOwnership" -> "Applying XML fixes"

            // approximation based on what it does
            "assignLayout" -> "Laying out diagram"

            "renderBpmnXml" -> "Rendering BPMN XML"

            "refine" -> "Validating and repairing"

            "finalizeBpmn" -> "Finalizing BPMN output"

            else -> null // Unknown events do not create noisy entries
        }
}
