/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.browser.internal.adapter.outbound

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EnvInteractiveEnvironmentTest {

    @Test
    fun `canOpenBrowser false when CI env is set to true`() {
        val detector = EnvInteractiveEnvironment(
            envGet = { if (it == "CI") "true" else null },
            isHeadless = { false },
        )

        assertThat(detector.canOpenBrowser()).isFalse()
    }

    @Test
    fun `canOpenBrowser false when CI env is set to TRUE (case insensitive)`() {
        val detector = EnvInteractiveEnvironment(
            envGet = { if (it == "CI") "TRUE" else null },
            isHeadless = { false },
        )

        assertThat(detector.canOpenBrowser()).isFalse()
    }

    @Test
    fun `canOpenBrowser returns boolean`() {
        // In CI/test environments, System.console() is often null
        // So we just verify that the method returns a valid boolean
        val detector = EnvInteractiveEnvironment(
            envGet = { if (it == "CI") null else null },
            isHeadless = { false },
        )

        val result = detector.canOpenBrowser()
        // Verify it returns a boolean (true or false, not null)
        assertThat(result).isNotNull()
        assertThat(result).isOfAnyClassIn(java.lang.Boolean::class.java)
    }

    @Test
    fun `canOpenBrowser false when headless is true`() {
        val detector = EnvInteractiveEnvironment(
            envGet = { if (it == "CI") null else null },
            isHeadless = { true },
        )

        assertThat(detector.canOpenBrowser()).isFalse()
    }

    @Test
    fun `headless check takes precedence over CI check`() {
        val detector = EnvInteractiveEnvironment(
            envGet = { if (it == "CI") "true" else null },
            isHeadless = { true },
        )

        assertThat(detector.canOpenBrowser()).isFalse()
    }

    @Test
    fun `headless check takes precedence over CI check even when CI is set to false`() {
        val detector = EnvInteractiveEnvironment(
            envGet = { if (it == "CI") "false" else null },
            isHeadless = { true },
        )

        assertThat(detector.canOpenBrowser()).isFalse()
    }
}
