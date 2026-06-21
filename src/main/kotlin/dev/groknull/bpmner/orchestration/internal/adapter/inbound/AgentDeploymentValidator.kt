/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.orchestration.internal.adapter.inbound

import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.spi.validation.GoapPathToCompletionValidator
import com.embabel.agent.spi.validation.PathToCompletionAgentValidator
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

internal class AgentDeploymentValidationException(
    message: String,
) : IllegalStateException(message)

/**
 * Fail-fast startup check that every bpmner agent passes the platform's static GOAP
 * path-to-completion validation.
 *
 * ## Why this exists
 *
 * Embabel validates each agent during deployment but the result is **non-fatal** —
 * `AgentMetadataReader` only logs a `WARN`/`ERROR` and the app boots regardless (there is even a
 * commented-out enforcement `return null` in the framework). Without this component a broken agent
 * graph is indistinguishable from a healthy one in the logs: `NO_PATH_TO_GOAL` appears at startup
 * and the application continues, silently unable to reach its goal.
 * This component turns that silent log into a hard boot failure, mirroring the existing fail-fast
 * precedent [dev.groknull.bpmner.repair.internal.domain.BpmnLocalRepairCapabilityValidator].
 *
 * ## Why it constructs its own validator instead of injecting `AgentValidationManager`
 *
 * [GoapPathToCompletionValidator] (and the other framework validators) are **not** Spring beans, so
 * the auto-registered `DefaultAgentValidationManager` bean is wired with an empty validator list and
 * silently passes everything. `AgentMetadataReader` works around this by constructing its own
 * validators internally; we do the same here so the check actually runs.
 *
 * Only bpmner-owned agents are checked (by goal/action name prefix) so a framework- or
 * starter-provided agent — which the framework also scans (`com.embabel.example`) and which may trip
 * the validator's known false positives — cannot fail our startup. Validation runs on
 * [ContextRefreshedEvent], by which point `AgentDeployer` has deployed every agent.
 */
@PrimaryAdapter
@Component
internal class AgentDeploymentValidator internal constructor(
    private val agentPlatform: AgentPlatform,
    private val ownedPackagePrefix: String,
    private val validator: PathToCompletionAgentValidator,
) {
    @Autowired
    constructor(agentPlatform: AgentPlatform) : this(
        agentPlatform = agentPlatform,
        ownedPackagePrefix = BPMNER_PACKAGE_PREFIX,
        validator = GoapPathToCompletionValidator(),
    )

    private val logger = LoggerFactory.getLogger(AgentDeploymentValidator::class.java)

    @EventListener(ContextRefreshedEvent::class)
    fun validateOnStartup() {
        val ourAgents = bpmnerAgents()
        val failures = validationFailures(ourAgents)
        if (failures.isNotEmpty()) {
            throw AgentDeploymentValidationException(
                "Agent deployment validation failed for ${failures.size} agent(s):\n" +
                    failures.joinToString("\n"),
            )
        }
        logger.info("Validated deployment of {} bpmner agent(s): all pass static GOAP validation", ourAgents.size)
    }

    /** The deployed agents owned by this application (excludes any framework/foreign agents). */
    internal fun bpmnerAgents(): List<Agent> = agentPlatform.agents().filter { it.isOurs() }

    /**
     * One human-readable line per invalid agent (empty when all valid). Pure and side-effect free
     * so it can be asserted directly in tests without booting Spring.
     */
    internal fun validationFailures(agents: List<Agent>): List<String> = agents.mapNotNull { agent ->
        val result = validator.validate(agent)
        if (result.isValid) {
            null
        } else {
            "${agent.name}: ${result.errors.joinToString { "${it.code}: ${it.message}" }}"
        }
    }

    // A deployed Agent is framework metadata (com.embabel.agent.core.Agent), so its JVM package is
    // never ours — identify bpmner agents by their goal/action names, which are fully-qualified
    // method names like dev.groknull.bpmner...approveReadyRequest.
    private fun Agent.isOurs(): Boolean = goals.any { it.name.startsWith(ownedPackagePrefix) } ||
        actions.any { it.name.startsWith(ownedPackagePrefix) }

    private companion object {
        const val BPMNER_PACKAGE_PREFIX = "dev.groknull.bpmner"
    }
}
