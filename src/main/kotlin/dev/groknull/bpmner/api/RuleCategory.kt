/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Rule category names and short codes used when deriving stable rule ids.
 */
enum class RuleCategory(
    val displayName: String,
    val shortCode: String,
) {
    Activity("Activity", "act"),
    Association("Association", "assoc"),
    Artifact("Artifact", "art"),
    Data("Data", "data"),
    Event("Event", "evt"),
    Flow("Flow", "flow"),
    Gateway("Gateway", "gtw"),
    General("General", "gen"),
    Lane("Lane", "lane"),
    Message("Message", "msg"),
    Name("Name", "name"),
    Pool("Pool", "pool"),
    Definition("Definition", "def"),
    ;

    override fun toString(): String = displayName

    companion object {
        fun fromDisplayName(name: String): RuleCategory = entries.firstOrNull { it.displayName == name }
            ?: error("Unknown rule category '$name'")
    }
}
