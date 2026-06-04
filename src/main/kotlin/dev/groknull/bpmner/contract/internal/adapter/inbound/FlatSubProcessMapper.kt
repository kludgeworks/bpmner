/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEventSubProcess

/*
 * Wire-format → sealed conversion for the two subprocess flavours, kept beside FlatContractMapper.kt.
 *
 * An embedded subprocess maps to a ContractActivity.SubProcess (folded into the activity list by
 * FlatProcessContract.toSealed, so the activity-keyed loops pick it up). An event subprocess maps to
 * a standalone ContractEventSubProcess (its own ProcessContract.eventSubProcesses collection — it sits
 * off the main flow with no incoming/outgoing edges, so it is not an activity).
 */

public fun FlatContractSubProcess.toSealed(): ContractActivity.SubProcess = ContractActivity.SubProcess(
    id = id,
    name = name,
    containedActivityIds = activityIds,
    sourceIds = sourceIds,
)

public fun FlatContractEventSubProcess.toSealed(): ContractEventSubProcess = ContractEventSubProcess(
    id = id,
    name = name,
    containedActivityIds = activityIds,
    trigger = trigger,
    interrupting = interrupting,
    sourceIds = sourceIds,
)
