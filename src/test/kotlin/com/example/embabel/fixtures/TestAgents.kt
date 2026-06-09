/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package com.example.embabel.fixtures

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export

/**
 * Test-only agent fixtures for `dev.groknull.bpmner.config.AgentDeploymentValidator`.
 *
 * They deliberately live OUTSIDE `dev.groknull.bpmner` (and outside `com.embabel.example`) so the
 * application's component scan never deploys them — an `@Agent` class on the scan path becomes a
 * deployed agent and would pollute every full-context test (one of these is intentionally invalid).
 * Tests instantiate them directly via `AgentMetadataReader`.
 */

data class Seed(val value: String)

data class Loop(val value: String)

data class Outcome(val value: String)

data class Step(val value: String)

/**
 * Reproduces the original gate-agent defect in miniature: [Loop] is required by the goal but its
 * only in-scope producer ([reassess]) also requires [Loop] — a cycle with no seed — so the static
 * planner reports `NO_PATH_TO_GOAL`. [begin] gives the validator a valid first action (so the
 * failure is NO_PATH_TO_GOAL, not NO_STARTING_ACTION), mirroring how the real agent had reachable
 * actions alongside the unreachable goal.
 */
@Agent(description = "Deliberately invalid: goal needs a type with only a cyclic producer")
class CyclicallyInvalidAgent {
    @Action
    fun begin(seed: Seed): Step = Step(seed.value)

    @Action(canRerun = true)
    fun reassess(
        loop: Loop,
        step: Step,
    ): Loop = Loop(loop.value + step.value)

    @AchievesGoal(
        description = "Finish from a loop value",
        export = Export(name = "cyclicInvalidGoal", remote = false, startingInputTypes = [Seed::class]),
    )
    @Action
    fun finish(loop: Loop): Outcome = Outcome(loop.value)
}

@Agent(description = "Trivially valid: single goal reachable from a starting input")
class TriviallyValidAgent {
    @AchievesGoal(
        description = "Produce an outcome from a seed",
        export = Export(name = "trivialValidGoal", remote = false, startingInputTypes = [Seed::class]),
    )
    @Action
    fun go(seed: Seed): Outcome = Outcome(seed.value)
}
