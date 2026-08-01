/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline

/**
 * Artifact availability for a generation run at the point a [RunUpdate] was produced.
 *
 * - [NONE] — no BPMN artifact exists yet (readiness, contract, awaiting input).
 * - [GRAPH_DRAFT] — the server-side process graph exists (post-`composeGraph`), but no BPMN XML
 *   has been rendered yet.
 * - [XML_DRAFT] — a renderable BPMN XML exists (post-`render`, post-`layout`, or a passed
 *   validation) but the run has not reached a terminal outcome.
 * - [DIAGNOSTIC] — the XML exists but carries blocking diagnostics from a failed validation
 *   pass (the repair loop is retrying, or the run terminated with `VALIDATION_FAILED`).
 * - [FINAL] — the terminal artifact is available for download via
 *   `GET /generations/{id}/bpmn` (`GENERATED` or `ALIGNMENT_FAILED`, both of which carry XML).
 */
enum class ArtifactState {
    NONE,
    GRAPH_DRAFT,
    XML_DRAFT,
    DIAGNOSTIC,
    FINAL,
}

/**
 * The deterministic milestone a [RunUpdate] reports on — the author-centred journey through
 * `BpmnGenerationAgent`'s action chain, projected without any Embabel action name or type.
 */
enum class RunPhase {
    /** `assessReadiness` / `reassess`. */
    READINESS,

    /** Parked in `AwaitingClarification`, waiting on a `WaitFor.formSubmission` answer (HITL). */
    AWAITING_INPUT,

    /** `extractContract`. */
    CONTRACT,

    /** `createOutline` / `composeGraph` — still pre-XML. */
    OUTLINE,

    /** `render` — the first BPMN XML exists. */
    DRAFT,

    /** `validate` (+ repair loop). */
    VALIDATION,

    /** `layout`. */
    LAYOUT,

    /** `align`. */
    ALIGNMENT,

    /** `finish` / `terminate` — the run has reached its one terminal outcome. */
    FINISHED,
}

/**
 * The one terminal marker a [RunUpdate] can carry — mirrors
 * [dev.groknull.bpmner.authoring.BpmnGenerationStatus] at a coarser, author-facing grain.
 */
enum class RunOutcome {
    COMPLETED,
    FAILED,
}

/**
 * An ordered, transient update from one BPMN generation run — the CQRS read model / event
 * narrative (Fowler; Drotbohm's `cqrs-spring-modulith`) that the anti-corruption layer
 * ([dev.groknull.bpmner.pipeline.internal.adapter.inbound.BpmnRunUpdateChannel]) projects
 * bpmner's own `@DomainEvent` milestones and select Embabel lifecycle/`OutputChannel` signals
 * onto. Carries **notification + minimal event-carried state** — never the BPMN XML itself
 * (that stays behind `GET /generations/{id}/bpmn`) and never an Embabel type,
 * action name, prompt, model-reasoning, credential, or provider payload (`detail` is a flat,
 * explicitly whitelisted `String -> String` bag; see call sites in `BpmnRunUpdateChannel`).
 *
 * [seq] is assigned by a single writer per process ([RunUpdateSinkRegistry]) and is strictly
 * increasing for a given run — the stale-update guard: a client (or a future consumer) must
 * never let an update with a lower-or-equal [seq] replace state built from a later one,
 * regardless of arrival order. [RunUpdate.supersedes] is the pure, testable expression of that
 * rule.
 *
 * A single terminal marker: only [Terminal] carries [Terminal.outcome]; there is exactly one
 * per run, and the sink completes immediately after it ([RunUpdateSinkRegistry.emitTerminal]).
 */
sealed interface RunUpdate {
    val seq: Long
    val phase: RunPhase
    val artifactState: ArtifactState
    val summary: String
    val detail: Map<String, String>

    /** A non-terminal step in the run's journey. */
    data class Progress(
        override val seq: Long,
        override val phase: RunPhase,
        override val artifactState: ArtifactState,
        override val summary: String,
        override val detail: Map<String, String> = emptyMap(),
    ) : RunUpdate

    /** The one terminal update for a run — [RunPhase.FINISHED] with a concluded [outcome]. */
    data class Terminal(
        override val seq: Long,
        override val artifactState: ArtifactState,
        override val summary: String,
        val outcome: RunOutcome,
        override val detail: Map<String, String> = emptyMap(),
        override val phase: RunPhase = RunPhase.FINISHED,
    ) : RunUpdate
}

/**
 * True when `this` update should replace [current] in a consumer's rendered state: it is
 * strictly newer by [RunUpdate.seq]. Because [seq] is assigned by a single writer per process,
 * this is a total order — an update can never be superseded by one with an equal or lower
 * sequence number, however it happens to arrive.
 */
fun RunUpdate.supersedes(current: RunUpdate?): Boolean = current == null || this.seq > current.seq
