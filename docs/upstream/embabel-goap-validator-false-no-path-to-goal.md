<!--
Copyright 2026 The Project Contributors
SPDX-License-Identifier: MIT
-->

# Draft upstream issue: `GoapPathToCompletionValidator` false `NO_PATH_TO_GOAL` + silent validation

This is a **draft** to file against [`embabel/embabel-agent`](https://github.com/embabel/embabel-agent).
It is kept in-repo (not yet filed) so the team can review the wording first.

---

**Title:** `GoapPathToCompletionValidator`: false `NO_PATH_TO_GOAL` for valid multi-agent setups
(ignores `startingInputTypes` & cross-agent goals); validation is silently non-fatal

## Summary

In a multi-`@Agent` Spring application, `GoapPathToCompletionValidator` logs `NO_PATH_TO_GOAL` at
startup for an agent that is correct at runtime. There are two distinct problems:

1. **False negative.** The validator plans each agent in isolation and ignores the goal's
   `Export.startingInputTypes` as well as inputs supplied by other agents' exported/remote goals. An
   agent that is perfectly reachable at runtime is reported as having no path to its goal.
2. **Silent.** Validation failures are only logged (`WARN`/`ERROR`); nothing fails the boot and there
   is no setting to make it fail. The false negative is therefore easy to miss and impossible to gate
   on, and a *genuine* defect is equally invisible.

## Environment

- `embabel-agent` `0.4.0`
- Spring Boot, annotation-based agents (`@Agent` / `@Action` / `@AchievesGoal`), GOAP planner

## Root cause (from the 0.4.0 validator)

`GoapPathToCompletionValidator.validate` seeds `initialWorldState` only with conditions that are
*needed but never produced* by an in-scope action (plus the effects of zero-precondition "first
actions"). The planning goal is the goal action's `TRUE`, non-`hasRun_` effects.

A type that is **both** a precondition **and** a `TRUE` effect of some in-scope action — for example a
re-runnable "reassess" action that consumes and re-produces the same type — is treated as
"internally produced" and is therefore **not** seeded, even when it is genuinely a *starting input*
declared in `startingInputTypes`. If the only in-scope producer of that type also requires it, there
is no seed and A\* reports `NO_PATH_TO_GOAL`. `startingInputTypes` is never consulted.

## Minimal reproduction

An agent whose goal action takes input type `T` (declared in
`Export(startingInputTypes = [..., T::class])`) plus a re-runnable action that takes `T` and returns
`T` (a reassessment loop). The goal is unreachable to the validator even though, at runtime, `T` is
provided as a starting input (or produced by another agent's exported goal).

```kotlin
@Agent(description = "valid at runtime, fails static validation")
class Example(private val invoker: SomeInvoker) {
    // Re-runnable producer that both consumes and produces T -> T is "internally produced",
    // so the validator never seeds it, yet its only producer needs it.
    @Action(canRerun = true)
    fun reassess(t: T, answers: Answers): T = invoker.reassess(t, answers)

    @AchievesGoal(
        description = "...",
        export = Export(name = "doIt", startingInputTypes = [Input::class, T::class]),
    )
    @Action(pre = ["ready"])
    fun finish(input: Input, t: T): Output = Output(input, t)
}
```

## Why it is a false negative

At runtime the multi-agent planner resolves `T` from `startingInputTypes` and/or a cross-agent
exported (`remote = true`) goal, so the agent works. Only the per-agent static validator disagrees.

## Secondary issue: validation is silently non-fatal

`AgentMetadataReader` validates during metadata construction but only logs the result (there is a
commented-out `// return null` enforcement path in the source); `DefaultAgentValidationManager`
logs `ERROR`. There is no `embabel.agent.platform.*` property to make validation fatal and no
documented deployment-time hook that fails the boot. Teams can ship a red startup log indefinitely
without noticing.

Compounding this for anyone trying to enforce validity themselves: `GoapPathToCompletionValidator`
and `AgentStructureAgentValidator` are **not** Spring beans, so the auto-registered
`DefaultAgentValidationManager` bean is wired with an empty validator list and silently passes every
agent. Code that injects `AgentValidationManager` to re-validate deployed agents therefore gets a
no-op unless it constructs the validators itself (as `AgentMetadataReader` does internally).

## Suggested fixes

1. Seed `goal.export.startingInputTypes` as `TRUE` in `initialWorldState` so declared starting
   inputs are honoured by the planner.
2. Optionally account for exported / `remote = true` goals from other agents when validating, or
   document explicitly that per-agent validation is intentionally local.
3. Add an opt-in setting (e.g. `embabel.agent.platform.validation.fail-fast`) — or surface the
   results through the deployment API — so applications can enforce validity at boot.
4. Register the framework validators as beans (or otherwise make the injected
   `AgentValidationManager` non-empty), so re-validation via the public API is not a silent no-op.
5. Expand the `NO_PATH_TO_GOAL` message to note that `startingInputTypes` and cross-agent goals are
   not considered, to speed diagnosis.

## Workaround (what we did)

Add an in-scope, non-cyclic producer of the starting-input type, cost-weighted above the canonical
cross-agent producer so the multi-agent planner never prefers it at runtime — it exists purely to
make the single-agent static validation provable. We also added our own `ContextRefreshedEvent`
startup check that constructs `GoapPathToCompletionValidator` directly (not via the injected,
empty manager) and fails the boot if any of our agents is invalid.
