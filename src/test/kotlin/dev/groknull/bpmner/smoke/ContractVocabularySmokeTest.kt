/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.api.BoundaryEventKind
import dev.groknull.bpmner.api.MultiInstanceMode
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractArtifactKind
import dev.groknull.bpmner.contract.ContractBranch
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.ContractIntermediateThrow
import dev.groknull.bpmner.contract.ContractTrigger
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.contract.boundaryEvents
import dev.groknull.bpmner.contract.iteration
import dev.groknull.bpmner.contract.loop
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.TimeUnit

@Suppress("TooManyFunctions")
@Tag("live-llm")
@EnabledForLiveLlmProfile
@ExtendWith(
    SmokeTestSummaryExtension::class,
    SmokeResultRecorder::class,
)
@SpringBootTest
@Import(PerTestEventCapture::class)
// Each method makes two sequential live-LLM calls (readiness + extraction); 120s was too tight
// under provider latency spikes / tool-loop retries. Generous headroom below Bazel's 'eternal'
// target still catches a genuine hang.
@Timeout(240, unit = TimeUnit.SECONDS)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=\${ANTHROPIC_API_KEY:}",
        "embabel.agent.platform.models.gemini.api-key=\${GEMINI_API_KEY:}",
        "embabel.agent.platform.models.mistralai.api-key=\${MISTRAL_API_KEY:}",
        "spring.shell.interactive.enabled=false",
        "spring.shell.noninteractive.enabled=false",
    ],
)
class ContractVocabularySmokeTest {

    @Autowired
    private lateinit var agentPlatform: AgentPlatform

    @Autowired
    private lateinit var readinessInvoker: BpmnReadinessInvoker

    @Autowired
    private lateinit var perTestCapture: PerTestEventCapture

    private fun extractContract(prose: String): ProcessContract {
        val request = BpmnRequest(processDescription = prose.trimIndent().trim())
        val assessment = readinessInvoker.assess(request)
        val readyContext = ReadyBpmnContext(request = request, assessment = assessment)
        return AgentPlatformTypedOps(agentPlatform).transform(
            readyContext,
            ValidatedProcessContract::class.java,
            ProcessOptions(listeners = listOf(SuiteCostCapturer, perTestCapture)),
        ).contract
    }

    private inline fun <reified T : ContractActivity> ProcessContract.assertHasActivity() {
        val hasActivity = activities.any { it is T }
        assertTrue(hasActivity) {
            "Expected activity of type ${T::class.simpleName} in contract, but found: " +
                activities.joinToString { "${it.javaClass.simpleName}(${it.name})" }
        }
    }

    private inline fun <reified T : ContractTrigger> ProcessContract.assertTriggerType() {
        val trigger = start.trigger
        assertTrue(trigger is T) {
            "Expected start trigger of type ${T::class.simpleName}, but got: ${trigger.javaClass.simpleName} ($trigger)"
        }
    }

    private inline fun <reified T : ContractEndState> ProcessContract.assertHasEndState() {
        val hasEndState = endStates.any { it is T }
        assertTrue(hasEndState) {
            "Expected end state of type ${T::class.simpleName} in contract, but found: " +
                endStates.joinToString { "${it.javaClass.simpleName}(${it.name})" }
        }
    }

    private inline fun <reified T : ContractIntermediateThrow> ProcessContract.assertHasIntermediateThrow() {
        val hasIntermediateThrow = intermediateThrows.any { it is T }
        assertTrue(hasIntermediateThrow) {
            "Expected intermediate throw of type ${T::class.simpleName} in contract, but found: " +
                intermediateThrows.joinToString { "${it.javaClass.simpleName}(${it.name})" }
        }
    }

    private fun ProcessContract.assertHasGatewayKind(kind: ContractGatewayKind) {
        val hasGateway = decisions.any { it.kind == kind }
        assertTrue(hasGateway) {
            "Expected decision gateway of kind $kind, but found: " +
                decisions.joinToString { "${it.id}: question='${it.question}', kind=${it.kind}" }
        }
    }

    private inline fun <reified T : ContractBranch> ProcessContract.assertHasBranchType() {
        val branches = decisions.flatMap { it.branches }
        val hasBranch = branches.any { it is T }
        assertTrue(hasBranch) {
            "Expected decision branch of type ${T::class.simpleName}, but found: " +
                branches.joinToString { "${it.javaClass.simpleName}(label='${it.label}')" }
        }
    }

    private fun ProcessContract.assertIterationMode(mode: MultiInstanceMode) {
        val hasIteration = activities.any { it.iteration?.mode == mode }
        assertTrue(hasIteration) {
            "Expected an activity with iteration mode $mode, but found: " +
                activities.joinToString { "${it.name}(iteration=${it.iteration})" }
        }
    }

    private fun ProcessContract.assertHasBoundaryEvent(kind: BoundaryEventKind) {
        val hasBoundary = activities.any { a -> a.boundaryEvents.any { it.kind == kind } }
        assertTrue(hasBoundary) {
            "Expected an activity carrying a $kind boundary event, but found: " +
                activities.joinToString { "${it.name}(boundaryEvents=${it.boundaryEvents})" }
        }
    }

    private fun ProcessContract.assertHasArtifactKind(kind: ContractArtifactKind) {
        val hasArtifact = artifacts.any { it.kind == kind }
        assertTrue(hasArtifact) {
            "Expected a $kind artifact, but found: " +
                artifacts.joinToString { "${it.id}: '${it.name}', kind=${it.kind}" }
        }
    }

    // Task Kinds

    @Test
    fun `service task`() {
        val c = extractContract(
            """
            When an order is submitted, the system automatically charges the customer's card through
            the payment gateway. Once the charge succeeds, the process ends.
            """,
        )
        c.assertHasActivity<ContractActivity.Service>()
    }

    @Test
    fun `user task`() {
        val c = extractContract(
            """
            When a claim arrives, a reviewer opens it in the claims application and records a decision
            in the system. Then the process ends.
            """,
        )
        c.assertHasActivity<ContractActivity.User>()
    }

    @Test
    fun `script task`() {
        val c = extractContract(
            """
            When a mortgage application arrives, the system runs a formatting script to clean up the fields.
            Then the application process finishes.
            """,
        )
        c.assertHasActivity<ContractActivity.Script>()
    }

    @Test
    fun `business rule task`() {
        val c = extractContract(
            """
            The process begins when an order is placed. The system then evaluates the pricing rules
            using the order discount table to determine the final price. Finally, the process ends.
            """,
        )
        c.assertHasActivity<ContractActivity.BusinessRule>()
    }

    @Test
    fun `sequential multi-instance activity`() {
        val c = extractContract(
            """
            When a packing slip is printed, the picker walks the warehouse with the slip. For each
            line item on the slip — one at a time, in the order they appear — the picker scans the
            SKU and places the item in the tote. Only once every line item has been picked does the
            picker hand the tote to the packing station, and the process ends.
            """,
        )
        c.assertIterationMode(MultiInstanceMode.SEQUENTIAL)
    }

    @Test
    fun `parallel multi-instance activity`() {
        val c = extractContract(
            """
            When a research paper is submitted, the editor assigns a panel of reviewers. For each
            reviewer on the panel, working in parallel and independently of the others, the reviewer
            reads the manuscript and submits a review. Once every reviewer has submitted, the editor
            decides the verdict and the process ends.
            """,
        )
        c.assertIterationMode(MultiInstanceMode.PARALLEL)
    }

    @Test
    fun `send task`() {
        val c = extractContract(
            """
            When the registration is complete, the application sends a confirmation email to the user.
            Then the process completes.
            """,
        )
        c.assertHasActivity<ContractActivity.Send>()
    }

    @Test
    fun `receive task`() {
        val c = extractContract(
            """
            The process starts when requested. After submitting the form, the system waits for the signature message to arrive.
            Then the process ends.
            """,
        )
        c.assertHasActivity<ContractActivity.Receive>()
    }

    @Test
    fun `manual task`() {
        val c = extractContract(
            """
            When a sample is delivered, the doctor manually inspects the physical test tube for color changes.
            Afterwards, the test is complete.
            """,
        )
        c.assertHasActivity<ContractActivity.Manual>()
    }

    // Typed Start Events

    @Test
    fun `timer start`() {
        val c = extractContract(
            """
            Every Friday at 5 PM, the system generates a weekly sales summary report and the process ends.
            """,
        )
        c.assertTriggerType<ContractTrigger.Timer>()
    }

    @Test
    fun `message start`() {
        val c = extractContract(
            """
            When a 'New User Registered' message is received, the onboarding process is initiated,
            an account is provisioned, and the process completes.
            """,
        )
        c.assertTriggerType<ContractTrigger.Message>()
    }

    @Test
    fun `signal start`() {
        val c = extractContract(
            """
            The process starts when the system broadcasts a 'Global System Shutdown' signal.
            Once started, it records the shutdown log and ends.
            """,
        )
        c.assertTriggerType<ContractTrigger.Signal>()
    }

    // Typed End Events

    @Test
    fun `terminate end`() {
        val c = extractContract(
            """
            The process begins when started. If the emergency stop button is pressed, the process terminates all active operations immediately.
            """,
        )
        c.assertHasEndState<ContractEndState.Terminate>()
    }

    @Test
    fun `error end`() {
        val c = extractContract(
            """
            The process begins when started. We validate the application. If validation fails, the process ends with a validation error.
            """,
        )
        c.assertHasEndState<ContractEndState.Error>()
    }

    @Test
    fun `message end`() {
        val c = extractContract(
            """
            The process begins when started. The order is validated.
            The process then terminates by dispatching the final invoice as an outbound message to the customer.
            """,
        )
        c.assertHasEndState<ContractEndState.Message>()
    }

    @Test
    fun `signal end`() {
        val c = extractContract(
            """
            The process begins when started. Upon successful completion of the process, a signal is broadcast to all subsystems.
            """,
        )
        c.assertHasEndState<ContractEndState.Signal>()
    }

    @Test
    fun `escalation end`() {
        val c = extractContract(
            """
            The process begins when started. If the approval is overdue, we trigger a manager escalation.
            """,
        )
        c.assertHasEndState<ContractEndState.Escalation>()
    }

    @Test
    fun `intermediate message throw`() {
        val c = extractContract(
            """
            The process starts when requested. The system validates the request.
            It then sends a confirmation message to billing without ending the process.
            Then the process completes normally.
            """,
        )
        c.assertHasIntermediateThrow<ContractIntermediateThrow.Message>()
    }

    @Test
    fun `intermediate signal throw`() {
        val c = extractContract(
            """
            The process starts when requested. After updating inventory, it broadcasts
            an inventory-updated signal to listening systems. Then the record is archived
            and the process ends.
            """,
        )
        c.assertHasIntermediateThrow<ContractIntermediateThrow.Signal>()
    }

    @Test
    fun `intermediate escalation throw`() {
        val c = extractContract(
            """
            The process starts when requested. The approver reviews the request.
            If approval is overdue, a non-interrupting escalation is raised and
            the process continues to archive the request before ending normally.
            """,
        )
        c.assertHasIntermediateThrow<ContractIntermediateThrow.Escalation>()
    }

    // Gateways

    @Test
    fun `exclusive gateway`() {
        val c = extractContract(
            """
            The process starts when an invoice is received. If the amount is over 1000, we route to supervisor approval.
            Otherwise, we auto-approve the request. Then the process ends.
            """,
        )
        c.assertHasGatewayKind(ContractGatewayKind.EXCLUSIVE)
    }

    @Test
    fun `exclusive gateway with default branch`() {
        val c = extractContract(
            """
            The process starts when a customer applies. If the customer is premium, we apply a 10% discount.
            Otherwise, by default, we apply no discount. Then the process ends.
            """,
        )
        c.assertHasGatewayKind(ContractGatewayKind.EXCLUSIVE)
        c.assertHasBranchType<DefaultBranch>()
    }

    @Test
    fun `parallel gateway`() {
        val c = extractContract(
            """
            The process starts. We execute both the background check and the credit check concurrently.
            Both must complete before we finalize the account. Then the process ends.
            """,
        )
        c.assertHasGatewayKind(ContractGatewayKind.PARALLEL)
    }

    // Boundary events

    @Test
    fun `timer boundary event`() {
        val c = extractContract(
            """
            The process starts when a claim is filed. An adjuster reviews the claim. If that review
            takes longer than 48 hours, the claim is escalated to a senior adjuster for handling.
            Either way, once a decision is recorded the process ends.
            """,
        )
        c.assertHasBoundaryEvent(BoundaryEventKind.TIMER)
    }

    @Test
    fun `error boundary event`() {
        val c = extractContract(
            """
            The process starts when a payment is submitted. The system attempts to charge the card.
            If the charge fails with a payment error, the order is cancelled. Otherwise the process ends.
            """,
        )
        c.assertHasBoundaryEvent(BoundaryEventKind.ERROR)
    }

    @Test
    fun `escalation boundary event`() {
        val c = extractContract(
            """
            The process begins when a support ticket is opened. An agent works the ticket. While the
            agent is handling it, if the agent raises an escalation because the issue is severe, a
            manager immediately takes over. Once the ticket is resolved, the process ends.
            """,
        )
        c.assertHasBoundaryEvent(BoundaryEventKind.ESCALATION)
    }

    // Inclusive gateway

    @Test
    fun `inclusive gateway`() {
        val c = extractContract(
            """
            The process starts. Depending on the application, we may send an optional customer notification AND/OR an optional manager notification.
            Then the process ends.
            """,
        )
        c.assertHasGatewayKind(ContractGatewayKind.INCLUSIVE)
    }

    // Event-based gateway

    @Test
    fun `event-based gateway`() {
        val c = extractContract(
            """
            The process starts when a card charge is submitted. The system then waits for whichever
            of these happens first: it receives a "success confirmation" message, it receives a
            "decline notification" message, or sixty seconds pass with no response. Each outcome is
            recorded and the process ends.
            """,
        )
        c.assertHasGatewayKind(ContractGatewayKind.EVENT_BASED)
    }

    // Standard loop

    @Test
    fun `standard loop activity`() {
        val c = extractContract(
            """
            When a payment is submitted, the system attempts to charge the card. If the charge
            fails it retries the same charge, up to three times, until the payment succeeds. Once
            it succeeds or the attempts are exhausted, the process ends.
            """,
        )
        assertTrue(c.activities.any { it.loop != null }) {
            "Expected an activity carrying a standard loop, but found: " +
                c.activities.joinToString { "${it.name}(loop=${it.loop})" }
        }
    }

    // Embedded subprocess

    @Test
    fun `embedded subprocess`() {
        val c = extractContract(
            """
            When a claim is filed, it is assessed. Assessing a claim involves three steps handled
            together as one stage: an adjuster validates the documents, the system estimates the
            damage, and the adjuster decides the payout. Once the claim has been assessed, it is
            paid and the process ends.
            """,
        )
        c.assertHasActivity<ContractActivity.SubProcess>()
    }

    // Event subprocess

    @Test
    fun `event subprocess`() {
        val c = extractContract(
            """
            An order is processed and then shipped. At any point before shipping, if a cancellation
            request arrives, the order is refunded and the customer is notified of the cancellation.
            Otherwise the order ships and the process ends.
            """,
        )
        assertTrue(c.eventSubProcesses.isNotEmpty()) {
            "Expected an event subprocess, but found none. Activities: " +
                c.activities.joinToString { it.name }
        }
    }

    @Test
    fun `data objects and stores`() {
        val c = extractContract(
            """
            When an order is received, the system reads the customer record from the customer
            database and produces a validated order, which it then stores.
            """,
        )
        c.assertHasArtifactKind(ContractArtifactKind.DATA_STORE)
        c.assertHasArtifactKind(ContractArtifactKind.DATA_OBJECT)
    }

    // Pools and lanes (actor responsibilities) — lanes are generated from distinct performing actors,
    // so the contract-vocabulary precondition is that the extractor records each named performer and
    // assigns activities to them.

    @Test
    fun `pools and lanes from distinct actors`() {
        val c = extractContract(
            """
            When an order is submitted, a sales representative confirms the order details. Once
            confirmed, a finance officer approves the payment. After approval, the warehouse team
            ships the goods, and the process ends.
            """,
        )
        c.assertHasDistinctActors(min = 2)
    }

    private fun ProcessContract.assertHasDistinctActors(min: Int) {
        val performingActorIds = activities.mapNotNull { it.actorId }.toSet()
        assertTrue(actors.size >= min && performingActorIds.size >= min) {
            "Expected >= $min actors each performing activities (the precondition for pool/lane " +
                "generation), but found actors=[${actors.joinToString { "${it.id}:'${it.name}'" }}] " +
                "and activity assignments=[${activities.joinToString { "${it.id}->${it.actorId}" }}]"
        }
    }
}
