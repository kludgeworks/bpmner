/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline

import org.jmolecules.architecture.onion.simplified.ApplicationRing

/**
 * Port for persisting generated BPMN XML permalinks.
 */
@ApplicationRing
interface BpmnPermalinkStore {
    /**
     * Saves the [xml] content for a given [id].
     */
    fun save(id: String, xml: String)

    /**
     * Loads the persisted XML content for [id], or null if it doesn't exist.
     */
    fun load(id: String): String?
}
