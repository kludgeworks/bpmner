/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.repair

import dev.groknull.bpmner.repair.internal.domain.BpmnLocalModelFixHandler
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalModelFixHandlerRegistry
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalRepairCapabilityValidator
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairCapabilityValidationException
import dev.groknull.bpmner.validation.BpmnAutoFixResult
import dev.groknull.bpmner.validation.BpmnLintPhase
import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnRepairSafety
import dev.groknull.bpmner.validation.LintIssue
import dev.groknull.bpmner.validation.RepairKind
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Proves that Spring startup fails when a `LOCAL_MODEL_FIX` rule declares a handler that is
 * not registered in [BpmnLocalModelFixHandlerRegistry]. This protects the runtime invariant
 * that declared-local routes always resolve to a concrete handler at boot time, rather than
 * failing silently in the repair loop.
 */
class RepairStartupValidationTest {
    @Test
    fun `context fails to start when a LOCAL_MODEL_FIX rule references a missing Kotlin handler`() {
        val failure =
            assertThrows<Exception> {
                AnnotationConfigApplicationContext(MissingHandlerConfig::class.java).use { /* refresh */ }
            }
        val rootCause = failure.deepestCause()
        assertTrue(
            rootCause is BpmnRepairCapabilityValidationException,
            "Expected BpmnRepairCapabilityValidationException, got: ${rootCause::class.qualifiedName}: ${rootCause.message}",
        )
        assertNotNull(rootCause.message)
        assertTrue(rootCause.message!!.contains("absentHandler"))
        assertTrue(rootCause.message!!.contains("LOCAL_MODEL_FIX"))
    }

    private fun Throwable.deepestCause(): Throwable {
        var current: Throwable = this
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    @Configuration
    internal open class MissingHandlerConfig {
        @Bean
        internal open fun lintingPort(): BpmnLintingPort = MissingHandlerLintingPort

        @Bean
        internal open fun handlerRegistry(): BpmnLocalModelFixHandlerRegistry =
            BpmnLocalModelFixHandlerRegistry(emptyList<BpmnLocalModelFixHandler>())

        @Bean
        internal open fun validator(
            lintingPort: BpmnLintingPort,
            registry: BpmnLocalModelFixHandlerRegistry,
        ): BpmnLocalRepairCapabilityValidator = BpmnLocalRepairCapabilityValidator(lintingPort, registry)
    }

    private object MissingHandlerLintingPort : BpmnLintingPort {
        override fun lint(
            bpmnXml: String,
            phase: BpmnLintPhase,
        ): List<LintIssue> = emptyList()

        override fun autoFix(
            bpmnXml: String,
            issues: List<LintIssue>,
            phase: BpmnLintPhase,
        ): BpmnAutoFixResult? = null

        override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> = emptyMap()

        override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> =
            mapOf(
                "topo-01" to
                    BpmnLintRuleCapability(
                        id = "topo-01",
                        kind = RepairKind.LOCAL_MODEL_FIX,
                        repairSafety = BpmnRepairSafety.SAFE_AUTOMATIC,
                        fixHandler = "absentHandler",
                        handlerExists = false,
                        replacementMap = null,
                    ),
            )
    }
}
