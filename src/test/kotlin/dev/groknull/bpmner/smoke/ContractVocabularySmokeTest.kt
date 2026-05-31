/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractBranch
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.ContractTrigger
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnRequest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.TimeUnit

@Suppress("TooManyFunctions")
@Tag("live-llm")
@SpringBootTest
@ActiveProfiles("anth")
@Timeout(120, unit = TimeUnit.SECONDS)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=\${ANTHROPIC_API_KEY:}",
        "embabel.agent.platform.models.openai.api-key=\${OPENAI_API_KEY:}",
        "spring.shell.interactive.enabled=false",
        "spring.shell.noninteractive.enabled=false",
    ],
)
class ContractVocabularySmokeTest {

    @Autowired
    private lateinit var agentPlatform: AgentPlatform

    private fun extractContract(prose: String): ProcessContract {
        val request = BpmnRequest(processDescription = prose.trimIndent().trim())
        return AgentPlatformTypedOps(agentPlatform).transform(
            request,
            ValidatedProcessContract::class.java,
            ProcessOptions(),
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

    // Task Kinds

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
            The process begins when started. When everything is done, the process wraps up by sending a final invoice.
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
}
