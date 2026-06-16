/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairMetadata
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.api.RuleCategory
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.rules.LlmRuleSpec
import dev.groknull.bpmner.rules.internal.domain.llmRule
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.primitiveRule
import dev.groknull.bpmner.rules.internal.domain.primitives.ConnectivityCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ConnectivityMode
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintMode
import dev.groknull.bpmner.rules.internal.domain.primitives.GrammaticalShape
import dev.groknull.bpmner.rules.internal.domain.primitives.GrammaticalShapeCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.NlpPosTag
import dev.groknull.bpmner.rules.internal.domain.primitives.PartOfSpeechCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PartOfSpeechMode
import dev.groknull.bpmner.rules.internal.domain.primitives.TopologyCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.TopologyMode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@Suppress("MaxLineLength")
internal class GatewayRuleConfig {
    // Deferred LLM metadata rules are added as LlmRuleSpec beans.
    // These are excluded from activeRules() but remain resolvable via ruleByIdOrAlias for markdown.
    @Bean
    fun gtwExclusiveInclusiveParallelSemantics(_nlp: BpmnNlp): LlmRuleSpec = llmRule(
        name = "Exclusive Inclusive Parallel Semantics",
        category = RuleCategory.Gateway,
        intent = "Keep gateway type choices aligned with BPMN token semantics.",
        forModellers =
        "Use exclusive gateways for exactly one path, inclusive gateways for one or more paths, and parallel gateways when all paths proceed together.",
        forAI =
        "Enforce deterministic parallel-gateway structure from XML. Treat XOR versus OR versus AND selection as a modelling-intent decision unless explicit structural evidence makes it invalid.",
        targetElements =
        listOf(
            "bpmn:ExclusiveGateway",
            "bpmn:InclusiveGateway",
            "bpmn:ParallelGateway",
        ),
        errorMessages = mapOf(
            "default" to "Gateway semantics should match exclusive, inclusive, or parallel behavior",
            "parallelCondition" to "Parallel gateway outgoing sequence flow must not be conditional or default-only",
            "parallelSplitCardinality" to "Parallel diverging gateway should have at least two outgoing sequence flows",
            "parallelJoinCardinality" to "Parallel converging gateway should have at least two incoming sequence flows",
        ),
        severity = RuleSeverity.WARNING,
        staticConfig = mapOf(
            "deterministicChecks" to listOf("parallelCondition", "parallelSplitCardinality", "parallelJoinCardinality"),
            "heuristicChecks" to listOf("xor-vs-or-business-intent", "matching-split-join-context"),
        ),
    )

    companion object {
        // DSL string literals shared across multiple @Bean methods in this class.
        private const val BPMN_EXCLUSIVE_GATEWAY = "bpmn:ExclusiveGateway"
        private const val BPMN_INCLUSIVE_GATEWAY = "bpmn:InclusiveGateway"
        private const val BPMN_PARALLEL_GATEWAY = "bpmn:ParallelGateway"
        private const val BPMN_COMPLEX_GATEWAY = "bpmn:ComplexGateway"
        private val EXCLUSIVE_INCLUSIVE_PARALLEL = listOf(
            BPMN_EXCLUSIVE_GATEWAY,
            BPMN_INCLUSIVE_GATEWAY,
            BPMN_PARALLEL_GATEWAY,
        )
        private val EXCLUSIVE_INCLUSIVE = listOf(BPMN_EXCLUSIVE_GATEWAY, BPMN_INCLUSIVE_GATEWAY)
        private val EXCLUSIVE_INCLUSIVE_COMPLEX = listOf(
            BPMN_EXCLUSIVE_GATEWAY,
            BPMN_INCLUSIVE_GATEWAY,
            BPMN_COMPLEX_GATEWAY,
        )
    }

    @Bean
    fun gwConvergingGatewayUnnamed(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Converging Gateway Unnamed",
        category = RuleCategory.Gateway,
        intent = "Keep converging gateway labels empty so decision wording stays on the diverging side.",
        forModellers = "Do not name converging exclusive, inclusive, or parallel gateways; use a text annotation if convergence needs explanation.",
        forAI = "Detect converging exclusive, inclusive, or parallel gateways with labels and remove the label when auto-fixing.",
        targetElements = EXCLUSIVE_INCLUSIVE_PARALLEL,
        errorMessages = mapOf(
            "default" to "Converging gateway should remain unnamed",
        ),
        check = TopologyCheckConfig(topology = TopologyMode.CONVERGING_UNNAMED),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
        repair = RepairMetadata(
            kind = RepairKind.LOCAL_MODEL_FIX,
            safety = RepairSafety.SAFE_AUTOMATIC,
            handler = "clearConvergingGatewayName",
        ),
    )

    @Bean
    fun gwDivergingGatewayQuestion(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Diverging Gateway Question",
        category = RuleCategory.Gateway,
        intent = "Encourage question-style naming on diverging exclusive and inclusive gateways.",
        forModellers = "Name diverging exclusive and inclusive gateways with a question that expresses the decision.",
        forAI = "Detect diverging exclusive and inclusive gateways with missing names or names that are not interrogative.",
        targetElements = EXCLUSIVE_INCLUSIVE,
        errorMessages = mapOf(
            "default" to "Diverging exclusive/inclusive gateway should be named as a question",
        ),
        check = GrammaticalShapeCheckConfig(
            property = "name",
            mode = GrammaticalShape.QUESTION_FORM,
        ),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    )

    @Bean
    fun gwFakeJoin(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Fake Join",
        category = RuleCategory.Gateway,
        intent = "Ensure converging flows pass through an explicit gateway rather than directly into a task.",
        forModellers = "When two or more flows merge before work continues, model the merge with a converging gateway before the task.",
        forAI = "Detect task elements with two or more incoming sequence flows and no explicit converging gateway.",
        targetElements = listOf(
            "bpmn:Task",
            "bpmn:UserTask",
            "bpmn:ServiceTask",
            "bpmn:SendTask",
            "bpmn:ReceiveTask",
            "bpmn:ManualTask",
            "bpmn:BusinessRuleTask",
            "bpmn:ScriptTask",
        ),
        errorMessages = mapOf(
            "default" to "Task has multiple incoming flows without an explicit converging gateway",
        ),
        check = TopologyCheckConfig(topology = TopologyMode.NO_FAKE_JOIN),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(
            kind = RepairKind.LOCAL_MODEL_FIX,
            safety = RepairSafety.SAFE_AUTOMATIC,
            handler = "insertConvergingGateway",
        ),
    )

    @Bean
    fun gwGatewayNoWorkLabel(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Gateway No Work Label",
        category = RuleCategory.Gateway,
        intent = "Keep gateway labels focused on decision conditions rather than work execution.",
        forModellers = "Model work as an activity before the gateway; use the gateway only to evaluate the resulting condition.",
        forAI = "Detect diverging gateway labels that start with action verbs or configured work verbs.",
        targetElements = EXCLUSIVE_INCLUSIVE_COMPLEX,
        errorMessages = mapOf(
            "default" to "Gateway label should describe a decision condition, not perform work",
        ),
        check = PartOfSpeechCheckConfig(
            property = "name",
            mode = PartOfSpeechMode.LEADING_MUST_NOT_BE,
            posClass = NlpPosTag.VERB,
        ),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
        repair = RepairMetadata(
            kind = RepairKind.LOCAL_MODEL_FIX,
            safety = RepairSafety.SAFE_AUTOMATIC,
            handler = "clearName",
        ),
    )

    @Bean
    fun gwNoGatewayJoinFork(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "No Gateway Join Fork",
        category = RuleCategory.Gateway,
        intent = "Prevent a single gateway from acting as both a join and a fork.",
        forModellers = "Use separate converging and diverging gateways instead of one gateway with multiple incoming and multiple outgoing flows.",
        forAI = "Detect exclusive, inclusive, or parallel gateways with at least two incoming and at least two outgoing flows.",
        targetElements = EXCLUSIVE_INCLUSIVE_PARALLEL,
        errorMessages = mapOf(
            "default" to "Gateway acts as both join and fork; split into separate converging and diverging gateways",
        ),
        check = TopologyCheckConfig(topology = TopologyMode.NO_JOIN_FORK),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(
            kind = RepairKind.LOCAL_MODEL_FIX,
            safety = RepairSafety.SAFE_AUTOMATIC,
            handler = "splitJoinForkGateway",
        ),
    )

    @Bean
    fun gwSuperfluousGateway(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Superfluous Gateway",
        category = RuleCategory.Gateway,
        intent = "Remove passthrough gateways that carry no routing decision.",
        forModellers = "Avoid gateways with exactly one incoming and one outgoing flow because they do not split or merge control flow.",
        forAI = "Detect exclusive, inclusive, or parallel gateways with a single incoming and single outgoing flow.",
        targetElements = EXCLUSIVE_INCLUSIVE_PARALLEL,
        errorMessages = mapOf(
            "default" to "Gateway has a single incoming and single outgoing flow and can be removed",
        ),
        check = TopologyCheckConfig(topology = TopologyMode.NO_SUPERFLUOUS),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(
            kind = RepairKind.LOCAL_MODEL_FIX,
            safety = RepairSafety.SAFE_AUTOMATIC,
            handler = "bypassGateway",
        ),
    )

    @Bean
    fun gwEventBasedGatewayDirectEvents(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Event Based Direct Events",
        category = RuleCategory.Gateway,
        intent = "Enforce event-based gateway semantics.",
        forModellers = "Use event-based gateways only when the process waits for events, and connect outgoing flows directly to intermediate catch events or receive tasks.",
        forAI = "Detect event-based gateway outgoing flows that target anything other than an intermediate catch event or receive task.",
        targetElements = listOf("bpmn:EventBasedGateway"),
        errorMessages = mapOf(
            "default" to "Event-based gateway must connect directly to intermediate catch events or receive tasks",
        ),
        check = ElementConstraintCheckConfig(
            element = "bpmn:EventBasedGateway",
            mode = ElementConstraintMode.EVENT_BASED_GATEWAY_DIRECT_EVENTS,
        ),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    )

    @Bean
    fun gwDivergingFlowNames(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Diverging Flow Names",
        category = RuleCategory.Gateway,
        intent = "Require outcome labels on diverging gateway branches.",
        forModellers = "Name outgoing flows from diverging exclusive, inclusive, and complex gateways with short outcome labels.",
        forAI = "Detect unnamed outgoing sequence flows from diverging exclusive, inclusive, or complex gateways.",
        targetElements = EXCLUSIVE_INCLUSIVE_COMPLEX,
        errorMessages = mapOf(
            "default" to "Sequence flow from diverging gateway must have an outcome label",
        ),
        check = ConnectivityCheckConfig(mode = ConnectivityMode.OUTGOING_FLOWS_NAMED),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    )
}
