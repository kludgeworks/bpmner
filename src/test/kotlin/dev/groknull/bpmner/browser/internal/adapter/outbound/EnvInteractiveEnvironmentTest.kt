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
            console = { true },
        )

        assertThat(detector.canOpenBrowser()).isFalse()
    }

    @Test
    fun `canOpenBrowser false when CI env is set to any non-null value`() {
        val detector = EnvInteractiveEnvironment(
            envGet = { if (it == "CI") "1" else null },
            isHeadless = { false },
            console = { true },
        )

        assertThat(detector.canOpenBrowser()).isFalse()
    }

    @Test
    fun `canOpenBrowser true when all gates pass`() {
        val detector = EnvInteractiveEnvironment(
            envGet = { null }, // CI not set
            isHeadless = { false },
            console = { true }, // Console present
        )

        assertThat(detector.canOpenBrowser()).isTrue()
    }

    @Test
    fun `canOpenBrowser false when headless is true`() {
        val detector = EnvInteractiveEnvironment(
            envGet = { null }, // CI not set
            isHeadless = { true },
            console = { true },
        )

        assertThat(detector.canOpenBrowser()).isFalse()
    }

    @Test
    fun `headless check takes precedence over CI check`() {
        val detector = EnvInteractiveEnvironment(
            envGet = { if (it == "CI") "true" else null },
            isHeadless = { true },
            console = { true },
        )

        assertThat(detector.canOpenBrowser()).isFalse()
    }

    @Test
    fun `headless check takes precedence over CI check even when CI is set to false string`() {
        // CI=false still means CI env is present (non-null), but headless triggers first
        val detector = EnvInteractiveEnvironment(
            envGet = { if (it == "CI") "false" else null },
            isHeadless = { true },
            console = { true },
        )

        assertThat(detector.canOpenBrowser()).isFalse()
    }

    @Test
    fun `canOpenBrowser false when console is absent`() {
        val detector = EnvInteractiveEnvironment(
            envGet = { null }, // CI not set
            isHeadless = { false },
            console = { false }, // No console
        )

        assertThat(detector.canOpenBrowser()).isFalse()
    }
}
