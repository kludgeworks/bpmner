/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.groknull.bpmner.authoring.internal.domain

/**
 * Relocated to the public authoring package as a `@SecondaryPort`, mirroring [BpmnRenderer].
 * This alias is retained so references within `internal.domain` continue to resolve without
 * churn. All new code must import from [dev.groknull.bpmner.authoring.BpmnXmlParser].
 */
internal typealias BpmnXmlParser = dev.groknull.bpmner.authoring.BpmnXmlParser
