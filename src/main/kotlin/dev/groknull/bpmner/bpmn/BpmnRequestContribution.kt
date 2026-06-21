/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

/**
 * Pure kernel extension for style-guide contribution text.
 *
 * Returns the style-guide header string for inclusion in a prompt contribution,
 * or an empty string when no style guide is present. This is a pure `String` function;
 * it carries no `com.embabel.*` import. Slices that drive LLM prompts wrap it locally
 * with `PromptContributor.fixed(request.styleGuideContribution())` (ADR-21 Decision 1).
 *
 * Compiles to a top-level Kotlin facade (`BpmnRequestContributionKt`), exempt from
 * `DOMAIN_ALLOWLIST` via the `haveSimpleNameNotEndingWith("Kt")` guard.
 */
fun BpmnRequest.styleGuideContribution(): String = styleGuide?.let { "## Style guide\n\n$it" } ?: ""
