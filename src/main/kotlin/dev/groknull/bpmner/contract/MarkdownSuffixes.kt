/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

internal object MarkdownSuffixes {
    fun branchSuffix(branch: ContractBranch): String = when (branch) {
        is ConditionalBranch -> " if \"${branch.condition}\""
        is DefaultBranch -> " [default]"
        is UnconditionalBranch -> ""
    }

    fun branchNextSuffix(branch: ContractBranch): String = branch.nextRef?.let { " → $it" }.orEmpty()

    // Activity-kind suffix for the markdown line. Service is the default kind so we omit
    // its label to keep the rendering quiet; Send / Receive / BusinessRule carry their
    // payload reference to keep the rendered contract self-contained for the BPMN-generation
    // LLM (otherwise it would have to walk back to the source prose for the messageName).
    fun activitySuffix(activity: ContractActivity): String = when (activity) {
        is ContractActivity.Service -> ""
        is ContractActivity.User -> " [USER]"
        is ContractActivity.Script -> " [SCRIPT]"
        is ContractActivity.BusinessRule -> " [BUSINESS_RULE decisionName=\"${activity.decisionName}\"]"
        is ContractActivity.Send -> " [SEND messageName=\"${activity.messageName}\"]"
        is ContractActivity.Receive -> " [RECEIVE messageName=\"${activity.messageName}\"]"
        is ContractActivity.Manual -> " [MANUAL]"
    }

    // End-state-kind suffix for the markdown line. Normal is the default and gets no
    // label; the four typed kinds carry their payload identifier (errorCode / messageName
    // / signalName / escalationCode) so the BPMN-generation LLM sees the catalogue keys
    // directly without re-walking the source prose.
    fun endStateSuffix(endState: ContractEndState): String = when (endState) {
        is ContractEndState.Normal -> ""
        is ContractEndState.Terminate -> " [TERMINATE]"
        is ContractEndState.Error -> " [ERROR errorCode=\"${endState.errorCode}\"]"
        is ContractEndState.Message -> " [MESSAGE messageName=\"${endState.messageName}\"]"
        is ContractEndState.Signal -> " [SIGNAL signalName=\"${endState.signalName}\"]"
        is ContractEndState.Escalation -> " [ESCALATION escalationCode=\"${endState.escalationCode}\"]"
    }

    fun intermediateThrowSuffix(intermediateThrow: ContractIntermediateThrow): String = when (intermediateThrow) {
        is ContractIntermediateThrow.Message -> " [MESSAGE messageName=\"${intermediateThrow.messageName}\"]"
        is ContractIntermediateThrow.Signal -> " [SIGNAL signalName=\"${intermediateThrow.signalName}\"]"
        is ContractIntermediateThrow.Escalation -> " [ESCALATION escalationCode=\"${intermediateThrow.escalationCode}\"]"
    }
}
