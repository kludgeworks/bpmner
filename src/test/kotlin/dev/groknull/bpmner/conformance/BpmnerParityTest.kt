/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import dev.groknull.bpmner.bpmn.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.bpmn.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.BpmnMessageEventDefinition
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.BpmnReceiveTask
import dev.groknull.bpmner.bpmn.BpmnSendTask
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnTimerEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTimerKind
import dev.groknull.bpmner.bpmn.BpmnUserTask
import dev.groknull.bpmner.conformance.internal.domain.BpmnDefinitionValidator
import dev.groknull.bpmner.ruleset.RuleEngine
import dev.groknull.bpmner.ruleset.RulesTestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parity regression gate (see #216).
 * Asserts that the legacy [BpmnDefinitionValidator] and the new [RuleEngine] producing
 * the compiled rules trigger the same set of diagnostic codes for the same inputs.
 */
@Suppress("TooManyFunctions")
class BpmnerParityTest {
    private val validator = BpmnDefinitionValidator()

    // Obtain the rule engine via the rules module's published test fixture, avoiding
    // direct imports from rules.internal (S5 — ARCHITECTURE §5 S5, §1.5).
    private val engine: RuleEngine = RulesTestFixtures.standardCompiledRuleEngine()

    @Test
    fun `clean definition produces no diagnostics in either pipeline`() {
        parity("clean", cleanDefinition())
    }

    @Test
    fun `duplicate node and edge ids produce same codes`() {
        parity("duplicate ids", duplicateIdsDefinition())
    }

    @Test
    fun `dangling source, target, and self-reference produce same codes`() {
        parity("dangling + self-ref", danglingAndSelfRefDefinition())
    }

    @Test
    fun `missing start and end events produce same codes`() {
        parity("missing start + end", missingStartAndEndDefinition())
    }

    @Test
    fun `event definitions produce same codes`() {
        // Triggers every retained event-definition diagnostic code in one shot:
        //  def-missing-event-def        (intermediate catch with NoneEventDefinition)
        //  def-missing-attached-to      (boundary with blank attachedToRef)
        //  def-invalid-attached-to      (boundary attachedToRef → missing node)
        //  def-non-task-attached-to     (boundary attachedToRef → gateway)
        //  def-missing-timer-expr       (start event with blank timer expression)
        //  def-invalid-message-ref      (intermediate throw with missing-catalog messageRef)
        //  def-invalid-error-ref        (boundary with missing-catalog errorRef)
        parity("event definitions", eventDefinitionsDefinition())
    }

    @Test
    fun `task payloads — all 3 codes — produce same codes`() {
        parity("task payloads", taskPayloadsDefinition())
    }

    @Test
    fun `default flows — both codes — produce same codes`() {
        parity("default flows", defaultFlowsDefinition())
    }

    @Test
    fun `naming policy — diverging exclusive gateway and unnamed user task produce same codes`() {
        // Diverging exclusive gateway (>1 outgoing) must be named; unnamed user task must
        // be named. Parallel gateway is never required to be named — used as the negative
        // control to keep this scenario tight.
        parity("naming policy", namingPolicyDefinition())
    }

    @Test
    fun `verifies every diagnosticCode is exercised by the suite`() {
        // This test re-runs every scenario inline and asserts that the union of triggered
        // diagnosticCodes covers EXPECTED_DIAGNOSTIC_CODES. Re-running inline (rather than
        // accumulating across @Test methods) is necessary because JUnit 5's default
        // Lifecycle.PER_METHOD creates a fresh class instance per test — any field-level
        // accumulator would be reset before this test runs.
        val triggered =
            listOf(
                cleanDefinition(),
                duplicateIdsDefinition(),
                danglingAndSelfRefDefinition(),
                missingStartAndEndDefinition(),
                eventDefinitionsDefinition(),
                taskPayloadsDefinition(),
                defaultFlowsDefinition(),
                namingPolicyDefinition(),
            ).flatMap { def -> engine.evaluate(def).diagnostics.map { it.diagnosticCode } }
                .toSet()

        assertEquals(
            EXPECTED_DIAGNOSTIC_CODES,
            triggered,
            "Parity-test scenario coverage drifted. Add or adjust a scenario to trigger the missing codes, " +
                "or update the scenario list in this method to include any newly-added scenarios.",
        )
    }

    // ----- helpers ----------------------------------------------------------------

    private fun parity(
        scenario: String,
        def: BpmnDefinition,
    ) {
        // Connectivity errors from validateScopeWeakConnectivity are excluded from parity
        // comparison: the compiled rule engine has no equivalent check, so these errors
        // would always produce a false parity break. Disconnected-component cases are
        // covered by dedicated BpmnDefinitionValidatorTest assertions instead.
        val rawValidatorErrors = validator.validate(def)
            .filterNot {
                it.startsWith("process contains disconnected flow nodes:") ||
                    (it.startsWith("subprocess '") && it.contains("contains disconnected flow nodes:"))
            }
        // Catch-all: every validator string must match exactly one pattern in the
        // translation table. Drift in validator output gets surfaced loudly.
        val unmatched = rawValidatorErrors.filter { msg -> TRANSLATION_TABLE.none { it.first.containsMatchIn(msg) } }
        assertTrue(unmatched.isEmpty()) {
            "Scenario '$scenario' — validator emitted ${unmatched.size} message(s) that match no translation-table pattern. " +
                "Update TRANSLATION_TABLE: $unmatched"
        }

        val legacyCodes = rawValidatorErrors.map(::translate).toSet()
        val ruleCodes = engine.evaluate(def).diagnostics.map { it.diagnosticCode }.toSet()
        assertEquals(legacyCodes, ruleCodes, "Parity broke in scenario '$scenario'")
    }

    private fun translate(message: String): String = TRANSLATION_TABLE.first { it.first.containsMatchIn(message) }.second

    // Scenario fixtures live as functions so the coverage assertion can re-execute the
    // same inputs independently of JUnit test ordering. Each function returns a fresh
    // `BpmnDefinition` so tests stay isolated.

    private fun cleanDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "P",
        processName = "P",
        nodes =
        listOf(
            BpmnStartEvent("s", "Started"),
            BpmnUserTask("t", "Task"),
            BpmnEndEvent("e", "Done"),
        ),
        sequences =
        listOf(
            BpmnEdge("f1", "s", "t"),
            BpmnEdge("f2", "t", "e"),
        ),
    )

    private fun duplicateIdsDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "P",
        processName = "P",
        nodes =
        listOf(
            BpmnStartEvent("s", "Start"),
            BpmnUserTask("dup", "A"),
            BpmnUserTask("dup", "B"),
            BpmnEndEvent("e", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("f1", "s", "dup"),
            BpmnEdge("f1", "dup", "e"),
        ),
    )

    private fun danglingAndSelfRefDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "P",
        processName = "P",
        nodes =
        listOf(
            BpmnStartEvent("s", "Start"),
            BpmnUserTask("t", "Task"),
            BpmnEndEvent("e", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("f-bad-src", "missing", "t"),
            BpmnEdge("f-bad-tgt", "t", "missing"),
            BpmnEdge("f-self", "t", "t"),
            BpmnEdge("f-ok", "s", "e"),
        ),
    )

    private fun missingStartAndEndDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "P",
        processName = "P",
        nodes = listOf(BpmnUserTask("t", "Task")),
        sequences = emptyList(),
    )

    private fun eventDefinitionsDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "P",
        processName = "P",
        nodes =
        listOf(
            BpmnStartEvent("s", "Start", BpmnTimerEventDefinition(timerKind = BpmnTimerKind.DURATION, expression = " ")),
            BpmnIntermediateCatchEvent("ic-none", "IC-none", BpmnNoneEventDefinition),
            BpmnIntermediateThrowEvent(
                "it-msg",
                "IT-msg",
                BpmnMessageEventDefinition(messageRef = "missing-msg"),
            ),
            BpmnExclusiveGateway("gw", "GW?"),
            BpmnUserTask("t-attach", "Attach target"),
            BpmnBoundaryEvent(
                id = "be-no-attach",
                name = "BE-no-attach",
                attachedToRef = " ",
                eventDefinition = BpmnErrorEventDefinition(errorRef = "missing-err"),
            ),
            BpmnBoundaryEvent(
                id = "be-bad-attach",
                name = "BE-bad-attach",
                attachedToRef = "no-such-node",
                eventDefinition = BpmnErrorEventDefinition(errorRef = "missing-err"),
            ),
            BpmnBoundaryEvent(
                id = "be-gw-attach",
                name = "BE-gw-attach",
                attachedToRef = "gw",
                eventDefinition = BpmnErrorEventDefinition(errorRef = "still-missing-err"),
            ),
            BpmnEndEvent("e", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("f1", "s", "ic-none"),
            BpmnEdge("f2", "ic-none", "it-msg"),
            BpmnEdge("f3", "it-msg", "gw"),
            BpmnEdge("f4", "gw", "t-attach"),
            BpmnEdge("f5", "t-attach", "e"),
        ),
    )

    private fun taskPayloadsDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "P",
        processName = "P",
        nodes =
        listOf(
            BpmnStartEvent("s", "Start"),
            BpmnSendTask("send-blank", "Send-blank", messageRef = " "),
            BpmnReceiveTask("recv-bad", "Recv-bad", messageRef = "missing-msg"),
            BpmnBusinessRuleTask("br", "BR", decisionRef = " "),
            BpmnEndEvent("e", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("f1", "s", "send-blank"),
            BpmnEdge("f2", "send-blank", "recv-bad"),
            BpmnEdge("f3", "recv-bad", "br"),
            BpmnEdge("f4", "br", "e"),
        ),
    )

    private fun defaultFlowsDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "P",
        processName = "P",
        nodes =
        listOf(
            BpmnStartEvent("s", "Start"),
            BpmnUserTask("t-non-gw", "Task"),
            BpmnExclusiveGateway("gw", "GW?"),
            BpmnUserTask("a", "A"),
            BpmnUserTask("b", "B"),
            BpmnEndEvent("e", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("f1", "s", "t-non-gw"),
            BpmnEdge("f2-default", "t-non-gw", "gw", isDefault = true),
            BpmnEdge("f3-default", "gw", "a", isDefault = true),
            BpmnEdge("f4-default", "gw", "b", isDefault = true),
            BpmnEdge("f5", "a", "e"),
            BpmnEdge("f6", "b", "e"),
        ),
    )

    private fun namingPolicyDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "P",
        processName = "P",
        nodes =
        listOf(
            BpmnStartEvent("s", "Started"),
            BpmnUserTask("ut", " "),
            BpmnExclusiveGateway("gw", " "),
            BpmnUserTask("a", "Approve"),
            BpmnUserTask("b", "Reject"),
            BpmnParallelGateway("pg", " "),
            BpmnEndEvent("e", "Done"),
        ),
        sequences =
        listOf(
            BpmnEdge("f1", "s", "ut"),
            BpmnEdge("f2", "ut", "gw"),
            BpmnEdge("f3", "gw", "a"),
            BpmnEdge("f4", "gw", "b"),
            BpmnEdge("f5", "a", "pg"),
            BpmnEdge("f6", "b", "pg"),
            BpmnEdge("f7", "pg", "e"),
        ),
    )

    private companion object {
        // PARITY CONTRACT (#216 — review before trusting this test).
        //
        // Each Regex matches a legacy BpmnDefinitionValidator error pattern. The mapped
        // String is the diagnosticCode the new compiled-rule pipeline emits for the same
        // condition. Patterns are checked in order; the first match wins. All patterns
        // are anchored with `^` so non-matching prefixes don't accidentally bind.
        val TRANSLATION_TABLE: List<Pair<Regex, String>> =
            listOf(
                Regex("^duplicate node id: .+$") to "def-duplicate-node-id",
                Regex("^duplicate edge id: .+$") to "def-duplicate-edge-id",
                Regex("^node \\S+ name must not be blank for \\S+$") to "def-missing-name",
                Regex("^edge \\S+ sourceRef '.*' does not match any node id$") to "def-dangling-source",
                Regex("^edge \\S+ targetRef '.*' does not match any node id$") to "def-dangling-target",
                Regex("^edge \\S+ must not self-reference .*$") to "def-self-reference",
                Regex("^definition must contain at least one START_EVENT$") to "def-missing-start-event",
                Regex("^definition must contain at least one END_EVENT$") to "def-missing-end-event",
                // Three legacy strings collapse to one diagnosticCode (many-to-one):
                Regex(
                    "^(intermediate catch event|intermediate throw event|boundary event) " +
                        "\\S+ must declare an event definition$",
                ) to "def-missing-event-def",
                Regex("^boundary event \\S+ is missing the required attachedToRef attribute$") to
                    "def-missing-attached-to",
                Regex("^boundary event \\S+ attachedToRef '.*' does not match any node id$") to
                    "def-invalid-attached-to",
                Regex("^boundary event \\S+ attachedToRef '.*' must reference an attachable activity$") to
                    "def-non-task-attached-to",
                Regex("^event \\S+ timer definition expression must not be blank$") to "def-missing-timer-expr",
                Regex(
                    "^event \\S+ (errorEventDefinition is missing the required errorRef attribute|" +
                        "errorRef '.*' does not match any error catalog id)$",
                ) to "def-invalid-error-ref",
                Regex(
                    "^event \\S+ (messageEventDefinition is missing the required messageRef attribute|" +
                        "messageRef '.*' does not match any message catalog id)$",
                ) to "def-invalid-message-ref",
                Regex("^(sendTask|receiveTask) \\S+ is missing the required messageRef attribute$") to "def-missing-message-ref",
                Regex("^(sendTask|receiveTask) \\S+ messageRef '.*' does not match any message catalog id$")
                    to "def-invalid-task-message-ref",
                // Only the blank-decisionRef case has a check today; catalogue-resolution
                // (i.e. a `def-invalid-task-decision-ref` analogue to def-invalid-task-message-ref)
                // is intentionally absent because the typed decision catalogue itself
                // doesn't exist yet — tracked in #196.
                Regex("^businessRuleTask \\S+ is missing the required decisionRef attribute$") to "def-missing-decision-ref",
                Regex("^edge .* isDefault is only valid when sourceRef points to an EXCLUSIVE_GATEWAY or INCLUSIVE_GATEWAY$")
                    to "def-default-flow-non-gateway",
                Regex("^node \\S+ has \\d+ default flows .*at most one is allowed$") to "def-multiple-default-flows",
                // Validator-only: no compiled rule emits a disconnected-component code, so this
                // entry only exists to satisfy the catch-all. Scenarios that need to assert on
                // disconnected-component errors must call validator.validate() directly (see
                // BpmnDefinitionValidatorTest) — they must NOT be exercised via parity() because
                // the rule engine will never emit the matching code.
                Regex("^(subprocess '\\S+'|process) contains disconnected flow nodes: .+$")
                    to "def-disconnected-nodes",
            )

        // Every diagnosticCode the compiled rules can emit. Verified by `git grep
        // "diagnosticCode = " src/main/kotlin/dev/groknull/bpmner/rules/internal/domain/compiled/`.
        // Used in `verifies every diagnosticCode is exercised by the suite`.
        val EXPECTED_DIAGNOSTIC_CODES: Set<String> =
            setOf(
                "def-duplicate-node-id",
                "def-duplicate-edge-id",
                "def-missing-name",
                "def-dangling-source",
                "def-dangling-target",
                "def-self-reference",
                "def-missing-start-event",
                "def-missing-end-event",
                "def-missing-event-def",
                "def-missing-attached-to",
                "def-invalid-attached-to",
                "def-non-task-attached-to",
                "def-missing-timer-expr",
                "def-invalid-error-ref",
                "def-invalid-message-ref",
                "def-missing-message-ref",
                "def-invalid-task-message-ref",
                "def-missing-decision-ref",
                "def-default-flow-non-gateway",
                "def-multiple-default-flows",
            )
    }
}
