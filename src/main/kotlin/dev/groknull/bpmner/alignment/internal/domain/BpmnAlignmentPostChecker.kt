package dev.groknull.bpmner.alignment.internal.domain

import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.core.AlignmentVerdict
import dev.groknull.bpmner.core.BpmnAlignmentConfig
import dev.groknull.bpmner.core.BpmnAlignmentReport
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

        val verdict =
            when {
                unsupported > 0 && config.blockOnUnsupportedElements -> AlignmentVerdict.FAILED
                missing > 0 && config.blockOnMissingContractItems -> AlignmentVerdict.FAILED
                assumptions > config.maxAssumptions -> AlignmentVerdict.FAILED
                unsupported > 0 || missing > 0 || assumptions > 0 -> AlignmentVerdict.PARTIALLY_ALIGNED
                else -> AlignmentVerdict.ALIGNED
            }

        if (verdict == AlignmentVerdict.FAILED) {
            logger.warn(
                "Alignment failed: unsupported={}, missing={}, assumptions={} (threshold={})",
                unsupported,
                missing,
                assumptions,
                config.maxAssumptions,
            )
        } else {
            logger.info(
                "Alignment passed: verdict={}, unsupported={}, missing={}, assumptions={}",
                verdict,
                unsupported,
                missing,
                assumptions,
            )
        }

        return report.copy(verdict = verdict)
    }
}
