/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import org.springframework.context.ApplicationEventPublisher

enum class StructuredOutputFailureCategory { INVALID_FORMAT, INVALID_TYPE }

/** Published when a role's structured-output call fails on Embabel's stable InvalidLlmReturn*
 *  surface. Both exception types are deterministic — malformed/invalid model output — distinct
 *  from transient failures (network, rate limit) the framework's default retry already handles.
 */
class StructuredOutputFailureEvent(
    val role: String,
    val category: StructuredOutputFailureCategory,
    val message: String?,
)

/** Wraps a structured-output role call: catches Embabel's two stable InvalidLlmReturn*
 *  exceptions, publishes [StructuredOutputFailureEvent] for observability, then rethrows so each
 *  role keeps its own exception translation on top. Any other exception (network, rate limit)
 *  propagates untouched — only these two get this treatment, which is the
 *  transient-vs-deterministic classification itself. */
fun <T> ApplicationEventPublisher.publishOnInvalidLlmReturn(role: String, block: () -> T): T =
    try {
        block()
    } catch (e: InvalidLlmReturnFormatException) {
        publishEvent(StructuredOutputFailureEvent(role, StructuredOutputFailureCategory.INVALID_FORMAT, e.message))
        throw e
    } catch (e: InvalidLlmReturnTypeException) {
        publishEvent(StructuredOutputFailureEvent(role, StructuredOutputFailureCategory.INVALID_TYPE, e.message))
        throw e
    }
