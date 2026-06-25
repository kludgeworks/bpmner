/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment.internal.domain

import dev.groknull.bpmner.alignment.AlignmentClassification
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.internal.BpmnAlignmentThresholdsConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
internal class BpmnAlignmentPostChecker(
    private val config: BpmnAlignmentThresholdsConfig,
) {
    private val logger = LoggerFactory.getLogger(BpmnAlignmentPostChecker::class.java)

    fun apply(
        findings: AlignmentFindings,
        summary: BpmnDefinitionSummary,
    ): BpmnAlignmentReport {
        val assumptions = findings.issues.count { it.classification == AlignmentClassification.ASSUMED }
        val unsupported = findings.issues.count { it.classification == AlignmentClassification.UNSUPPORTED }
        val missing = findings.issues.count { it.classification == AlignmentClassification.MISSING }
        val partiallyCovered = findings.issues.count { it.classification == AlignmentClassification.PARTIALLY_COVERED }

        val verdict =
            when {
                unsupported > 0 && config.blockOnUnsupportedElements -> AlignmentVerdict.FAILED
                missing > 0 && config.blockOnMissingContractItems -> AlignmentVerdict.FAILED
                assumptions > config.maxAssumptions -> AlignmentVerdict.FAILED
                findings.issues.isNotEmpty() -> AlignmentVerdict.PARTIALLY_ALIGNED
                else -> AlignmentVerdict.ALIGNED
            }

        if (verdict == AlignmentVerdict.FAILED) {
            logger.warn(
                "Alignment failed: unsupported={}, missing={}, partiallyCovered={}, assumptions={} (threshold={})",
                unsupported,
                missing,
                partiallyCovered,
                assumptions,
                config.maxAssumptions,
            )
        } else {
            logger.info(
                "Alignment passed: verdict={}, unsupported={}, missing={}, partiallyCovered={}, assumptions={}",
                verdict,
                unsupported,
                missing,
                partiallyCovered,
                assumptions,
            )
        }

        return BpmnAlignmentReport(
            verdict = verdict,
            bpmnSummary = summary,
            issues = findings.issues,
            rationale = findings.rationale,
        )
    }
}
