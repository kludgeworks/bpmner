/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

/**
 * Asserts a kind-required wire field is present and (for text fields) non-blank, returning the
 * non-null value. Jakarta `@NotBlank` on the sealed constructors is schema-only, so the flat→sealed
 * mappers enforce kind-required fields here. [kind] and [context] (an id or description) give the
 * failure message enough to point the modeller at the offending element.
 */
internal fun <T : Any> requireField(value: T?, kind: Enum<*>, fieldName: String, context: String): T {
    val nonNull = requireNotNull(value) { "$kind ($context) requires $fieldName" }
    if (nonNull is CharSequence) {
        require(nonNull.isNotBlank()) { "$kind ($context) requires non-blank $fieldName" }
    }
    return nonNull
}
