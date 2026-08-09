/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ValidatedProcessContractTest {
    private val contract = ProcessContract(
        id = "contract-1",
        processName = "Test process",
        summary = "A minimal process for factory tests",
        trigger = "An order is received",
        activities = listOf(ContractActivity.Service("act1", "Do work")),
        endStates = listOf(ContractEndState.Normal("end1", "Done")),
    )

    @Test
    fun `of returns null for a report containing an ERROR issue`() {
        val report = ContractValidationReport(
            issues = listOf(
                ContractValidationIssue(
                    code = ContractValidationCode.MISSING_TRIGGER,
                    severity = ContractIssueSeverity.ERROR,
                    message = "Process contract has no trigger",
                ),
            ),
        )

        assertNull(ValidatedProcessContract.of(contract, report))
    }

    @Test
    fun `of returns non-null for a warnings-only report`() {
        val report = ContractValidationReport(
            issues = listOf(
                ContractValidationIssue(
                    code = ContractValidationCode.MISSING_TRIGGER,
                    severity = ContractIssueSeverity.WARNING,
                    message = "Trigger could be more specific",
                ),
            ),
        )

        assertNotNull(ValidatedProcessContract.of(contract, report))
    }
}
