/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment.internal.domain

import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.core.BpmnAlignmentConfig
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
internal class BpmnAlignmentPostChecker(
    private val config: BpmnAlignmentConfig,
) {
    private val logger = LoggerFactory.getLogger(BpmnAlignmentPostChecker::class.java)

    fun apply(report: BpmnAlignmentReport): BpmnAlignmentReport {
        val assumptions = report.alignedElements.count { it.classification == AlignmentClassification.ASSUMED }
        val unsupported = report.alignedElements.count { it.classification == AlignmentClassification.UNSUPPORTED }
        val missing = report.alignedElements.count { it.classification == AlignmentClassification.MISSING }
        val partiallyCovered = report.alignedElements.count { it.classification == AlignmentClassification.PARTIALLY_COVERED }

        val policyVerdict =
            when {
                unsupported > 0 && config.blockOnUnsupportedElements -> AlignmentVerdict.FAILED
                missing > 0 && config.blockOnMissingContractItems -> AlignmentVerdict.FAILED
                assumptions > config.maxAssumptions -> AlignmentVerdict.FAILED
                unsupported > 0 || missing > 0 || partiallyCovered > 0 || assumptions > 0 -> AlignmentVerdict.PARTIALLY_ALIGNED
                else -> AlignmentVerdict.ALIGNED
            }

        // Worst-of: policy may downgrade an LLM ALIGNED verdict, and an LLM FAILED verdict is never upgraded by counts.
        val verdict = worstOf(report.verdict, policyVerdict)

        if (verdict == AlignmentVerdict.FAILED) {
            logger.warn(
                "Alignment failed: llmVerdict={}, unsupported={}, missing={}, partiallyCovered={}, assumptions={} (threshold={})",
                report.verdict,
                unsupported,
                missing,
                partiallyCovered,
                assumptions,
                config.maxAssumptions,
            )
        } else {
            logger.info(
                "Alignment passed: verdict={}, llmVerdict={}, unsupported={}, missing={}, partiallyCovered={}, assumptions={}",
                verdict,
                report.verdict,
                unsupported,
                missing,
                partiallyCovered,
                assumptions,
            )
        }

        return report.copy(verdict = verdict)
    }

    private fun worstOf(
        a: AlignmentVerdict,
        b: AlignmentVerdict,
    ): AlignmentVerdict = if (severity(a) >= severity(b)) a else b

    private fun severity(verdict: AlignmentVerdict): Int =
        when (verdict) {
            AlignmentVerdict.ALIGNED -> 0
            AlignmentVerdict.PARTIALLY_ALIGNED -> 1
            AlignmentVerdict.FAILED -> 2
        }
}
